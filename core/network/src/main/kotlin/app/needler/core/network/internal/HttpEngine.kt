package app.needler.core.network.internal

import app.needler.core.network.ApiLane
import app.needler.core.network.CredentialProvider
import app.needler.core.network.NeedlerHttpClient
import app.needler.core.network.NetworkError
import app.needler.core.network.RetryPolicy
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.random.Random

/**
 * One JSON configuration for both lanes.
 *
 * `ignoreUnknownKeys` is not optional: REQUIREMENTS.md requires that a newer DroppedNeedle adding
 * fields cannot break the client, and the server's msgspec structs emit every field including
 * nulls. `coerceInputValues` turns an unexpected `null` on a field that has a default into that
 * default rather than a hard failure.
 */
internal val NeedlerJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = false
}

/** The `/api/v1` error envelope: `{"error":{"code":…,"message":…,"details":…}}`. */
@Serializable
internal data class V1ErrorEnvelope(
    val error: V1ErrorBody? = null,
)

@Serializable
internal data class V1ErrorBody(
    val code: String? = null,
    val message: String? = null,
)

/**
 * Executes requests, applies the short retry policy, and converts every transport and HTTP
 * failure into a [NetworkError]. Both lane clients are built on top of this.
 */
internal class HttpEngine(
    private val http: NeedlerHttpClient,
    private val credentials: CredentialProvider,
) {

    val json: Json get() = NeedlerJson

    /**
     * Run [request], retrying per [policy]. The returned response is open: the caller owns it and
     * must close it (`response.use { }`).
     *
     * @throws NetworkError.Offline on timeout, DNS or socket failure
     * @throws NetworkError.TlsNotTrusted when TLS validation failed and no pin matched
     */
    suspend fun execute(
        request: Request,
        media: Boolean = false,
        policy: RetryPolicy = http.retryPolicy,
    ): Response {
        val client = if (media) http.mediaClient else http.client
        val retryable = request.method == "GET" || request.method == "HEAD"
        var attemptIndex = 0
        while (true) {
            val response = try {
                singleAttempt(client, request)
            } catch (failure: IOException) {
                if (retryable && attemptIndex < policy.maxRetries) {
                    attemptIndex++
                    delay(backoffMillis(policy, attemptIndex))
                    continue
                }
                throw mapTransportFailure(request, failure)
            }

            if (!retryable || attemptIndex >= policy.maxRetries || !isRetryableStatus(response.code)) {
                return response
            }

            val waitMillis = when (response.code) {
                429 -> {
                    val retryAfter = retryAfterSeconds(response)
                    if (retryAfter == null || retryAfter > policy.maxHonouredRetryAfterSeconds) {
                        return response
                    }
                    retryAfter * 1_000
                }

                else -> backoffMillis(policy, attemptIndex + 1)
            }
            response.close()
            attemptIndex++
            delay(waitMillis)
        }
    }

    /** Execute and decode a `/api/v1` JSON body, mapping every failure status to a [NetworkError]. */
    suspend fun <T> v1Json(request: Request, deserializer: DeserializationStrategy<T>): T {
        execute(request).use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw mapHttpFailure(response, body, ApiLane.V1)
            return decode(deserializer, body, ApiLane.V1)
        }
    }

    /** Execute a `/api/v1` call whose body is irrelevant (204 No Content, or an ignored payload). */
    suspend fun v1Unit(request: Request) {
        execute(request).use { response ->
            if (!response.isSuccessful) throw mapHttpFailure(response, response.body.string(), ApiLane.V1)
        }
    }

    fun <T> decode(deserializer: DeserializationStrategy<T>, body: String, lane: ApiLane): T =
        try {
            json.decodeFromString(deserializer, body)
        } catch (failure: SerializationException) {
            throw NetworkError.Serialisation(lane, failure)
        } catch (failure: IllegalArgumentException) {
            throw NetworkError.Serialisation(lane, failure)
        }

    /**
     * HTTP status to [NetworkError].
     *
     * The `/api/v1` lane fails this way for everything. The Subsonic lane normally fails inside a
     * `status=failed` envelope over HTTP 200 instead, and only reaches here for its binary
     * endpoints (`403`, `404`, `416`, `429`) or for an infrastructure answer such as a proxy `502`
     * or a `404` from the wrong base path.
     */
    fun mapHttpFailure(response: Response, body: String?, lane: ApiLane): NetworkError {
        val envelope = body?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(V1ErrorEnvelope.serializer(), it) }.getOrNull()
        }
        val code = envelope?.error?.code
        val message = envelope?.error?.message
        return when (val status = response.code) {
            // The v1 lane's 401s come in two shapes: AuthMiddleware emits code UNAUTHORIZED,
            // while a route-raised 401 (bad login) is mislabelled INTERNAL_ERROR upstream, so the
            // HTTP status is the only reliable signal.
            401 -> {
                if (lane == ApiLane.V1) credentials.onBearerRejected() else credentials.onAppPasswordRejected()
                NetworkError.Unauthorised(lane)
            }

            403 -> NetworkError.Forbidden(lane, message)
            404, 410 -> NetworkError.NotFound(lane, message)
            416 -> NetworkError.RangeNotSatisfiable(completeLengthOf(response))
            429 -> NetworkError.RateLimited(retryAfterSeconds(response), lane)
            in 500..599 -> NetworkError.Server(status, lane, retryAfterSeconds(response), message)
            else -> NetworkError.InvalidRequest(lane, status, code, message)
        }
    }

    /** Transport-level failure to [NetworkError]. Cancellation is never swallowed. */
    fun mapTransportFailure(request: Request, failure: IOException): NetworkError = when (failure) {
        is NetworkError -> failure
        is SSLException -> NetworkError.TlsNotTrusted(request.url.host, failure)
        is UnknownHostException -> NetworkError.Offline(failure, NetworkError.Offline.Kind.Dns)
        is SocketTimeoutException -> NetworkError.Offline(failure, NetworkError.Offline.Kind.Timeout)
        // OkHttp's whole-call timeout surfaces as InterruptedIOException("timeout").
        is InterruptedIOException -> NetworkError.Offline(failure, NetworkError.Offline.Kind.Timeout)
        else -> NetworkError.Offline(failure, NetworkError.Offline.Kind.Io)
    }

    private suspend fun singleAttempt(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { continuation: CancellableContinuation<Response> ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isActive) {
                            continuation.resume(response)
                        } else {
                            response.close()
                        }
                    }
                },
            )
        }

    private fun backoffMillis(policy: RetryPolicy, attempt: Int): Long {
        val exponential = policy.initialBackoffMillis shl (attempt - 1).coerceAtLeast(0)
        val capped = min(exponential, policy.maxBackoffMillis)
        val jitter = (capped * policy.jitterRatio).toLong()
        if (jitter <= 0) return capped
        return (capped - jitter + Random.nextLong(0, 2 * jitter + 1)).coerceAtLeast(0)
    }

    private fun isRetryableStatus(code: Int): Boolean = code == 429 || code in 500..599

    companion object {

        /** `Retry-After` in seconds, clamped to at least one — the v1 limiter can emit `0`. */
        fun retryAfterSeconds(response: Response): Long? {
            val raw = response.header("Retry-After")?.trim() ?: return null
            val seconds = raw.toLongOrNull() ?: return null
            return seconds.coerceAtLeast(1)
        }

        /** Complete length from `Content-Range: bytes * /1234` on a 416. */
        fun completeLengthOf(response: Response): Long? {
            val header = response.header("Content-Range") ?: return null
            return header.substringAfterLast('/', "").trim().toLongOrNull()
        }
    }
}

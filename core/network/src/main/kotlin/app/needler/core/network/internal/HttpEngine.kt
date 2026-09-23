package app.needler.core.network.internal

import app.needler.core.network.ApiLane
import app.needler.core.network.AuthenticatingProxyDetector
import app.needler.core.network.CredentialProvider
import app.needler.core.network.NeedlerHttpClient
import app.needler.core.network.NetworkError
import app.needler.core.network.NetworkLog
import app.needler.core.network.RetryPolicy
import app.needler.core.network.describeForLog
import app.needler.core.network.redactParseMessage
import app.needler.core.network.redactUrl
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
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
     * The shared diagnostic log, so that the lane clients built on this engine write under the
     * same tag as the interceptor that made the call.
     */
    val log: NetworkLog get() = http.log

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
                // An authenticating proxy is never retried: it will refuse the next attempt
                // identically, and three refusals take three times as long to tell the user the
                // one thing they need to hear.
                if (failure !is NetworkError.AuthenticatingProxy &&
                    retryable && attemptIndex < policy.maxRetries
                ) {
                    attemptIndex++
                    val backoff = backoffMillis(policy, attemptIndex)
                    // Without this line a call that eventually succeeded on its third attempt is
                    // indistinguishable in the log from one that went straight through, and a
                    // flaky server looks like a healthy one.
                    log.debug {
                        "retry " + attemptIndex + "/" + policy.maxRetries + " of " + request.method +
                            " " + redactUrl(request.url) + " in " + backoff + "ms after " +
                            failure.javaClass.simpleName
                    }
                    delay(backoff)
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
            log.debug {
                "retry " + (attemptIndex + 1) + "/" + policy.maxRetries + " of " + request.method +
                    " " + redactUrl(request.url) + " in " + waitMillis + "ms after HTTP " + response.code
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
            requireNotIntercepted(response, body, ApiLane.V1)
            if (!response.isSuccessful) throw mapHttpFailure(response, body, ApiLane.V1)
            return decode(deserializer, body, ApiLane.V1, response.request.url)
        }
    }

    /** Execute a `/api/v1` call whose body is irrelevant (204 No Content, or an ignored payload). */
    suspend fun v1Unit(request: Request) {
        execute(request).use { response ->
            val body = response.body.string()
            requireNotIntercepted(response, body, ApiLane.V1)
            if (!response.isSuccessful) throw mapHttpFailure(response, body, ApiLane.V1)
        }
    }

    /**
     * Fails fast when the answer came from a proxy rather than from DroppedNeedle.
     *
     * Checked **before** the status code is looked at and before the body is parsed, because the
     * interesting cases carry an innocent status: a Cloudflare Access login page is an HTTP 200,
     * and parsing it as JSON produces [NetworkError.Serialisation], whose message ("could not
     * parse") sends the user to look for a fault in their server that is not there.
     *
     * Does nothing at all for a JSON body, which is every response a healthy server sends on
     * either lane, success or failure. See [app.needler.core.network.AuthenticatingProxyDetector]
     * for why the rule cannot fire on an ordinary HTML error page.
     */
    fun requireNotIntercepted(response: Response, body: String?, lane: ApiLane) {
        val interception = AuthenticatingProxyDetector.fromResponse(
            response = response,
            bodyPrefix = body,
            proxyCredentialsSent = credentials.proxyCredentials().isNotEmpty,
        ) ?: return
        log.error {
            lane.name + " " + redactUrl(response.request.url) + " -> " + response.code +
                " was answered by something else: " + interception.summary
        }
        throw NetworkError.AuthenticatingProxy(interception, lane)
    }

    /**
     * Decode a body, turning any shape mismatch into [NetworkError.Serialisation].
     *
     * [url] is optional only because one caller genuinely has no request to hand; pass it wherever
     * it exists. A parse failure with no URL in the log line is a parse failure nobody can act on:
     * "could not parse the V1 response" is true of every endpoint at once.
     *
     * The failure's own message is put through [redactParseMessage] rather than logged raw. See
     * that function for the reason — in short, `kotlinx.serialization` appends the document it
     * could not read, and one of the documents this module parses is the login response.
     */
    fun <T> decode(
        deserializer: DeserializationStrategy<T>,
        body: String,
        lane: ApiLane,
        url: HttpUrl? = null,
    ): T =
        try {
            json.decodeFromString(deserializer, body)
        } catch (failure: SerializationException) {
            throw serialisation(lane, failure, url)
        } catch (failure: IllegalArgumentException) {
            throw serialisation(lane, failure, url)
        }

    /** Build [NetworkError.Serialisation] and say, once, what could not be read and from where. */
    fun serialisation(lane: ApiLane, failure: Throwable, url: HttpUrl?): NetworkError {
        log.error {
            lane.name + " could not parse " + (url?.let { redactUrl(it) } ?: "the response") +
                ": " + failure.javaClass.simpleName + ": " + redactParseMessage(failure.message)
        }
        return NetworkError.Serialisation(lane, failure)
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
        val error = mapStatus(response, lane, code, message)
        // The line the second of the two reported bugs needed and did not have. A failing
        // `GET /api/v1/artists/{mbid}/releases` now says which lane it was on, which status came
        // back, which error envelope code the server used and which NetworkError the app will act
        // on - so SessionExpired, a 404 from the wrong base path and a 500 stop looking identical
        // from outside the process. The body itself is never logged; `message` here is the
        // server's own `error.message`, which is what the UI would have shown anyway.
        log.warn {
            lane.name + " " + response.request.method + " " + redactUrl(response.request.url) +
                " -> " + response.code + (code?.let { " code=" + it } ?: "") +
                " mapped to " + error.describeForLog()
        }
        return error
    }

    private fun mapStatus(
        response: Response,
        lane: ApiLane,
        code: String?,
        message: String?,
    ): NetworkError {
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

    /**
     * The same check for a binary endpoint, where the body is audio or artwork and must not be
     * read into memory.
     *
     * Artwork and audio go through the same proxy as the JSON lanes and are challenged the same
     * way, so skipping this would leave a user with a working catalogue and silent playback. Only
     * a text or HTML content type is peeked at; a real audio body is never touched.
     */
    fun requireNotInterceptedBinary(response: Response, lane: ApiLane) {
        val contentType = response.body.contentType()
        val textual = contentType?.type?.lowercase() == "text" ||
            contentType?.subtype?.lowercase() == "html"
        val prefix = if (textual) {
            runCatching {
                response.peekBody(AuthenticatingProxyDetector.BODY_SNIFF_CHARS.toLong()).string()
            }.getOrNull()
        } else {
            null
        }
        requireNotIntercepted(response, prefix, lane)
    }

    /**
     * Transport-level failure to [NetworkError]. Cancellation is never swallowed.
     *
     * Logged at [app.needler.core.network.NetworkLogLevel.Error] because this is the branch where
     * the app decides the server is unreachable and falls back to the Room mirror - the decision
     * that makes an offline app look like a broken one, and the decision hardest to second-guess
     * afterwards without a record of which exception produced it. An error already of type
     * [NetworkError] is passed through without a second line: whoever raised it logged it.
     */
    fun mapTransportFailure(request: Request, failure: IOException): NetworkError {
        if (failure is NetworkError) return failure
        val error = when (failure) {
            is SSLException -> NetworkError.TlsNotTrusted(request.url.host, failure)
            is UnknownHostException -> NetworkError.Offline(failure, NetworkError.Offline.Kind.Dns)
            is SocketTimeoutException -> NetworkError.Offline(failure, NetworkError.Offline.Kind.Timeout)
            // OkHttp's whole-call timeout surfaces as InterruptedIOException("timeout").
            is InterruptedIOException -> NetworkError.Offline(failure, NetworkError.Offline.Kind.Timeout)
            else -> NetworkError.Offline(failure, NetworkError.Offline.Kind.Io)
        }
        log.error {
            request.method + " " + redactUrl(request.url) + " failed in transport: " +
                failure.javaClass.simpleName + " -> " + error.describeForLog()
        }
        return error
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

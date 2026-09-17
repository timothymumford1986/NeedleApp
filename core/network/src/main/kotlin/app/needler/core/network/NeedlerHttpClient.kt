package app.needler.core.network

import app.needler.core.network.tls.CertificatePinStore
import app.needler.core.network.tls.MutableCertificatePinStore
import app.needler.core.network.tls.PinnedHostTrustManager
import app.needler.core.network.tls.PinnedHostnameVerifier
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Marker tag: this request must go out without credentials (login, public probes). */
internal object SkipAuth

/** Timeouts for the two client flavours. Defaults are tuned for a LAN or VPN-reached server. */
public data class NetworkTimeouts(
    public val connectSeconds: Long = 10,
    public val readSeconds: Long = 20,
    public val writeSeconds: Long = 20,
    /** Whole-call ceiling for JSON calls. Zero disables it. */
    public val callSeconds: Long = 45,
    /** Read timeout for audio and artwork, where a slow first byte is normal. */
    public val mediaReadSeconds: Long = 60,
)

/**
 * Short, bounded retries performed inside this module.
 *
 * REQUIREMENTS.md asks for exponential backoff with jitter capped at five minutes on `5xx`, but a
 * five-minute sleep belongs to the polling and write-queue loops in `:core:data`, not to a single
 * HTTP call: a call that blocks for minutes holds a coroutine and a connection and makes the UI
 * look hung. So this module retries a couple of times over a few seconds and then surfaces
 * [NetworkError.Server] or [NetworkError.RateLimited] — both carry what the caller needs
 * (`statusCode`, `retryAfterSeconds`) to run the long backoff itself.
 */
public data class RetryPolicy(
    /** Retries after the first attempt. Only ever applied to `GET` and `HEAD`. */
    public val maxRetries: Int = 2,
    public val initialBackoffMillis: Long = 300,
    public val maxBackoffMillis: Long = 4_000,
    /** Fraction of the backoff added or removed at random, to avoid a thundering herd. */
    public val jitterRatio: Double = 0.25,
    /** A `Retry-After` longer than this is reported to the caller instead of being waited out. */
    public val maxHonouredRetryAfterSeconds: Long = 5,
) {
    public companion object {
        /** No retries, for call sites that want one fast, honest answer — a connect-time probe. */
        public val None: RetryPolicy = RetryPolicy(maxRetries = 0)
    }
}

/**
 * The single OkHttp setup for both lanes: timeouts, credential-redacting logging, bearer and
 * `apiKey` injection, and per-host certificate pinning that can be configured at runtime.
 *
 * Two clients come out of it, sharing one connection pool and one dispatcher:
 *  * [client] for JSON calls, with a whole-call timeout;
 *  * [mediaClient] for audio and artwork, with no call timeout and a longer read timeout, which
 *    is what Media3's `OkHttpDataSource` and Coil should be handed.
 *
 * Cleartext HTTP to a LAN address works: OkHttp keeps its default `CLEARTEXT` connection spec and
 * nothing here forces HTTPS. Android itself still needs `:app` to allow cleartext — a
 * `networkSecurityConfig` with `cleartextTrafficPermitted="true"` (or
 * `android:usesCleartextTraffic="true"`) — because the platform default blocks it from API 28.
 *
 * Pinning is live: [PinnedHostTrustManager] reads the [CertificatePinStore] on every handshake, so
 * a pin the user confirms mid-session takes effect without rebuilding the client. Pooled
 * connections are evicted when the pins change so a prior failure cannot be cached.
 */
public class NeedlerHttpClient(
    private val credentials: CredentialProvider,
    public val pinStore: CertificatePinStore = CertificatePinStore.Empty,
    logSink: NetworkLogSink = NetworkLogSink.None,
    loggingEnabled: Boolean = true,
    public val timeouts: NetworkTimeouts = NetworkTimeouts(),
    public val retryPolicy: RetryPolicy = RetryPolicy(),
) {

    /** JSON client: both lanes' non-binary calls. */
    public val client: OkHttpClient

    /** Binary client for `stream`, `download` and `getCoverArt`. Never buffers a whole body. */
    public val mediaClient: OkHttpClient

    init {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeouts.connectSeconds, TimeUnit.SECONDS)
            .readTimeout(timeouts.readSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeouts.writeSeconds, TimeUnit.SECONDS)
            .callTimeout(timeouts.callSeconds, TimeUnit.SECONDS)
            // OkHttp's own retry only covers route/connection failures; response-level retries
            // are this module's job (see RetryPolicy) so they can honour Retry-After.
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(false)
            .addInterceptor(CredentialInterceptor(credentials))
            .addInterceptor(RedactingLogInterceptor(logSink, loggingEnabled))

        val pinned = PinnedHostTrustManager.socketFactory(pinStore)
        if (pinned != null) {
            builder.sslSocketFactory(pinned.first, pinned.second)
            builder.hostnameVerifier(PinnedHostnameVerifier(pinStore))
        }

        client = builder.build()
        mediaClient = client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.mediaReadSeconds, TimeUnit.SECONDS)
            .build()

        (pinStore as? MutableCertificatePinStore)?.onChanged = { evictConnections() }
    }

    /**
     * Drop pooled and idle connections. Called automatically when the pin set changes, and worth
     * calling when the device's network changes or the saved server is replaced.
     */
    public fun evictConnections() {
        runCatching { client.connectionPool.evictAll() }
    }

    /** Release both clients' resources. Only for tests and for tearing down a server profile. */
    public fun shutdown() {
        evictConnections()
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.cache?.close() }
    }
}

/**
 * Attaches the right credential for the request's lane, and nothing else.
 *
 * `/api/v1` gets `Authorization: Bearer <companion token>`; Subsonic gets `apiKey=<app-password>`
 * appended to the query. Requests tagged [SkipAuth] (login, the pre-sign-in reachability probe)
 * go out bare. A missing credential is not an error here — the server answers `401` or Subsonic
 * code 44 and the engine maps that to [NetworkError.Unauthorised].
 */
internal class CredentialInterceptor(
    private val credentials: CredentialProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(SkipAuth::class) != null) return chain.proceed(request)
        return chain.proceed(authenticate(request))
    }

    private fun authenticate(request: Request): Request = when (laneOf(request)) {
        ApiLane.V1 -> {
            val token = credentials.bearerToken()
            if (token.isNullOrEmpty() || request.header(HEADER_AUTHORIZATION) != null) {
                request
            } else {
                request.newBuilder().header(HEADER_AUTHORIZATION, "Bearer $token").build()
            }
        }

        ApiLane.Subsonic -> {
            val appPassword = credentials.appPassword()
            if (appPassword.isNullOrEmpty() || request.url.queryParameter(QUERY_API_KEY) != null) {
                request
            } else {
                val url = request.url.newBuilder().addQueryParameter(QUERY_API_KEY, appPassword).build()
                request.newBuilder().url(url).build()
            }
        }

        null -> request
    }

    /**
     * The request's lane: its tag when this module built it, otherwise inferred from the path.
     *
     * Path inference exists for the clients we hand to Media3 and Coil, which build their own
     * requests and cannot carry a tag. A `/api/v1/covers/...` artwork request therefore still gets
     * the bearer, and a `/subsonic/rest/...` request still gets `apiKey` if its URL lacks one.
     */
    private fun laneOf(request: Request): ApiLane? {
        request.tag(ApiLane::class)?.let { return it }
        val path = request.url.encodedPath
        return when {
            path.contains(ServerUrl.SUBSONIC_REST_PREFIX) -> ApiLane.Subsonic
            path.contains("${ServerUrl.API_V1_PREFIX}/") -> ApiLane.V1
            else -> null
        }
    }

    private companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val QUERY_API_KEY = "apiKey"
    }
}

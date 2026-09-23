package app.needler.core.network

import app.needler.core.network.tls.CertificatePinStore
import app.needler.core.network.tls.MutableCertificatePinStore
import app.needler.core.network.tls.PinnedHostTrustManager
import app.needler.core.network.tls.PinnedHostnameVerifier
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.ProtocolException
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
    logSink: NetworkLogSink = NetworkLogSink.Installed,
    loggingEnabled: Boolean = true,
    public val timeouts: NetworkTimeouts = NetworkTimeouts(),
    public val retryPolicy: RetryPolicy = RetryPolicy(),
) {

    /**
     * This client's diagnostic log, shared with everything built on top of it — the HTTP engine,
     * both lane clients and the range downloader — so that a request and the failure it turned
     * into appear under the same tag, in order, in one `adb logcat`.
     *
     * The default [logSink] is [NetworkLogSink.Installed], which resolves through
     * [NetworkDiagnostics] on every line. That means this client does not have to be rebuilt, or
     * even constructed after the application starts, for diagnostics to be switched on; and with
     * nothing installed — a release build — it is [NetworkLogSink.None] and every log call in this
     * module compiles down to a volatile read and a return.
     */
    public val log: NetworkLog = NetworkLog(logSink, loggingEnabled)

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
            // Redirects are followed by RedirectGuardInterceptor instead, which refuses to follow
            // one that leaves the host the user named. See that class.
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(RedirectGuardInterceptor(credentials, log))
            .addInterceptor(ProxyHeaderInterceptor(credentials))
            .addInterceptor(CredentialInterceptor(credentials, log))
            // Last, so that it sees the request as it will actually go out: with the bearer
            // attached, with `apiKey` appended to a Subsonic query, and with the user's proxy
            // headers on it. Everything it is about to log is therefore credential-bearing, and
            // redactUrl is the only thing standing between that and logcat.
            .addInterceptor(RedactingLogInterceptor(log))

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
 *
 * It does, however, log the absence. "There was no bearer to send" and "the server rejected the
 * bearer we sent" both surface to the user as one re-sign-in prompt, and telling them apart from
 * outside the process is otherwise impossible: both produce an identical `401` on the wire. The
 * line names the lane and nothing else — never a credential, never a length, never a prefix,
 * because the presence or absence of a secret is a fact worth logging and any part of its value
 * is not.
 */
internal class CredentialInterceptor(
    private val credentials: CredentialProvider,
    private val log: NetworkLog = NetworkLog.Disabled,
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
                if (token.isNullOrEmpty() && request.header(HEADER_AUTHORIZATION) == null) {
                    log.warn {
                        "V1 " + request.method + " " + redactUrl(request.url) +
                            " sent with no bearer: none is stored, so a 401 here is expected"
                    }
                }
                request
            } else {
                request.newBuilder().header(HEADER_AUTHORIZATION, "Bearer $token").build()
            }
        }

        ApiLane.Subsonic -> {
            val appPassword = credentials.appPassword()
            if (appPassword.isNullOrEmpty() || request.url.queryParameter(QUERY_API_KEY) != null) {
                if (appPassword.isNullOrEmpty() && request.url.queryParameter(QUERY_API_KEY) == null) {
                    log.warn {
                        "Subsonic " + request.method + " " + redactUrl(request.url) +
                            " sent with no apiKey: none is stored, so error 44 here is expected"
                    }
                }
                request
            } else {
                val url = request.url.newBuilder().addQueryParameter(QUERY_API_KEY, appPassword).build()
                request.newBuilder().url(url).build()
            }
        }

        null -> request
    }

    private companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val QUERY_API_KEY = "apiKey"
    }
}

/**
 * The request's lane: its tag when this module built it, otherwise inferred from the path.
 *
 * Path inference exists for the clients we hand to Media3 and Coil, which build their own
 * requests and cannot carry a tag. A `/api/v1/covers/...` artwork request therefore still gets
 * the bearer, and a `/subsonic/rest/...` request still gets `apiKey` if its URL lacks one.
 */
internal fun laneOf(request: Request): ApiLane? {
    request.tag(ApiLane::class)?.let { return it }
    val path = request.url.encodedPath
    return when {
        path.contains(ServerUrl.SUBSONIC_REST_PREFIX) -> ApiLane.Subsonic
        path.contains("${ServerUrl.API_V1_PREFIX}/") -> ApiLane.V1
        else -> null
    }
}

/**
 * Attaches the user's fixed proxy headers, so an edge proxy in front of the server lets the call
 * through.
 *
 * Runs on **every** request both clients make - both API lanes, audio, artwork, and the requests
 * Media3 and Coil build for themselves - because the proxy sits in front of all of them and
 * challenges all of them identically. A `getCoverArt` that is answered with a login page is the
 * same failure as a `ping` that is, and fixing only the JSON lanes would leave artwork and
 * playback broken on exactly the servers this exists for.
 *
 * Unlike the bearer and the `apiKey`, these are sent even on a request tagged [SkipAuth]: the
 * public probe is intercepted by the proxy just like everything else, and it is the first request
 * onboarding makes.
 *
 * The headers only ever go to the host the user saved. A redirect can never carry them somewhere
 * else, because [RedirectGuardInterceptor] refuses to follow a cross-host redirect at all; this
 * check is the second lock on the same door.
 */
internal class ProxyHeaderInterceptor(
    private val credentials: CredentialProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val proxy = credentials.proxyCredentials()
        if (proxy.isEmpty) return chain.proceed(request)

        val saved = credentials.serverUrl()
        if (saved != null && !request.url.host.equals(saved.host, ignoreCase = true)) {
            return chain.proceed(request)
        }

        val builder = request.newBuilder()
        for (header in proxy.headers) {
            builder.header(header.name, header.value)
        }
        return chain.proceed(builder.build())
    }
}

/**
 * Follows redirects by hand, and refuses to follow one that leaves the host the user named.
 *
 * OkHttp's own redirect following happens below every application interceptor, so with it enabled
 * this module never saw the `302` at all: it saw whatever the login page returned, minutes of
 * connect timeout later, and had no way to say what had happened. Worse, following a cross-host
 * redirect **sends the request, and any header on it, to a host the user never typed** - the exact
 * thing a credential must never do.
 *
 * So redirects are off on the client and re-implemented here:
 *
 *  * a redirect to the same host is followed, up to [MAX_HOPS] - that is a base-path or trailing
 *    slash fix-up and is perfectly ordinary;
 *  * a redirect to a different host is [NetworkError.AuthenticatingProxy], thrown on the spot,
 *    which is why the Connect screen now fails in one round trip instead of hanging;
 *  * an `https` to `http` downgrade is not followed, and the redirect is returned as-is.
 *
 * `301`, `302` and `303` turn a non-`GET` into a `GET` without a body, as every browser does;
 * `307` and `308` keep the method and the body, which is what they are for.
 */
internal class RedirectGuardInterceptor(
    private val credentials: CredentialProvider,
    private val log: NetworkLog = NetworkLog.Disabled,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var response = proceed(chain, chain.request())
        var hops = 0

        while (response.isRedirect) {
            val sent = response.request
            val location = response.header("Location")?.trim()
            if (location.isNullOrEmpty()) return response
            val target = sent.url.resolve(location) ?: return response

            val interception = AuthenticatingProxyDetector.fromRedirect(
                response = response,
                target = target,
                proxyCredentialsSent = credentials.proxyCredentials().isNotEmpty,
            )
            if (interception != null) {
                // Logged here rather than left to the caller: this throw happens above the logging
                // interceptor, which saw only an unremarkable 302 and would never mention it again.
                log.error {
                    "redirect from " + redactUrl(sent.url) + " to " + redactUrl(target) +
                        " is an authenticating proxy: " + interception.summary
                }
                response.close()
                throw NetworkError.AuthenticatingProxy(interception, laneOf(sent))
            }

            // Same host, but a downgrade out of TLS. Not followed, and not an interception either:
            // the caller sees the redirect and reports it as a server that is not DroppedNeedle.
            if (sent.url.isHttps && !target.isHttps) {
                log.warn {
                    "refusing an https to http redirect from " + redactUrl(sent.url) +
                        " to " + redactUrl(target)
                }
                return response
            }

            if (hops >= MAX_HOPS) {
                log.error { "too many redirects from " + redactUrl(sent.url) }
                response.close()
                throw NetworkError.Offline(
                    ProtocolException("Too many redirects from " + redactUrl(sent.url)),
                    NetworkError.Offline.Kind.Io,
                )
            }

            val builder = sent.newBuilder().url(target)
            val keepsMethod = response.code == 307 || response.code == 308
            if (!keepsMethod && sent.method != "GET" && sent.method != "HEAD") {
                builder.method("GET", null)
                    .removeHeader("Content-Type")
                    .removeHeader("Content-Length")
                    .removeHeader("Transfer-Encoding")
            }
            response.close()
            response = proceed(chain, builder.build())
            hops++
        }
        return response
    }

    /**
     * `chain.proceed`, with one translation.
     *
     * A `407 Proxy Authentication Required` from the address the user typed never reaches an
     * application interceptor: OkHttp's own retry-and-follow-up stage turns it into a
     * `ProtocolException` first, because a `407` is only meaningful from a configured HTTP proxy.
     * That is the one status whose whole meaning is "a proxy wants a credential", so it is
     * translated here rather than left to surface as a generic unreachable-server error.
     *
     * Deliberately conservative: anything that does not look like that specific case is rethrown
     * untouched, and the fallback is exactly the behaviour there was before.
     */
    private fun proceed(chain: Interceptor.Chain, request: Request): Response = try {
        chain.proceed(request)
    } catch (failure: ProtocolException) {
        val proxyAuth = failure.message?.contains(
            AuthenticatingProxyDetector.HTTP_PROXY_AUTH_REQUIRED.toString(),
        ) == true
        if (!proxyAuth) throw failure
        log.error {
            "407 from " + redactUrl(request.url) + ": a proxy in front of the server wants a credential"
        }
        throw NetworkError.AuthenticatingProxy(
            ProxyInterception(
                proxyHost = null,
                vendor = ProxyVendor.Unknown,
                signal = ProxySignal.ProxyAuthRequired,
                requestedHost = request.url.host,
                requestedUrl = redactUrl(request.url),
                statusCode = AuthenticatingProxyDetector.HTTP_PROXY_AUTH_REQUIRED,
                proxyCredentialsSent = credentials.proxyCredentials().isNotEmpty,
            ),
            laneOf(request),
        )
    }

    private companion object {
        /** OkHttp's own ceiling is 20; a self-hosted server needing more than a handful is broken. */
        const val MAX_HOPS = 5
    }
}

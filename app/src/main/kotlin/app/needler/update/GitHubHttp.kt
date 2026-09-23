package app.needler.update

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * The HTTP clients this package talks to GitHub with — and, much more importantly, **the reason
 * they are not `NeedlerHttpClient`**.
 *
 * ## Do not reuse the app's HTTP client here. Ever.
 *
 * `:core:network`'s `NeedlerHttpClient` is built for one destination: the DroppedNeedle server the
 * listener signed in to. Three of its application interceptors attach the listener's credentials,
 * and **none of them is scoped to a host**:
 *
 *  * `CredentialInterceptor` puts `Authorization: Bearer <companion token>` on every request whose
 *    path looks like `/api/v1/...`, and appends `apiKey=<app-password>` to every request whose path
 *    looks like `/subsonic/rest/...`. The lane is inferred from the *path* when the request carries
 *    no tag — which is exactly the situation a request built outside `:core:network` is in. The only
 *    escape is the module-internal `SkipAuth` tag, which this module cannot even reference.
 *  * `ProxyHeaderInterceptor` attaches the listener's fixed proxy headers. It does check the saved
 *    server's host first, so it is the one that would not have leaked — but it is one `if` away
 *    from not checking, and relying on that is not a security posture.
 *  * `RedactingLogInterceptor` would write github.com traffic into the app's network log alongside
 *    the server's.
 *
 * The concrete failure, had this package reused that client: a `GET` to
 * `https://api.github.com/repos/.../releases/latest` carries no `/api/v1/` or `/subsonic/rest/` in
 * its path, so today it would go out bare. That is luck, not design. The download URL is
 * `https://github.com/timothymumford1986/NeedleApp/releases/download/v1.2.3/needler-v1.2.3.apk`,
 * and GitHub redirects it to `objects.githubusercontent.com` with a signed query string. One future
 * path on either host containing the substring `/api/v1/` — GitHub's own REST API has lived under
 * `/api/v3/` on Enterprise for years, so this is not a fanciful shape — and the listener's private
 * server bearer token would be sent, in a header, to a host they have never heard of, in a request
 * nobody in the app initiated. There would be no error, no log line and no way to notice.
 *
 * REQUIREMENTS.md "Security" puts both secrets in `EncryptedSharedPreferences` under a Keystore
 * master key and says they are "never written to Room, logs, analytics or crash reports". Sending
 * one to github.com would be a larger breach of that rule than any of the three it names. So this
 * package builds its own client, from a bare [OkHttpClient.Builder], with **no interceptors of any
 * kind**, and never touches `CredentialProvider`, `SecureCredentialStore` or anything that can
 * reach them. There is deliberately no constructor parameter here through which a credential could
 * be passed in, and nothing in this package imports from `app.needler.core.network`.
 *
 * ## Unauthenticated, on purpose
 *
 * No `Authorization` header goes to GitHub either — not the listener's, and not a token of ours.
 * The endpoint is public, an embedded token in a sideloaded APK is a published token, and the
 * unauthenticated allowance of 60 requests an hour per IP is sixty times what a once-a-day check
 * needs. Rate limiting is handled by waiting (see [ReleaseLookup.RateLimited]), not by
 * authenticating.
 *
 * ## Redirects are followed here, unlike everywhere else
 *
 * `NeedlerHttpClient` disables OkHttp's redirect following and re-implements it with
 * `RedirectGuardInterceptor`, which refuses to leave the host the listener typed — the right rule
 * when every request carries a credential. Here the opposite is true: the asset download *must*
 * cross from `github.com` to `objects.githubusercontent.com`, and it is safe to let it because
 * there is no credential on the request to carry anywhere. What protects this path is not the
 * redirect policy, it is that the URL we start from is checked against
 * [NeedleAppRepository.DOWNLOAD_URL_PREFIX] before the request is made, and that the bytes are
 * checked against the asset's published size and the platform's signature check afterwards.
 *
 * ## Two clients, one connection pool
 *
 * [api] has a whole-call ceiling, because a JSON check that has not finished in a minute is a check
 * that should be abandoned and retried tomorrow. [download] has none: an APK on a slow connection
 * legitimately takes minutes, and a call timeout would truncate it. `newBuilder()` shares the
 * dispatcher, the connection pool and the TLS configuration, which is the same arrangement
 * `NeedlerHttpClient` uses for its own JSON and media pair.
 */
@Singleton
class GitHubHttp @Inject constructor() {

    /**
     * Metadata calls: the release check, and nothing else.
     *
     * No interceptors. No authenticator. No cookie jar. No cache — a 24-hour cadence makes an HTTP
     * cache pointless, and a cache would put a copy of the response on disk for no benefit.
     */
    val api: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** The APK download. Same client, no whole-call ceiling and a longer read timeout. */
    val download: OkHttpClient by lazy {
        api.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(DOWNLOAD_READ_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The `User-Agent` GitHub requires on every API request.
     *
     * Deliberately carries no version, no device model, no locale and no identifier of any kind.
     * GitHub necessarily sees the IP address; it has no business learning anything else, and this
     * request is made on the app's schedule rather than the listener's, so it should be as close to
     * anonymous as a request can be while still being well-behaved.
     */
    val userAgent: String get() = USER_AGENT

    private companion object {
        const val CONNECT_SECONDS = 10L
        const val READ_SECONDS = 20L
        const val CALL_SECONDS = 60L
        const val DOWNLOAD_READ_SECONDS = 60L
        const val USER_AGENT = "Needler (+https://github.com/timothymumford1986/NeedleApp)"
    }
}

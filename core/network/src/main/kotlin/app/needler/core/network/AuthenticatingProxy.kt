package app.needler.core.network

import okhttp3.HttpUrl
import okhttp3.Response

/**
 * Which forward-auth product is standing in front of the server, when the answer says enough to
 * name it.
 *
 * Naming it is not cosmetic. "Cloudflare Access is asking you to sign in" tells a self-hoster
 * exactly which dashboard to open and which fix to reach for; "something intercepted the request"
 * leaves them guessing. Anything unrecognised is still reported - the behaviour is identical, only
 * the wording is vaguer.
 */
public enum class ProxyVendor(public val displayName: String) {
    CloudflareAccess("Cloudflare Access"),
    Authelia("Authelia"),
    Authentik("authentik"),
    OAuth2Proxy("OAuth2 Proxy"),

    /** Detected by behaviour rather than by a fingerprint. */
    Unknown("an authenticating proxy"),
}

/** Which of the three rules fired. Kept on the error so a bug report says what was observed. */
public enum class ProxySignal {

    /**
     * An API call was answered with a redirect to a **different host** - the shape Cloudflare
     * Access, Authelia and authentik all produce when a session cookie is missing.
     */
    CrossHostRedirect,

    /** HTML arrived where this client asked for JSON, and it reads as a sign-in page. */
    HtmlInsteadOfJson,

    /** `407 Proxy Authentication Required`, which is the case saying so in the status line. */
    ProxyAuthRequired,
}

/**
 * One observed interception, with everything the Connect screen needs to explain it and nothing
 * that could be a secret.
 *
 * [requestedUrl] is always run through [redactUrl] before it gets here, so this object is safe to
 * put in a log line, an error message or a bug report.
 */
public data class ProxyInterception(
    /** The host that answered for the server - the login host on a redirect, else the server. */
    public val proxyHost: String?,
    public val vendor: ProxyVendor,
    public val signal: ProxySignal,
    /** The host the app actually asked for: the address the user typed. */
    public val requestedHost: String,
    /** Redacted URL of the intercepted request. */
    public val requestedUrl: String,
    public val statusCode: Int,
    /** True when proxy headers were attached and the proxy refused them anyway. */
    public val proxyCredentialsSent: Boolean,
) {

    /** The host to name on screen: the proxy's when it identified itself, else the server's. */
    public val describedHost: String get() = proxyHost ?: requestedHost

    /** One line, credential-free, for a log or an error message. */
    public val summary: String
        get() = "Intercepted by " + vendor.displayName + " at " + describedHost +
            " (" + signal.name + ", HTTP " + statusCode + ")"
}

/**
 * Recognises an authenticating proxy from what came back, without special-casing one vendor.
 *
 * Three rules, and each one is a thing a healthy DroppedNeedle never does:
 *
 *  1. **[ProxySignal.CrossHostRedirect]** - an API request answered `30x` with a `Location` on a
 *     different host. DroppedNeedle serves both lanes from the address the user typed and never
 *     bounces them elsewhere, so this can only be something in front of it. It is also the exact
 *     shape of a Cloudflare Access challenge.
 *  2. **[ProxySignal.HtmlInsteadOfJson]** - an HTML body where the JSON lanes were asked for,
 *     **and** either an auth status (`401`, `403`) or markup that reads as a sign-in page. Both
 *     lanes answer `application/json` on every path, success or failure, so HTML is never this
 *     server. The second half of the condition is what keeps the rule off an HTML `404` or a `502`
 *     page, which mean "wrong address" and "server down" and already have their own messages.
 *  3. **[ProxySignal.ProxyAuthRequired]** - HTTP `407`, which cannot mean anything else.
 *
 * A vendor fingerprint (a `CF_AppSession` cookie, a `cloudflareaccess.com` host, an
 * `authelia_session` cookie) is enough on its own to name the vendor, but never enough on its own
 * to declare an interception: rule 1, 2 or 3 must fire first.
 */
public object AuthenticatingProxyDetector {

    /** How much of a body is sniffed. A login page announces itself well inside this. */
    public const val BODY_SNIFF_CHARS: Int = 4_096

    /** `407 Proxy Authentication Required`. */
    public const val HTTP_PROXY_AUTH_REQUIRED: Int = 407

    /**
     * Rule 1. [target] is the resolved `Location`; returns null for a same-host redirect, which is
     * a base-path or scheme fix-up and is followed normally.
     */
    public fun fromRedirect(
        response: Response,
        target: HttpUrl,
        proxyCredentialsSent: Boolean,
    ): ProxyInterception? {
        val requested: HttpUrl = response.request.url
        if (target.host.equals(requested.host, ignoreCase = true)) return null
        return ProxyInterception(
            proxyHost = target.host,
            vendor = vendorOf(response, target.toString(), null),
            signal = ProxySignal.CrossHostRedirect,
            requestedHost = requested.host,
            requestedUrl = redactUrl(requested),
            statusCode = response.code,
            proxyCredentialsSent = proxyCredentialsSent,
        )
    }

    /**
     * Rules 2 and 3, for a response this client expected JSON from.
     *
     * @param bodyPrefix the first [BODY_SNIFF_CHARS] characters of the body, or null when the body
     *   has not been read (a binary endpoint, where `Content-Type` alone decides).
     */
    public fun fromResponse(
        response: Response,
        bodyPrefix: String?,
        proxyCredentialsSent: Boolean,
    ): ProxyInterception? {
        val requested: HttpUrl = response.request.url
        val snippet: String? = bodyPrefix?.take(BODY_SNIFF_CHARS)

        val signal: ProxySignal = when {
            response.code == HTTP_PROXY_AUTH_REQUIRED -> ProxySignal.ProxyAuthRequired
            looksLikeHtml(response, snippet) && challengesForSignIn(response, snippet) ->
                ProxySignal.HtmlInsteadOfJson
            else -> return null
        }

        return ProxyInterception(
            proxyHost = proxyHostOf(response) ?: requested.host,
            vendor = vendorOf(response, null, snippet),
            signal = signal,
            requestedHost = requested.host,
            requestedUrl = redactUrl(requested),
            statusCode = response.code,
            proxyCredentialsSent = proxyCredentialsSent,
        )
    }

    /** True when the body is HTML, by declared type or by its first bytes. */
    public fun looksLikeHtml(response: Response, bodyPrefix: String?): Boolean {
        val contentType: String = response.header("Content-Type").orEmpty().lowercase()
        if (contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")) {
            return true
        }
        val start: String = bodyPrefix.orEmpty().trimStart().take(64).lowercase()
        return start.startsWith("<!doctype html") || start.startsWith("<html")
    }

    /**
     * Names the product when it left a fingerprint: its own host, one of its cookies, or one of
     * its headers. Unrecognised is [ProxyVendor.Unknown], never an error.
     */
    public fun vendorOf(
        response: Response,
        redirectTarget: String?,
        bodyPrefix: String?,
    ): ProxyVendor {
        val haystack: String = buildString {
            append(redirectTarget.orEmpty()).append("\n")
            append(response.header("Location").orEmpty()).append("\n")
            for (index in 0 until response.headers.size) {
                val name: String = response.headers.name(index).lowercase()
                if (name in FINGERPRINT_HEADERS || name.startsWith("cf-") ||
                    name.startsWith("x-authentik") || name.startsWith("x-authelia")
                ) {
                    append(name).append(":").append(response.headers.value(index)).append("\n")
                }
            }
            append(bodyPrefix.orEmpty())
        }.lowercase()

        return when {
            haystack.contains("cloudflareaccess.com") ||
                haystack.contains("cf_appsession") ||
                haystack.contains("cf_authorization") ||
                haystack.contains("cf-access-") ||
                haystack.contains("cloudflare access") -> ProxyVendor.CloudflareAccess

            haystack.contains("authelia") -> ProxyVendor.Authelia
            haystack.contains("authentik") -> ProxyVendor.Authentik
            haystack.contains("_oauth2_proxy") || haystack.contains("oauth2-proxy") ->
                ProxyVendor.OAuth2Proxy

            else -> ProxyVendor.Unknown
        }
    }

    /**
     * The half of rule 2 that keeps it off an ordinary error page.
     *
     * An auth status is proof by itself. Otherwise the markup has to read as a sign-in page: a
     * password input, a form, or the words a login page uses. A single-page app's catch-all
     * `index.html` and a `404` from an unrelated web server have neither, and keep the message they
     * already had - "that is not a Dropped Needle server".
     */
    private fun challengesForSignIn(response: Response, bodyPrefix: String?): Boolean {
        if (response.code == 401 || response.code == 403) return true
        if (vendorOf(response, null, bodyPrefix) != ProxyVendor.Unknown) return true
        val body: String = bodyPrefix.orEmpty().lowercase()
        if (body.isEmpty()) return false
        return LOGIN_PAGE_HINTS.any { body.contains(it) }
    }

    /** The host named by a `Location` header, when there is one. */
    private fun proxyHostOf(response: Response): String? {
        val location: String = response.header("Location") ?: return null
        val resolved: HttpUrl = response.request.url.resolve(location) ?: return null
        return resolved.host
    }

    private val FINGERPRINT_HEADERS: Set<String> = setOf(
        "set-cookie",
        "server",
        "www-authenticate",
        "proxy-authenticate",
    )

    private val LOGIN_PAGE_HINTS: List<String> = listOf(
        "type=\"password\"",
        "type=password",
        "sign in",
        "signin",
        "log in",
        "login",
        "single sign-on",
        "authenticate",
    )
}

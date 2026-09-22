package app.needler.core.network

import java.util.Base64

/**
 * One fixed request header the user has asked Needler to send to their server.
 *
 * Deliberately **not** a data class: a generated `toString` on a credential holder is how a secret
 * ends up in a crash report. [toString] here names the header and redacts the value, exactly as
 * [redactUrl] does for a credential-bearing query parameter.
 */
public class ProxyHeader(
    public val name: String,
    public val value: String,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProxyHeader) return false
        return name.equals(other.name, ignoreCase = true) && value == other.value
    }

    override fun hashCode(): Int = 31 * name.lowercase().hashCode() + value.hashCode()

    /** Name in the clear, value redacted. Safe to log. */
    override fun toString(): String = name + ": REDACTED"
}

/** Why a header the user typed cannot be sent. Each one is shown as-is on the Connect screen. */
public enum class ProxyHeaderProblem(public val message: String) {
    BlankName("A header needs a name."),
    IllegalName("A header name can only contain letters, digits and ! # $ % & ' * + - . ^ _ ` | ~"),
    ReservedName("Needler sends that header itself. Choose another name."),
    BlankValue("A header needs a value."),
    IllegalValue("A header value cannot contain a line break or a control character."),
}

/**
 * The fixed headers Needler attaches to every request to the saved server, so that an edge proxy
 * in front of it lets the app through.
 *
 * ## Why this is a list of headers and not a Cloudflare feature
 *
 * The wall is the same everywhere: Cloudflare Access, Authelia, authentik, `oauth2-proxy` and a
 * plain basic-auth reverse proxy all sit in front of the server and refuse anything that arrives
 * without a credential *they* recognise. Each one names its credential differently and every one of
 * them accepts it as one or two request headers. So the mechanism is a header list, and the vendors
 * become [ProxyPresets] on top of it - which is also the only shape that serves the self-hoster
 * whose gateway wants some header nobody has ever heard of.
 *
 * ## Every value is a secret
 *
 * A Cloudflare client secret, a base64 basic-auth pair and an API gateway key are all credentials.
 * They live with the companion bearer and the app-password in Keystore-backed storage, they are
 * never logged (see [RedactingLogInterceptor], which logs no request headers at all), and they
 * never appear in an error message or on screen after they are typed.
 *
 * ## What cannot be set
 *
 * [RESERVED_NAMES] are refused. `Authorization` carries the companion bearer on the `/api/v1` lane,
 * so a proxy header of that name would silently replace it and turn every call into a `401` with
 * nothing on screen to explain it. A proxy that demands basic auth in `Authorization` is therefore
 * unsupportable by construction; `Proxy-Authorization` - RFC 7235's header for exactly this
 * conversation - is what [ProxyPresets.basicAuth] writes, and it collides with nothing.
 *
 * The Subsonic lane's credential is the `apiKey` **query parameter**, not a header, so no header
 * name can clobber it. The transport headers Needler sets for range downloads are reserved too,
 * because overriding those breaks resume rather than authentication.
 */
public class ProxyCredentials private constructor(
    public val headers: List<ProxyHeader>,
) {

    public val isEmpty: Boolean get() = headers.isEmpty()

    public val isNotEmpty: Boolean get() = headers.isNotEmpty()

    /**
     * One line per header, `name: value`, for the encrypted store.
     *
     * A newline separator is unambiguous because [validate] rejects control characters in both
     * halves, so no name or value can contain one.
     */
    public fun encode(): String = headers.joinToString("\n") { it.name + ": " + it.value }

    /** Names only, for a diagnostic line. Never the values. */
    override fun toString(): String =
        "ProxyCredentials(" + headers.joinToString(", ") { it.name } + ")"

    override fun equals(other: Any?): Boolean =
        this === other || (other is ProxyCredentials && headers == other.headers)

    override fun hashCode(): Int = headers.hashCode()

    public companion object {

        /** No proxy in front of the server: what almost every user has. */
        public val None: ProxyCredentials = ProxyCredentials(emptyList())

        /** More than this is a misunderstanding, not a deployment. */
        public const val MAX_HEADERS: Int = 8

        /**
         * Header names Needler sends itself. Case-insensitive.
         *
         * `Authorization` is the companion bearer. `Host`, `Content-Length` and
         * `Transfer-Encoding` are OkHttp's to set. `Range` and `Accept-Encoding` are what makes a
         * download resumable.
         */
        public val RESERVED_NAMES: Set<String> = setOf(
            "authorization",
            "host",
            "content-length",
            "content-type",
            "transfer-encoding",
            "range",
            "accept-encoding",
        )

        /** RFC 7230 `token`: what a header name may contain. */
        private val TOKEN = Regex("^[!#\$%&'*+\\-.^_`|~0-9A-Za-z]+$")

        /**
         * Whether one name/value pair can be sent, or why not.
         *
         * Rejecting a control character is not tidiness: a `\r\n` in a header value is request
         * splitting, and OkHttp would throw at request-build time deep inside an interceptor where
         * the failure reads as a network error.
         */
        public fun validate(name: String, value: String): ProxyHeaderProblem? {
            val trimmedName: String = name.trim()
            return when {
                trimmedName.isEmpty() -> ProxyHeaderProblem.BlankName
                !TOKEN.matches(trimmedName) -> ProxyHeaderProblem.IllegalName
                trimmedName.lowercase() in RESERVED_NAMES -> ProxyHeaderProblem.ReservedName
                value.isEmpty() -> ProxyHeaderProblem.BlankValue
                value.any { it.code < 0x20 || it.code == 0x7f } -> ProxyHeaderProblem.IllegalValue
                else -> null
            }
        }

        /**
         * Builds a credential set, dropping any pair that does not validate.
         *
         * Dropping rather than throwing is deliberate: this is also the decode path for whatever is
         * on disk, and one corrupt row must not make the app unable to reach a server it was
         * reaching yesterday.
         */
        public fun of(headers: List<ProxyHeader>): ProxyCredentials {
            val kept: List<ProxyHeader> = headers
                .map { ProxyHeader(it.name.trim(), it.value) }
                .filter { validate(it.name, it.value) == null }
                .distinctBy { it.name.lowercase() }
                .take(MAX_HEADERS)
            return if (kept.isEmpty()) None else ProxyCredentials(kept)
        }

        /** As [of], for a handful of literal pairs. */
        public fun of(vararg headers: Pair<String, String>): ProxyCredentials =
            of(headers.map { ProxyHeader(it.first, it.second) })

        /** Reads back [encode]. Unparseable lines are skipped, never fatal. */
        public fun decode(raw: String?): ProxyCredentials {
            if (raw.isNullOrEmpty()) return None
            val parsed: List<ProxyHeader> = raw.split("\n").mapNotNull { line ->
                val separator: Int = line.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                ProxyHeader(
                    name = line.substring(0, separator).trim(),
                    value = line.substring(separator + 1).trimStart(),
                )
            }
            return of(parsed)
        }
    }
}

/**
 * The two arrangements common enough that typing header names by hand would be a chore, plus the
 * escape hatch for everything else.
 *
 * These build a [ProxyCredentials] and nothing more: the presets exist in the UI, but nothing
 * downstream of this file knows which one was used. That is what keeps the transport vendor-neutral
 * while still letting the Connect screen say "Cloudflare Access" to the people looking for it.
 */
public object ProxyPresets {

    /** Cloudflare's own header names for a service token. */
    public const val CLOUDFLARE_CLIENT_ID: String = "CF-Access-Client-Id"
    public const val CLOUDFLARE_CLIENT_SECRET: String = "CF-Access-Client-Secret"

    /**
     * RFC 7235's header for authenticating to an intermediary, which is precisely what a
     * forward-auth proxy is. `Authorization` is not an option here - see [ProxyCredentials].
     */
    public const val PROXY_AUTHORIZATION: String = "Proxy-Authorization"

    /**
     * A Cloudflare Access service token: the id and secret minted in the Zero Trust dashboard.
     *
     * Returns [ProxyCredentials.None] when either half is missing, so a half-typed form sends
     * nothing rather than a header the proxy will reject.
     */
    public fun cloudflareAccess(clientId: String?, clientSecret: String?): ProxyCredentials {
        val id: String = clientId?.trim().orEmpty()
        val secret: String = clientSecret?.trim().orEmpty()
        if (id.isEmpty() || secret.isEmpty()) return ProxyCredentials.None
        return ProxyCredentials.of(
            CLOUDFLARE_CLIENT_ID to id,
            CLOUDFLARE_CLIENT_SECRET to secret,
        )
    }

    /** HTTP basic auth to the proxy, base64-encoded into [PROXY_AUTHORIZATION]. */
    public fun basicAuth(username: String?, password: String?): ProxyCredentials {
        val user: String = username?.trim().orEmpty()
        val secret: String = password.orEmpty()
        if (user.isEmpty() || secret.isEmpty()) return ProxyCredentials.None
        val encoded: String = Base64.getEncoder()
            .encodeToString((user + ":" + secret).toByteArray(Charsets.UTF_8))
        return ProxyCredentials.of(PROXY_AUTHORIZATION to "Basic " + encoded)
    }
}

/**
 * Read and write access to the saved proxy headers, for the one screen that collects them.
 *
 * Separate from [CredentialProvider], which is read-only and exists for the interceptors. The
 * Connect screen needs to *write* the headers before the first request goes out - the public probe
 * is intercepted exactly like everything else, so it has to carry them - and `SessionRepository`
 * has no parameter for them, so this narrow interface is what `:app` injects.
 *
 * Implemented by `:core:data`'s `SecureCredentialStore`, so the values land in
 * `EncryptedSharedPreferences` under the Keystore master key with the other two secrets.
 */
public interface ProxyCredentialStore {

    /** The saved headers, or [ProxyCredentials.None] when there is no proxy (the usual case). */
    public fun proxyCredentials(): ProxyCredentials

    /**
     * Saves or clears the headers. Returns false when the write did not reach disk, exactly as the
     * other credential writes do.
     */
    public fun saveProxyCredentials(credentials: ProxyCredentials): Boolean
}

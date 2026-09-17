package app.needler.core.network

/**
 * A validated, normalised DroppedNeedle server address.
 *
 * REQUIREMENTS.md "Accepted URL forms" lists four shapes that must all work:
 *
 *  * `https://music.yourhome.net`            — scheme given, no port
 *  * `http://192.168.1.50:8688`              — cleartext on a LAN, explicit port
 *  * `https://home.net/music`                — sub-path deployment (server `base_path`)
 *  * `192.168.1.50` / `192.168.1.50:8688`    — bare host, defaulted to `http://` port 8688
 *
 * Normalisation rules applied here:
 *  * surrounding whitespace is trimmed;
 *  * the scheme and host are lower-cased (the base path is **not** — the server's
 *    `normalize_base_path` accepts `[A-Za-z0-9._~-]` segments case-sensitively);
 *  * trailing slashes are trimmed from the base path, and an empty path becomes `""`;
 *  * a missing scheme defaults to `http` **and**, when no port was typed, port `8688`;
 *  * an explicit scheme keeps the protocol default port (80/443), which is left implicit.
 *
 * This class is deliberately pure Kotlin (no OkHttp, no Android) so it is unit-testable
 * on the JVM and cheap to use from any layer.
 */
public class ServerUrl internal constructor(
    /** `http` or `https`, lower-case. */
    public val scheme: String,
    /** Host or IP literal, lower-case. IPv6 literals are stored **without** brackets. */
    public val host: String,
    /** Explicit port, or `null` when the scheme default (80/443) applies. */
    public val port: Int?,
    /** `""` or a canonical `/seg[/seg…]` deployment base path, never with a trailing slash. */
    public val basePath: String,
    /** True when no scheme was typed and `http` was assumed. */
    public val schemeWasAssumed: Boolean,
    /** True when no port was typed and [DEFAULT_PORT] was assumed. */
    public val portWasAssumed: Boolean,
) {

    /** Host as it appears in a URL — IPv6 literals re-bracketed. */
    public val urlHost: String
        get() = if (host.contains(':')) "[$host]" else host

    /** True for a plain-HTTP origin. The `:app` manifest must permit cleartext for these. */
    public val isCleartext: Boolean get() = scheme == "http"

    /**
     * Origin plus base path, with no trailing slash — e.g. `https://home.net/music`.
     * Every request URL in this module is built by appending an absolute path to this.
     */
    public val baseUrl: String
        get() = buildString {
            append(scheme).append("://").append(urlHost)
            if (port != null) append(':').append(port)
            append(basePath)
        }

    /** Absolute URL for a path under the `/api/v1` lane, e.g. `apiV1("/version")`. */
    public fun apiV1(path: String): String = baseUrl + API_V1_PREFIX + normaliseSuffix(path)

    /** Absolute URL for a Subsonic REST method, e.g. `subsonicRest("ping")`. */
    public fun subsonicRest(method: String): String =
        baseUrl + SUBSONIC_REST_PREFIX + method.trim().trimStart('/')

    /** Two servers are the same identity when origin and base path match. */
    public fun sameServerAs(other: ServerUrl): Boolean =
        scheme == other.scheme &&
            host == other.host &&
            effectivePort() == other.effectivePort() &&
            basePath == other.basePath

    /** Port actually dialled, filling in the scheme default. */
    public fun effectivePort(): Int = port ?: if (scheme == "https") 443 else 80

    override fun toString(): String = baseUrl

    /**
     * Written out by hand rather than generated: the constructor is internal so that a
     * [ServerUrl] can only come from [parse], and a data class would expose an unvalidated
     * `copy()` around that.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ServerUrl) return false
        return scheme == other.scheme &&
            host == other.host &&
            port == other.port &&
            basePath == other.basePath
    }

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + (port ?: 0)
        result = 31 * result + basePath.hashCode()
        return result
    }

    public companion object {
        /** DroppedNeedle's own default listen port, used when no scheme and no port are typed. */
        public const val DEFAULT_PORT: Int = 8688

        public const val API_V1_PREFIX: String = "/api/v1"
        public const val SUBSONIC_REST_PREFIX: String = "/subsonic/rest/"

        /** Mirrors the server's `MAX_BASE_PATH_LENGTH`. */
        public const val MAX_BASE_PATH_LENGTH: Int = 256

        private val HOST_NAME = Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*\\.?$")
        private val IPV6_CHARS = Regex("^[0-9A-Fa-f:.]+$")

        /** Same character class the server's `normalize_base_path` allows per segment. */
        private val BASE_PATH_SEGMENT = Regex("^[A-Za-z0-9._~-]+$")

        /**
         * Parse and normalise whatever the user typed on the Connect screen.
         *
         * Never throws: every rejection comes back as [ServerUrlResult.Invalid] with a
         * [ServerUrlResult.Reason] the Connect screen can turn into a message.
         */
        public fun parse(raw: String?): ServerUrlResult {
            val input = raw?.trim().orEmpty()
            if (input.isEmpty()) return invalid(ServerUrlResult.Reason.Empty, "No server address entered")
            if (input.any { it.isWhitespace() }) {
                return invalid(ServerUrlResult.Reason.Malformed, "The address contains a space")
            }
            if (input.any { it.code < 0x20 || it.code == 0x7f }) {
                return invalid(ServerUrlResult.Reason.Malformed, "The address contains a control character")
            }

            val schemeSeparator = input.indexOf("://")
            val schemeWasAssumed: Boolean
            val scheme: String
            val rest: String
            if (schemeSeparator > 0) {
                scheme = input.substring(0, schemeSeparator).lowercase()
                if (scheme != "http" && scheme != "https") {
                    return invalid(ServerUrlResult.Reason.UnsupportedScheme, "Only http:// and https:// are supported")
                }
                rest = input.substring(schemeSeparator + 3)
                schemeWasAssumed = false
            } else {
                // No scheme typed: the doc's fourth form, defaulted to http on DroppedNeedle's port.
                scheme = "http"
                rest = input
                schemeWasAssumed = true
            }

            if (rest.isEmpty()) return invalid(ServerUrlResult.Reason.MissingHost, "No host in the address")
            if (rest.contains('?') || rest.contains('#')) {
                return invalid(
                    ServerUrlResult.Reason.QueryOrFragment,
                    "A server address cannot carry a query string or fragment",
                )
            }
            if (rest.contains('@')) {
                return invalid(
                    ServerUrlResult.Reason.CredentialsInUrl,
                    "Sign-in details go in the username and password fields, not the address",
                )
            }
            if (rest.contains('\\')) return invalid(ServerUrlResult.Reason.Malformed, "Use forward slashes in the address")

            // Split authority from path.
            val pathStart = rest.indexOf('/')
            val authority = if (pathStart >= 0) rest.substring(0, pathStart) else rest
            val rawPath = if (pathStart >= 0) rest.substring(pathStart) else ""
            if (authority.isEmpty()) return invalid(ServerUrlResult.Reason.MissingHost, "No host in the address")

            // Authority -> host + optional port, IPv6-literal aware.
            val host: String
            val portText: String?
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return invalid(ServerUrlResult.Reason.InvalidHost, "Unclosed IPv6 address")
                host = authority.substring(1, close).lowercase()
                val tail = authority.substring(close + 1)
                portText = when {
                    tail.isEmpty() -> null
                    tail.startsWith(":") -> tail.substring(1)
                    else -> return invalid(ServerUrlResult.Reason.InvalidHost, "Unexpected text after the IPv6 address")
                }
                if (host.isEmpty() || !IPV6_CHARS.matches(host) || !host.contains(':')) {
                    return invalid(ServerUrlResult.Reason.InvalidHost, "Not a valid IPv6 address")
                }
            } else {
                val colon = authority.lastIndexOf(':')
                if (authority.count { it == ':' } > 1) {
                    return invalid(ServerUrlResult.Reason.InvalidHost, "Bracket an IPv6 address, for example http://[fd00::1]:8688")
                }
                if (colon >= 0) {
                    host = authority.substring(0, colon).lowercase()
                    portText = authority.substring(colon + 1)
                } else {
                    host = authority.lowercase()
                    portText = null
                }
                if (host.isEmpty()) return invalid(ServerUrlResult.Reason.MissingHost, "No host in the address")
                if (!HOST_NAME.matches(host)) {
                    return invalid(ServerUrlResult.Reason.InvalidHost, "'$host' is not a valid host name or IP address")
                }
            }

            val port: Int?
            val portWasAssumed: Boolean
            if (portText != null) {
                if (portText.isEmpty() || portText.length > 5 || portText.any { !it.isDigit() }) {
                    return invalid(ServerUrlResult.Reason.InvalidPort, "'$portText' is not a valid port")
                }
                val value = portText.toInt()
                if (value < 1 || value > 65535) {
                    return invalid(ServerUrlResult.Reason.InvalidPort, "Port $value is out of range")
                }
                port = if (isDefaultPort(scheme, value)) null else value
                portWasAssumed = false
            } else if (schemeWasAssumed) {
                port = DEFAULT_PORT
                portWasAssumed = true
            } else {
                port = null
                portWasAssumed = false
            }

            val basePath = when (val result = normaliseBasePath(rawPath)) {
                is BasePath.Ok -> result.value
                is BasePath.Bad -> return invalid(result.reason, result.detail)
            }

            return ServerUrlResult.Valid(
                ServerUrl(
                    scheme = scheme,
                    host = host.removeSuffix("."),
                    port = port,
                    basePath = basePath,
                    schemeWasAssumed = schemeWasAssumed,
                    portWasAssumed = portWasAssumed,
                ),
            )
        }

        /** Convenience for call sites that already know the input is good (tests, restored state). */
        public fun parseOrNull(raw: String?): ServerUrl? = (parse(raw) as? ServerUrlResult.Valid)?.url

        private fun isDefaultPort(scheme: String, port: Int): Boolean =
            (scheme == "http" && port == 80) || (scheme == "https" && port == 443)

        private fun invalid(reason: ServerUrlResult.Reason, detail: String) = ServerUrlResult.Invalid(reason, detail)

        private sealed interface BasePath {
            data class Ok(val value: String) : BasePath
            data class Bad(val reason: ServerUrlResult.Reason, val detail: String) : BasePath
        }

        private fun normaliseBasePath(rawPath: String): BasePath {
            val trimmed = rawPath.trimEnd('/')
            if (trimmed.isEmpty()) return BasePath.Ok("")
            if (trimmed.length > MAX_BASE_PATH_LENGTH) {
                return BasePath.Bad(
                    ServerUrlResult.Reason.BasePathTooLong,
                    "The sub-path is longer than $MAX_BASE_PATH_LENGTH characters",
                )
            }
            if (trimmed.contains('%')) {
                return BasePath.Bad(
                    ServerUrlResult.Reason.InvalidBasePath,
                    "The sub-path cannot contain percent escapes",
                )
            }
            val segments = trimmed.removePrefix("/").split('/')
            for (segment in segments) {
                if (segment.isEmpty()) {
                    return BasePath.Bad(ServerUrlResult.Reason.InvalidBasePath, "The sub-path has an empty segment")
                }
                if (segment == "." || segment == "..") {
                    return BasePath.Bad(ServerUrlResult.Reason.InvalidBasePath, "The sub-path cannot contain '.' or '..'")
                }
                if (!BASE_PATH_SEGMENT.matches(segment)) {
                    return BasePath.Bad(
                        ServerUrlResult.Reason.InvalidBasePath,
                        "'$segment' is not a valid sub-path segment",
                    )
                }
            }
            return BasePath.Ok("/" + segments.joinToString("/"))
        }

        private fun normaliseSuffix(path: String): String {
            if (path.isEmpty() || path == "/") return ""
            return if (path.startsWith("/")) path else "/$path"
        }
    }
}

/** Outcome of [ServerUrl.parse]. */
public sealed interface ServerUrlResult {

    public data class Valid(public val url: ServerUrl) : ServerUrlResult

    public data class Invalid(
        public val reason: Reason,
        /** Human-readable, safe to show on the Connect screen. Contains no credentials. */
        public val detail: String,
    ) : ServerUrlResult

    public enum class Reason {
        Empty,
        UnsupportedScheme,
        MissingHost,
        InvalidHost,
        InvalidPort,
        CredentialsInUrl,
        QueryOrFragment,
        InvalidBasePath,
        BasePathTooLong,
        Malformed,
    }
}

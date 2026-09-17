package app.needler.core.network

import java.io.IOException

/** Which of DroppedNeedle's two HTTP surfaces a call used. */
public enum class ApiLane {
    /** `/api/v1/*`, `Authorization: Bearer <companion token>`. */
    V1,

    /** `/subsonic/rest/*`, `apiKey=<app-password>`. */
    Subsonic,
}

/**
 * Every failure this module can produce, one subclass per row of REQUIREMENTS.md
 * §"Failure handling" (plus the TLS row from §"Self-signed certificates").
 *
 * All suspend functions on [app.needler.core.network.v1.V1Api],
 * [app.needler.core.network.subsonic.SubsonicApi] and
 * [app.needler.core.network.media.RangeDownloader] throw only this type.
 *
 * Messages are deliberately terse and are safe to write to the diagnostics log: no message ever
 * contains a bearer token, an app-password, or a URL query string (see [redactUrl]).
 */
public sealed class NetworkError(
    message: String,
    override val cause: Throwable? = null,
) : IOException(message, cause) {

    /**
     * `401` on `/api/v1` — the companion bearer expired or was revoked. Playback must keep
     * working; the app shows a non-blocking re-sign-in prompt.
     *
     * On the Subsonic lane this is error code 40 (wrong credentials) or 44 (invalid apiKey),
     * which instead means the app-password is dead and full re-onboarding is required.
     * [requiresReonboarding] distinguishes the two.
     */
    public data class Unauthorised(
        public val lane: ApiLane,
        /** Subsonic `error.code` (40/41/42/44) when [lane] is [ApiLane.Subsonic]. */
        public val subsonicCode: Int? = null,
    ) : NetworkError("Unauthorised on the ${lane.name} lane" + (subsonicCode?.let { " (code $it)" } ?: "")) {
        public val requiresReonboarding: Boolean
            get() = lane == ApiLane.Subsonic && (subsonicCode == 40 || subsonicCode == 44)
    }

    /**
     * Subsonic answered `status=failed` with code 0 and the "API is disabled" message: an
     * administrator has not turned on `subsonic_enabled`. Distinct from [Unauthorised] because
     * the UI must name the exact server setting instead of asking the user to sign in again.
     */
    public data object SubsonicProtocolDisabled :
        NetworkError("The Subsonic API is disabled on this server")

    /**
     * `429`, or a Subsonic rate-limit envelope. [retryAfterSeconds] is the server's
     * `Retry-After`, clamped to at least one second (the `/api/v1` limiter can emit `0`).
     *
     * The transcode ceiling is one concurrent transcode per user and two server-wide, so this
     * is the expected answer when a second device or a Cast session is already transcoding:
     * fall back to `format=raw` for that track.
     */
    public data class RateLimited(
        public val retryAfterSeconds: Long?,
        public val lane: ApiLane,
    ) : NetworkError("Rate limited on the ${lane.name} lane" + (retryAfterSeconds?.let { ", retry after ${it}s" } ?: ""))

    /**
     * `416` on a range request — the cached length is wrong. Discard the partial file and refetch.
     * [completeLength] is parsed from `Content-Range: bytes * /<size>` when the server sent it.
     */
    public data class RangeNotSatisfiable(
        public val completeLength: Long? = null,
    ) : NetworkError("Range not satisfiable" + (completeLength?.let { ", complete length $it" } ?: ""))

    /**
     * `403`, or Subsonic code 50. On a download this means library download is admin-gated off,
     * so every pin and download affordance must be hidden.
     */
    public data class Forbidden(
        public val lane: ApiLane,
        public val serverMessage: String? = null,
    ) : NetworkError("Forbidden on the ${lane.name} lane" + (serverMessage?.let { ": $it" } ?: ""))

    /** `404`, or Subsonic code 70. The row is gone from the server. */
    public data class NotFound(
        public val lane: ApiLane,
        public val serverMessage: String? = null,
    ) : NetworkError("Not found on the ${lane.name} lane" + (serverMessage?.let { ": $it" } ?: ""))

    /**
     * Timeout, DNS failure, connection refused, no route, socket closed mid-body. Treated as
     * offline: serve the Room mirror and cached audio, and journal mutations for replay.
     */
    public data class Offline(
        public override val cause: Throwable,
        public val kind: Kind = Kind.Io,
    ) : NetworkError("Server unreachable (${kind.name}): ${cause.javaClass.simpleName}", cause) {
        public enum class Kind { Timeout, Dns, Io }
    }

    /**
     * TLS validation failed and no pin matched. The Connect screen shows the certificate's
     * fingerprint, subject and expiry and offers to pin it for this one host.
     * Use [app.needler.core.network.tls.TlsCertificateProbe] to read the certificate to show.
     */
    public data class TlsNotTrusted(
        public val host: String,
        public override val cause: Throwable,
    ) : NetworkError("Certificate for $host is not trusted", cause)

    /** `5xx`. Callers back off exponentially with jitter, capped at five minutes. */
    public data class Server(
        public val statusCode: Int,
        public val lane: ApiLane,
        public val retryAfterSeconds: Long? = null,
        public val serverMessage: String? = null,
    ) : NetworkError("Server error $statusCode on the ${lane.name} lane" + (serverMessage?.let { ": $it" } ?: ""))

    /**
     * `400`/`422`, or Subsonic code 10/43. A client bug or stale assumption about the server's
     * contract — not user-actionable, but it must be logged.
     */
    public data class InvalidRequest(
        public val lane: ApiLane,
        public val statusCode: Int? = null,
        public val code: String? = null,
        public val serverMessage: String? = null,
    ) : NetworkError("Rejected by the ${lane.name} lane" + (serverMessage?.let { ": $it" } ?: ""))

    /** A Subsonic `status=failed` envelope this module has no specific mapping for. */
    public data class SubsonicFailure(
        public val code: Int,
        public val serverMessage: String?,
    ) : NetworkError("Subsonic error $code" + (serverMessage?.let { ": $it" } ?: ""))

    /**
     * The body did not match the expected shape. A newer server adding fields can never cause
     * this — every DTO ignores unknown keys — so it means a genuine contract break, a captive
     * portal, or the URL points at something that is not DroppedNeedle.
     */
    public data class Serialisation(
        public val lane: ApiLane,
        public override val cause: Throwable,
    ) : NetworkError("Could not parse the ${lane.name} response: ${cause.javaClass.simpleName}", cause)

    /** True for failures where retrying the same request later is sensible. */
    public val isTransient: Boolean
        get() = when (this) {
            is Offline, is Server, is RateLimited -> true
            else -> false
        }
}

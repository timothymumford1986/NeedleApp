package app.needler.core.data.mapper

import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import app.needler.core.network.ApiLane
import app.needler.core.network.NetworkError
import kotlin.time.Duration.Companion.seconds

/**
 * Transport failures mapped onto the domain's failure cases.
 *
 * The distinctions here are the whole point of `NeedlerError`: the UI branches on them, and three
 * pairs of them mean opposite things.
 *
 *  * A `401` on `/api/v1` is a *stale companion bearer*: the app degrades to a music player and
 *    prompts. A Subsonic auth rejection (code 40 or 44, delivered over **HTTP 200**) is a *revoked
 *    app-password*: the app mints a replacement silently and says nothing.
 *  * Subsonic error code 0 is overloaded three ways. `:core:network` has already split it into
 *    [NetworkError.SubsonicProtocolDisabled], [NetworkError.RateLimited] and a residual
 *    [NetworkError.SubsonicFailure], so the code itself never has to be re-read here.
 *  * A `403` means "library download is admin-disabled" on a download and "your role does not permit
 *    this" everywhere else, so the caller says which one it is via [downloadContext].
 */
public object ErrorMapper {

    /**
     * @param downloadContext true when the failing call was a download or a pin, where `403` means
     *   the administrator has switched library download off and every pin affordance must be hidden.
     * @param transcodeRequest true when the failing call asked for a server-side transcode, so a
     *   `429` can be reported as the scarce-slot case it is and fall back to the original stream.
     */
    public fun toNeedlerError(
        error: NetworkError,
        downloadContext: Boolean = false,
        transcodeRequest: Boolean = false,
    ): NeedlerError = when (error) {
        is NetworkError.Unauthorised -> when (error.lane) {
            ApiLane.V1 -> NeedlerError.SessionExpired
            ApiLane.Subsonic -> NeedlerError.AppPasswordRevoked(error.subsonicCode)
        }

        NetworkError.SubsonicProtocolDisabled -> NeedlerError.SubsonicProtocolDisabled

        is NetworkError.RateLimited -> NeedlerError.RateLimited(
            // The `/api/v1` limiter can emit `Retry-After: 0`, and an unclamped zero is a hot retry
            // loop. `:core:network` clamps it too; clamping again costs nothing and means a
            // hand-built error in a test cannot reintroduce the loop.
            retryAfter = error.retryAfterSeconds?.coerceAtLeast(1L)?.seconds,
            wasTranscodeRequest = transcodeRequest,
        )

        is NetworkError.RangeNotSatisfiable -> NeedlerError.RangeNotSatisfiable

        is NetworkError.Forbidden ->
            if (downloadContext) NeedlerError.DownloadForbidden else NeedlerError.PermissionDenied()

        is NetworkError.NotFound -> NeedlerError.NotFound(error.serverMessage)

        is NetworkError.Offline -> NeedlerError.Offline(
            cause = when (error.kind) {
                NetworkError.Offline.Kind.Timeout -> OfflineCause.TIMEOUT
                NetworkError.Offline.Kind.Dns -> OfflineCause.DNS_FAILURE
                NetworkError.Offline.Kind.Io -> OfflineCause.CONNECTION_FAILED
            },
        )

        // The transport cannot supply a `CertificateInfo` - it only saw the handshake fail - and the
        // domain's CertificateUntrusted requires one. Only the Connect screen has a reason to show a
        // certificate, and `SessionRepository.probeServer` reads it there with `TlsCertificateProbe`.
        // Everywhere else a distrusted certificate means the server is unreachable, which is exactly
        // what the app already handles: serve the mirror and the cached audio.
        is NetworkError.TlsNotTrusted -> NeedlerError.Offline(OfflineCause.CONNECTION_FAILED)

        is NetworkError.Server -> NeedlerError.ServerError(
            statusCode = error.statusCode,
            message = error.serverMessage,
        )

        is NetworkError.InvalidRequest -> NeedlerError.Rejected(
            statusCode = error.statusCode,
            message = error.serverMessage,
        )

        is NetworkError.SubsonicFailure -> when (error.code) {
            SUBSONIC_NOT_FOUND -> NeedlerError.NotFound(error.serverMessage)
            SUBSONIC_NOT_AUTHORISED -> if (downloadContext) {
                NeedlerError.DownloadForbidden
            } else {
                NeedlerError.PermissionDenied()
            }
            else -> NeedlerError.ProtocolViolation(
                "Subsonic error " + error.code + ": " + error.serverMessage,
            )
        }

        is NetworkError.Serialisation -> NeedlerError.ProtocolViolation(error.message.orEmpty())

        // The domain has no case for an authenticating proxy, and `:core:domain` is not this
        // module's to change, so it travels as `Unexpected` with the transport error itself as the
        // cause. That is not a fudge: `Unexpected.cause` is typed `Throwable`, `NetworkError` *is*
        // a `Throwable`, and the Connect screen reads the structured
        // `NetworkError.AuthenticatingProxy` back out of it to render the host and the vendor. A
        // `NeedlerError.AuthenticatingProxy(host, vendor)` is the right home for this and is
        // reported as a follow-up; everything below the UI already behaves correctly, because a
        // proxy standing in the way is genuinely not retryable and not offline.
        is NetworkError.AuthenticatingProxy -> NeedlerError.Unexpected(
            // Host and vendor only. `ProxyInterception.requestedUrl` is redacted, and no header
            // value ever reaches this object.
            detail = error.interception.summary,
            cause = error,
        )
    }

    /** Anything that escaped the clients unclassified. Never let a raw throwable reach the UI. */
    public fun toNeedlerError(throwable: Throwable): NeedlerError = when (throwable) {
        is NetworkError -> toNeedlerError(throwable)
        is kotlinx.coroutines.CancellationException -> NeedlerError.Cancelled
        else -> NeedlerError.Unexpected(detail = throwable.message, cause = throwable)
    }

    /** Subsonic code 70: the row is gone from the server. */
    public const val SUBSONIC_NOT_FOUND: Int = 70

    /** Subsonic code 50: the user is not authorised for this operation. */
    public const val SUBSONIC_NOT_AUTHORISED: Int = 50
}

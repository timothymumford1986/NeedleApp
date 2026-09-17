package app.needler.core.domain.model

import kotlin.time.Duration

/**
 * Every failure the domain reports, modelled one-to-one with the requirements' failure-handling table.
 *
 * The distinctions matter to the UI, which is why this is a sealed hierarchy rather than a message
 * string: a stale `/api/v1` session must keep playback alive and show a soft prompt, while a rejected
 * app-password must force re-onboarding. Collapsing those two into "unauthorised" would produce exactly
 * the wrong behaviour in both cases.
 *
 * | Condition | Case here |
 * | --- | --- |
 * | `401` on `/api/v1` | [SessionExpired] - mark stale, keep playback, prompt to sign in |
 * | `401` on Subsonic (code 40 or 44) | [AppPasswordRevoked] - full re-onboarding |
 * | Subsonic `status=failed` code 0 | [SubsonicProtocolDisabled] - name the admin setting |
 * | `429` with `Retry-After` | [RateLimited] - honour the header, retry once, then original stream |
 * | `416` on a range request | [RangeNotSatisfiable] - cached length wrong, discard and refetch |
 * | `403` on a download | [DownloadForbidden] - hide the affordance |
 * | timeout or DNS failure | [Offline] - serve the mirror and cached audio |
 * | `5xx` | [ServerError] - exponential backoff with jitter, capped at five minutes |
 */
public sealed interface NeedlerError {

    /** A short, non-localised description for the diagnostics log. Never shown raw to the user. */
    public val diagnostic: String

    /**
     * True when retrying the same call later could plausibly succeed. The offline write queue drops an
     * entry after a *permanent* rejection, so this is what decides whether an entry survives.
     */
    public val isRetryable: Boolean

    /**
     * The `/api/v1` companion bearer is stale (HTTP `401` on that lane).
     *
     * Callers must not treat this as fatal: the app-password is untouched, so library browsing and
     * playback keep working and the app degrades to player-only. See [SessionState.PlayerOnly].
     */
    public data object SessionExpired : NeedlerError {
        override val diagnostic: String get() = "401 on /api/v1: companion bearer stale"
        override val isRetryable: Boolean get() = false
    }

    /**
     * Subsonic rejected the app-password (`401`, error code 40 or 44).
     *
     * The library and playback lane is gone, so this is the one auth failure that genuinely requires
     * full re-onboarding: an app-password's secret is returned exactly once and is never re-fetchable.
     */
    public data class AppPasswordRevoked(
        val subsonicErrorCode: Int? = null,
    ) : NeedlerError {
        override val diagnostic: String get() = "Subsonic auth rejected, code " + subsonicErrorCode
        override val isRetryable: Boolean get() = false
    }

    /**
     * The Subsonic protocol is switched off server-side (`status=failed`, error code 0).
     *
     * `subsonic_enabled` defaults off and is admin-only, so the UI must name that exact setting rather
     * than reporting a generic failure.
     */
    public data object SubsonicProtocolDisabled : NeedlerError {
        override val diagnostic: String get() = "Subsonic disabled: subsonic_enabled is off"
        override val isRetryable: Boolean get() = false
    }

    /**
     * `429`. [retryAfter] carries the server's `Retry-After` when it sent one.
     *
     * The server allows one transcode per user and two in total, so this is the expected outcome of a
     * second listener or a Cast session. On a transcode, honour the header, retry once, then fall back
     * to the original stream with a one-line notice - never fail the track.
     */
    public data class RateLimited(
        val retryAfter: Duration? = null,
        val wasTranscodeRequest: Boolean = false,
    ) : NeedlerError {
        override val diagnostic: String get() = "429 rate limited, retryAfter=" + retryAfter
        override val isRetryable: Boolean get() = true
    }

    /**
     * The server ran out of concurrent stream slots (8 per user, 32 global).
     *
     * Downloads must be serialised and one slot kept free for playback, so this should be rare; when it
     * happens, a download yields to playback.
     */
    public data object StreamSlotsExhausted : NeedlerError {
        override val diagnostic: String get() = "Direct stream slot limit reached"
        override val isRetryable: Boolean get() = true
    }

    /**
     * `416` on a range request: the cached length is wrong. Discard the cached bytes and refetch.
     */
    public data object RangeNotSatisfiable : NeedlerError {
        override val diagnostic: String get() = "416: cached length wrong, discard and refetch"
        override val isRetryable: Boolean get() = true
    }

    /**
     * `403` on a download: library download is disabled by an administrator.
     *
     * Needler checks `GET /api/v1/download/access` up front and hides every pin and download
     * affordance when `allowed` is false, so seeing this means the setting changed under us.
     */
    public data object DownloadForbidden : NeedlerError {
        override val diagnostic: String get() = "403: library download disabled by admin"
        override val isRetryable: Boolean get() = false
    }

    /** The caller's role does not permit the operation, e.g. editing server-side quality policy. */
    public data class PermissionDenied(
        val requiredRole: UserRole? = null,
    ) : NeedlerError {
        override val diagnostic: String get() = "403: requires role " + requiredRole
        override val isRetryable: Boolean get() = false
    }

    /**
     * No usable network, a timeout, or a DNS failure - all treated identically, because from the
     * product's point of view offline is a state rather than an error.
     */
    public data class Offline(
        val cause: OfflineCause = OfflineCause.NO_NETWORK,
    ) : NeedlerError {
        override val diagnostic: String get() = "Offline: " + cause
        override val isRetryable: Boolean get() = true
    }

    /** `5xx`. Back off exponentially with jitter, capped at five minutes. */
    public data class ServerError(
        val statusCode: Int,
        val message: String? = null,
    ) : NeedlerError {
        override val diagnostic: String get() = "Server error " + statusCode + ": " + message
        override val isRetryable: Boolean get() = true
    }

    /** `404`, or a Subsonic "not found" code. The mirror may be ahead of the server. */
    public data class NotFound(
        val what: String? = null,
    ) : NeedlerError {
        override val diagnostic: String get() = "Not found: " + what
        override val isRetryable: Boolean get() = false
    }

    /** `400`-class rejection the server explained. A write-queue entry carrying this is dropped. */
    public data class Rejected(
        val statusCode: Int? = null,
        val message: String? = null,
    ) : NeedlerError {
        override val diagnostic: String get() = "Rejected " + statusCode + ": " + message
        override val isRetryable: Boolean get() = false
    }

    /**
     * TLS validation failed and the user has not pinned this certificate.
     *
     * The UI shows fingerprint, subject and expiry and offers to pin this exact leaf certificate for
     * this one host. Validation is never disabled globally.
     */
    public data class CertificateUntrusted(
        val certificate: CertificateInfo,
    ) : NeedlerError {
        override val diagnostic: String get() = "TLS not trusted: " + certificate.sha256Fingerprint
        override val isRetryable: Boolean get() = false
    }

    /**
     * The pinned certificate no longer matches. This must fail loudly and require re-confirmation
     * rather than silently accepting the new certificate.
     */
    public data class CertificateChanged(
        val expectedFingerprint: String,
        val presented: CertificateInfo,
    ) : NeedlerError {
        override val diagnostic: String
            get() = "TLS pin mismatch: expected " + expectedFingerprint +
                ", got " + presented.sha256Fingerprint
        override val isRetryable: Boolean get() = false
    }

    /** The URL typed on the Connect screen is not a DroppedNeedle server. Must fail at Connect time. */
    public data class NotADroppedNeedleServer(
        val probedUrl: String,
    ) : NeedlerError {
        override val diagnostic: String get() = "No /api/v1/version at " + probedUrl
        override val isRetryable: Boolean get() = false
    }

    /** The credentials typed on the Connect screen were wrong. */
    public data object InvalidCredentials : NeedlerError {
        override val diagnostic: String get() = "Login rejected"
        override val isRetryable: Boolean get() = false
    }

    /**
     * The server responded, but not in a shape Needler understands.
     *
     * Clients are hand-written against a spec snapshot rather than generated, and several compat
     * endpoints are marked `partial`, so this case is expected to happen occasionally in the field.
     */
    public data class ProtocolViolation(
        val detail: String,
    ) : NeedlerError {
        override val diagnostic: String get() = "Protocol violation: " + detail
        override val isRetryable: Boolean get() = false
    }

    /** A feature was used that this server does not offer. The UI should have hidden it. */
    public data class CapabilityUnavailable(
        val capability: String,
    ) : NeedlerError {
        override val diagnostic: String get() = "Capability unavailable: " + capability
        override val isRetryable: Boolean get() = false
    }

    /** No room left on the device for a download. */
    public data class InsufficientStorage(
        val requiredBytes: Long? = null,
    ) : NeedlerError {
        override val diagnostic: String get() = "Insufficient storage, needed " + requiredBytes
        override val isRetryable: Boolean get() = false
    }

    /** The operation was cancelled by the user or by the caller's scope. */
    public data object Cancelled : NeedlerError {
        override val diagnostic: String get() = "Cancelled"
        override val isRetryable: Boolean get() = false
    }

    /** Anything unclassified. [cause] never reaches logs that leave the device. */
    public data class Unexpected(
        val detail: String? = null,
        val cause: Throwable? = null,
    ) : NeedlerError {
        override val diagnostic: String get() = "Unexpected: " + (detail ?: cause?.message)
        override val isRetryable: Boolean get() = false
    }
}

/** Which flavour of unreachable the transport saw. All three are surfaced to the user as "offline". */
public enum class OfflineCause {
    NO_NETWORK,
    TIMEOUT,
    DNS_FAILURE,
    CONNECTION_FAILED,
}

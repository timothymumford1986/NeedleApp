package app.needler.connect

import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import kotlin.time.Duration

/**
 * Everything the Connect screen renders.
 *
 * The screen itself is stateless: it is handed one of these and a callback, so
 * it can be previewed, screenshotted and tested in any state without a
 * repository, a network or a server behind it.
 *
 * REQUIREMENTS.md "Required change to the Connect screen": the third field is
 * the user's **account password**, not an app-password. The label and helper
 * text are fixed strings on the screen rather than state, but the reason is
 * worth repeating here because it is what [password] holds - see
 * [ConnectScreen].
 */
data class ConnectUiState(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    /** A connection attempt is in flight. The form is disabled and the button reads Connecting. */
    val connecting: Boolean = false,
    /** What went wrong last time, or null. */
    val failure: ConnectFailure? = null,
    /** Set once onboarding has completed, so the host can navigate away. */
    val connected: Boolean = false,
) {
    /**
     * Whether Connect may be pressed.
     *
     * All three fields are required. The URL is not validated here - a typo is
     * indistinguishable from a valid address without probing the server, and
     * REQUIREMENTS.md requires exactly that probe rather than a regex.
     */
    val canConnect: Boolean
        get() = !connecting &&
            server.isNotBlank() &&
            username.isNotBlank() &&
            password.isNotEmpty()
}

/**
 * The failures the Connect screen can show, and they are not interchangeable.
 *
 * Each one leads somewhere different: a typo is fixed in the field above, an
 * unreachable server is a network or VPN problem, wrong credentials are the
 * user's to correct, an untrusted certificate needs an explicit decision with
 * the fingerprint in front of them, and the Subsonic gate needs an
 * administrator and cannot be fixed on this device at all. Collapsing them into
 * one "could not connect" message would leave the last two unactionable.
 *
 * Mapped from [NeedlerError] by [from].
 */
sealed interface ConnectFailure {

    /** Headline for the notice. */
    val title: String

    /** The body of the notice: what happened, and what to do about it. */
    val detail: String

    /**
     * The address does not answer as a DroppedNeedle server.
     *
     * REQUIREMENTS.md "Accepted URL forms": normalise and probe
     * `GET /api/v1/auth/providers`, which is public, before accepting. A wrong
     * URL must fail here, on the Connect screen, never later.
     */
    data class BadServerAddress(val typed: String) : ConnectFailure {
        override val title: String get() = "That is not a Dropped Needle server"
        override val detail: String
            get() = "Nothing at $typed answered as a Dropped Needle. Check the address, " +
                "including the port and any sub-path. A local server usually looks like " +
                "http://192.168.1.50:8688."
    }

    /**
     * The address is plausible but nothing answered: DNS, a timeout, a refused
     * connection.
     *
     * REQUIREMENTS.md is explicit that remote access is the user's own problem
     * to solve with a VPN or a reverse proxy, so the copy says so rather than
     * implying Needler could reach further.
     */
    data class ServerUnreachable(val cause: OfflineCause) : ConnectFailure {
        override val title: String get() = "Cannot reach that server"
        override val detail: String
            get() = when (cause) {
                OfflineCause.DNS_FAILURE ->
                    "That host name did not resolve. Check the spelling, or use the " +
                        "server's IP address."
                OfflineCause.TIMEOUT ->
                    "The server did not answer in time. If it is only reachable from home, " +
                        "connect to your VPN first."
                else ->
                    "No connection. Check this device is online, and that the server is " +
                        "reachable from it - Needler does not tunnel to your network for you."
            }
    }

    /** The login was rejected. */
    data object WrongCredentials : ConnectFailure {
        override val title: String get() = "That username or password was rejected"
        override val detail: String
            get() = "Use your Dropped Needle account password - the one you sign in to the " +
                "web player with. Not an app password."
    }

    /**
     * TLS validation failed on a certificate the user has not pinned.
     *
     * REQUIREMENTS.md "Self-signed certificates": show the fingerprint, subject
     * and expiry, and let the user pin *that exact certificate for this one
     * host*. Validation is never disabled globally, which is why the action on
     * this notice trusts one certificate rather than turning anything off.
     */
    data class UntrustedCertificate(val certificate: CertificateInfo) : ConnectFailure {
        override val title: String get() = "This server's certificate is not trusted"
        override val detail: String
            get() = "Self-hosted servers usually have a certificate no public authority " +
                "vouches for. Check the fingerprint below matches your server, then trust it."
    }

    /**
     * The pinned certificate no longer matches the one presented.
     *
     * This must fail loudly. It is the one case here with no one-tap way
     * forward: re-confirmation is required, and a silent accept would defeat
     * the entire point of pinning.
     */
    data class CertificateChanged(
        val expectedFingerprint: String,
        val presented: CertificateInfo,
    ) : ConnectFailure {
        override val title: String get() = "This server's certificate has changed"
        override val detail: String
            get() = "The certificate is not the one you trusted. That can mean the server was " +
                "reissued a certificate - or that something is intercepting the connection. " +
                "Confirm the new fingerprint with whoever runs the server before trusting it."
    }

    /**
     * `subsonic_enabled` is off.
     *
     * REQUIREMENTS.md: "The gate at `subsonic_enabled` is the one hard
     * dependency on an administrator. A non-admin user on a server with the
     * protocol switched off cannot finish onboarding, so the setup screen must
     * say exactly which setting an admin has to turn on." Hence the setting is
     * named, in the words the server's own settings page uses, and the notice
     * offers a retry rather than a fix - because there is no fix on this device.
     */
    data object SubsonicDisabled : ConnectFailure {
        override val title: String get() = "The Subsonic protocol is switched off"
        override val detail: String
            get() = "Needler plays your library over Dropped Needle's Subsonic interface, and " +
                "it is off by default. An administrator has to turn on \"Enable Subsonic API\" " +
                "(subsonic_enabled) under Settings, Connect Apps on the server. Sign-in works " +
                "either way, so try again once they have."
    }

    /** The server answered, but with a 5xx or a rate limit. Retrying is the right move. */
    data class ServerProblem(val detail0: String) : ConnectFailure {
        override val title: String get() = "The server had a problem"
        override val detail: String get() = detail0
    }

    /** Anything unmodelled. Shows the diagnostic, which never contains a secret. */
    data class Unexpected(val diagnostic: String) : ConnectFailure {
        override val title: String get() = "Could not connect"
        override val detail: String get() = diagnostic
    }

    companion object {

        /**
         * Maps a domain error onto what the Connect screen should say.
         *
         * @param typedUrl what the user actually entered, so the "not a Dropped
         *   Needle" message can quote it back rather than showing a normalised
         *   form the user never typed.
         */
        fun from(error: NeedlerError, typedUrl: String): ConnectFailure = when (error) {
            is NeedlerError.NotADroppedNeedleServer -> BadServerAddress(typedUrl)
            is NeedlerError.Offline -> ServerUnreachable(error.cause)
            NeedlerError.InvalidCredentials -> WrongCredentials
            is NeedlerError.CertificateUntrusted -> UntrustedCertificate(error.certificate)
            is NeedlerError.CertificateChanged ->
                CertificateChanged(error.expectedFingerprint, error.presented)
            NeedlerError.SubsonicProtocolDisabled -> SubsonicDisabled
            is NeedlerError.ServerError -> ServerProblem(
                "The server answered ${error.statusCode}. It may still be starting up; " +
                    "try again in a moment.",
            )
            is NeedlerError.RateLimited -> ServerProblem(rateLimitDetail(error.retryAfter))
            // A 401 on /api/v1 during onboarding is not the "degrade to
            // player-only" case the rest of the app handles: there is no
            // session to degrade yet, so it can only mean the credentials were
            // refused.
            NeedlerError.SessionExpired -> WrongCredentials
            else -> Unexpected(error.diagnostic)
        }

        private fun rateLimitDetail(retryAfter: Duration?): String = when (retryAfter) {
            null -> "The server is rate limiting requests. Try again in a moment."
            else -> "The server is rate limiting requests. Try again in " +
                "${retryAfter.inWholeSeconds.coerceAtLeast(1)}s."
        }
    }
}

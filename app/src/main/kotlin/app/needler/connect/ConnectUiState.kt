package app.needler.connect

import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import app.needler.core.network.NetworkError
import app.needler.core.network.ProxyVendor
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
    /**
     * The attempt has been running long enough to look stuck.
     *
     * The bug this exists for was a 45-second dead button: the whole-call timeout is 45s, three
     * lanes of it back to back is longer, and nothing on screen changed for any of it. After a few
     * seconds the screen says it is still trying and offers to stop, which is the difference
     * between a slow server and a broken app.
     */
    val attemptIsSlow: Boolean = false,
    /** What went wrong last time, or null. */
    val failure: ConnectFailure? = null,
    /** Set once onboarding has completed, so the host can navigate away. */
    val connected: Boolean = false,
    /** The optional extra headers for a server behind an authenticating proxy. */
    val proxy: ProxyFormState = ProxyFormState(),
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

    /**
     * Something in front of the server answered instead of it: a forward-auth proxy - Cloudflare
     * Access, Authelia, authentik, a basic-auth reverse proxy - wanting a browser sign-in.
     *
     * Its own state because nothing else on this screen leads anywhere near the fix. The address is
     * right, the credentials are right, the certificate is fine and the server may be perfectly
     * healthy; what is wrong is one layer in front of it, and the three ways out (a credential the
     * app can send, a bypass rule for this path, or reaching the server over a VPN or its LAN
     * address) are things the user does elsewhere. Folding this into "could not connect" is what
     * produced a 45-second hang with no explanation.
     *
     * @param host the host that answered - the login host when the proxy named one, so the user can
     *   recognise it.
     * @param vendorName the product's name when it identified itself, else null.
     * @param credentialsSent true when Needler did send the configured headers and was refused
     *   anyway, which is a different instruction from having sent none.
     */
    data class ProxyIntercepted(
        val host: String,
        val vendorName: String?,
        val credentialsSent: Boolean,
    ) : ConnectFailure {
        override val title: String
            get() = if (vendorName != null) {
                vendorName + " is asking for a sign-in first"
            } else {
                "Something is intercepting the connection"
            }

        override val detail: String
            get() = buildString {
                append(host)
                append(" answered instead of your server, and sent a sign-in page where Needler ")
                append("asked for data. Needler cannot complete a sign-in meant for a browser.")
                if (credentialsSent) {
                    append(" The extra headers you set were sent and refused, so check they are ")
                    append("still valid.")
                } else {
                    append(" Open \"My server is behind a proxy that needs its own credentials\" ")
                    if (vendorName == "Cloudflare Access") {
                        append("below and add a service token's Client ID and Secret.")
                    } else {
                        append("below and add the credential your proxy expects.")
                    }
                }
                append(" Or add a bypass rule for this path on the proxy, or reach the server ")
                append("over a VPN or its LAN address instead.")
            }
    }

    /**
     * The user stopped the attempt.
     *
     * Not really a failure, but it belongs in the same slot: something has to replace "Connecting…"
     * and say that nothing was changed, or a cancelled attempt looks like a crash.
     */
    data object Cancelled : ConnectFailure {
        override val title: String get() = "Stopped"
        override val detail: String
            get() = "The connection attempt was cancelled. Nothing on this device was changed."
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
            NeedlerError.Cancelled -> Cancelled
            // `:core:domain` has no case for an authenticating proxy and is not this change's to
            // edit, so `:core:data` passes the transport error through as the cause of
            // `Unexpected`. Reading it back here keeps the screen's state typed rather than parsing
            // a diagnostic string - and if a `NeedlerError.AuthenticatingProxy` ever lands, this is
            // the one place that changes.
            is NeedlerError.Unexpected -> proxyInterception(error) ?: Unexpected(error.diagnostic)
            else -> Unexpected(error.diagnostic)
        }

        private fun proxyInterception(error: NeedlerError.Unexpected): ProxyIntercepted? {
            val proxy = error.cause as? NetworkError.AuthenticatingProxy ?: return null
            return ProxyIntercepted(
                host = proxy.interception.describedHost,
                vendorName = proxy.interception.vendor
                    .takeIf { it != ProxyVendor.Unknown }
                    ?.displayName,
                credentialsSent = proxy.interception.proxyCredentialsSent,
            )
        }

        private fun rateLimitDetail(retryAfter: Duration?): String = when (retryAfter) {
            null -> "The server is rate limiting requests. Try again in a moment."
            else -> "The server is rate limiting requests. Try again in " +
                "${retryAfter.inWholeSeconds.coerceAtLeast(1)}s."
        }
    }
}

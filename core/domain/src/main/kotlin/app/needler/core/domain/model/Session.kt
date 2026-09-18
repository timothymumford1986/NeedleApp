package app.needler.core.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Instant

/** The signed-in user, from `GET /api/v1/auth/me`. */
public data class User(
    val id: String,
    val username: String,
    val displayName: String? = null,
    val role: UserRole,
)

/**
 * The user's role, which changes what the app may offer.
 *
 * | Role | Requests | Library download |
 * | --- | --- | --- |
 * | `admin` | execute immediately | subject to the same admin setting |
 * | `trusted` | execute immediately | subject to the same admin setting |
 * | `user` | queue for admin approval | often disabled |
 *
 * [requestsNeedApproval] may be used to *predict* a "Waiting" outcome in the request sheet's copy, but
 * never to display the outcome: the status the server returns on the request is authoritative, because
 * an admin may have changed the role moments earlier.
 */
public enum class UserRole {
    ADMIN,
    TRUSTED,
    USER,
    ;

    public val requestsNeedApproval: Boolean get() = this == USER

    public companion object {
        /** Unknown role tokens map to the least privileged role. */
        public fun fromServerToken(token: String?): UserRole {
            return when (token?.trim()?.lowercase()) {
                "admin" -> ADMIN
                "trusted" -> TRUSTED
                else -> USER
            }
        }
    }
}

/** One saved server, normalised. Remote reachability is the user's problem (VPN or reverse proxy). */
public data class ServerIdentity(
    /** Normalised base URL: scheme present, trailing slashes trimmed, sub-path preserved. */
    val baseUrl: String,
    /** Server-reported identifier, when it offers one. A change means the mirror must be dropped. */
    val serverId: String? = null,
    val serverVersion: String? = null,
)

/** The result of probing a URL typed on the Connect screen, before any credentials are sent. */
public data class ServerProbe(
    val identity: ServerIdentity,
    val apiVersion: String? = null,
    /** From `GET /api/v1/connect-apps/settings`: the one hard dependency on an administrator. */
    val subsonicEnabled: Boolean,
    /** Present when TLS validation failed and the user must decide whether to pin this certificate. */
    val certificate: CertificateInfo? = null,
)

/**
 * A leaf certificate the user may pin for this one host.
 *
 * Pinning is scoped to the host the user pinned; validation is never disabled globally, and a changed
 * fingerprint must fail loudly and require re-confirmation.
 */
public data class CertificateInfo(
    val sha256Fingerprint: String,
    val subject: String,
    val issuer: String,
    val notAfter: Instant?,
)

/**
 * What the server can actually do, discovered once per connection.
 *
 * Needler targets no fixed DroppedNeedle version: it negotiates and then *disables features* rather
 * than failing. Four of the OpenSubsonic extensions this server implements are unadvertised pending
 * certification, so anything absent from `getOpenSubsonicExtensions` must be treated as unavailable
 * even if it would in fact work.
 */
public data class ServerCapabilities(
    /**
     * `subsonic_enabled` from `GET /api/v1/connect-apps/settings`.
     *
     * False blocks onboarding entirely - the library and playback lane is Subsonic - so the setup
     * screen must name the exact setting an admin has to turn on.
     */
    val subsonicEnabled: Boolean,
    /**
     * True only when `transcoding:1` is advertised **and** the server reports transcoding enabled.
     * ffmpeg may be absent, so neither is guaranteed. When false, the "stream MP3 320 on mobile data"
     * setting is hidden entirely rather than offered and failing.
     */
    val transcodingAvailable: Boolean,
    /**
     * `allowed` from `GET /api/v1/download/access`. When false, every pin and download affordance is
     * hidden rather than allowed to fail with a `403` later.
     */
    val libraryDownloadAllowed: Boolean,
    /** Extensions advertised by `getOpenSubsonicExtensions`, parsed. */
    val extensions: Set<OpenSubsonicExtension> = emptySet(),
    /** Raw extension names as advertised, including any this version does not model. */
    val rawExtensions: Set<String> = emptySet(),
    val serverVersion: String? = null,
    val subsonicApiVersion: String? = null,
    val negotiatedAt: Instant? = null,
) {
    public fun supports(extension: OpenSubsonicExtension): Boolean = extensions.contains(extension)

    /** True when the app may offer anything at all: without Subsonic there is no library lane. */
    public val canUseLibrary: Boolean get() = subsonicEnabled

    public companion object {
        /** A pessimistic default: everything optional off until negotiation says otherwise. */
        public val Unknown: ServerCapabilities = ServerCapabilities(
            subsonicEnabled = false,
            transcodingAvailable = false,
            libraryDownloadAllowed = false,
        )
    }
}

/**
 * OpenSubsonic extensions Needler looks for.
 *
 * The first three are advertised by this server; the rest are implemented but unadvertised pending
 * client certification and must be probed for, not assumed.
 */
public enum class OpenSubsonicExtension(public val wireName: String) {
    API_KEY_AUTHENTICATION("apiKeyAuthentication"),
    FORM_POST("formPost"),
    TRANSCODE_OFFSET("transcodeOffset"),
    SONG_LYRICS("songLyrics"),
    PLAYBACK_REPORT("playbackReport"),
    INDEX_BASED_QUEUE("indexBasedQueue"),
    TRANSCODING("transcoding"),
    ;

    public companion object {
        /** Matches a wire name, with or without a `:1` version suffix. Returns null if unmodelled. */
        public fun fromWireName(name: String): OpenSubsonicExtension? {
            val bare: String = name.trim().substringBefore(':')
            return entries.firstOrNull { it.wireName.equals(bare, ignoreCase = true) }
        }
    }
}

/**
 * The state of the app's relationship with its server.
 *
 * ## The credential asymmetry, modelled explicitly
 *
 * Needler holds two secrets with different lifetimes:
 *
 * | Credential | Lifetime | What breaks when it expires |
 * | --- | --- | --- |
 * | companion bearer (`/api/v1`) | 30 days, fixed, does not slide | search, requesting, queue monitoring |
 * | app-password (Subsonic) | until revoked | nothing - library, streaming and playlists keep working |
 *
 * So an expired bearer must degrade the app to a **pure music player**, not brick it: that is
 * [PlayerOnly]. A companion session cannot mint another device session, so silent renewal is
 * impossible without storing the account password, which Needler does not do - it prompts instead, and
 * warns in-app from day 25.
 *
 * A rejected app-password is **not** the opposite case, and this is the correction that matters most
 * here. Subsonic `401` (code 40 or 44) means the library lane is gone *for now*, but
 * `POST /api/v1/connect-apps/app-passwords` needs only the companion bearer - which the app is still
 * holding - so Needler mints a replacement and carries on. That is [RepairingAppPassword]: playback
 * pauses for as long as the repair takes and then resumes, and the user is told nothing, because
 * nothing about their intent has changed. The alternative, which this app deliberately does not do,
 * throws away a working session and a multi-gigabyte cache over one revoked secret - most often a
 * secret the user revoked from the web UI without realising which app it belonged to.
 *
 * Re-onboarding is therefore required in exactly one case: **both** credentials are dead.
 *
 * | Bearer | App-password | State |
 * | --- | --- | --- |
 * | valid | valid | [Authenticated] |
 * | expired | valid | [PlayerOnly] - prompt to sign in; playback unaffected |
 * | valid | revoked | [RepairingAppPassword] - mint a replacement silently |
 * | expired | revoked | [ReonboardingRequired] |
 *
 * Any code gating a feature must branch on this type, not on "is there a token", so the degraded case
 * cannot be forgotten.
 */
public sealed interface SessionState {

    /** True when `/api/v1` calls (search, requests, queue) may be attempted. */
    public val canUseCatalogueLane: Boolean

    /** True when Subsonic calls (library, streaming, playlists, favourites) may be attempted. */
    public val canUseLibraryLane: Boolean

    /** No server saved yet: the Connect screen owns the app. */
    public data object NotConfigured : SessionState {
        override val canUseCatalogueLane: Boolean get() = false
        override val canUseLibraryLane: Boolean get() = false
    }

    /**
     * A server is saved and onboarding is blocked by the administrator gate: `subsonic_enabled` is
     * off. The setup screen must name the setting rather than showing a generic failure.
     */
    public data class SubsonicDisabled(
        val server: ServerIdentity,
    ) : SessionState {
        override val canUseCatalogueLane: Boolean get() = true
        override val canUseLibraryLane: Boolean get() = false
    }

    /** Both credentials valid. The whole product is available. */
    public data class Authenticated(
        val server: ServerIdentity,
        val user: User,
        val capabilities: ServerCapabilities,
        /** When the companion bearer dies. It is fixed at issue + 30 days and does not slide on use. */
        val bearerExpiresAt: Instant?,
    ) : SessionState {
        override val canUseCatalogueLane: Boolean get() = true
        override val canUseLibraryLane: Boolean get() = true

        /**
         * True once the expiry is close enough to warn about. The requirement is a warning from day 25
         * of 30, so re-authentication is rarely a surprise.
         */
        public fun shouldWarnAboutExpiry(
            now: Instant,
            within: Duration = SessionState.ReauthWarningWindow,
        ): Boolean {
            val expiry: Instant = bearerExpiresAt ?: return false
            return expiry - now <= within
        }
    }

    /**
     * The companion bearer is stale, the app-password is not.
     *
     * **Playback, library browsing, playlists and favourites must keep working.** Only search,
     * requesting and queue monitoring are unavailable, and the prompt to sign in again is
     * non-blocking.
     */
    public data class PlayerOnly(
        val server: ServerIdentity,
        val user: User?,
        val capabilities: ServerCapabilities,
        val reason: PlayerOnlyReason,
    ) : SessionState {
        override val canUseCatalogueLane: Boolean get() = false
        override val canUseLibraryLane: Boolean get() = true
    }

    /**
     * The app-password was rejected while the companion bearer is still alive, and a replacement is
     * being minted right now.
     *
     * **This is a recoverable state, not a failure.** It is the only state in which the catalogue
     * lane works and the library lane does not, which is the exact inverse of [PlayerOnly]: search,
     * requests and the queue are fine because they run on the bearer, while streaming, playlists and
     * favourites wait for the new secret.
     *
     * Playback pauses for the duration and then resumes. Nothing in the UI announces this state - no
     * dialog, no prompt, no sign-in screen - because there is nothing for the user to do and the
     * repair is one HTTP round trip. A UI may render it as the ordinary buffering it looks like from
     * the outside.
     *
     * [lastAttemptError] is set when a repair attempt failed in a way worth retrying - the phone was
     * offline, or the server answered `5xx`. The state is kept rather than escalated, because a
     * retryable failure says nothing about whether the bearer is still good.
     *
     * Reachable only from [Authenticated], which is why [user] and the capabilities are non-null
     * here: if the bearer were dead the app would already be in [PlayerOnly], and an app-password
     * rejection from there means both credentials are gone.
     */
    public data class RepairingAppPassword(
        val server: ServerIdentity,
        val user: User,
        val capabilities: ServerCapabilities,
        /** Carried through the repair so the day-25 expiry warning is not lost by it. */
        val bearerExpiresAt: Instant?,
        val lastAttemptError: NeedlerError? = null,
    ) : SessionState {
        override val canUseCatalogueLane: Boolean get() = true
        override val canUseLibraryLane: Boolean get() = false

        /** The state to move to once a replacement app-password is stored. */
        public fun repaired(): Authenticated = Authenticated(
            server = server,
            user = user,
            capabilities = capabilities,
            bearerExpiresAt = bearerExpiresAt,
        )
    }

    /**
     * Both credentials are dead, so nothing can be minted from anything: the user must sign in again.
     *
     * This is **not** where a revoked app-password lands. A revoked app-password with a live bearer
     * is [RepairingAppPassword] and is repaired silently; only the loss of both secrets, an
     * unreadable keystore, a changed server or an explicit sign-out gets here. See
     * [ReonboardingReason] for the four ways in.
     */
    public data class ReonboardingRequired(
        val server: ServerIdentity?,
        val reason: ReonboardingReason,
    ) : SessionState {
        override val canUseCatalogueLane: Boolean get() = false
        override val canUseLibraryLane: Boolean get() = false
    }

    public companion object {
        /** Warn from day 25 of the bearer's 30-day life. */
        public val ReauthWarningWindow: Duration = 5.days

        /**
         * The state to move to when Subsonic rejects the app-password (code 40 or 44).
         *
         * The whole product decision is this one function, so it lives in the domain rather than
         * being re-derived by the repository, the credential store and whatever else sees the
         * rejection. With the bearer alive the app repairs itself silently; only with the bearer
         * dead as well does the user ever hear about it.
         *
         * @param bearerAlive whether a companion bearer that has neither expired nor been revoked is
         *   still held. It is the sole input, because it is the sole thing needed to mint a
         *   replacement app-password.
         */
        public fun afterAppPasswordRejected(
            current: SessionState,
            bearerAlive: Boolean,
        ): SessionState = when (current) {
            is Authenticated ->
                if (bearerAlive) {
                    RepairingAppPassword(
                        server = current.server,
                        user = current.user,
                        capabilities = current.capabilities,
                        bearerExpiresAt = current.bearerExpiresAt,
                    )
                } else {
                    ReonboardingRequired(current.server, ReonboardingReason.BOTH_CREDENTIALS_DEAD)
                }

            // A second rejection while a repair is in flight is the common case, not an anomaly:
            // several Subsonic calls are usually in the air when the first one is refused. Staying
            // put makes the repair idempotent instead of restarting it once per failed request.
            is RepairingAppPassword ->
                if (bearerAlive) {
                    current
                } else {
                    ReonboardingRequired(current.server, ReonboardingReason.BOTH_CREDENTIALS_DEAD)
                }

            // Player-only already means the bearer is gone, so there is nothing left to mint with.
            is PlayerOnly ->
                ReonboardingRequired(current.server, ReonboardingReason.BOTH_CREDENTIALS_DEAD)

            // The lane was never usable in these states, so a rejection on it changes nothing.
            is SubsonicDisabled, NotConfigured, is ReonboardingRequired -> current
        }

        /**
         * The state to move to when a repair attempt fails.
         *
         * A retryable failure - offline, `5xx`, rate limited - keeps the app in
         * [RepairingAppPassword] with the error recorded. It says nothing about whether the bearer
         * is still good, and escalating to re-onboarding on a lost connection would hand the user a
         * sign-in screen for a problem that fixes itself when the train leaves the tunnel.
         *
         * Anything permanent - the bearer itself refused, the server refusing to mint - means the
         * silent path is exhausted and signing in again is the only way out.
         */
        public fun afterAppPasswordRepairFailed(
            current: RepairingAppPassword,
            error: NeedlerError,
        ): SessionState = if (error.isRetryable) {
            current.copy(lastAttemptError = error)
        } else {
            ReonboardingRequired(current.server, ReonboardingReason.BOTH_CREDENTIALS_DEAD)
        }

        /**
         * The state to move to when `/api/v1` answers `401`.
         *
         * From [Authenticated] this is the documented degradation to a pure music player. From
         * [RepairingAppPassword] it is the one case that cannot degrade: the bearer was the tool the
         * repair was using, so losing it mid-repair leaves both credentials dead.
         */
        public fun afterBearerRejected(
            current: SessionState,
            reason: PlayerOnlyReason,
        ): SessionState = when (current) {
            is Authenticated -> PlayerOnly(
                server = current.server,
                user = current.user,
                capabilities = current.capabilities,
                reason = reason,
            )

            is RepairingAppPassword ->
                ReonboardingRequired(current.server, ReonboardingReason.BOTH_CREDENTIALS_DEAD)

            is PlayerOnly, is SubsonicDisabled, NotConfigured, is ReonboardingRequired -> current
        }
    }
}

/** Why the app has degraded to player-only. */
public enum class PlayerOnlyReason {
    /** The 30-day companion bearer reached its fixed expiry. */
    BEARER_EXPIRED,

    /** A `401` came back from `/api/v1` before the stored expiry, e.g. the session was revoked. */
    BEARER_REJECTED,
}

/**
 * Why the user must onboard again from scratch.
 *
 * There is deliberately no "app-password revoked" reason. A revoked app-password with a live bearer
 * is repaired silently ([SessionState.RepairingAppPassword]); only the loss of *both* credentials
 * reaches this enum, which is what [BOTH_CREDENTIALS_DEAD] names.
 */
public enum class ReonboardingReason {
    /**
     * The app-password was rejected (Subsonic code 40 or 44) **and** the companion bearer is gone
     * too, so there is nothing left to mint a replacement with.
     *
     * This is the only credential failure that ever reaches the user. Reaching it from a *single*
     * revoked secret would be a bug: it would discard a working session and a multi-gigabyte cache
     * to fix something one authenticated request repairs.
     */
    BOTH_CREDENTIALS_DEAD,

    /** The stored secrets could not be read back from Keystore-backed storage. */
    CREDENTIALS_UNREADABLE,

    /** The server's identity changed, so the mirror, cache and playlist IDs were dropped. */
    SERVER_IDENTITY_CHANGED,

    /** The user signed out. */
    SIGNED_OUT,
}

/**
 * Network conditions, as far as the domain needs them.
 *
 * Offline is a first-class state, not an error: with no server reachable the app still plays on-device
 * music, browses the full mirror, and queues pulls, playlist edits, favourites and scrobbles.
 */
public data class ConnectivityState(
    val status: NetworkStatus,
    /**
     * Android Data Saver is on and this app is restricted. No artwork prefetch, no speculative
     * buffering, no metered sync.
     */
    val isDataSaverRestricted: Boolean = false,
) {
    public val isOnline: Boolean get() = status != NetworkStatus.OFFLINE
    public val isMetered: Boolean get() = status == NetworkStatus.METERED

    public companion object {
        public val Offline: ConnectivityState = ConnectivityState(NetworkStatus.OFFLINE)
        public val Unmetered: ConnectivityState = ConnectivityState(NetworkStatus.UNMETERED)
    }
}

/** Coarse network status. Timeouts and DNS failures are treated as [OFFLINE]. */
public enum class NetworkStatus {
    OFFLINE,

    /** Mobile data or a metered hotspot. Governs transcode-on-metered and Wi-Fi-only downloads. */
    METERED,

    UNMETERED,
}

/** The user's scrobbling preference plus the destinations the *server* is configured to forward to. */
public data class ScrobblePreferences(
    /** Whether Needler reports plays at all. The destination is the server's business. */
    val reportingEnabled: Boolean,
    /**
     * Targets read from `GET /api/v1/me/scrobble-preferences`, e.g. ListenBrainz, Last.fm. The toggle
     * must be labelled from this rather than hard-coding a destination as screen 12 does.
     */
    val serverTargets: List<String> = emptyList(),
)

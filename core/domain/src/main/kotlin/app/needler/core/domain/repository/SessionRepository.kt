package app.needler.core.domain.repository

import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PinnedCertificate
import app.needler.core.domain.model.PlayerOnlyReason
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.ServerProbe
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Onboarding, credentials, capability negotiation and connectivity.
 *
 * Secrets never appear in this interface. The companion bearer and the app-password live in
 * Keystore-backed storage behind the implementation, and must never be written to logs, crash reports
 * or the metadata database - so nothing here returns or accepts a token.
 */
public interface SessionRepository {

    /**
     * The current session state.
     *
     * Feature gating must branch on this rather than on "do we have a token", because two states are
     * partial rather than broken, in opposite directions. [SessionState.PlayerOnly] has a *valid*
     * app-password and must keep playback, library browsing, playlists and favourites fully working
     * while search, requests and the queue are unavailable. [SessionState.RepairingAppPassword] is
     * the inverse and is transient: the catalogue lane works, the library lane is waiting on a new
     * app-password, and neither a prompt nor an error belongs on screen while it does.
     */
    public fun observeSession(): Flow<SessionState>

    public suspend fun currentSession(): SessionState

    /** The negotiated capability set, or null before the first negotiation. */
    public fun observeCapabilities(): Flow<ServerCapabilities?>

    public suspend fun currentCapabilities(): ServerCapabilities?

    /**
     * Network conditions.
     *
     * Exposed here because it belongs with the transport rather than with any one feature, and because
     * three domain decisions depend on it: whether to attempt the catalogue lane at all, whether to
     * request a transcode on a metered connection, and whether a Wi-Fi-only download may start.
     */
    public fun observeConnectivity(): Flow<ConnectivityState>

    public suspend fun currentConnectivity(): ConnectivityState

    /**
     * Normalises a URL typed on the Connect screen and probes `GET /api/v1/version`.
     *
     * Accepts `https://music.yourhome.net`, `http://192.168.1.50:8688`, a sub-path deployment such as
     * `https://home.net/music`, and a bare host, which defaults to `http://` and port 8688. Trailing
     * slashes are trimmed. A wrong URL must fail here, on the Connect screen, never later:
     * [app.needler.core.domain.model.NeedlerError.NotADroppedNeedleServer].
     *
     * On a TLS failure the probe fails with
     * [app.needler.core.domain.model.NeedlerError.CertificateUntrusted], whose [CertificateInfo] the
     * UI shows before offering [trustCertificate].
     */
    public suspend fun probeServer(rawUrl: String): Outcome<ServerProbe>

    /**
     * Pins one leaf certificate for this one host, after the user has seen its fingerprint, subject and
     * expiry. Validation is never disabled globally, and a later fingerprint change fails loudly with
     * [app.needler.core.domain.model.NeedlerError.CertificateChanged].
     */
    public suspend fun trustCertificate(certificate: CertificateInfo): Outcome<Unit>

    /**
     * The certificate the user has trusted, or null when validation is running normally.
     *
     * ## Why this exists
     *
     * [trustCertificate] was a one-way door. A user could grant a self-signed certificate an exception
     * on the Connect screen and from then on no surface in the application said that they had, what
     * they had trusted, or offered any way to take it back - so the single most consequential security
     * decision in the product was also the only one with no record and no undo. REQUIREMENTS.md
     * "Self-signed certificates" requires the pin be scoped to "the one host"; a scope the user cannot
     * inspect is a scope they are taking on trust.
     *
     * It is a suspend read rather than a `Flow` because a pin changes only when the user changes it -
     * on the Connect screen, or through [forgetPinnedCertificate] - and both are actions whose caller
     * is in a position to ask again. There is no background writer to observe.
     *
     * ## The default
     *
     * Null, and defaulted rather than abstract, because an implementation that does no TLS has no pin
     * and never will. Every test double of this interface is in that position, and a stub returning
     * "nothing is pinned" is the truth for all of them rather than a placeholder.
     */
    public suspend fun pinnedCertificate(): PinnedCertificate? = null

    /**
     * Forgets the pin, so the host's certificate is validated normally again.
     *
     * Both stores have to hear about it, for the reason [trustCertificate] records about granting one:
     * the persisted pin survives launches and the in-memory one is what the handshake consults, and
     * clearing only one leaves either a pin that comes back on restart or a pin that is still in force
     * until the process dies.
     *
     * **This will break the connection to a self-signed server**, immediately and by design: the next
     * handshake fails with
     * [app.needler.core.domain.model.NeedlerError.CertificateUntrusted] and the user is asked to
     * decide again. That is the point of the control, and the caller must say so before it is used
     * rather than afterwards.
     *
     * Succeeds when there was nothing pinned. Forgetting a certificate that is already forgotten is
     * the state the caller asked for, not a failure.
     */
    public suspend fun forgetPinnedCertificate(): Outcome<Unit> = Outcome.Ok

    /**
     * Completes onboarding with the user's **account password** - not an app-password.
     *
     * An app-password cannot authenticate `/api/v1` at all: the bearer middleware validates only
     * against the server's auth-tokens table, while app-passwords live in a separate store that only
     * the Subsonic and Jellyfin shims consult. A user who supplied an app-password would get a working
     * music player with no search, no pull and no queue.
     *
     * The implementation logs in, mints a companion device session named after the device, creates one
     * app-password named `Needler`, stores both in Keystore-backed storage and discards the login
     * bearer. The app-password secret is returned exactly once and is never re-fetchable, so a failed
     * write must abort and revoke.
     */
    public suspend fun connect(
        serverUrl: String,
        username: String,
        password: String,
        deviceName: String,
    ): Outcome<SessionState>

    /**
     * Runs capability negotiation: version, connect-app settings, `getOpenSubsonicExtensions` and
     * `GET /api/v1/download/access`, then persists the result.
     *
     * Fails with [app.needler.core.domain.model.NeedlerError.SubsonicProtocolDisabled] when
     * `subsonic_enabled` is off, which is the one hard dependency on an administrator; the setup screen
     * must name that setting.
     */
    public suspend fun negotiateCapabilities(): Outcome<ServerCapabilities>

    /** Re-reads `GET /api/v1/auth/me`, so a role change lands without re-onboarding. */
    public suspend fun refreshUser(): Outcome<User>

    /**
     * Re-authenticates after the 30-day companion bearer has expired, minting a fresh device session
     * and a replacement app-password.
     *
     * A companion session cannot mint another device session, so silent renewal is impossible without
     * storing the account password - which Needler does not do. The user is prompted instead, and
     * warned in-app from day 25.
     */
    public suspend fun reauthenticate(password: String): Outcome<SessionState>

    /**
     * Marks the `/api/v1` session stale after a `401` on that lane, degrading to
     * [SessionState.PlayerOnly] without disturbing playback.
     */
    public suspend fun markSessionStale(reason: PlayerOnlyReason)

    /**
     * Records that Subsonic rejected the app-password (error code 40 or 44) and returns the state
     * the session moved to.
     *
     * This does **not** mean re-onboarding. With the companion bearer still alive the session moves
     * to [SessionState.RepairingAppPassword] and [repairAppPassword] mints a replacement silently;
     * only a rejection with the bearer already dead reaches
     * [SessionState.ReonboardingRequired]. The transition itself is
     * [SessionState.afterAppPasswordRejected], so every caller that sees a code 40 or 44 - the
     * Subsonic client, the player service, a background sync - agrees on the answer.
     *
     * Safe to call repeatedly: several Subsonic requests are usually in flight when the first one is
     * refused, and each of them reports it.
     */
    public suspend fun markAppPasswordRejected(subsonicErrorCode: Int? = null): SessionState

    /**
     * Mints a replacement app-password with the existing companion bearer, and stores it.
     *
     * `POST /api/v1/connect-apps/app-passwords` needs only the bearer, so the app already holds
     * everything required and the user is told nothing: playback pauses for as long as this takes
     * and then resumes. Losing the app-password must not cost the user a sign-in, a re-sync and a
     * multi-gigabyte cache, which is what full re-onboarding would have cost.
     *
     * Implementations must be **single-flight**: many in-flight Subsonic calls fail at once, and one
     * repair per failed request would burn through the server's cap of 25 active app-passwords per
     * user in a single burst. Concurrent callers await the same attempt.
     *
     * The secret is returned exactly once and is never re-fetchable, so a failed write aborts and
     * revokes rather than leaving a credential neither side can use - the same rule onboarding
     * follows.
     *
     * Failure does not necessarily end the repair: a retryable error leaves the session in
     * [SessionState.RepairingAppPassword] with
     * [SessionState.RepairingAppPassword.lastAttemptError] set, so a repair that failed in a tunnel
     * resumes when the connection comes back. Only a permanent failure - the bearer refused too -
     * lands on [SessionState.ReonboardingRequired]. See
     * [SessionState.afterAppPasswordRepairFailed].
     */
    public suspend fun repairAppPassword(): Outcome<SessionState>

    /**
     * Signs out. When [revokeRemote] is true the companion session and app-password are revoked
     * server-side first, so an abandoned device does not keep a live session in the user's list.
     */
    public suspend fun signOut(revokeRemote: Boolean = true): Outcome<Unit>
}

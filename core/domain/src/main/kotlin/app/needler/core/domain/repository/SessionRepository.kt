package app.needler.core.domain.repository

import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.Outcome
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
     * Feature gating must branch on this rather than on "do we have a token", because the degraded
     * [SessionState.PlayerOnly] case has a *valid* app-password and must keep playback, library
     * browsing, playlists and favourites fully working while search, requests and the queue are
     * unavailable.
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
     * Marks the app-password rejected after a Subsonic `401` (error code 40 or 44), which requires full
     * re-onboarding: this is the one auth failure that cannot be degraded around.
     */
    public suspend fun markAppPasswordRevoked()

    /**
     * Signs out. When [revokeRemote] is true the companion session and app-password are revoked
     * server-side first, so an abandoned device does not keep a live session in the user's list.
     */
    public suspend fun signOut(revokeRemote: Boolean = true): Outcome<Unit>
}

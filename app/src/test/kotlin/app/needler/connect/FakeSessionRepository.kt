package app.needler.connect

import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlayerOnlyReason
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.ServerIdentity
import app.needler.core.domain.model.ServerProbe
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.model.User
import app.needler.core.domain.model.UserRole
import app.needler.core.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * A scriptable [SessionRepository] for testing and previewing the Connect
 * screen.
 *
 * `:core:data` has no implementation yet, which is exactly the situation
 * REQUIREMENTS.md describes, and it does not change how this screen is built:
 * the screen talks to the domain interface, so a fake that answers the same
 * interface exercises the real code path. When the implementation lands, these
 * tests keep passing unchanged - that is the whole value of having gone through
 * the interface rather than the network.
 *
 * Each step of onboarding has its own outcome, so a test can make exactly one
 * of them fail and leave the rest working. That matters here: several of the
 * failures REQUIREMENTS.md lists can only happen at one specific step, and a
 * fake that failed everything at once could not tell them apart.
 */
internal class FakeSessionRepository(
    var probeOutcome: Outcome<ServerProbe> = Outcome.Success(PROBE),
    var connectOutcome: Outcome<SessionState> = Outcome.Success(AUTHENTICATED),
    var negotiateOutcome: Outcome<ServerCapabilities> = Outcome.Success(CAPABILITIES),
    var trustOutcome: Outcome<Unit> = Outcome.Ok,
) : SessionRepository {

    /** Every call the screen made, in order, for asserting on the sequence. */
    val calls: MutableList<String> = mutableListOf()

    /** The arguments the last [connect] was made with. */
    var lastConnect: ConnectArgs? = null

    /** The certificate the last [trustCertificate] was made with. */
    var lastTrusted: CertificateInfo? = null

    private val session = MutableStateFlow<SessionState>(SessionState.NotConfigured)

    data class ConnectArgs(
        val serverUrl: String,
        val username: String,
        val password: String,
        val deviceName: String,
    )

    override suspend fun probeServer(rawUrl: String): Outcome<ServerProbe> {
        calls += "probeServer($rawUrl)"
        return probeOutcome
    }

    override suspend fun connect(
        serverUrl: String,
        username: String,
        password: String,
        deviceName: String,
    ): Outcome<SessionState> {
        calls += "connect($serverUrl, $username)"
        lastConnect = ConnectArgs(serverUrl, username, password, deviceName)
        return connectOutcome
    }

    override suspend fun negotiateCapabilities(): Outcome<ServerCapabilities> {
        calls += "negotiateCapabilities()"
        return negotiateOutcome
    }

    override suspend fun trustCertificate(certificate: CertificateInfo): Outcome<Unit> {
        calls += "trustCertificate()"
        lastTrusted = certificate
        return trustOutcome
    }

    // ---- not exercised by the Connect screen --------------------------------

    override fun observeSession(): Flow<SessionState> = session

    override suspend fun currentSession(): SessionState = session.value

    override fun observeCapabilities(): Flow<ServerCapabilities?> = flowOf(null)

    override suspend fun currentCapabilities(): ServerCapabilities? = null

    override fun observeConnectivity(): Flow<ConnectivityState> = flowOf(ConnectivityState.Unmetered)

    override suspend fun currentConnectivity(): ConnectivityState = ConnectivityState.Unmetered

    override suspend fun refreshUser(): Outcome<User> = Outcome.Success(USER)

    override suspend fun reauthenticate(password: String): Outcome<SessionState> = connectOutcome

    override suspend fun markSessionStale(reason: PlayerOnlyReason) = Unit

    override suspend fun markAppPasswordRejected(subsonicErrorCode: Int?): SessionState =
        session.value

    override suspend fun repairAppPassword(): Outcome<SessionState> =
        Outcome.Failure(NeedlerError.Cancelled)

    override suspend fun signOut(revokeRemote: Boolean): Outcome<Unit> = Outcome.Ok

    companion object {
        val IDENTITY = ServerIdentity(baseUrl = "https://music.yourhome.net")

        val PROBE = ServerProbe(identity = IDENTITY, subsonicEnabled = true)

        val CAPABILITIES = ServerCapabilities(
            subsonicEnabled = true,
            transcodingAvailable = true,
            libraryDownloadAllowed = true,
        )

        val USER = User(id = "1", username = "yourname", role = UserRole.ADMIN)

        val AUTHENTICATED = SessionState.Authenticated(
            server = IDENTITY,
            user = USER,
            capabilities = CAPABILITIES,
            bearerExpiresAt = null,
        )
    }
}

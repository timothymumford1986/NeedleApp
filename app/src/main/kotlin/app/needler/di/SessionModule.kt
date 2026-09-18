package app.needler.di

import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlayerOnlyReason
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.ServerProbe
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.model.User
import app.needler.core.domain.repository.SessionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * ### SEAM: replace this whole file when `:core:data` gains its repositories.
 *
 * `:app` is the only module that may see `:core:data`, so it is where every
 * `:core:domain` interface is bound to its implementation. `SessionRepository`
 * has no implementation yet - REQUIREMENTS.md lists "Repository
 * implementations, sync, the write queue" as outstanding work in `:core:data` -
 * and the Connect screen is built against the interface, as intended.
 *
 * Something has to satisfy the binding or the app will not start, so this is
 * that something: a repository that answers every call with
 * [NeedlerError.CapabilityUnavailable] naming itself. It is deliberately not a
 * convincing fake. It reports no server, no session and no capabilities, and it
 * cannot be mistaken at runtime for a working implementation - press Connect
 * and the screen says, in as many words, that the implementation is missing.
 *
 * Replacing it is one edit: delete [provideSessionRepository] and bind
 * `:core:data`'s implementation in its place. Nothing else in `:app` refers to
 * [PendingSessionRepository].
 */
@Module
@InstallIn(SingletonComponent::class)
object SessionModule {

    @Provides
    @Singleton
    fun provideSessionRepository(): SessionRepository = PendingSessionRepository
}

/**
 * The placeholder [SessionRepository]. See [SessionModule].
 *
 * Every suspending call fails the same way, and the failure names the thing
 * that is missing rather than pretending to be a network or credential
 * problem - a fake that answered "wrong password" would send whoever hit it
 * looking in entirely the wrong place.
 */
internal object PendingSessionRepository : SessionRepository {

    private val notImplemented: Outcome<Nothing> = Outcome.Failure(
        NeedlerError.CapabilityUnavailable(
            "SessionRepository has no implementation yet - :core:data does not provide one, " +
                "and :app is binding the placeholder in app.needler.di.SessionModule.",
        ),
    )

    override fun observeSession(): Flow<SessionState> = flowOf(SessionState.NotConfigured)

    override suspend fun currentSession(): SessionState = SessionState.NotConfigured

    override fun observeCapabilities(): Flow<ServerCapabilities?> = flowOf(null)

    override suspend fun currentCapabilities(): ServerCapabilities? = null

    override fun observeConnectivity(): Flow<ConnectivityState> =
        flowOf(ConnectivityState.Unmetered)

    override suspend fun currentConnectivity(): ConnectivityState = ConnectivityState.Unmetered

    override suspend fun probeServer(rawUrl: String): Outcome<ServerProbe> = notImplemented

    override suspend fun trustCertificate(certificate: CertificateInfo): Outcome<Unit> =
        notImplemented

    override suspend fun connect(
        serverUrl: String,
        username: String,
        password: String,
        deviceName: String,
    ): Outcome<SessionState> = notImplemented

    override suspend fun negotiateCapabilities(): Outcome<ServerCapabilities> = notImplemented

    override suspend fun refreshUser(): Outcome<User> = notImplemented

    override suspend fun reauthenticate(password: String): Outcome<SessionState> = notImplemented

    override suspend fun markSessionStale(reason: PlayerOnlyReason) = Unit

    override suspend fun markAppPasswordRejected(subsonicErrorCode: Int?): SessionState =
        SessionState.NotConfigured

    override suspend fun repairAppPassword(): Outcome<SessionState> = notImplemented

    override suspend fun signOut(revokeRemote: Boolean): Outcome<Unit> = notImplemented
}

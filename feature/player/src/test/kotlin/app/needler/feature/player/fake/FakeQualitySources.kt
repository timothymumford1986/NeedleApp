package app.needler.feature.player.fake

import app.needler.core.domain.model.CacheEvictionReason
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.EvictionReport
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Pin
import app.needler.core.domain.model.PinSource
import app.needler.core.domain.model.PlayerOnlyReason
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RemovedDownload
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.ServerProbe
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.model.StoragePreferences
import app.needler.core.domain.model.StorageUsage
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.User
import app.needler.core.domain.repository.DownloadedAlbumOrder
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

/**
 * The two repositories the quality tag pair needs, faked.
 *
 * Both are deliberately narrow: only the members the player reads do anything, and every other one
 * fails loudly. A fake that quietly returned a plausible value for something the player never calls
 * would be a fake that keeps passing when the player starts calling it.
 *
 * They live in this file rather than in `FakePlayerRepositories.kt` so that the pair of repositories
 * added for one feature can be read as one thing.
 */
internal class FakePins(
    cached: CachedAudio? = null,
) : PinRepository {

    /** The on-device copy of whatever is playing. Set it to a pinned, complete row to get `Pulled:`. */
    val cachedAudio: MutableStateFlow<CachedAudio?> = MutableStateFlow(cached)

    override fun observePins(): Flow<List<Pin>> = MutableStateFlow(emptyList())

    override fun observePin(mbid: ReleaseGroupMbid): Flow<Pin?> = MutableStateFlow(null)

    override fun observeDownloadState(mbid: ReleaseGroupMbid): Flow<OfflineDownloadState> =
        MutableStateFlow(OfflineDownloadState.Queued)

    override fun observeStorageUsage(): Flow<StorageUsage> = MutableStateFlow(StorageUsage.Empty)

    override fun observeDownloadedAlbums(
        order: DownloadedAlbumOrder,
        limit: Int,
        offset: Int,
    ): Flow<List<DownloadedAlbum>> = error("not used by :feature:player")

    override fun observeStoragePreferences(): Flow<StoragePreferences> =
        MutableStateFlow(StoragePreferences())

    override suspend fun pinAlbum(mbid: ReleaseGroupMbid, source: PinSource): Outcome<Unit> =
        error("not used by :feature:player")

    override suspend fun unpinAlbum(mbid: ReleaseGroupMbid): Outcome<RemovedDownload> =
        error("not used by :feature:player")

    override suspend fun retryPinnedDownload(mbid: ReleaseGroupMbid): Outcome<Unit> =
        error("not used by :feature:player")

    override suspend fun getCachedAudio(key: TrackKey): CachedAudio? = cachedAudio.value

    override fun observeCachedAudio(key: TrackKey): Flow<CachedAudio?> = cachedAudio.map { it }

    override suspend fun recordPlayed(key: TrackKey, at: Instant) = Unit

    override suspend fun evictCachedAudio(
        key: TrackKey,
        reason: CacheEvictionReason,
    ): Outcome<Unit> = error("not used by :feature:player")

    override suspend fun invalidateIfStale(key: TrackKey, current: TrackFetchHandle): Boolean = false

    override suspend fun enforceFreeSpaceFloor(): Outcome<EvictionReport> =
        error("not used by :feature:player")

    override suspend fun canCacheBytes(bytes: Long): Boolean = true

    override suspend fun clearCachedAudio(): Outcome<EvictionReport> =
        error("not used by :feature:player")

    override suspend fun setKeepPulledAlbumsOnDevice(enabled: Boolean): Outcome<Unit> =
        error("not used by :feature:player")

    override suspend fun setDownloadToDeviceOnWifiOnly(enabled: Boolean): Outcome<Unit> =
        error("not used by :feature:player")

    override suspend fun removeAllFromDevice(): Outcome<EvictionReport> =
        error("not used by :feature:player")
}

/**
 * The session, as the quality tags read it: a connection and a set of capabilities.
 *
 * Unmetered and transcoding-capable by default, which is the state in which the Server tag shows the
 * source's own quality - the ordinary case, and the one every other player test wants to be in
 * without saying so.
 */
internal class FakeSessions(
    connectivity: ConnectivityState = ConnectivityState.Unmetered,
    capabilities: ServerCapabilities? = TRANSCODING_SERVER,
) : SessionRepository {

    val connectivityFlow: MutableStateFlow<ConnectivityState> = MutableStateFlow(connectivity)
    val capabilitiesFlow: MutableStateFlow<ServerCapabilities?> = MutableStateFlow(capabilities)

    override fun observeSession(): Flow<SessionState> = MutableStateFlow(SessionState.NotConfigured)

    override suspend fun currentSession(): SessionState = SessionState.NotConfigured

    override fun observeCapabilities(): Flow<ServerCapabilities?> = capabilitiesFlow

    override suspend fun currentCapabilities(): ServerCapabilities? = capabilitiesFlow.value

    override fun observeConnectivity(): Flow<ConnectivityState> = connectivityFlow

    override suspend fun currentConnectivity(): ConnectivityState = connectivityFlow.value

    override suspend fun probeServer(rawUrl: String): Outcome<ServerProbe> =
        error("not used by :feature:player")

    override suspend fun trustCertificate(certificate: CertificateInfo): Outcome<Unit> =
        error("not used by :feature:player")

    override suspend fun connect(
        serverUrl: String,
        username: String,
        password: String,
        deviceName: String,
    ): Outcome<SessionState> = error("not used by :feature:player")

    override suspend fun negotiateCapabilities(): Outcome<ServerCapabilities> =
        error("not used by :feature:player")

    override suspend fun refreshUser(): Outcome<User> = error("not used by :feature:player")

    override suspend fun reauthenticate(password: String): Outcome<SessionState> =
        error("not used by :feature:player")

    override suspend fun markSessionStale(reason: PlayerOnlyReason) = Unit

    override suspend fun markAppPasswordRejected(subsonicErrorCode: Int?): SessionState =
        SessionState.NotConfigured

    override suspend fun repairAppPassword(): Outcome<SessionState> =
        error("not used by :feature:player")

    override suspend fun signOut(revokeRemote: Boolean): Outcome<Unit> =
        error("not used by :feature:player")

    companion object {
        val TRANSCODING_SERVER: ServerCapabilities = ServerCapabilities(
            subsonicEnabled = true,
            transcodingAvailable = true,
            libraryDownloadAllowed = true,
        )
    }
}

package app.needler.feature.library

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumListKind
import app.needler.core.domain.model.AlbumRequest
import app.needler.core.domain.model.AlbumSyncReport
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.BatchRequestReceipt
import app.needler.core.domain.model.CacheEvictionReason
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.EvictionReport
import app.needler.core.domain.model.FullSyncReason
import app.needler.core.domain.model.Genre
import app.needler.core.domain.model.LibraryStats
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Pin
import app.needler.core.domain.model.PinSource
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.PlaybackSpeed
import app.needler.core.domain.model.PlayerOnlyReason
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullActivitySummary
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.model.PullTaskId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RemovedDownload
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.ServerIdentity
import app.needler.core.domain.model.ServerProbe
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.model.StoragePreferences
import app.needler.core.domain.model.StorageUsage
import app.needler.core.domain.model.SyncReport
import app.needler.core.domain.model.SyncState
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.TrackRequest
import app.needler.core.domain.model.User
import app.needler.core.domain.model.UserRole
import app.needler.core.domain.model.WriteQueueEntry
import app.needler.core.domain.model.WriteQueueFlushReport
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.core.domain.playback.PlaybackState
import app.needler.core.domain.playback.RepeatMode
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.domain.repository.SyncRepository
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Scriptable fakes for the domain interfaces these ViewModels talk to.
 *
 * Hand-written rather than mocked, which is the convention this repository
 * already set with `FakeSessionRepository` in `:app`. The reason holds here
 * too: every one of these screens is driven by `Flow`s, and a test is far more
 * useful when it can push a second value down one of them — a sync landing, a
 * pull finishing, a track starting — than when it can only assert on the first.
 *
 * Methods no screen in this module calls throw. A fake that silently returns a
 * plausible default for a method under test is a test that passes for the wrong
 * reason.
 */
internal class FakeLibraryRepository(
    albums: List<Album> = emptyList(),
    artists: List<Artist> = emptyList(),
    stats: LibraryStats = EMPTY_STATS,
) : LibraryRepository {

    val albumList = MutableStateFlow(albums)
    val artistList = MutableStateFlow(artists)
    val statsFlow = MutableStateFlow(stats)
    val albumsByMbid = MutableStateFlow<Map<String, Album>>(emptyMap())
    val tracksByMbid = MutableStateFlow<Map<String, List<Track>>>(emptyMap())
    val ownedByArtist = MutableStateFlow<Map<String, List<Album>>>(emptyMap())
    val discographyByArtist = MutableStateFlow<Map<String, List<Album>>>(emptyMap())

    /** Every `observeAlbumList` subscription, so a test can assert the sort reached the query. */
    val albumListRequests: MutableList<AlbumListKind> = mutableListOf()

    var refreshAlbumOutcome: Outcome<Unit> = Outcome.Ok
    var refreshDiscographyOutcome: Outcome<Unit> = Outcome.Ok
    var refreshedAlbums: MutableList<ReleaseGroupMbid> = mutableListOf()

    override fun observeArtists(): Flow<List<Artist>> = artistList

    override fun observeArtist(mbid: ArtistMbid): Flow<Artist?> =
        artistList.map { list -> list.firstOrNull { it.mbid == mbid } }

    override fun observeOwnedAlbumsByArtist(mbid: ArtistMbid): Flow<List<Album>> =
        ownedByArtist.map { it[mbid.value].orEmpty() }

    override fun observeArtistDiscography(mbid: ArtistMbid): Flow<List<Album>> =
        discographyByArtist.map { it[mbid.value].orEmpty() }

    override fun observeAlbum(mbid: ReleaseGroupMbid): Flow<Album?> =
        albumsByMbid.map { it[mbid.value] }

    override fun observeAlbumTracks(mbid: ReleaseGroupMbid): Flow<List<Track>> =
        tracksByMbid.map { it[mbid.value].orEmpty() }

    override fun observeAlbumList(kind: AlbumListKind, limit: Int, offset: Int): Flow<List<Album>> {
        albumListRequests += kind
        return albumList
    }

    override fun observeGenres(): Flow<List<Genre>> = error("not used by :feature:library")

    override fun observeTracksByGenre(genre: String, limit: Int, offset: Int): Flow<List<Track>> =
        error("not used by :feature:library")

    override fun observeLibraryStats(): Flow<LibraryStats> = statsFlow

    override suspend fun getTrack(key: TrackKey): Track? =
        tracksByMbid.value[key.releaseGroupMbid.value]?.firstOrNull { it.key == key }

    override suspend fun getTracks(keys: List<TrackKey>): List<Track> = keys.mapNotNull { getTrack(it) }

    override suspend fun getAlbum(mbid: ReleaseGroupMbid): Album? = albumsByMbid.value[mbid.value]

    override suspend fun refreshArtistDiscography(mbid: ArtistMbid): Outcome<Unit> =
        refreshDiscographyOutcome

    override suspend fun refreshAlbum(mbid: ReleaseGroupMbid): Outcome<Unit> {
        refreshedAlbums += mbid
        return refreshAlbumOutcome
    }

    companion object {
        val EMPTY_STATS: LibraryStats = LibraryStats(
            albumCount = 0,
            artistCount = 0,
            trackCount = 0,
            totalSizeBytes = null,
        )
    }
}

internal class FakeSyncRepository : SyncRepository {

    val syncState = MutableStateFlow(SyncState())
    var deltaSyncCalls: Int = 0
    var lastForced: Boolean? = null

    override fun observeSyncState(): Flow<SyncState> = syncState

    override suspend fun currentSyncState(): SyncState = syncState.value

    override suspend fun syncIfStale(maxAge: Duration): Outcome<SyncReport> =
        error("not used by :feature:library")

    override suspend fun deltaSync(force: Boolean): Outcome<SyncReport> {
        deltaSyncCalls++
        lastForced = force
        return Outcome.Success(SyncReport(phase = syncState.value.phase))
    }

    override suspend fun fullSync(reason: FullSyncReason): Outcome<SyncReport> =
        error("not used by :feature:library")

    override suspend fun syncAlbum(mbid: ReleaseGroupMbid): Outcome<AlbumSyncReport> =
        error("not used by :feature:library")

    override suspend fun refreshScanStatus(): Outcome<Unit> = Outcome.Ok

    override fun observeWriteQueue(): Flow<List<WriteQueueEntry>> =
        error("not used by :feature:library")

    override suspend fun flushWriteQueue(): Outcome<WriteQueueFlushReport> =
        error("not used by :feature:library")

    override suspend fun dropWriteQueueEntry(sequence: Long): Outcome<Unit> =
        error("not used by :feature:library")

    override suspend fun resetForServerIdentityChange(): Outcome<Unit> =
        error("not used by :feature:library")
}

internal class FakeSessions(
    connectivity: ConnectivityState = ConnectivityState.Unmetered,
    capabilities: ServerCapabilities? = CAPABILITIES,
) : SessionRepository {

    val connectivityFlow = MutableStateFlow(connectivity)
    val capabilitiesFlow = MutableStateFlow(capabilities)

    /**
     * Signed in by default.
     *
     * A fake that started at `NotConfigured` would make `RequestAlbumUseCase`
     * refuse every pull before it reached the repository, and the resulting
     * "no server configured" would look like a screen bug rather than a test
     * set-up one.
     */
    var sessionState: SessionState = AUTHENTICATED

    override fun observeSession(): Flow<SessionState> = MutableStateFlow(sessionState)

    override suspend fun currentSession(): SessionState = sessionState

    override fun observeCapabilities(): Flow<ServerCapabilities?> = capabilitiesFlow

    override suspend fun currentCapabilities(): ServerCapabilities? = capabilitiesFlow.value

    override fun observeConnectivity(): Flow<ConnectivityState> = connectivityFlow

    override suspend fun currentConnectivity(): ConnectivityState = connectivityFlow.value

    override suspend fun probeServer(rawUrl: String): Outcome<ServerProbe> =
        error("not used by :feature:library")

    override suspend fun trustCertificate(certificate: CertificateInfo): Outcome<Unit> =
        error("not used by :feature:library")

    override suspend fun connect(
        serverUrl: String,
        username: String,
        password: String,
        deviceName: String,
    ): Outcome<SessionState> = error("not used by :feature:library")

    override suspend fun negotiateCapabilities(): Outcome<ServerCapabilities> =
        error("not used by :feature:library")

    override suspend fun refreshUser(): Outcome<User> = error("not used by :feature:library")

    override suspend fun reauthenticate(password: String): Outcome<SessionState> =
        error("not used by :feature:library")

    override suspend fun markSessionStale(reason: PlayerOnlyReason) = Unit

    override suspend fun markAppPasswordRejected(subsonicErrorCode: Int?): SessionState = sessionState

    override suspend fun repairAppPassword(): Outcome<SessionState> =
        error("not used by :feature:library")

    override suspend fun signOut(revokeRemote: Boolean): Outcome<Unit> =
        error("not used by :feature:library")

    companion object {
        val CAPABILITIES: ServerCapabilities = ServerCapabilities(
            subsonicEnabled = true,
            transcodingAvailable = false,
            libraryDownloadAllowed = true,
        )

        val AUTHENTICATED: SessionState = SessionState.Authenticated(
            server = ServerIdentity(baseUrl = "https://music.yourhome.net"),
            user = User(id = "1", username = "yourname", role = UserRole.TRUSTED),
            capabilities = CAPABILITIES,
            bearerExpiresAt = null,
        )
    }
}

internal class FakePullRepository : PullRepository {

    val pullsByMbid = MutableStateFlow<Map<String, Pull>>(emptyMap())

    var requestAlbumOutcome: Outcome<RequestReceipt> =
        Outcome.Success(RequestReceipt(releaseGroupMbid = null, status = RequestStatus.ACCEPTED))
    var requestTrackOutcome: Outcome<RequestReceipt> =
        Outcome.Success(RequestReceipt(releaseGroupMbid = null, status = RequestStatus.ACCEPTED))
    var cancelOutcome: Outcome<Unit> = Outcome.Ok
    var retryOutcome: Outcome<Unit> = Outcome.Ok

    val albumRequests: MutableList<AlbumRequest> = mutableListOf()
    val trackRequests: MutableList<TrackRequest> = mutableListOf()
    val cancelled: MutableList<ReleaseGroupMbid> = mutableListOf()
    val retried: MutableList<ReleaseGroupMbid> = mutableListOf()

    override fun observePulls(): Flow<List<Pull>> = pullsByMbid.map { it.values.toList() }

    override fun observePulls(bucket: PullBucket): Flow<List<Pull>> =
        pullsByMbid.map { pulls -> pulls.values.filter { it.bucket == bucket } }

    override fun observePull(mbid: ReleaseGroupMbid): Flow<Pull?> =
        pullsByMbid.map { it[mbid.value] }

    override fun observePendingApprovals(): Flow<List<Pull>> =
        error("not used by :feature:library")

    override fun observeActivitySummary(): Flow<PullActivitySummary?> =
        error("not used by :feature:library")

    override fun observePullBadgeCount(): Flow<Int> = error("not used by :feature:library")

    override suspend fun requestAlbum(request: AlbumRequest): Outcome<RequestReceipt> {
        albumRequests += request
        return requestAlbumOutcome
    }

    override suspend fun requestTrack(request: TrackRequest): Outcome<RequestReceipt> {
        trackRequests += request
        return requestTrackOutcome
    }

    override suspend fun requestAlbums(
        requests: List<AlbumRequest>,
    ): Outcome<BatchRequestReceipt> = error("not used by :feature:library")

    override suspend fun cancelRequest(mbid: ReleaseGroupMbid): Outcome<Unit> {
        cancelled += mbid
        return cancelOutcome
    }

    override suspend fun retryRequest(mbid: ReleaseGroupMbid): Outcome<Unit> {
        retried += mbid
        return retryOutcome
    }

    override suspend fun cancelTask(taskId: PullTaskId): Outcome<Unit> =
        error("not used by :feature:library")

    override suspend fun retryTask(taskId: PullTaskId): Outcome<Unit> =
        error("not used by :feature:library")

    override suspend fun refreshPulls(): Outcome<Unit> = Outcome.Ok

    override suspend fun refreshActivitySummary(): Outcome<PullActivitySummary> =
        error("not used by :feature:library")

    override suspend fun markCompletionsSeen(mbids: Set<ReleaseGroupMbid>) = Unit
}

internal class FakePinRepository : PinRepository {

    val pins = MutableStateFlow<Map<String, Pin>>(emptyMap())
    val usage = MutableStateFlow(StorageUsage.Empty.copy(deviceFreeBytes = 200L * 1_073_741_824L))
    val preferences = MutableStateFlow(StoragePreferences())

    var pinOutcome: Outcome<Unit> = Outcome.Ok
    var unpinOutcome: Outcome<RemovedDownload>? = null

    val pinned: MutableList<ReleaseGroupMbid> = mutableListOf()
    val unpinned: MutableList<ReleaseGroupMbid> = mutableListOf()

    override fun observePins(): Flow<List<Pin>> = pins.map { it.values.toList() }

    override fun observePin(mbid: ReleaseGroupMbid): Flow<Pin?> = pins.map { it[mbid.value] }

    override fun observeDownloadState(mbid: ReleaseGroupMbid): Flow<OfflineDownloadState> =
        pins.map { it[mbid.value]?.download ?: OfflineDownloadState.Queued }

    override fun observeStorageUsage(): Flow<StorageUsage> = usage

    override fun observeDownloadedAlbums(): Flow<List<DownloadedAlbum>> =
        error("not used by :feature:library")

    override fun observeStoragePreferences(): Flow<StoragePreferences> = preferences

    override suspend fun pinAlbum(mbid: ReleaseGroupMbid, source: PinSource): Outcome<Unit> {
        pinned += mbid
        return pinOutcome
    }

    override suspend fun unpinAlbum(mbid: ReleaseGroupMbid): Outcome<RemovedDownload> {
        unpinned += mbid
        return unpinOutcome ?: Outcome.Success(
            RemovedDownload(releaseGroupMbid = mbid, removedTracks = 8, freedBytes = 412_000_000L),
        )
    }

    override suspend fun retryPinnedDownload(mbid: ReleaseGroupMbid): Outcome<Unit> = Outcome.Ok

    override suspend fun getCachedAudio(key: TrackKey): CachedAudio? = null

    override fun observeCachedAudio(key: TrackKey): Flow<CachedAudio?> = MutableStateFlow(null)

    override suspend fun recordPlayed(key: TrackKey, at: Instant) = Unit

    override suspend fun evictCachedAudio(
        key: TrackKey,
        reason: CacheEvictionReason,
    ): Outcome<Unit> = error("not used by :feature:library")

    override suspend fun invalidateIfStale(key: TrackKey, current: TrackFetchHandle): Boolean = false

    override suspend fun enforceFreeSpaceFloor(): Outcome<EvictionReport> =
        error("not used by :feature:library")

    override suspend fun canCacheBytes(bytes: Long): Boolean = true

    override suspend fun clearCachedAudio(): Outcome<EvictionReport> =
        error("not used by :feature:library")

    override suspend fun setKeepPulledAlbumsOnDevice(enabled: Boolean): Outcome<Unit> =
        error("not used by :feature:library")

    override suspend fun setDownloadToDeviceOnWifiOnly(enabled: Boolean): Outcome<Unit> =
        error("not used by :feature:library")

    override suspend fun removeAllFromDevice(): Outcome<EvictionReport> =
        error("not used by :feature:library")
}

/** Records what the screens asked the player to do, without a Media3 session in sight. */
internal class FakePlaybackController : PlaybackController {

    val playbackState = MutableStateFlow(PlaybackState.Idle)

    data class PlayAlbumCall(
        val mbid: ReleaseGroupMbid,
        val startIndex: Int,
        val shuffle: Boolean,
    )

    val playAlbumCalls: MutableList<PlayAlbumCall> = mutableListOf()
    val playTracksCalls: MutableList<List<Track>> = mutableListOf()

    override fun observeState(): Flow<PlaybackState> = playbackState

    override fun observeProgress(): Flow<PlaybackProgress> = MutableStateFlow(PlaybackProgress.Zero)

    override fun observeQueue(): Flow<PlayQueue> = MutableStateFlow(PlayQueue.Empty)

    override suspend fun currentState(): PlaybackState = playbackState.value

    override suspend fun play() = Unit

    override suspend fun pause() = Unit

    override suspend fun playPause() = Unit

    override suspend fun seekTo(positionMs: Long) = Unit

    override suspend fun skipToNext() = Unit

    override suspend fun skipToPrevious() = Unit

    override suspend fun skipToQueueItem(itemId: String) = Unit

    override suspend fun playAlbum(mbid: ReleaseGroupMbid, startIndex: Int, shuffle: Boolean) {
        playAlbumCalls += PlayAlbumCall(mbid, startIndex, shuffle)
    }

    override suspend fun playTracks(tracks: List<Track>, startIndex: Int) {
        playTracksCalls += tracks
    }

    override suspend fun enqueue(tracks: List<Track>, playNext: Boolean) = Unit

    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) = Unit

    override suspend fun removeQueueItem(itemId: String) = Unit

    override suspend fun clearQueue() = Unit

    override suspend fun setShuffleEnabled(enabled: Boolean) = Unit

    override suspend fun setRepeatMode(mode: RepeatMode) = Unit

    override suspend fun setPlaybackSpeed(speed: PlaybackSpeed) = Unit

    override suspend fun stop() = Unit
}

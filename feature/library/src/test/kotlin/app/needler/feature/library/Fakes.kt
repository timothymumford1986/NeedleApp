package app.needler.feature.library

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumListKind
import app.needler.core.domain.model.AlbumRequest
import app.needler.core.domain.model.AlbumSyncReport
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistDiscographyPage
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.BatchRequestReceipt
import app.needler.core.domain.model.CacheEvictionReason
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.EvictionReport
import app.needler.core.domain.model.FavouriteTarget
import app.needler.core.domain.model.Favourites
import app.needler.core.domain.model.FullSyncReason
import app.needler.core.domain.model.Genre
import app.needler.core.domain.model.LibraryStats
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Pin
import app.needler.core.domain.model.PinSource
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.QueueItem
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
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.model.StoragePreferences
import app.needler.core.domain.model.StorageUsage
import app.needler.core.domain.model.SyncReport
import app.needler.core.domain.model.SyncState
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.TrackListKind
import app.needler.core.domain.model.TrackRequest
import app.needler.core.domain.model.User
import app.needler.core.domain.model.UserRole
import app.needler.core.domain.model.WriteQueueEntry
import app.needler.core.domain.model.WriteQueueFlushReport
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.core.domain.playback.PlaybackState
import app.needler.core.domain.playback.RepeatMode
import app.needler.core.domain.repository.DownloadedAlbumOrder
import app.needler.core.domain.repository.FavouriteRepository
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
    songs: List<Track> = emptyList(),
    stats: LibraryStats = EMPTY_STATS,
) : LibraryRepository {

    val albumList = MutableStateFlow(albums)
    val artistList = MutableStateFlow(artists)
    val songList = MutableStateFlow(songs)
    val statsFlow = MutableStateFlow(stats)
    val albumsByMbid = MutableStateFlow<Map<String, Album>>(emptyMap())
    val tracksByMbid = MutableStateFlow<Map<String, List<Track>>>(emptyMap())
    val ownedByArtist = MutableStateFlow<Map<String, List<Album>>>(emptyMap())
    val discographyByArtist = MutableStateFlow<Map<String, List<Album>>>(emptyMap())

    /** Every `observeAlbumList` subscription, so a test can assert the sort reached the query. */
    val albumListRequests: MutableList<AlbumListKind> = mutableListOf()

    /**
     * Every `observeTracks` subscription.
     *
     * Recorded separately from [albumListRequests] because the distinction is the point: the Songs
     * tab used to subscribe to an *album* list and flatten it, which is how choosing "Title" on it
     * came to sort by album title. A test can now assert that the songs query is a songs query.
     */
    val trackListRequests: MutableList<TrackListKind> = mutableListOf()

    var refreshAlbumOutcome: Outcome<Unit> = Outcome.Ok
    var refreshDiscographyOutcome: Outcome<Unit> = Outcome.Ok

    /**
     * What one page of a discography answers, by the offset it was asked for.
     *
     * Defaults to honouring [refreshDiscographyOutcome] and reporting a single complete page, so
     * every test written before the fetch was paged still scripts it the same way. A test about
     * paging replaces this instead, and [discographyOffsets] is what it asserts the walk against.
     */
    var discographyPage: (Int) -> Outcome<ArtistDiscographyPage> = { _ ->
        when (val outcome: Outcome<Unit> = refreshDiscographyOutcome) {
            is Outcome.Failure -> outcome
            is Outcome.Success -> Outcome.Success(ArtistDiscographyPage.UNPAGED)
        }
    }

    /** Every offset a discography page was asked for, in order. */
    val discographyOffsets: MutableList<Int> = mutableListOf()
    var refreshedAlbums: MutableList<ReleaseGroupMbid> = mutableListOf()

    /**
     * Every artist whose discography was actually fetched.
     *
     * Recorded because the interesting assertion is now a *negative* one: a DroppedNeedle
     * name-derived artist id must never reach this route, and "did not call" is only
     * assertable if the calls are counted.
     */
    val refreshedDiscographies: MutableList<ArtistMbid> = mutableListOf()

    override fun observeArtists(limit: Int, offset: Int): Flow<List<Artist>> =
        artistList.map { it.drop(offset).take(limit) }

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

    override fun observeTracks(kind: TrackListKind, limit: Int, offset: Int): Flow<List<Track>> {
        trackListRequests += kind
        return songList
    }

    override fun observeGenres(limit: Int, offset: Int): Flow<List<Genre>> =
        error("not used by :feature:library")

    override fun observeTracksByGenre(genre: String, limit: Int, offset: Int): Flow<List<Track>> =
        error("not used by :feature:library")

    override fun observeLibraryStats(): Flow<LibraryStats> = statsFlow

    override suspend fun getTrack(key: TrackKey): Track? =
        tracksByMbid.value[key.releaseGroupMbid.value]?.firstOrNull { it.key == key }

    override suspend fun getTracks(keys: List<TrackKey>): List<Track> = keys.mapNotNull { getTrack(it) }

    override suspend fun getAlbum(mbid: ReleaseGroupMbid): Album? = albumsByMbid.value[mbid.value]

    override suspend fun refreshArtistDiscography(mbid: ArtistMbid): Outcome<Unit> {
        refreshedDiscographies += mbid
        return refreshDiscographyOutcome
    }

    override suspend fun refreshArtistDiscographyPage(
        mbid: ArtistMbid,
        offset: Int,
    ): Outcome<ArtistDiscographyPage> {
        refreshedDiscographies += mbid
        discographyOffsets += offset
        return discographyPage(offset)
    }

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

    /** Each `requests/batch` body, for the artist-wide pull. */
    val batchRequests: MutableList<List<AlbumRequest>> = mutableListOf()

    /**
     * The batch receipt.
     *
     * `overflow` is 0 and stays 0 deliberately: REQUIREMENTS.md "Placing a request" says the
     * server "never sets it to anything but `0`" because a 501-item body is rejected before the
     * handler runs, and callers "must not rely on `overflow` for anything at all". A fake that
     * set it would invite a caller to start reading it.
     */
    var requestAlbumsOutcome: Outcome<BatchRequestReceipt> = Outcome.Success(
        BatchRequestReceipt(requested = emptyList(), skipped = emptyList(), overflow = 0),
    )
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
    ): Outcome<BatchRequestReceipt> {
        batchRequests += requests
        return requestAlbumsOutcome
    }

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

    override fun observeDownloadedAlbums(
        order: DownloadedAlbumOrder,
        limit: Int,
        offset: Int,
    ): Flow<List<DownloadedAlbum>> =
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

    /**
     * The live crate, scriptable.
     *
     * A field rather than a fresh `MutableStateFlow` per call, because the album screen's transport
     * is decided by what the crate is **made of** and a test has to be able to load one. The
     * default is still [PlayQueue.Empty], so every existing caller sees what it saw before.
     */
    val queue = MutableStateFlow(PlayQueue.Empty)

    data class PlayAlbumCall(
        val mbid: ReleaseGroupMbid,
        val startIndex: Int,
        val shuffle: Boolean,
    )

    val playAlbumCalls: MutableList<PlayAlbumCall> = mutableListOf()
    val playTracksCalls: MutableList<List<Track>> = mutableListOf()

    data class EnqueueCall(val tracks: List<Track>, val playNext: Boolean)

    /**
     * Every add to the crate, in order.
     *
     * Recorded rather than ignored because the assertion that matters about an add is a
     * *negative* one: it must reach `enqueue` and must **not** reach `playTracks` or
     * `playAlbum`, which are the commands that replace the crate. That was the whole of the
     * defect - every surface in the library could only replace - and "did not call" is only
     * assertable if the calls are kept.
     */
    val enqueueCalls: MutableList<EnqueueCall> = mutableListOf()

    /**
     * Every transport command this controller was given, in order, named as the interface names it:
     * `play`, `pause`, `playPause`, `playAlbum`.
     *
     * A log rather than a set of booleans because the assertion that matters is a negative one, and
     * it is about which command was chosen: resuming must reach `play` and **not** `playAlbum`,
     * which is what replaces the crate and seeks to zero. "Did not call" is only assertable if the
     * calls are recorded.
     */
    val transportCommands: MutableList<String> = mutableListOf()

    override fun observeState(): Flow<PlaybackState> = playbackState

    override fun observeProgress(): Flow<PlaybackProgress> = MutableStateFlow(PlaybackProgress.Zero)

    override fun observeQueue(): Flow<PlayQueue> = queue

    override suspend fun currentState(): PlaybackState = playbackState.value

    override suspend fun play() {
        transportCommands += "play"
    }

    override suspend fun pause() {
        transportCommands += "pause"
    }

    override suspend fun playPause() {
        transportCommands += "playPause"
    }

    override suspend fun seekTo(positionMs: Long) = Unit

    override suspend fun skipToNext() = Unit

    override suspend fun skipToPrevious() = Unit

    override suspend fun skipToQueueItem(itemId: String) = Unit

    override suspend fun playAlbum(mbid: ReleaseGroupMbid, startIndex: Int, shuffle: Boolean) {
        playAlbumCalls += PlayAlbumCall(mbid, startIndex, shuffle)
        transportCommands += "playAlbum"
    }

    override suspend fun playTracks(tracks: List<Track>, startIndex: Int) {
        playTracksCalls += tracks
        // Starting a queue loads it and makes something current, which is what the real
        // session does and what the screens then read back as "the crate has 12 in it".
        queue.value = PlayQueue(items = tracks.map(::rowFor), currentIndex = 0)
        playbackState.value = playbackState.value.copy(
            currentItem = queue.value.currentItem,
            isPlaying = true,
        )
    }

    /**
     * Appends, or inserts after the playing row, exactly as the interface promises.
     *
     * The crate is really mutated rather than only recorded, so that a test can assert the
     * count and the total duration the screens show came from the session and not from an
     * expectation the ViewModel formed for itself. Nothing about [PlaybackState] changes:
     * `PlaybackController.enqueue` never starts sound, and a fake that quietly started
     * playing would hide the one case the feature has to handle itself.
     */
    override suspend fun enqueue(tracks: List<Track>, playNext: Boolean) {
        enqueueCalls += EnqueueCall(tracks = tracks, playNext = playNext)
        val rows: List<QueueItem> = tracks.map(::rowFor)
        val at: Int? = if (playNext) (queue.value.currentIndex ?: -1) + 1 else null
        queue.value = queue.value.withItemsInserted(rows, index = at)
    }

    /** A crate row for a track, with an id as unlikely to collide as the session's own. */
    private fun rowFor(track: Track): QueueItem =
        QueueItem(id = (nextRowId++).toString() + "@" + track.key.canonicalString, track = track)

    private var nextRowId: Int = 1

    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) = Unit

    override suspend fun removeQueueItem(itemId: String) = Unit

    override suspend fun clearQueue() = Unit

    override suspend fun setShuffleEnabled(enabled: Boolean) = Unit

    override suspend fun setRepeatMode(mode: RepeatMode) = Unit

    override suspend fun setPlaybackSpeed(speed: PlaybackSpeed) = Unit

    /**
     * Declared because the interface declares it with no default body.
     *
     * `PlaybackController.setSleepTimer` is deliberately abstract - the player service is the only
     * thing that can evaluate a timer, and a no-op default would let a surface silently arm one that
     * never fired. Nothing in this module calls it, so the fake records nothing; it exists so that
     * the contract stays total.
     */
    override suspend fun setSleepTimer(timer: SleepTimer) = Unit

    override suspend fun stop() = Unit
}

/**
 * Favourites in memory, the shape `DefaultFavouriteRepository` presents.
 *
 * `setFavourite` writes the local state first and only then reports, which is what the real one does
 * — REQUIREMENTS.md "Write queue": a star is applied to the mirror at once and journalled for replay.
 * That ordering is what lets the screens leave the star outside their busy gate, so a fake that only
 * recorded the call would let a test pass for a control that never moved.
 */
internal class FakeFavouriteRepository : FavouriteRepository {

    val starred = MutableStateFlow<Set<FavouriteTarget>>(emptySet())
    val calls: MutableList<Pair<FavouriteTarget, Boolean>> = mutableListOf()

    var setOutcome: Outcome<Unit> = Outcome.Ok

    override fun observeFavourites(): Flow<Favourites> = error("not used by :feature:library")

    override fun observeIsFavourite(target: FavouriteTarget): Flow<Boolean> =
        starred.map { it.contains(target) }

    override suspend fun setFavourite(target: FavouriteTarget, starred: Boolean): Outcome<Unit> {
        calls += target to starred
        if (setOutcome is Outcome.Success) {
            this.starred.value = if (starred) {
                this.starred.value + target
            } else {
                this.starred.value - target
            }
        }
        return setOutcome
    }

    override suspend fun refreshFavourites(): Outcome<Unit> = Outcome.Ok
}

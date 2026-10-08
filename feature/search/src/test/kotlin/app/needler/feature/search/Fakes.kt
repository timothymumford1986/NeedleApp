package app.needler.feature.search

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumListKind
import app.needler.core.domain.model.AlbumRequest
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.BatchRequestReceipt
import app.needler.core.domain.model.CatalogueSearchPage
import app.needler.core.domain.model.CatalogueSearchResults
import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.Genre
import app.needler.core.domain.model.LibraryStats
import app.needler.core.domain.model.LocalSearchResults
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.PlaybackSpeed
import app.needler.core.domain.model.PlayerOnlyReason
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullActivitySummary
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.model.PullTaskId
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.ServerIdentity
import app.needler.core.domain.model.ServerProbe
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.model.SearchBucket
import app.needler.core.domain.model.SearchSuggestion
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.TrackListKind
import app.needler.core.domain.model.TrackRequest
import app.needler.core.domain.model.User
import app.needler.core.domain.model.UserRole
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.core.domain.playback.PlaybackState
import app.needler.core.domain.playback.RepeatMode
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SearchRepository
import app.needler.core.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The repositories [app.needler.feature.search.search.SearchViewModel] takes, in memory.
 *
 * A deliberate near-duplicate of `:feature:library`'s `Fakes.kt`, written against the same
 * interfaces. A test source set is not publishable, so there is no way for one module to depend on
 * another's fakes; the shared `:core:testing` module that would fix it is in the handover notes and
 * these are kept trivial so that moving them there is a file move.
 *
 * Everything this screen does not use throws rather than returning a plausible empty value, so a
 * test that starts depending on a lane the screen must not touch - the catalogue, from the Songs
 * block - fails loudly instead of passing quietly.
 */
internal class FakeSearchRepository : SearchRepository {

    val localResults = MutableStateFlow(LocalSearchResults(query = ""))
    val recent = MutableStateFlow<List<String>>(emptyList())
    val recorded: MutableList<String> = mutableListOf()

    var catalogueOutcome: Outcome<CatalogueSearchResults> =
        Outcome.Success(CatalogueSearchResults(query = "", artists = emptyList(), albums = emptyList()))

    override fun searchLocal(query: String, limit: Int): Flow<LocalSearchResults> =
        localResults.map { results -> results.copy(query = query) }

    override suspend fun searchCatalogue(
        query: String,
        limitArtists: Int,
        limitAlbums: Int,
    ): Outcome<CatalogueSearchResults> = catalogueOutcome

    override suspend fun searchCatalogueBucket(
        bucket: SearchBucket,
        query: String,
        limit: Int,
        offset: Int,
    ): Outcome<CatalogueSearchPage> = error("not used by these tests")

    override suspend fun suggest(query: String, limit: Int): Outcome<List<SearchSuggestion>> =
        Outcome.Success(emptyList())

    override fun observeRecentQueries(limit: Int): Flow<List<String>> = recent

    override suspend fun recordRecentQuery(query: String) {
        recorded += query
    }

    override suspend fun clearRecentQueries() {
        recent.value = emptyList()
    }
}

/** The mirror, holding one album's tracks: all this screen reads it for. */
internal class FakeLibraryRepository : LibraryRepository {

    val tracksByMbid = MutableStateFlow<Map<String, List<Track>>>(emptyMap())

    override fun observeAlbumTracks(mbid: ReleaseGroupMbid): Flow<List<Track>> =
        tracksByMbid.map { it[mbid.value].orEmpty() }

    override fun observeArtists(limit: Int, offset: Int): Flow<List<Artist>> =
        error("not used by :feature:search")

    override fun observeArtist(mbid: ArtistMbid): Flow<Artist?> =
        error("not used by :feature:search")

    override fun observeOwnedAlbumsByArtist(mbid: ArtistMbid): Flow<List<Album>> =
        error("not used by :feature:search")

    override fun observeArtistDiscography(mbid: ArtistMbid): Flow<List<Album>> =
        error("not used by :feature:search")

    override fun observeAlbum(mbid: ReleaseGroupMbid): Flow<Album?> =
        error("not used by :feature:search")

    override fun observeAlbumList(kind: AlbumListKind, limit: Int, offset: Int): Flow<List<Album>> =
        error("not used by :feature:search")

    override fun observeTracks(kind: TrackListKind, limit: Int, offset: Int): Flow<List<Track>> =
        error("not used by :feature:search")

    override fun observeGenres(limit: Int, offset: Int): Flow<List<Genre>> =
        error("not used by :feature:search")

    override fun observeTracksByGenre(genre: String, limit: Int, offset: Int): Flow<List<Track>> =
        error("not used by :feature:search")

    override fun observeLibraryStats(): Flow<LibraryStats> = error("not used by :feature:search")

    override suspend fun getTrack(key: TrackKey): Track? =
        tracksByMbid.value[key.releaseGroupMbid.value]?.firstOrNull { it.key == key }

    override suspend fun getTracks(keys: List<TrackKey>): List<Track> = keys.mapNotNull { getTrack(it) }

    override suspend fun getAlbum(mbid: ReleaseGroupMbid): Album? = null

    override suspend fun refreshArtistDiscography(mbid: ArtistMbid): Outcome<Unit> = Outcome.Ok

    override suspend fun refreshAlbum(mbid: ReleaseGroupMbid): Outcome<Unit> = Outcome.Ok
}

/** Pulls, recorded. The request sheet's confirm is the only thing that reaches it. */
internal class FakePullRepository : PullRepository {

    val albumRequests: MutableList<AlbumRequest> = mutableListOf()

    var requestOutcome: Outcome<RequestReceipt> = Outcome.Success(
        RequestReceipt(
            releaseGroupMbid = ReleaseGroupMbid("rg-x"),
            status = RequestStatus.ACCEPTED,
        ),
    )

    override fun observePulls(): Flow<List<Pull>> = MutableStateFlow(emptyList())

    override fun observePulls(bucket: PullBucket): Flow<List<Pull>> = MutableStateFlow(emptyList())

    override fun observePull(mbid: ReleaseGroupMbid): Flow<Pull?> = MutableStateFlow(null)

    override fun observePendingApprovals(): Flow<List<Pull>> = MutableStateFlow(emptyList())

    override fun observeActivitySummary(): Flow<PullActivitySummary?> = MutableStateFlow(null)

    override fun observePullBadgeCount(): Flow<Int> = MutableStateFlow(0)

    override suspend fun requestAlbum(request: AlbumRequest): Outcome<RequestReceipt> {
        albumRequests += request
        return requestOutcome
    }

    override suspend fun requestTrack(request: TrackRequest): Outcome<RequestReceipt> =
        error("not used by :feature:search")

    override suspend fun requestAlbums(
        requests: List<AlbumRequest>,
    ): Outcome<BatchRequestReceipt> = error("not used by :feature:search")

    /** The release groups a row's `⋯` asked to stop, newest last. */
    val cancelled: MutableList<ReleaseGroupMbid> = mutableListOf()

    var cancelOutcome: Outcome<Unit> = Outcome.Ok

    override suspend fun cancelRequest(mbid: ReleaseGroupMbid): Outcome<Unit> {
        cancelled += mbid
        return cancelOutcome
    }

    override suspend fun retryRequest(mbid: ReleaseGroupMbid): Outcome<Unit> =
        error("not used by :feature:search")

    override suspend fun cancelTask(taskId: PullTaskId): Outcome<Unit> =
        error("not used by :feature:search")

    override suspend fun retryTask(taskId: PullTaskId): Outcome<Unit> =
        error("not used by :feature:search")

    override suspend fun refreshPulls(): Outcome<Unit> = Outcome.Ok

    override suspend fun refreshActivitySummary(): Outcome<PullActivitySummary> =
        error("not used by :feature:search")

    override suspend fun markCompletionsSeen(mbids: Set<ReleaseGroupMbid>) = Unit
}

/** Signed in and online, which is the state every test here wants. */
internal class FakeSessions(
    connectivity: ConnectivityState = ConnectivityState.Unmetered,
) : SessionRepository {

    val connectivityFlow = MutableStateFlow(connectivity)
    val capabilitiesFlow = MutableStateFlow<ServerCapabilities?>(CAPABILITIES)

    var sessionState: SessionState = AUTHENTICATED

    override fun observeSession(): Flow<SessionState> = MutableStateFlow(sessionState)

    override suspend fun currentSession(): SessionState = sessionState

    override fun observeCapabilities(): Flow<ServerCapabilities?> = capabilitiesFlow

    override suspend fun currentCapabilities(): ServerCapabilities? = capabilitiesFlow.value

    override fun observeConnectivity(): Flow<ConnectivityState> = connectivityFlow

    override suspend fun currentConnectivity(): ConnectivityState = connectivityFlow.value

    override suspend fun probeServer(rawUrl: String): Outcome<ServerProbe> =
        error("not used by :feature:search")

    override suspend fun trustCertificate(certificate: CertificateInfo): Outcome<Unit> =
        error("not used by :feature:search")

    override suspend fun connect(
        serverUrl: String,
        username: String,
        password: String,
        deviceName: String,
    ): Outcome<SessionState> = error("not used by :feature:search")

    override suspend fun negotiateCapabilities(): Outcome<ServerCapabilities> =
        error("not used by :feature:search")

    override suspend fun refreshUser(): Outcome<User> = error("not used by :feature:search")

    override suspend fun reauthenticate(password: String): Outcome<SessionState> =
        error("not used by :feature:search")

    override suspend fun markSessionStale(reason: PlayerOnlyReason) = Unit

    override suspend fun markAppPasswordRejected(subsonicErrorCode: Int?): SessionState = sessionState

    override suspend fun repairAppPassword(): Outcome<SessionState> =
        error("not used by :feature:search")

    override suspend fun signOut(revokeRemote: Boolean): Outcome<Unit> =
        error("not used by :feature:search")

    private companion object {
        val CAPABILITIES: ServerCapabilities = ServerCapabilities(
            subsonicEnabled = true,
            transcodingAvailable = false,
            libraryDownloadAllowed = true,
        )

        val AUTHENTICATED: SessionState = SessionState.Authenticated(
            server = ServerIdentity(baseUrl = "https://music.yourhome.net"),
            user = User(id = "1", username = "yourname", role = UserRole.TRUSTED),
            capabilities = CAPABILITIES,
            // The companion bearer's expiry. Far enough away that nothing under test warns
            // about it; `null` would also do, and a date says which case this is.
            bearerExpiresAt = null,
        )
    }
}

/**
 * Records what the screen asked the player to do, with no Media3 session in sight.
 *
 * The crate is really mutated by [enqueue] and [playTracks], so a test can assert that the count
 * and total duration the screen shows came from the session rather than from a figure the ViewModel
 * worked out for itself. [enqueue] deliberately leaves [PlaybackState] alone: the interface
 * promises it never starts sound, and a fake that quietly started playing would hide the one case
 * the feature has to handle itself.
 */
internal class FakePlaybackController : PlaybackController {

    val playbackState = MutableStateFlow(PlaybackState.Idle)
    val queue = MutableStateFlow(PlayQueue.Empty)

    data class EnqueueCall(val tracks: List<Track>, val playNext: Boolean)

    val enqueueCalls: MutableList<EnqueueCall> = mutableListOf()
    val playTracksCalls: MutableList<List<Track>> = mutableListOf()
    val playAlbumCalls: MutableList<ReleaseGroupMbid> = mutableListOf()

    private var nextRowId: Int = 1

    override fun observeState(): Flow<PlaybackState> = playbackState

    override fun observeProgress(): Flow<PlaybackProgress> = MutableStateFlow(PlaybackProgress.Zero)

    override fun observeQueue(): Flow<PlayQueue> = queue

    override suspend fun currentState(): PlaybackState = playbackState.value

    override suspend fun play() = Unit

    override suspend fun pause() = Unit

    override suspend fun playPause() = Unit

    override suspend fun seekTo(positionMs: Long) = Unit

    override suspend fun skipToNext() = Unit

    override suspend fun skipToPrevious() = Unit

    override suspend fun skipToQueueItem(itemId: String) = Unit

    override suspend fun playAlbum(mbid: ReleaseGroupMbid, startIndex: Int, shuffle: Boolean) {
        playAlbumCalls += mbid
    }

    override suspend fun playTracks(tracks: List<Track>, startIndex: Int) {
        playTracksCalls += tracks
        queue.value = PlayQueue(items = tracks.map(::rowFor), currentIndex = 0)
        playbackState.value = playbackState.value.copy(
            currentItem = queue.value.currentItem,
            isPlaying = true,
        )
    }

    override suspend fun enqueue(tracks: List<Track>, playNext: Boolean) {
        enqueueCalls += EnqueueCall(tracks = tracks, playNext = playNext)
        val at: Int? = if (playNext) (queue.value.currentIndex ?: -1) + 1 else null
        queue.value = queue.value.withItemsInserted(tracks.map(::rowFor), index = at)
    }

    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) = Unit

    override suspend fun removeQueueItem(itemId: String) = Unit

    override suspend fun clearQueue() = Unit

    override suspend fun setShuffleEnabled(enabled: Boolean) = Unit

    override suspend fun setRepeatMode(mode: RepeatMode) = Unit

    override suspend fun setPlaybackSpeed(speed: PlaybackSpeed) = Unit

    /**
     * Declared because the interface declares it with no default body.
     *
     * `PlaybackController.setSleepTimer` is deliberately abstract - a silently defaulted no-op is
     * the bug that member was added to fix - so this exists to keep the contract total rather than
     * because anything here arms a timer.
     */
    override suspend fun setSleepTimer(timer: SleepTimer) = Unit

    override suspend fun stop() = Unit

    private fun rowFor(track: Track): QueueItem =
        QueueItem(id = (nextRowId++).toString() + "@" + track.key.canonicalString, track = track)
}

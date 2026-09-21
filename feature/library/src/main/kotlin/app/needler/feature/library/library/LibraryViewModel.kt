@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package app.needler.feature.library.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.LibraryStats
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.domain.repository.SyncRepository
import app.needler.feature.library.common.hasPlayableFile
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import javax.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * Drives the Library screen off the local mirror.
 *
 * Every list on this screen is a Room query behind a repository interface, so
 * nothing here awaits a network call and offline needs no second code path —
 * REQUIREMENTS.md: "The mirror is the read path." The only outbound call this
 * ViewModel can make is the explicit **Sync now** from the empty state.
 *
 * ## Only the visible tab is queried
 *
 * The tab and the sort are flattened into one flow with `flatMapLatest`, so
 * switching to Artists cancels the album query rather than leaving three lists
 * subscribed. On a five-thousand-album library that is the difference between
 * one Room query and three.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val sync: SyncRepository,
    session: SessionRepository,
    private val playback: Optional<PlaybackController>,
) : ViewModel() {

    private val selectedTab = MutableStateFlow(LibraryTab.ALBUMS)
    private val selectedSort = MutableStateFlow(LibrarySort.RECENT)
    private val viewMode = MutableStateFlow(LibraryViewMode.GRID)

    private val listings: Flow<Listings> =
        combine(selectedTab, selectedSort) { tab, sort -> tab to sort }
            .flatMapLatest { (tab, sort) ->
                when (tab) {
                    LibraryTab.ALBUMS ->
                        library.observeAlbumList(sort.kind, ALBUM_PAGE_SIZE)
                            .map { albums -> Listings(albums = albums) }

                    LibraryTab.ARTISTS ->
                        library.observeArtists().map { artists -> Listings(artists = artists) }

                    LibraryTab.SONGS ->
                        observeSongs(sort).map { songs -> Listings(songs = songs) }
                }
            }

    private val nowPlayingKey: Flow<TrackKey?> =
        playback.orElse(null)
            ?.observeState()
            ?.map { playbackState -> playbackState.currentItem?.track?.key }
            ?: flowOf(null)

    private val status: Flow<Status> = combine(
        library.observeLibraryStats(),
        session.observeConnectivity(),
        sync.observeSyncState(),
        nowPlayingKey,
    ) { stats, connectivity, syncState, playingKey ->
        Status(
            stats = stats,
            offline = !connectivity.isOnline,
            syncing = syncState.isSyncing,
            nowPlayingTrackKey = playingKey,
        )
    }

    val state: StateFlow<LibraryUiState> = combine(
        selectedTab,
        selectedSort,
        viewMode,
        listings,
        status,
    ) { tab, sort, mode, listing, current ->
        LibraryUiState(
            tab = tab,
            sort = sort,
            viewMode = mode,
            loading = false,
            stats = current.stats,
            albums = listing.albums,
            artists = listing.artists,
            songs = listing.songs,
            nowPlayingTrackKey = current.nowPlayingTrackKey,
            offline = current.offline,
            syncing = current.syncing,
            renderedAt = now(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = LibraryUiState(),
    )

    // ---- intents ------------------------------------------------------------

    fun onTabSelect(tab: LibraryTab) {
        selectedTab.value = tab
    }

    fun onSortSelect(sort: LibrarySort) {
        selectedSort.value = sort
    }

    fun onViewModeToggle() {
        viewMode.value = viewMode.value.toggled
    }

    /** Play an album from the grid's play affordance, from track one. */
    fun onAlbumPlay(mbid: ReleaseGroupMbid) {
        val controller: PlaybackController = playback.orElse(null) ?: return
        viewModelScope.launch { controller.playAlbum(mbid) }
    }

    /**
     * Play one song from the Songs tab.
     *
     * A track with no file behind it — a part-delivered pull — is not playable
     * and the row does not offer the tap, but the check is repeated here
     * because a ViewModel that trusts its screen to have filtered correctly is
     * one refactor away from a crash.
     */
    fun onSongPlay(track: Track) {
        if (!track.hasPlayableFile) return
        val controller: PlaybackController = playback.orElse(null) ?: return
        viewModelScope.launch { controller.playTracks(listOf(track)) }
    }

    /** The empty state's only action. Forces a delta sync. */
    fun onSyncNow() {
        viewModelScope.launch { sync.deltaSync(force = true) }
    }

    // ---- internals ----------------------------------------------------------

    /**
     * The Songs tab.
     *
     * **This is a stand-in.** `LibraryRepository` exposes no "every track in the
     * library" query — it has `observeAlbumTracks` and `observeTracksByGenre`
     * and nothing between them — so the songs list is assembled from the tracks
     * of the first [SONG_SOURCE_ALBUM_LIMIT] albums under the current sort.
     * That is correct as far as it goes and it is bounded, but it is not the
     * whole library, and a `observeTracks(kind, limit, offset)` on the
     * repository would replace this method with one line. It is in the handover
     * notes.
     */
    private fun observeSongs(sort: LibrarySort): Flow<List<Track>> =
        library.observeAlbumList(sort.kind, SONG_SOURCE_ALBUM_LIMIT)
            .flatMapLatest { albums ->
                if (albums.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        albums.map { album -> library.observeAlbumTracks(album.releaseGroupMbid) },
                    ) { perAlbum -> perAlbum.toList().flatten() }
                }
            }

    private fun now(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())

    private data class Listings(
        val albums: List<Album> = emptyList(),
        val artists: List<Artist> = emptyList(),
        val songs: List<Track> = emptyList(),
    )

    private data class Status(
        val stats: LibraryStats?,
        val offline: Boolean,
        val syncing: Boolean,
        val nowPlayingTrackKey: TrackKey?,
    )

    private companion object {
        /**
         * How many albums a tab holds at once.
         *
         * REQUIREMENTS.md's performance budget is "no dropped frames on a
         * 5,000-album grid", and a lazy grid handles that. What it does not
         * handle is holding five thousand `Album` objects in a StateFlow that
         * is recreated on every sort change, so the query is capped and paging
         * is the next thing this screen grows.
         */
        const val ALBUM_PAGE_SIZE: Int = 500

        /** How many albums the stand-in Songs tab draws its tracks from. */
        const val SONG_SOURCE_ALBUM_LIMIT: Int = 40

        /**
         * Keep the queries alive briefly after the last subscriber leaves, so
         * a rotation or a trip into album detail and back does not re-run every
         * Room query and re-lay-out the grid from scratch.
         */
        const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L
    }
}

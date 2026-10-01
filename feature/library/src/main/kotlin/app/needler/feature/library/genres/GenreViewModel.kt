package app.needler.feature.library.genres

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives one genre's song list.
 *
 * Read-only, like the genres list: the only actions are playback, and playback goes
 * through [PlaybackController] rather than through anything this module owns —
 * REQUIREMENTS.md "The player boundary" puts the one Media3 session behind that
 * interface, and a feature module has no Media3 dependency at all.
 *
 * Nothing is refreshed here. `observeTracksByGenre` is a mirror query over the
 * albums the library already knows about, so there is no per-genre fetch to make
 * and no failure to report.
 */
@HiltViewModel
class GenreViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: LibraryRepository,
    sessions: SessionRepository,
    private val playback: Optional<PlaybackController>,
) : ViewModel() {

    /**
     * The genre this screen is showing.
     *
     * A genre's identity is its name — `observeTracksByGenre` takes a name, and the
     * mirror holds names in the album's genre column. The argument arrives decoded
     * by the navigation library, so a genre containing a space or an ampersand
     * needs nothing done to it here; encoding it is the host's job when it builds
     * the route.
     */
    private val genre: String =
        requireNotNull(savedStateHandle.get<String>(GENRE_ARG)) {
            "GenreViewModel needs a '" + GENRE_ARG + "' navigation argument"
        }

    private val nowPlayingKey: Flow<TrackKey?> =
        playback.orElse(null)
            ?.observeState()
            ?.map { playbackState -> playbackState.currentItem?.track?.key }
            ?: flowOf(null)

    val state: StateFlow<GenreUiState> = combine(
        library.observeTracksByGenre(genre, limit = TRACK_LIMIT),
        nowPlayingKey,
        sessions.observeConnectivity(),
    ) { tracks, playingKey, connectivity ->
        GenreUiState(
            loading = false,
            genre = genre,
            tracks = tracks,
            nowPlayingTrackKey = playingKey,
            offline = !connectivity.isOnline,
            trackLimit = TRACK_LIMIT,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = GenreUiState(genre = genre),
    )

    /**
     * Plays the genre into the crate, in the order the list is drawn.
     *
     * Only the tracks with a file behind them go in. REQUIREMENTS.md "Partial
     * content is a normal state": a part-delivered pull leaves rows with nothing to
     * stream anywhere, and a crate holding them skips through gaps the listener
     * cannot account for.
     */
    fun onPlayAll() {
        val controller: PlaybackController = playback.orElse(null) ?: return
        val tracks: List<Track> = state.value.playableTracks
        if (tracks.isEmpty()) return
        viewModelScope.launch { controller.playTracks(tracks, startIndex = 0) }
    }

    /**
     * Shuffles the genre into the crate.
     *
     * The list is shuffled here rather than by switching the session's shuffle mode
     * on: `playTracks` takes no shuffle flag, and `setShuffleEnabled` is a mode that
     * would stay on for everything played afterwards. Album detail's Shuffle makes
     * the same promise — the record's own tracks, not a global mode — and this keeps
     * it the only way this interface allows.
     */
    fun onShuffleAll() {
        val controller: PlaybackController = playback.orElse(null) ?: return
        val tracks: List<Track> = state.value.playableTracks.shuffled()
        if (tracks.isEmpty()) return
        viewModelScope.launch { controller.playTracks(tracks, startIndex = 0) }
    }

    /**
     * Plays from one row.
     *
     * The index is the track's position among the playable tracks, not among all
     * rows: the crate is built from the playable ones, so an index into the drawn
     * list would start the wrong song in any genre with a hole in it.
     */
    fun onPlayTrack(track: Track) {
        val controller: PlaybackController = playback.orElse(null) ?: return
        val tracks: List<Track> = state.value.playableTracks
        val index: Int = tracks.indexOfFirst { it.key == track.key }
        if (index < 0) return
        viewModelScope.launch { controller.playTracks(tracks, startIndex = index) }
    }

    companion object {
        /** The navigation argument this ViewModel reads the genre name from. */
        const val GENRE_ARG: String = "genre"

        /**
         * How many tracks are asked for.
         *
         * `observeTracksByGenre` is bounded rather than paged, for the reason
         * `LibraryRepository.observeTracks` sets out: a flow holding every track of
         * a large library and rebuilt on every change would spend the whole of
         * REQUIREMENTS.md's scroll budget on allocation. 200 is the repository's own
         * default and is passed explicitly so that the screen and its "showing the
         * first 200" line cannot disagree about the number.
         */
        const val TRACK_LIMIT: Int = 200

        private const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L
    }
}

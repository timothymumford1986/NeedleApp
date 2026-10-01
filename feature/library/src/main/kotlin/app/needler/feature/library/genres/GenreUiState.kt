package app.needler.feature.library.genres

import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.feature.library.common.LibraryFormat
import app.needler.feature.library.common.hasPlayableFile

/**
 * Everything one genre's song list renders.
 *
 * REQUIREMENTS.md "Library browse" sources this from `getSongsByGenre`. In this
 * app that is `LibraryRepository.observeTracksByGenre`, which serves it from the
 * mirror, so the screen reads identically online and offline and never waits on a
 * network call to draw.
 *
 * ## The order is the server's shelf, not the alphabet
 *
 * The query returns tracks ordered by album artist, then album title, then disc and
 * track number — so a genre reads as a row of records in running order rather than
 * an alphabetical jumble of song titles. That is the same decision
 * `LibraryRepository.observeTracks` records for "sorted by artist" on the Songs
 * tab: "an artist's records arrive whole and in running order, not their songs
 * interleaved alphabetically". This screen offers no sort control at all, because
 * every other order it could offer would need a query that does not exist.
 *
 * @property atLimit true when as many tracks came back as were asked for, so there
 *   may be more. The screen says so rather than pretending the list is the whole
 *   genre; see [trackLimit].
 * @property trackLimit how many tracks were asked for. `observeTracksByGenre` is
 *   "bounded rather than paged" — a cap, not a cursor — and a genre on a large
 *   library can exceed it.
 */
data class GenreUiState(

    /** True until the mirror has answered once. */
    val loading: Boolean = true,

    /** The genre being shown. Carried in state because it is also the screen's title. */
    val genre: String = "",

    val tracks: List<Track> = emptyList(),

    /** The track the player is on, so its row can mark itself. */
    val nowPlayingTrackKey: TrackKey? = null,

    val offline: Boolean = false,

    val trackLimit: Int = 0,
) {

    val atLimit: Boolean get() = trackLimit > 0 && tracks.size >= trackLimit

    /** The mirror answered and this genre has nothing in it. */
    val showEmptyState: Boolean get() = !loading && tracks.isEmpty()

    /** Tracks with a file behind them, in order. The only ones that can go in the crate. */
    val playableTracks: List<Track> get() = tracks.filter { it.hasPlayableFile }

    val hasPlayableTracks: Boolean get() = playableTracks.isNotEmpty()

    /**
     * `42 tracks · 2 hr 51 min`.
     *
     * The running time is summed from the rows, and only when every row has a
     * duration: a sum over half of them is a wrong number wearing the confidence
     * of a right one. Unknown draws nothing rather than zero.
     */
    val headerLine: String
        get() {
            val parts: List<String> = buildList {
                add(LibraryFormat.plural(tracks.size.toLong(), "track"))
                LibraryFormat.runningTime(summedDurationMs())?.let { add(it) }
            }
            return parts.joinToString(separator = " · ")
        }

    private fun summedDurationMs(): Long? {
        if (tracks.isEmpty()) return null
        val durations: List<Long> = tracks.mapNotNull { it.durationMs }
        return if (durations.size == tracks.size) durations.sum() else null
    }
}

package app.needler.feature.player

import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.playback.PlaybackState
import app.needler.core.domain.playback.RepeatMode
import app.needler.feature.player.ui.PlayerFormat

/**
 * Everything the transport surfaces draw, and nothing that ticks.
 *
 * This is [PlaybackState] with the strings already made: the same fields, plus the title, subtitle
 * and format badge the pack prints, so the composables hold no formatting logic and the formatting
 * can be tested without rendering anything.
 *
 * **Position is not here, deliberately.** `PlaybackController` splits the ticking half of playback
 * into its own flow precisely so that a screen bound to this one does not recompose several times a
 * second for an entire album, and folding it back in "for tidiness" would undo that. The scrubber
 * and the two time labels take [app.needler.core.domain.playback.PlaybackProgress] directly, as a
 * deferred read, and they are the only things that do.
 *
 * The queue is absent for the mirror-image reason: the crate is a long list that changes rarely, and
 * a queue edit must not re-run the transport's layout. It has its own state, [CrateUiState].
 */
data class PlayerUiState(
    /** The crate row that is playing or paused, or null when nothing is loaded. */
    val item: QueueItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Length of the current item, or null while unknown. The scrubber needs it; the tick does not. */
    val durationMs: Long? = null,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** Where the session is actually routing sound, which is not always where the user pointed it. */
    val output: OutputTarget? = null,
    /** The last playback failure, shown as a line above the transport until a command succeeds. */
    val error: NeedlerError? = null,
    /** How many rows follow the playing one. Drawn as a count only, so it costs no list diff. */
    val upNextCount: Int = 0,
) {
    /** True when there is something to draw a title and a transport for. */
    val hasTrack: Boolean get() = item != null

    /** The big line: the track title, or the pack's empty-state heading. */
    val title: String get() = item?.track?.title ?: "Nothing playing"

    /** The small line: `The Marias - Submarine`, or what to do about it. */
    val subtitle: String
        get() = if (item == null) "Play an album and it lands in the crate" else PlayerFormat.artistAndAlbum(item)

    /** The quality badge beside the title, or null when the server reported no format. */
    val formatBadge: String? get() = PlayerFormat.formatBadge(quality)

    /** The current item's audio quality, or null when nothing is loaded. */
    val quality: AudioQuality? get() = item?.track?.quality

    /** Where sound is going, named plainly, whether or not anything is playing. */
    val outputName: String get() = PlayerFormat.outputName(output)

    /** The failure line, or null. */
    val errorMessage: String? get() = error?.let(PlayerFormat::errorMessage)

    /**
     * How the whole player reads aloud, for surfaces that merge into one node - the mini player.
     */
    val spokenSummary: String
        get() = if (item == null) {
            "Nothing playing"
        } else {
            title + ", " + subtitle + ", " + (if (isPlaying) "playing" else "paused") +
                ", on " + outputName
        }

    companion object {
        /** Nothing loaded: what every player surface draws before the session connects. */
        val Idle: PlayerUiState = PlayerUiState()

        /**
         * Folds a [PlaybackState] and the crate's size into the UI's own shape.
         *
         * [upNextCount] comes from the queue flow rather than from the state, so it is passed in:
         * the two arrive separately and a transport update must not wait on a queue update.
         */
        fun from(state: PlaybackState, upNextCount: Int = 0): PlayerUiState = PlayerUiState(
            item = state.currentItem,
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            durationMs = state.durationMs,
            shuffleEnabled = state.shuffleEnabled,
            repeatMode = state.repeatMode,
            output = state.output,
            error = state.error,
            upNextCount = upNextCount,
        )
    }
}

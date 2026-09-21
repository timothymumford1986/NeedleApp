package app.needler.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.playback.PlaybackProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The transport, shared by Now Playing, the mini player and the tablet sidebar.
 *
 * All three draw the same thing at three sizes, so there is one view model behind them rather than
 * three that would drift. It holds no playback of its own: every command is one call on
 * [PlaybackController], which is the session's only client-facing surface, and every failure comes
 * back on [PlayerUiState.error] rather than as a return value - because the session is shared, and a
 * track that will not play has to be visible to the lock screen and Wear too, not only to whichever
 * client pressed play.
 *
 * ## Two flows, kept apart
 *
 * [state] changes when a person changed something. [progress] ticks several times a second. They are
 * separate here because they are separate on the controller, and for the same reason: a screen that
 * merged them would relayout artwork, titles and the transport on every tick, for an entire album.
 * The screens read [progress] through a lambda so the read is deferred into the scrubber itself.
 *
 * The crate's *size* does reach [state], because the sidebar prints "4 up next" beside the crate
 * heading. That costs nothing: it is an `Int` inside a data class, so a queue edit that does not
 * change the count produces an equal state and `StateFlow` drops it.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlaybackController,
) : ViewModel() {

    val state: StateFlow<PlayerUiState> =
        combine(controller.observeState(), controller.observeQueue()) { playback, queue ->
            PlayerUiState.from(playback, upNextCount = queue.upNext.size)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = PlayerUiState.Idle,
        )

    val progress: StateFlow<PlaybackProgress> = controller.observeProgress().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = PlaybackProgress.Zero,
    )

    fun playPause() {
        viewModelScope.launch { controller.playPause() }
    }

    fun skipToNext() {
        viewModelScope.launch { controller.skipToNext() }
    }

    /**
     * Skips backwards.
     *
     * The "past a few seconds in, restart the track instead" rule lives in the implementation on
     * purpose, and must not be second-guessed here: two surfaces that each had their own threshold
     * would disagree about what the same button does.
     */
    fun skipToPrevious() {
        viewModelScope.launch { controller.skipToPrevious() }
    }

    /**
     * Seeks to a fraction of the current track.
     *
     * The scrubber works in fractions because that is what it draws and what TalkBack's
     * `setProgress` action hands it; the controller works in milliseconds. A track of unknown length
     * cannot be seeked in fractions at all, so the command is dropped rather than sent as a guess.
     */
    fun seekToFraction(fraction: Float) {
        val duration: Long = state.value.durationMs ?: return
        if (duration <= 0L) return
        val position: Long = (duration * fraction.coerceIn(0f, 1f)).toLong()
        viewModelScope.launch { controller.seekTo(position) }
    }

    fun toggleShuffle() {
        val next: Boolean = !state.value.shuffleEnabled
        viewModelScope.launch { controller.setShuffleEnabled(next) }
    }

    /** Off, all, one, off - the order [app.needler.core.domain.playback.RepeatMode.next] defines. */
    fun cycleRepeat() {
        val next = state.value.repeatMode.next
        viewModelScope.launch { controller.setRepeatMode(next) }
    }

    private companion object {
        /**
         * How long the flows stay collected after the last subscriber goes.
         *
         * Long enough to survive a rotation or a trip through the crate screen without re-subscribing
         * to the session, short enough that a backgrounded app stops ticking.
         */
        const val STOP_TIMEOUT_MS: Long = 5_000L
    }
}

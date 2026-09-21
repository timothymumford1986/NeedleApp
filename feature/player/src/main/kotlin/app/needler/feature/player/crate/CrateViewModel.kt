package app.needler.feature.player.crate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The crate screen, driven by the session's own queue.
 *
 * It binds to [PlaybackController.observeQueue] and not to the persisted copy:
 * `PlaybackSettingsRepository` owns a restore point, the controller owns the live queue, and the
 * live one is the only one that describes what is playing. Every edit goes back through the
 * controller for the same reason - two writers of one queue is a race whose visible symptom is the
 * crate rearranging itself under the user's finger.
 *
 * ## Why a move is applied locally as well as sent
 *
 * A drag has to look instant. Waiting for the session to echo the move back means the row springs
 * back to where it was and then jumps, on every drag, on the slowest frame of the gesture. So the
 * move is applied here with [PlayQueue.withItemMoved] - the domain's own helper, which is what makes
 * the current index follow the *item* rather than the slot, so dragging a row past the Playing row
 * cannot change what is playing - and the command is sent at the same moment.
 *
 * The optimistic copy is then given up the instant it is no longer the truth. [CrateReconciler]
 * decides that: the echo landing, or the crate changing underneath, both drop it.
 */
@HiltViewModel
class CrateViewModel @Inject constructor(
    private val controller: PlaybackController,
) : ViewModel() {

    /** The locally-applied reorder, held only until the session confirms or contradicts it. */
    private val optimisticQueue = MutableStateFlow<PlayQueue?>(null)

    private val liveQueue: Flow<PlayQueue> = controller.observeQueue()
        .onEach { upstream -> optimisticQueue.update { CrateReconciler.keep(it, upstream) } }

    private val isPlaying: Flow<Boolean> = controller.observeState()
        .map { it.isPlaying }
        .distinctUntilChanged()

    val state: StateFlow<CrateUiState> =
        combine(liveQueue, isPlaying, optimisticQueue) { upstream, playing, pending ->
            CrateUiState(queue = pending ?: upstream, isPlaying = playing)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = CrateUiState.Empty,
        )

    /**
     * One drag, or one press of the "Move up in the crate" accessibility action.
     *
     * Indices are positions in [PlayQueue.items], as [PlaybackController.moveQueueItem] specifies.
     * An out-of-range index is dropped rather than clamped: a drag and a skip can land in either
     * order, and guessing what a stale index meant is worse than ignoring it.
     */
    fun moveItem(fromIndex: Int, toIndex: Int) {
        val current: PlayQueue = state.value.queue
        val moved: PlayQueue = current.withItemMoved(fromIndex, toIndex)
        if (moved == current) return
        optimisticQueue.value = moved
        viewModelScope.launch { controller.moveQueueItem(fromIndex, toIndex) }
    }

    /** Tapping a row: jump to it by id, never by index, because a skip may have moved it. */
    fun skipTo(itemId: String) {
        viewModelScope.launch { controller.skipToQueueItem(itemId) }
    }

    /**
     * Clear.
     *
     * Stops playback, because nothing is left to play. The optimistic copy is dropped first so the
     * screen cannot briefly redraw a crate that has just been emptied.
     */
    fun clear() {
        optimisticQueue.value = null
        viewModelScope.launch { controller.clearQueue() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS: Long = 5_000L
    }
}

/**
 * Decides how long an optimistically-applied reorder is still the truth.
 *
 * Pulled out of the view model because it is the whole correctness of drag-to-reorder and is worth
 * asserting directly. Three cases:
 *
 *  - **the echo landed** - the session's queue now has the same rows in the same order, so the local
 *    copy has nothing left to say and is dropped;
 *  - **the crate changed** - rows were added or removed by something else (a "Play next" from
 *    another screen, a track finishing and being consumed), so the local copy is describing a queue
 *    that no longer exists and is dropped;
 *  - **neither yet** - the same rows in a different order, which is the session simply not having
 *    caught up. The local copy is kept, and the row stays where the finger left it.
 */
internal object CrateReconciler {

    fun keep(pending: PlayQueue?, upstream: PlayQueue): PlayQueue? {
        if (pending == null) return null
        val pendingIds: List<String> = pending.items.map { it.id }
        val upstreamIds: List<String> = upstream.items.map { it.id }
        if (pendingIds == upstreamIds) return null
        if (pendingIds.toSet() != upstreamIds.toSet()) return null
        return pending
    }
}

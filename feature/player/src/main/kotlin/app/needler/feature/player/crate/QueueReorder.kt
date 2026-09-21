package app.needler.feature.player.crate

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * Drag-to-reorder for the crate, over a [LazyListState].
 *
 * The gesture is deliberately small and owned by this feature rather than by `:core:design`:
 * `NeedlerQueueRow` draws the handle and supplies the *accessible* move actions, and says so - "the
 * feature module owns the drag gesture".
 *
 * ## How it works
 *
 * A drag moves nothing by itself. It tracks how far the finger has travelled, works out which row
 * the dragged row's centre is now over, and asks for a move the moment that changes - so the list
 * reorders continuously under the finger, one row at a time, exactly as the user watches it happen.
 * After each move the anchor is re-based onto the row's new slot so the row stays under the finger
 * instead of leaping by its own height.
 *
 * Rows are addressed by **key**, never by index. Indices belong to the queue and a skip can change
 * them mid-gesture; the key is the row identity, which is the whole reason
 * [app.needler.core.domain.model.QueueItem.id] exists as something separate from the track.
 *
 * Only keys that [isDraggable] accepts take part, so the section headings the crate screen puts in
 * the same list are never a drop target.
 */
class QueueReorderState internal constructor(
    private val listState: LazyListState,
    private val isDraggable: (Any?) -> Boolean,
    private val onMove: (fromKey: String, toKey: String) -> Unit,
) {

    /** The row currently under the finger, or null. */
    var draggingKey: String? by mutableStateOf(null)
        private set

    /** How far that row is drawn from its laid-out position, in pixels. */
    var draggedDistance: Float by mutableStateOf(0f)
        private set

    /** Where the dragged row's slot starts, re-based every time a move is applied. */
    private var anchorOffset: Int = 0
    private var anchorSize: Int = 0

    fun onDragStart(key: String) {
        val info: LazyListItemInfo = visible(key) ?: return
        draggingKey = key
        anchorOffset = info.offset
        anchorSize = info.size
        draggedDistance = 0f
    }

    fun onDrag(delta: Float) {
        val key: String = draggingKey ?: return
        draggedDistance += delta

        val top: Float = anchorOffset + draggedDistance
        val centre: Float = top + anchorSize / 2f
        val target: LazyListItemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != key &&
                isDraggable(item.key) &&
                centre >= item.offset &&
                centre <= item.offset + item.size
        } ?: return

        val targetKey: String = target.key as? String ?: return
        onMove(key, targetKey)

        // The row is about to be laid out where the target was. Re-anchor onto that slot and keep
        // the drawn offset pointing at the same pixel, so the row does not jump out from under the
        // finger on the frame the move lands.
        anchorOffset = target.offset
        draggedDistance = top - target.offset
    }

    fun onDragEnd() {
        draggingKey = null
        draggedDistance = 0f
    }

    /** How far to draw the row with this key from where it was laid out. */
    fun offsetFor(key: String): Float = if (key == draggingKey) draggedDistance else 0f

    private fun visible(key: String): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
}

/**
 * Remembers a [QueueReorderState] for [listState].
 *
 * The two callbacks are read through [rememberUpdatedState], so a gesture that started three
 * recompositions ago still resolves keys against the crate as it is now. Capturing them in the
 * `remember` block instead would pin the first composition's view of the queue for the life of the
 * screen, and a move applied against a stale queue is the reorder bug wearing a different hat.
 *
 * @param isDraggable which list keys are rows rather than headings.
 * @param onMove called with the key being dragged and the key it has been dragged onto, as often as
 *   that pair changes during one gesture.
 */
@Composable
fun rememberQueueReorderState(
    listState: LazyListState,
    isDraggable: (Any?) -> Boolean,
    onMove: (fromKey: String, toKey: String) -> Unit,
): QueueReorderState {
    val currentIsDraggable by rememberUpdatedState(isDraggable)
    val currentOnMove by rememberUpdatedState(onMove)
    return remember(listState) {
        QueueReorderState(
            listState = listState,
            isDraggable = { key -> currentIsDraggable(key) },
            onMove = { from, to -> currentOnMove(from, to) },
        )
    }
}

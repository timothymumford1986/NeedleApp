package app.needler.feature.player.crate

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.needler.core.design.component.NeedlerQueueRow
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.QueueItem
import app.needler.feature.player.ui.PlayerArtwork
import app.needler.feature.player.ui.PlayerFormat

/** One row of the crate, with the drag gesture and the lift the pack gives a dragged card. */
@Composable
internal fun CrateRow(
    item: QueueItem,
    isPlaying: Boolean,
    reorder: QueueReorderState,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val key: String = ROW_KEY_PREFIX + item.id
    val dragging: Boolean = reorder.draggingKey == key
    val offset: Float = reorder.offsetFor(key)

    NeedlerQueueRow(
        title = item.track.title,
        subtitle = PlayerFormat.artistAndAlbum(item),
        isPlaying = isPlaying,
        onClick = onClick,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        artwork = {
            PlayerArtwork(
                artwork = item.track.artwork,
                albumTitle = item.track.albumTitle,
                artistName = item.track.artistName,
                modifier = Modifier.size(NeedlerTheme.sizes.artworkThumb),
                describe = false,
            )
        },
        modifier = Modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = offset }
            .shadow(elevation = if (dragging) 8.dp else 0.dp)
            .pointerInput(key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { reorder.onDragStart(key) },
                    onDragEnd = { reorder.onDragEnd() },
                    onDragCancel = { reorder.onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        reorder.onDrag(dragAmount.y)
                    },
                )
            },
    )
}

/**
 * What a crate row's list key looks like.
 *
 * Prefixed so that the section headings, which share the same lazy list, can never be mistaken for a
 * drop target: [QueueReorderState] is told which keys are rows, and this prefix is how it is told.
 */
internal const val ROW_KEY_PREFIX: String = "crate-row:"

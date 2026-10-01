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
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerNowPlayingIcon
import app.needler.core.design.component.NeedlerQueueRow
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathClose
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.QueueItem
import app.needler.feature.player.ui.PlayerArtwork
import app.needler.feature.player.ui.PlayerFormat

/**
 * One row of the crate, with the drag gesture and the lift the pack gives a dragged card.
 *
 * @param onRemove drops this one row from the crate, or null where removal is not offered.
 *
 *   The crate had drag-to-reorder and a Clear action and no way at all to drop a single track, which
 *   left "I do not want to hear this next" as a choice between dragging it to the end and emptying the
 *   queue. REQUIREMENTS.md describes the crate as an editable list with a Clear action, not as a list
 *   whose only edit is its total destruction, and `PlayQueue.withItemRemoved` and
 *   `PlaybackController.removeQueueItem` were both already written and reasoned about for this - down to
 *   what removing the *playing* row means.
 *
 *   It is a real button rather than a swipe and rather than a custom accessibility action. REQUIREMENTS.md
 *   asks that "TalkBack must reach the crate's reordering through an accessible action, not only by
 *   dragging", and the same standard applies to removal - but a button clears that bar more plainly than a
 *   custom action does: it is focusable, it is labelled with the track it removes, and it is 48 dp.
 *   `NeedlerIconButton` is documented as staying separately reachable inside a row that merges its own
 *   descendants, which is exactly this case.
 */
@Composable
internal fun CrateRow(
    item: QueueItem,
    isPlaying: Boolean,
    reorder: QueueReorderState,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemove: (() -> Unit)? = null,
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
        trailing = if (onRemove == null) {
            null
        } else {
            {
                // NeedlerQueueRow draws the playing glyph itself only when nothing is in its trailing
                // slot, so the playing row keeps its record here rather than losing it to the button.
                if (isPlaying) {
                    NeedlerNowPlayingIcon(tint = NeedlerTheme.colors.accent)
                }
                NeedlerIconButton(
                    contentDescription = "Remove " + item.track.title + " from the crate",
                    onClick = onRemove,
                    visualSize = 40.dp,
                ) {
                    NeedlerStrokeIcon(
                        pathData = PathClose,
                        tint = NeedlerTheme.colors.textSecondary,
                        size = 18.dp,
                    )
                }
            }
        },
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

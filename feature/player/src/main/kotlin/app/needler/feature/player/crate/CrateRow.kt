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
import app.needler.core.design.component.NeedlerAlbumBadge
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerNowPlayingIcon
import app.needler.core.design.component.NeedlerQueueRow
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathClose
import app.needler.core.design.component.label
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.QueueItem
import app.needler.feature.player.ui.PlayerArtwork
import app.needler.feature.player.ui.PlayerFormat

/**
 * One row of the crate, with the drag gesture and the lift the pack gives a dragged card.
 *
 * @param canReorder whether this row can move at all. False for the Playing row, and for a lone Up
 *   next row that has nowhere to go.
 *
 *   It gates the handle and the gesture together, which is the point: a row that draws a handle it
 *   will not respond to is worse than a row with no handle, because the handle is the app's own
 *   promise that the row moves. Before this, the Playing row drew a handle and accepted a drag while
 *   offering neither "Move up in the crate" nor "Move down in the crate" - so the gesture could do
 *   something no TalkBack user could ask for, which is the inverse of what REQUIREMENTS.md
 *   "Accessibility" requires: "TalkBack must reach the crate's reordering through an accessible
 *   action, not only by dragging." [CrateScreen] carries the rule the two routes now share.
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
 *
 * @param subtitle the second line. Defaults to the artist and the album, which is what the pack draws
 *   and what a mixed crate wants; a crate that is one record passes
 *   [CrateUiState.rowSubtitle] instead, which prints the track's place in it. The caller decides,
 *   because the decision is a property of the whole crate and not of one row.
 * @param showArtwork false for a one-record crate, where eleven copies of one sleeve say what the sleeve
 *   above the crate already says. `NeedlerQueueRow`'s artwork slot is nullable, so the row closes up
 *   around it rather than drawing a gap.
 * @param retrieval where this row's record is - **Device** or **Server** - or null for a caller that
 *   cannot tell.
 *
 *   REQUIREMENTS.md "Vocabulary" makes the three state words "the words every surface draws", and the
 *   crate drew none of them: no marker and no quality tag on any queued row, on the one screen where a
 *   listener is deciding what they will still be able to hear with no connection.
 *
 *   ## Why it is a word on the second line and not a `NeedlerStateBadge`
 *
 *   It was a badge in the trailing column, where every other list in the app puts a state, and that
 *   column has no room for one here. `NeedlerQueueRow`'s trailing slot already holds the playing glyph
 *   and a 48 dp remove button, and the badge is another 60-odd dp of glyph and label - measured, it
 *   squeezed the remove button to **0 dp wide** in a 320 dp window, which `CrateRemoveTest` caught and
 *   which 200% text would have made worse on any width. `NeedlerAlbumRow` caps and re-stacks its
 *   trailing block for exactly this; `NeedlerQueueRow` does not, and this module does not own it.
 *
 *   So the word joins the line that is already there. It costs no width, it survives 200% text, and
 *   `NeedlerQueueRow` builds its content description as `"$title, $subtitle"` - so the state is spoken
 *   wherever it is drawn, with no second reading to keep in step.
 *
 *   What is given up is the hue, and REQUIREMENTS.md "Accessibility" is explicit that this is the
 *   cheaper half: accent and positive measure **1.01:1 against each other**, so "the hue is never the
 *   signal... it only makes the distinction quicker for the readers who can see it". The word carries
 *   the information either way. A hued badge here needs a capped trailing slot on `NeedlerQueueRow`,
 *   which is `:core:design`'s to add.
 */
@Composable
internal fun CrateRow(
    item: QueueItem,
    isPlaying: Boolean,
    reorder: QueueReorderState,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    canReorder: Boolean,
    onRemove: (() -> Unit)? = null,
    subtitle: String = PlayerFormat.artistAndAlbum(item),
    showArtwork: Boolean = true,
    retrieval: NeedlerAlbumBadge? = null,
) {
    val key: String = ROW_KEY_PREFIX + item.id
    val dragging: Boolean = reorder.draggingKey == key
    val offset: Float = reorder.offsetFor(key)
    val line: String = if (retrieval == null) {
        subtitle
    } else {
        subtitle + PlayerFormat.DOT + retrieval.label()
    }

    NeedlerQueueRow(
        title = item.track.title,
        subtitle = line,
        isPlaying = isPlaying,
        showHandle = canReorder,
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
        artwork = if (!showArtwork) {
            null
        } else {
            {
                PlayerArtwork(
                    artwork = item.track.artwork,
                    identity = item.track.releaseGroupMbid.value,
                    albumTitle = item.track.albumTitle,
                    artistName = item.track.artistName,
                    modifier = Modifier.size(NeedlerTheme.sizes.artworkThumb),
                    describe = false,
                )
            }
        },
        modifier = Modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = offset }
            .shadow(elevation = if (dragging) 8.dp else 0.dp)
            .then(
                if (!canReorder) {
                    Modifier
                } else {
                    Modifier.pointerInput(key) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { reorder.onDragStart(key) },
                            onDragEnd = { reorder.onDragEnd() },
                            onDragCancel = { reorder.onDragEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                reorder.onDrag(dragAmount.y)
                            },
                        )
                    }
                },
            ),
    )
}

/**
 * What a crate row's list key looks like.
 *
 * Prefixed so that the section headings, which share the same lazy list, can never be mistaken for a
 * drop target: [QueueReorderState] is told which keys are rows, and this prefix is how it is told.
 */
internal const val ROW_KEY_PREFIX: String = "crate-row:"

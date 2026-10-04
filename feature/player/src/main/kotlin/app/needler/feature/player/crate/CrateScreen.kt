package app.needler.feature.player.crate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.NeedlerTextButton
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.QueueItem

/**
 * The crate, screen 08 - "in the crate", which is what this product calls the play queue.
 *
 * A Playing row, an Up next list with its count and total length, a drag handle on every row that
 * can move, and Clear in the header.
 *
 * ## Reordering, twice over
 *
 * The handle drags, after a long press anywhere on the row. That is the gesture everybody else gets.
 * REQUIREMENTS.md also asks that "TalkBack must reach the crate's reordering through an accessible
 * action, not only by dragging", and `NeedlerQueueRow` provides exactly that: supplying `onMoveUp`
 * and `onMoveDown` puts "Move up in the crate" and "Move down in the crate" in TalkBack's local
 * context menu. Both routes call the same view-model method, so there is one implementation of what
 * a move means and no second code path to get wrong.
 *
 * Both express the move as indices into the whole queue, never as positions within Up next, because
 * that is what `PlaybackController.moveQueueItem` takes and what
 * `PlayQueue.withItemMoved` reasons about.
 *
 * ## Up next reorders. The Playing row is an anchor
 *
 * **The decided rule: a row moves only within Up next. The Playing row never moves, and nothing can
 * be moved above it.** It is enforced in one place - `upNextKeys` below is what `isDraggable` and
 * `CrateRow`'s own `canReorder` are both derived from - so the drag and the accessible actions have
 * exactly the same reach. Neither route can express a move the other cannot, which is what
 * REQUIREMENTS.md "Accessibility" is asking for when it says the reordering must be reachable "not
 * only by dragging": an accessible action that covers less than the gesture is still a gesture-only
 * feature for the part it does not cover.
 *
 * The rule is **not** about playback safety. `PlayQueue.withItemMoved` already guarantees that
 * `currentIndex` follows the playing item rather than the slot, so a move past the Playing row
 * cannot change what is playing, and `Media3PlaybackController.moveQueueItem` would carry any move
 * the screen sent. The rule is about what this screen can honestly draw. It has two sections,
 * Playing and Up next, and `PlayQueue.upNext` is everything *after* `currentIndex` - so a row moved
 * above the playing one is in neither section and is drawn nowhere at all. Before this, a drag could
 * do that: `isDraggable` accepted every row key including the Playing row's, so a track dragged to
 * the top left the screen entirely while remaining in the session's queue. It had not vanished, it
 * had become unreachable, which is the worse of the two readings.
 *
 * Moving the Playing row itself is refused for the mirror image of the same reason: it would push
 * rows above `currentIndex`, turning tracks the user queued into the same invisible prefix.
 *
 * **The rejected alternative** is to allow both and draw a third section - "Earlier", or a played
 * history - above Playing. It is what would make an unrestricted drag honest, and it is a change to
 * the product rather than to this file: the design pack's screen 08 draws two sections, and
 * REQUIREMENTS.md "Queue" describes the crate as the Playing row and Up next with a count and a
 * total length. Adding a section to the crate so that one gesture has somewhere to put its result is
 * the wrong way round.
 *
 * A single Up next row is not reorderable either, by the same consistency rule: it has nowhere to
 * go, so it is given no handle, no gesture and no move actions rather than a handle that does
 * nothing.
 *
 * ## Removing one track
 *
 * Every row carries a remove button, the Playing row included. Before it, the only edits the crate
 * offered were a reorder and Clear, so dropping one track meant dragging it to the bottom or emptying the
 * whole queue. Removal is addressed by [QueueItem.id] and never by index - the same track may legitimately
 * appear twice, and a skip can move a row between the tap and the command - which is what
 * `PlayQueue.withItemRemoved` and `PlaybackController.removeQueueItem` both take.
 *
 * Removing the Playing row is allowed, because `PlayQueue.withItemRemoved` already defines what it means:
 * the row that followed becomes the current one, and removing the last row while it plays stops the
 * session rather than wrapping round to the top.
 *
 * ## Empty
 *
 * The crate spends most of its life empty, so that is drawn rather than left blank, and Clear
 * disappears along with the thing it would clear.
 */
@Composable
fun CrateScreen(
    state: CrateUiState,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onPlayItem: (String) -> Unit,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState: LazyListState = rememberLazyListState()
    val upNext: List<QueueItem> = state.upNext

    // The one definition of "this row can move", read by the drag gesture and by every row's own
    // handle. See "Up next reorders. The Playing row is an anchor" above for why it is Up next only
    // and why the two routes must not be allowed to drift apart.
    val canReorder: Boolean = upNext.size > 1
    val upNextKeys: Set<String> = remember(upNext) {
        upNext.mapTo(HashSet(upNext.size)) { item -> ROW_KEY_PREFIX + item.id }
    }

    val reorder: QueueReorderState = rememberQueueReorderState(
        listState = listState,
        isDraggable = { key -> canReorder && key is String && key in upNextKeys },
        onMove = { fromKey, toKey ->
            val from: Int = state.queue.items.indexOfFirst { ROW_KEY_PREFIX + it.id == fromKey }
            val to: Int = state.queue.items.indexOfFirst { ROW_KEY_PREFIX + it.id == toKey }
            if (from >= 0 && to >= 0) onMove(from, to)
        },
    )

    Column(modifier = modifier.fillMaxSize()) {
        CrateHeader(onBack = onBack, onClear = onClear, canClear = !state.isEmpty)

        if (state.isEmpty) {
            EmptyCrate(modifier = Modifier.weight(1f))
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            val playing: QueueItem? = state.playing
            if (playing != null) {
                item(key = "heading-playing") {
                    CrateHeading(title = "Playing", modifier = Modifier.padding(bottom = 6.dp))
                }
                item(key = ROW_KEY_PREFIX + playing.id) {
                    CrateRow(
                        item = playing,
                        isPlaying = true,
                        reorder = reorder,
                        onClick = { onPlayItem(playing.id) },
                        onMoveUp = null,
                        onMoveDown = null,
                        canReorder = false,
                        onRemove = { onRemove(playing.id) },
                        subtitle = state.rowSubtitle(playing),
                        showArtwork = state.showsRowArtwork,
                    )
                }
            }

            item(key = "heading-upnext") {
                CrateHeading(
                    title = "Up next",
                    trailing = state.upNextSummary,
                    trailingSpoken = state.spokenUpNextSummary,
                    modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
                )
            }

            itemsIndexed(items = upNext, key = { _, item -> ROW_KEY_PREFIX + item.id }) { position, item ->
                val queueIndex: Int = state.queueIndexOfUpNext(position)
                CrateRow(
                    item = item,
                    isPlaying = false,
                    reorder = reorder,
                    onClick = { onPlayItem(item.id) },
                    // `position > 0` is what keeps a move inside Up next: the first Up next row is
                    // already as high as the rule lets it go, and moving it up would put it above
                    // the Playing row where nothing is drawn.
                    onMoveUp = if (position > 0) {
                        { onMove(queueIndex, queueIndex - 1) }
                    } else {
                        null
                    },
                    onMoveDown = if (position < upNext.lastIndex) {
                        { onMove(queueIndex, queueIndex + 1) }
                    } else {
                        null
                    },
                    canReorder = canReorder,
                    onRemove = { onRemove(item.id) },
                    subtitle = state.rowSubtitle(item),
                    showArtwork = state.showsRowArtwork,
                )
            }
        }
    }
}

/**
 * A section heading with its summary on the right: `UP NEXT` and `6 tracks - 21 min`.
 *
 * Not `NeedlerSectionHeader`, because the pack aligns these two on their baselines and speaks the
 * summary differently from how it prints it - "6 tracks, 21 minutes" rather than a middle dot a
 * screen reader has no word for.
 */
@Composable
private fun CrateHeading(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    trailingSpoken: String? = null,
) {
    val colors = NeedlerTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title.uppercase(),
            style = NeedlerTheme.typography.sectionHeader,
            color = colors.textSecondary,
            modifier = Modifier.semantics { heading() },
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = NeedlerTheme.typography.meta,
                color = colors.textMuted,
                modifier = Modifier.semantics {
                    if (trailingSpoken != null) contentDescription = trailingSpoken
                },
            )
        }
    }
}

@Composable
private fun CrateHeader(onBack: () -> Unit, onClear: () -> Unit, canClear: Boolean) {
    val colors = NeedlerTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(start = 12.dp, end = 12.dp, top = 52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerIconButton(
            contentDescription = "Back",
            onClick = onBack,
            visualSize = 44.dp,
        ) {
            NeedlerStrokeIcon(PathChevronLeft, tint = colors.textPrimary, size = 24.dp)
        }
        Text(
            text = "In the crate".uppercase(),
            style = NeedlerTheme.typography.sectionHeader,
            color = colors.textSecondary,
            modifier = Modifier.semantics { heading() },
        )
        if (canClear) {
            NeedlerTextButton(
                text = "Clear",
                onClick = onClear,
                color = colors.textSecondary,
                contentDescription = "Clear the crate",
            )
        } else {
            // Holds the header's balance when there is nothing to clear, so the title does not
            // slide off centre the moment the crate empties.
            Box(modifier = Modifier.size(NeedlerTheme.sizes.minTouchTarget))
        }
    }
}

@Composable
private fun EmptyCrate(modifier: Modifier = Modifier) {
    val colors = NeedlerTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "The crate is empty",
            style = NeedlerTheme.typography.albumTitle,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Play an album and it lands here.",
            style = NeedlerTheme.typography.meta,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

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
 * A Playing row, an Up next list with its count and total length, a drag handle on every row and
 * Clear in the header.
 *
 * ## Reordering, twice over
 *
 * The handle drags, after a long press anywhere on the row. That is the gesture everybody else gets.
 * REQUIREMENTS.md also asks that "TalkBack must reach the crate's reordering through an accessible
 * action, not only by dragging", and `NeedlerQueueRow` provides exactly that: supplying [onMoveUp]
 * and [onMoveDown] puts "Move up in the crate" and "Move down in the crate" in TalkBack's local
 * context menu. Both routes call the same view-model method, so there is one implementation of what
 * a move means and no second code path to get wrong.
 *
 * Both express the move as indices into the whole queue, never as positions within Up next, because
 * that is what `PlaybackController.moveQueueItem` takes and what
 * `PlayQueue.withItemMoved` reasons about.
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
    modifier: Modifier = Modifier,
) {
    val listState: LazyListState = rememberLazyListState()
    val upNext: List<QueueItem> = state.upNext

    val reorder: QueueReorderState = rememberQueueReorderState(
        listState = listState,
        isDraggable = { key -> key is String && key.startsWith(ROW_KEY_PREFIX) },
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

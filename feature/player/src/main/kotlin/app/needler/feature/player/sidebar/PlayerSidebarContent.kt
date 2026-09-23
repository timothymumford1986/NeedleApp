package app.needler.feature.player.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerFormatBadge
import app.needler.core.design.component.NeedlerVerticalHairline
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.feature.player.PlayerUiState
import app.needler.feature.player.crate.CrateRow
import app.needler.feature.player.crate.CrateUiState
import app.needler.feature.player.crate.QueueReorderState
import app.needler.feature.player.crate.ROW_KEY_PREFIX
import app.needler.feature.player.crate.rememberQueueReorderState
import app.needler.feature.player.ui.ArtworkOnRecord
import app.needler.feature.player.ui.ArtworkOnRecordMetrics
import app.needler.feature.player.ui.OutputChip
import app.needler.feature.player.ui.Scrubber
import app.needler.feature.player.ui.TransportRow
import app.needler.feature.player.ui.TransportSize

/**
 * The tablet's permanent right-hand player, screen 09.
 *
 * This is the panel itself, not a part of one: hairline, surface and full 400 dp width included, so
 * it drops straight into `NeedlerNavigationScaffold`'s `sidebar` slot. It replaced `:app`'s
 * chrome-only placeholder (`ui/player/PlayerSidebar.kt`), which had asked to be deleted the moment
 * this module landed and has been - it now survives only as a stand-in in `:app`'s own screenshot
 * tests, which have no Hilt graph to build [PlayerSidebarRoute] with.
 *
 * The pack's measurements: 400 dp wide on the raised surface with a hairline down its left edge, 36
 * dp of top padding and 32 dp either side, then a 250 dp sleeve over a 270 dp disc, the title with
 * its format badge, a 14 dp-thumbed scrubber, the transport at the smaller of its two sizes, the
 * output chip at its quieter weight, and the crate filling whatever is left.
 *
 * It is the same transport and the same crate rows as the phone draws, at different sizes, from the
 * same two view models - not a second implementation that would drift from the first.
 *
 * @param progress a lambda for the same reason it is one everywhere else: the position ticks several
 *   times a second and must not recompose a 400 dp panel with a list in it.
 */
@Composable
fun PlayerSidebarContent(
    state: PlayerUiState,
    crate: CrateUiState,
    progress: () -> PlaybackProgress,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onChooseOutput: () -> Unit,
    onPlayItem: (String) -> Unit,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing

    Row(modifier = modifier.fillMaxHeight()) {
        NeedlerVerticalHairline()
        Column(
            modifier = Modifier
                .width(NeedlerTheme.sizes.sidebarWidth)
                .fillMaxHeight()
                .background(colors.surface)
                .padding(
                    start = spacing.tabletSidebarGutter,
                    end = spacing.tabletSidebarGutter,
                    top = 36.dp,
                    bottom = spacing.tabletSidebarGutter,
                ),
            verticalArrangement = Arrangement.spacedBy(spacing.step12),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ArtworkOnRecord(
                    artwork = state.item?.track?.artwork,
                    albumTitle = state.item?.track?.albumTitle,
                    artistName = state.item?.track?.artistName,
                    playing = state.isPlaying,
                    metrics = ArtworkOnRecordMetrics.sidebar(),
                    emptyLabel = if (state.hasTrack) null else "Nothing playing",
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = state.title,
                        style = typography.sidebarTitle,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = state.subtitle,
                        style = typography.meta,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val badge: String? = state.formatBadge
                if (badge != null) NeedlerFormatBadge(format = badge)
            }

            Scrubber(
                progress = progress,
                durationMs = state.durationMs,
                onSeek = onSeek,
                enabled = state.hasTrack,
                thumbSize = NeedlerTheme.sizes.scrubberThumbSmall,
                gap = 8.dp,
            )

            val error: String? = state.errorMessage
            if (error != null) {
                Text(text = error, style = typography.caption, color = colors.destructive)
            }

            TransportRow(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                shuffleEnabled = state.shuffleEnabled,
                repeatMode = state.repeatMode,
                onShuffle = onToggleShuffle,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onRepeat = onCycleRepeat,
                size = TransportSize.Sidebar,
                enabled = state.hasTrack,
            )

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                OutputChip(target = state.output, onClick = onChooseOutput, emphasised = false)
            }

            SidebarCrate(
                crate = crate,
                onPlayItem = onPlayItem,
                onMove = onMove,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The crate at the foot of the sidebar: the heading with its count, then Up next.
 *
 * The Playing row is deliberately not repeated here - the sleeve, the title and the transport two
 * hand-widths above it are the Playing row, and drawing it again would say the same thing twice in a
 * 400 dp column. Screen 09 does the same thing: its list starts at the next track.
 */
@Composable
private fun SidebarCrate(
    crate: CrateUiState,
    onPlayItem: (String) -> Unit,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val upNext: List<QueueItem> = crate.upNext
    val listState: LazyListState = rememberLazyListState()

    val reorder: QueueReorderState = rememberQueueReorderState(
        listState = listState,
        isDraggable = { key -> key is String && key.startsWith(ROW_KEY_PREFIX) },
        onMove = { fromKey, toKey ->
            val from: Int = crate.queue.items.indexOfFirst { ROW_KEY_PREFIX + it.id == fromKey }
            val to: Int = crate.queue.items.indexOfFirst { ROW_KEY_PREFIX + it.id == toKey }
            if (from >= 0 && to >= 0) onMove(from, to)
        },
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "In the crate".uppercase(),
                style = typography.sectionHeader,
                color = colors.textSecondary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = if (upNext.isEmpty()) "Nothing queued" else crate.upNextCountLabel,
                style = typography.meta,
                color = colors.textMuted,
            )
        }

        if (upNext.isEmpty()) {
            Text(
                text = "Play an album and the rest of it lands here.",
                style = typography.meta,
                color = colors.textMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            itemsIndexed(
                items = upNext,
                key = { _, item -> ROW_KEY_PREFIX + item.id },
            ) { position, item ->
                val queueIndex: Int = crate.queueIndexOfUpNext(position)
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

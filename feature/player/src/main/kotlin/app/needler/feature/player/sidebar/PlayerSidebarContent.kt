package app.needler.feature.player.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerVerticalHairline
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.StreamRung
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
import app.needler.feature.player.ui.FavouriteButton
import app.needler.feature.player.ui.QualityTags
import app.needler.feature.player.ui.Scrubber
import app.needler.feature.player.ui.SessionControls
import app.needler.feature.player.ui.SleepTimerChoice
import app.needler.feature.player.ui.TrackByline
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
 * ## Why the panel measures itself
 *
 * **This panel was inoperable in landscape on a phone, and that is what [BoxWithConstraints] is here
 * for.** REQUIREMENTS.md makes the sidebar a `WindowSizeClass` decision - "one navigation model at two
 * widths" - and a phone turned to landscape is 844 dp wide, which is `Expanded`, so it composes this
 * panel into **390 dp of height**. The pack's own measurements need about 690 dp before the crate gets
 * anything: a 300 dp artwork box, a title, a scrubber, a 68 dp transport, a 48 dp chip and five 24 dp
 * gaps. A `Column` measures its unweighted children in order against the height that is left, so the
 * artwork took all of it and the scrubber, the transport, the output chip and the crate were each
 * measured with a maximum height of zero. They were not merely cramped - they were laid out at 0 dp,
 * which is why the accessibility tree reported every transport control at `[0,0][0,0]` and no
 * play/pause node at all. A player that cannot be paused or seen by TalkBack is worse than a player
 * that does not draw a record.
 *
 * So the panel reads the height it was given and spends it in priority order: the transport, the
 * scrubber and the output chip first, because they are the controls; then the crate, down to one row;
 * and the record last, scaled to what is left and dropped entirely below the size at which it is a
 * smudge rather than a sleeve. At screen 09's own height nothing changes at all - a tablet gets
 * `ArtworkOnRecordMetrics.sidebar()` unscaled, 36 dp of top padding and 24 dp gaps, exactly as drawn.
 *
 * @param progress a lambda for the same reason it is one everywhere else: the position ticks several
 *   times a second and must not recompose a 400 dp panel with a list in it.
 * @param onOpenArtist where the artist line goes. Called only when the artist has been resolved to an
 *   MBID; see [TrackByline] for why the line is drawn as plain text until then.
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
    onToggleFavourite: () -> Unit,
    onChooseSleepTimer: (SleepTimerChoice) -> Unit,
    onOpenArtist: (ArtistMbid) -> Unit,
    onPlayItem: (String) -> Unit,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOverrideQuality: ((StreamRung) -> Unit)? = null,
    onClearQualityOverride: (() -> Unit)? = null,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing

    Row(modifier = modifier.fillMaxHeight()) {
        NeedlerVerticalHairline()
        BoxWithConstraints(
            modifier = Modifier
                .width(NeedlerTheme.sizes.sidebarWidth)
                .fillMaxHeight()
                .background(colors.surface),
        ) {
            val full: Boolean = maxHeight >= SIDEBAR_FULL_HEIGHT
            val gap: Dp = if (full) spacing.step12 else spacing.step6
            val topPadding: Dp = if (full) 36.dp else spacing.step8
            val bottomPadding: Dp = if (full) spacing.tabletSidebarGutter else spacing.step8
            val artwork: ArtworkOnRecordMetrics? = sidebarArtwork(
                available = maxHeight - topPadding - bottomPadding,
                full = full,
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = spacing.tabletSidebarGutter,
                        end = spacing.tabletSidebarGutter,
                        top = topPadding,
                        bottom = bottomPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                if (artwork != null) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ArtworkOnRecord(
                            artwork = state.item?.track?.artwork,
                            albumTitle = state.item?.track?.albumTitle,
                            artistName = state.item?.track?.artistName,
                            playing = state.isPlaying,
                            metrics = artwork,
                            emptyLabel = if (state.hasTrack) null else "Nothing playing",
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        val artist: ArtistMbid? = state.artistMbid
                        if (state.hasTrack) {
                            TrackByline(
                                artistName = state.artistName,
                                albumTitle = state.albumTitle,
                                // The form the crate rows use for their optional accessibility
                                // actions: a nullable lambda from an `if`, with no inference to do.
                                onOpenArtist = if (artist == null) {
                                    null
                                } else {
                                    { onOpenArtist(artist) }
                                },
                                style = typography.meta,
                            )
                        } else {
                            Text(
                                text = state.subtitle,
                                style = typography.meta,
                                color = colors.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (state.hasTrack) {
                        FavouriteButton(
                            isFavourite = state.isFavourite,
                            onToggle = onToggleFavourite,
                            visualSize = 40.dp,
                            iconSize = 20.dp,
                        )
                    }
                }

                QualityTags(
                    state = state,
                    onSelectRung = onOverrideQuality,
                    onClearRung = onClearQualityOverride,
                )

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

                SessionControls(
                    output = state.output,
                    onChooseOutput = onChooseOutput,
                    timer = state.sleepTimer,
                    onChooseSleepTimer = onChooseSleepTimer,
                    emphasised = false,
                )

                SidebarCrate(
                    crate = crate,
                    onPlayItem = onPlayItem,
                    onMove = onMove,
                    onRemove = onRemove,
                    modifier = Modifier.weight(1f),
                )
            }
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
    onRemove: (String) -> Unit,
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
                    onRemove = { onRemove(item.id) },
                    subtitle = crate.rowSubtitle(item),
                    showArtwork = crate.showsRowArtwork,
                )
            }
        }
    }
}

/**
 * The artwork metrics this panel's height can afford, or null when it can afford none.
 *
 * [full] is the tablet, and the tablet gets the pack. Below that the record is what gives way, in this
 * order and for this reason: the transport and the scrubber are the only parts a listener cannot work
 * around, the crate is the panel's second job, and the sleeve is the part that is also drawn on the
 * album screen, in the mini player and in the notification. A landscape phone therefore shows a player
 * with no record rather than a record with no player.
 *
 * @param available the panel's height less its own vertical padding.
 */
@Composable
private fun sidebarArtwork(available: Dp, full: Boolean): ArtworkOnRecordMetrics? {
    val scale: Float = sidebarArtworkScale(available, full) ?: return null
    return ArtworkOnRecordMetrics.sidebar(scale = scale)
}

/**
 * How much of the pack's artwork box this height can afford: `1f` for all of it, a fraction for some of
 * it, null for none.
 *
 * Split out of [sidebarArtwork] and deliberately not `@Composable`, because it is the whole of the P0 and
 * the whole of it is arithmetic. A screenshot proves the panel renders; this proves the decision, at
 * every height, without an emulator - and it is the assertion that would have failed before the fix.
 */
internal fun sidebarArtworkScale(available: Dp, full: Boolean): Float? {
    if (full) return 1f
    val budget: Dp = available - SIDEBAR_CONTROLS_HEIGHT - SIDEBAR_CRATE_MINIMUM
    if (budget < SIDEBAR_ARTWORK_MINIMUM) return null
    val box: Dp = minOf(budget, ArtworkOnRecordMetrics.SIDEBAR_BOX_HEIGHT)
    return box / ArtworkOnRecordMetrics.SIDEBAR_BOX_HEIGHT
}

/** The height above which the panel is screen 09 exactly; below it, the panel adapts. */
internal val sidebarFullHeight: Dp get() = SIDEBAR_FULL_HEIGHT

/**
 * The height at which the panel is screen 09 and nothing is adapted.
 *
 * Measured from the pack rather than guessed: 300 dp of artwork box, a 44 dp title block, a 46 dp
 * scrubber with its labels, a 68 dp transport, a 48 dp row of session chips, five 24 dp gaps and 68 dp
 * of vertical padding come to about 694 dp before the crate has a single row. A tablet is 800 dp tall in
 * landscape and clears it comfortably; everything shorter adapts.
 */
private val SIDEBAR_FULL_HEIGHT: Dp = 700.dp

/**
 * What the panel needs for everything that is not the artwork or the crate.
 *
 * The title block, the scrubber, the transport, the row of session chips, the gaps between them at the
 * compact rhythm, and the gap the artwork itself would add. Deliberately generous: under-estimate and
 * the crate is squeezed, over-estimate and the record shrinks - and it was the first of those, taken to
 * its limit, that left every control in this panel laid out at zero height.
 */
private val SIDEBAR_CONTROLS_HEIGHT: Dp = 272.dp

/** One crate row, so "in the crate" is never a heading with nothing under it. */
private val SIDEBAR_CRATE_MINIMUM: Dp = 80.dp

/** Below this the sleeve is a smudge, and the panel is better off without it. */
private val SIDEBAR_ARTWORK_MINIMUM: Dp = 150.dp

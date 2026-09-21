@file:OptIn(ExperimentalLayoutApi::class)

package app.needler.feature.library.album

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerButtonSize
import app.needler.core.design.component.NeedlerButtonTone
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerLinearProgress
import app.needler.core.design.component.NeedlerMoreIcon
import app.needler.core.design.component.NeedlerOnDeviceIcon
import app.needler.core.design.component.NeedlerPillButton
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerSecondaryButton
import app.needler.core.design.component.NeedlerStateBadge
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathPlay
import app.needler.core.design.component.PathPull
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.feature.library.common.AlbumArtwork
import app.needler.feature.library.common.LibraryFormat
import app.needler.feature.library.common.albumBadge
import app.needler.feature.library.common.failureExplanation
import app.needler.feature.library.library.LibraryTrackRow

/**
 * The album screen: screens 04 (owned), 05 (not owned) and 11 (tablet).
 *
 * One composable, because they are one album at different states. What changes
 * between them is the block of actions under the header and whether the track
 * rows are tappable; the artwork, the title, the artist and the list itself are
 * identical, which is the point REQUIREMENTS.md's identity model is making.
 *
 * ## Every state in `AlbumState` is drawn
 *
 * | State | What this screen shows |
 * | --- | --- |
 * | `NotOwned` | **Pull this album**, with the line explaining what the server will do |
 * | `PendingApproval` | The Waiting badge, and why no progress is moving |
 * | `Acquiring` | Pulling with its percentage, a progress bar, and Cancel |
 * | `Owned` | Play, Shuffle, Pull local, and the track list |
 * | `Pinned` | The same, with the on-device mark and Remove from device |
 * | `Failed` | What went wrong, in words, and Retry |
 *
 * A part-delivered album is `Owned` and plays what arrived; the tracks that
 * never came are greyed in their right positions, each with its own retry,
 * because there is nothing to stream for them anywhere.
 */
@Composable
fun AlbumScreen(
    state: AlbumUiState,
    widthSizeClass: WindowWidthSizeClass,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (AlbumTrack) -> Unit,
    onPull: () -> Unit,
    onCancelPull: () -> Unit,
    onRetryPull: () -> Unit,
    onDownloadToDevice: () -> Unit,
    onRemoveFromDevice: () -> Unit,
    onRetryTrack: (AlbumTrack) -> Unit,
    onOpenArtist: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val wide: Boolean = widthSizeClass == WindowWidthSizeClass.Expanded
    val gutter: Dp = if (wide) spacing.tabletGutter else spacing.phoneGutterWide

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .safeDrawingPadding(),
    ) {
        AlbumTopBar(
            canOpenArtist = state.album?.artistMbid != null,
            onBack = onBack,
            onOpenArtist = onOpenArtist,
            gutter = gutter,
        )

        when {
            state.loading -> AlbumSkeleton(gutter = gutter)
            state.notFound -> AlbumNotFound(gutter = gutter)
            wide -> TabletAlbum(
                state = state,
                gutter = gutter,
                onPlay = onPlay,
                onShuffle = onShuffle,
                onPlayTrack = onPlayTrack,
                onPull = onPull,
                onCancelPull = onCancelPull,
                onRetryPull = onRetryPull,
                onDownloadToDevice = onDownloadToDevice,
                onRemoveFromDevice = onRemoveFromDevice,
                onRetryTrack = onRetryTrack,
                onOpenArtist = onOpenArtist,
                onDismissNotice = onDismissNotice,
            )
            else -> PhoneAlbum(
                state = state,
                gutter = gutter,
                onPlay = onPlay,
                onShuffle = onShuffle,
                onPlayTrack = onPlayTrack,
                onPull = onPull,
                onCancelPull = onCancelPull,
                onRetryPull = onRetryPull,
                onDownloadToDevice = onDownloadToDevice,
                onRemoveFromDevice = onRemoveFromDevice,
                onRetryTrack = onRetryTrack,
                onOpenArtist = onOpenArtist,
                onDismissNotice = onDismissNotice,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Phone (screens 04, 05)
// ---------------------------------------------------------------------------

@Composable
private fun PhoneAlbum(
    state: AlbumUiState,
    gutter: Dp,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (AlbumTrack) -> Unit,
    onPull: () -> Unit,
    onCancelPull: () -> Unit,
    onRetryPull: () -> Unit,
    onDownloadToDevice: () -> Unit,
    onRemoveFromDevice: () -> Unit,
    onRetryTrack: (AlbumTrack) -> Unit,
    onOpenArtist: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val album: Album = state.album ?: return
    val spacing = NeedlerTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = spacing.step12),
    ) {
        // The header is one item rather than several, so the track rows below
        // keep the pack's own 52dp rhythm instead of inheriting the header's
        // generous spacing.
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.step11)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.step8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArtwork(
                        album = album,
                        modifier = Modifier.size(NeedlerTheme.sizes.artworkDetail),
                        shape = NeedlerTheme.shapes.artworkDetail,
                    )
                    AlbumTitleBlock(
                        state = state,
                        onOpenArtist = onOpenArtist,
                        modifier = Modifier.weight(1f),
                    )
                }
                AlbumActions(
                    state = state,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    onPull = onPull,
                    onCancelPull = onCancelPull,
                    onRetryPull = onRetryPull,
                    onDownloadToDevice = onDownloadToDevice,
                    onRemoveFromDevice = onRemoveFromDevice,
                )
                if (state.download != null && state.download != OfflineDownloadState.Complete) {
                    DownloadProgress(state.download)
                }
                state.notice?.let { NoticeCard(notice = it, onDismiss = onDismissNotice) }
                PartialDeliveryNote(state)
                Spacer(modifier = Modifier.height(spacing.step2))
            }
        }
        trackRows(state = state, onPlayTrack = onPlayTrack, onRetryTrack = onRetryTrack)
    }
}

// ---------------------------------------------------------------------------
// Tablet (screen 11)
// ---------------------------------------------------------------------------

/**
 * Screen 11: artwork, title and actions in a left column, the track list beside
 * them.
 *
 * The left column scrolls on its own so a very long album does not push the
 * Play button off the top of the screen — on a tablet it is the one control
 * that must never scroll away.
 */
@Composable
private fun TabletAlbum(
    state: AlbumUiState,
    gutter: Dp,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (AlbumTrack) -> Unit,
    onPull: () -> Unit,
    onCancelPull: () -> Unit,
    onRetryPull: () -> Unit,
    onDownloadToDevice: () -> Unit,
    onRemoveFromDevice: () -> Unit,
    onRetryTrack: (AlbumTrack) -> Unit,
    onOpenArtist: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val album: Album = state.album ?: return
    val spacing = NeedlerTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = gutter, end = gutter),
        horizontalArrangement = Arrangement.spacedBy(spacing.step16),
    ) {
        Column(
            modifier = Modifier
                .width(HERO_COLUMN_WIDTH)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.step12),
        ) {
            AlbumArtwork(
                album = album,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = NeedlerTheme.shapes.artworkHeroTablet,
            )
            AlbumTitleBlock(state = state, onOpenArtist = onOpenArtist)
            AlbumActions(
                state = state,
                onPlay = onPlay,
                onShuffle = onShuffle,
                onPull = onPull,
                onCancelPull = onCancelPull,
                onRetryPull = onRetryPull,
                onDownloadToDevice = onDownloadToDevice,
                onRemoveFromDevice = onRemoveFromDevice,
            )
            DownloadProgress(state.download)
            state.notice?.let { NoticeCard(notice = it, onDismiss = onDismissNotice) }
            PartialDeliveryNote(state)
            Spacer(modifier = Modifier.height(spacing.step12))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = spacing.step12),
        ) {
            trackRows(state = state, onPlayTrack = onPlayTrack, onRetryTrack = onRetryTrack)
        }
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

private fun LazyListScope.trackRows(
    state: AlbumUiState,
    onPlayTrack: (AlbumTrack) -> Unit,
    onRetryTrack: (AlbumTrack) -> Unit,
) {
    items(
        count = state.tracks.size,
        key = { index -> state.tracks[index].key.canonicalString },
    ) { index ->
        val row: AlbumTrack = state.tracks[index]
        val owned: Boolean = state.album?.isOwned == true
        if (row.available) {
            LibraryTrackRow(
                index = row.position,
                track = row.track,
                isPlaying = row.key == state.nowPlayingTrackKey,
                available = true,
                onClick = { onPlayTrack(row) },
            )
        } else if (!owned) {
            // Screen 05: an un-owned album's track list is catalogue metadata.
            // Every row is greyed and none of them offers a per-track retry,
            // because the action for the whole record is Pull this album.
            LibraryTrackRow(
                index = row.position,
                track = row.track,
                isPlaying = false,
                available = false,
                onClick = null,
            )
        } else {
            // A track a part-delivered pull never brought. Greyed, not tappable,
            // and carrying its own retry — the only way to ask the server for
            // the one track that is missing.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibraryTrackRow(
                    index = row.position,
                    track = row.track,
                    isPlaying = false,
                    available = false,
                    onClick = null,
                    modifier = Modifier.weight(1f),
                )
                NeedlerPillButton(
                    text = "Retry",
                    onClick = { onRetryTrack(row) },
                    emphasised = true,
                    contentDescription = "Retry " + row.track.title,
                )
            }
        }
    }
}

@Composable
private fun AlbumTopBar(
    canOpenArtist: Boolean,
    onBack: () -> Unit,
    onOpenArtist: () -> Unit,
    gutter: Dp,
) {
    val colors = NeedlerTheme.colors
    var menuOpen: Boolean by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gutter - 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerIconButton(contentDescription = "Back", onClick = onBack) {
            NeedlerStrokeIcon(pathData = PathChevronLeft, tint = colors.textPrimary, size = 24.dp)
        }
        if (canOpenArtist) {
            Box {
                NeedlerIconButton(
                    contentDescription = "More actions for this album",
                    onClick = { menuOpen = true },
                ) {
                    NeedlerMoreIcon(tint = colors.textPrimary, size = 22.dp)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Go to artist",
                                style = NeedlerTheme.typography.body,
                                color = colors.textPrimary,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onOpenArtist()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumTitleBlock(
    state: AlbumUiState,
    onOpenArtist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val album: Album = state.album ?: return
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val meta: String = LibraryFormat.albumMetaLine(album, includeRunningTime = true)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = album.title,
            style = typography.albumTitle,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = album.artistName,
            style = typography.bodyStrong,
            color = colors.accent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .then(
                    if (album.artistMbid != null) {
                        Modifier.clickable(role = Role.Button, onClick = onOpenArtist)
                    } else {
                        Modifier
                    },
                )
                .semantics {
                    if (album.artistMbid != null) {
                        contentDescription = "Go to " + album.artistName
                    }
                },
        )
        Text(
            text = meta,
            style = typography.meta,
            color = colors.textSecondary,
            modifier = Modifier.semantics { contentDescription = meta.replace(" · ", ", ") },
        )
        // Only the states whose action area does not already say it. An owned
        // album's buttons read Play and Pull local, and a pinned one's read
        // "On device"; repeating the badge above them would say the same thing
        // twice in the same eyeful.
        val badge = albumBadge(album.state)
        if (badge != null && !album.isOwned) {
            Spacer(modifier = Modifier.height(2.dp))
            NeedlerStateBadge(badge = badge)
        }
    }
}

/**
 * The action block.
 *
 * Which buttons appear is read off [AlbumUiState.primaryAction], which is read
 * off `AlbumState.offeredActions` — so the set of actions the UI offers and the
 * set the domain says are legal cannot drift apart.
 */
@Composable
private fun AlbumActions(
    state: AlbumUiState,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPull: () -> Unit,
    onCancelPull: () -> Unit,
    onRetryPull: () -> Unit,
    onDownloadToDevice: () -> Unit,
    onRemoveFromDevice: () -> Unit,
) {
    val album: Album = state.album ?: return
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.step5),
    ) {
        when (state.primaryAction) {
            AlbumPrimaryAction.PLAY -> {
                val pinned: Boolean = album.state is AlbumState.Pinned
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.step4),
                    verticalArrangement = Arrangement.spacedBy(spacing.step4),
                ) {
                    NeedlerPrimaryButton(
                        text = "Play",
                        onClick = onPlay,
                        size = NeedlerButtonSize.Medium,
                        enabled = !state.busy && state.hasPlayableTracks,
                        leadingIcon = { tint ->
                            NeedlerStrokeIcon(
                                pathData = PathPlay,
                                tint = tint,
                                size = 18.dp,
                                filled = true,
                            )
                        },
                        contentDescription = "Play " + album.title,
                    )
                    NeedlerSecondaryButton(
                        text = "Shuffle",
                        onClick = onShuffle,
                        size = NeedlerButtonSize.Medium,
                        enabled = !state.busy && state.hasPlayableTracks,
                        contentDescription = "Shuffle " + album.title,
                    )
                    // REQUIREMENTS.md: hide the pin affordance entirely when the
                    // administrator has turned library download off, rather than
                    // letting it fail on a 403 after the tap.
                    if (state.downloadAllowed) {
                        NeedlerSecondaryButton(
                            text = if (pinned) "On device" else "Pull local",
                            onClick = if (pinned) onRemoveFromDevice else onDownloadToDevice,
                            size = NeedlerButtonSize.Medium,
                            enabled = !state.busy,
                            selected = pinned,
                            reportSelection = true,
                            leadingIcon = { tint -> NeedlerOnDeviceIcon(tint = tint) },
                            contentDescription = if (pinned) {
                                "On device. Remove " + album.title + " from this device"
                            } else {
                                "Pull local. Download " + album.title + " to this device"
                            },
                        )
                    }
                }
                if (!state.downloadAllowed) {
                    Text(
                        text = DOWNLOAD_DISABLED,
                        style = typography.caption,
                        color = colors.textMuted,
                    )
                }
            }

            AlbumPrimaryAction.PULL -> {
                NeedlerPrimaryButton(
                    text = "Pull this album",
                    onClick = onPull,
                    modifier = Modifier.fillMaxWidth(),
                    tone = NeedlerButtonTone.Positive,
                    size = NeedlerButtonSize.Medium,
                    enabled = !state.busy,
                    textStyle = typography.rowTitle,
                    leadingIcon = { tint ->
                        NeedlerStrokeIcon(pathData = PathPull, tint = tint, size = 20.dp)
                    },
                    contentDescription = "Pull " + album.title,
                )
                Text(
                    // REQUIREMENTS.md "Design pack discrepancies": screen 05
                    // says "Dropped Needle picks the best Soulseek source", and
                    // the server also supports Usenet — so the copy names no
                    // one source. When the server returns its own quality
                    // policy for this request, that is shown instead, because
                    // it is the only version of this sentence that is
                    // guaranteed true.
                    text = album.qualityPolicySummary ?: PULL_EXPLANATION,
                    style = typography.caption,
                    color = colors.textMuted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            AlbumPrimaryAction.WAITING -> {
                Text(
                    text = WAITING_EXPLANATION,
                    style = typography.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            AlbumPrimaryAction.ACQUIRING -> {
                val acquiring = album.state as? AlbumState.Acquiring
                val fraction: Float? = acquiring?.progress?.fraction
                if (fraction != null) {
                    NeedlerLinearProgress(
                        progress = fraction,
                        modifier = Modifier.fillMaxWidth(),
                        contentDescription = "Pulling, " +
                            (fraction * 100f).toInt() + " percent complete",
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                NeedlerSecondaryButton(
                    text = "Cancel",
                    onClick = onCancelPull,
                    size = NeedlerButtonSize.Medium,
                    enabled = !state.busy,
                    contentDescription = "Cancel the pull of " + album.title,
                )
            }

            AlbumPrimaryAction.RETRY -> {
                val failed = album.state as? AlbumState.Failed
                if (failed != null) {
                    Text(
                        text = failureExplanation(failed.reason, failed.message),
                        style = typography.caption,
                        color = colors.textSecondary,
                    )
                }
                NeedlerPrimaryButton(
                    text = "Retry",
                    onClick = onRetryPull,
                    size = NeedlerButtonSize.Medium,
                    enabled = !state.busy,
                    leadingIcon = { tint ->
                        NeedlerStrokeIcon(pathData = PathPull, tint = tint, size = 18.dp)
                    },
                    contentDescription = "Retry the pull of " + album.title,
                )
            }

            AlbumPrimaryAction.NONE -> Unit
        }
    }
}

/** Progress of the download to *this device*, which is not the same as a pull. */
@Composable
private fun DownloadProgress(download: OfflineDownloadState?) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val label: String = when (download) {
        null, OfflineDownloadState.Complete -> return
        OfflineDownloadState.Queued -> "Queued for download to this device."
        OfflineDownloadState.WaitingForUnmeteredNetwork ->
            "Waiting for Wi-Fi before downloading to this device."
        is OfflineDownloadState.Downloading ->
            "Downloading to this device, " + download.tracksComplete + " of " +
                download.tracksTotal + " tracks."
        is OfflineDownloadState.Partial ->
            "On this device in part: " + download.tracksComplete + " of " +
                download.tracksTotal + " tracks. The rest stream."
        is OfflineDownloadState.Failed ->
            "The download to this device did not finish."
    }
    val fraction: Float? = (download as? OfflineDownloadState.Downloading)?.fraction
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (fraction != null) {
            NeedlerLinearProgress(progress = fraction, modifier = Modifier.fillMaxWidth())
        }
        Text(text = label, style = typography.caption, color = colors.textSecondary)
    }
}

/**
 * The part-delivered line.
 *
 * REQUIREMENTS.md: such an album "reads as **in library** and plays the tracks
 * that arrived; the missing ones are listed in their right positions, greyed,
 * each with a retry. There is nothing to stream for them — those tracks exist
 * nowhere, not on the server and not on any device — so there is no fallback to
 * offer". The sentence below says exactly that, because a greyed row on its own
 * looks like a bug.
 */
@Composable
private fun PartialDeliveryNote(state: AlbumUiState) {
    if (!state.isPartiallyDelivered) return
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    val missing: Int = state.missingTracks.size
    val message: String = LibraryFormat.plural(missing.toLong(), "track") +
        " did not arrive with this pull. They exist nowhere yet, so there is nothing to play " +
        "or stream for them — retry one to ask the server again."
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = message },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = message,
            style = NeedlerTheme.typography.caption,
            color = colors.textSecondary,
        )
    }
}

/** A one-line result of the last action, dismissible. */
@Composable
private fun NoticeCard(notice: AlbumNotice, onDismiss: () -> Unit) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    val tint = if (notice.isProblem) colors.destructive else colors.positive
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, tint, shape)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = notice.message
                liveRegion = LiveRegionMode.Assertive
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = notice.message,
            style = NeedlerTheme.typography.caption,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        NeedlerIconButton(
            contentDescription = "Dismiss",
            onClick = onDismiss,
            visualSize = 36.dp,
        ) {
            NeedlerStrokeIcon(pathData = PATH_CLOSE, tint = colors.textMuted, size = 16.dp)
        }
    }
}

@Composable
private fun AlbumSkeleton(gutter: Dp) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter)
            .semantics(mergeDescendants = true) { contentDescription = "Loading this album" },
        verticalArrangement = Arrangement.spacedBy(spacing.step11),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.step8)) {
            Box(
                modifier = Modifier
                    .size(NeedlerTheme.sizes.artworkDetail)
                    .clip(NeedlerTheme.shapes.artworkDetail)
                    .background(colors.surface),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .width(180.dp)
                        .height(20.dp)
                        .clip(NeedlerTheme.shapes.progress)
                        .background(colors.surface),
                )
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(14.dp)
                        .clip(NeedlerTheme.shapes.progress)
                        .background(colors.surface),
                )
            }
        }
        repeat(6) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .clip(NeedlerTheme.shapes.progress)
                    .background(colors.surface),
            )
        }
    }
}

@Composable
private fun AlbumNotFound(gutter: Dp) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter, vertical = 24.dp)
            .widthIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "That album is not here",
            style = typography.displayCompact,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "It is not in the mirror on this device. A sync may have dropped it, or it " +
                "was removed from the server.",
            style = typography.body,
            color = colors.textSecondary,
        )
    }
}

/** The width of the artwork-and-actions column on a tablet, from screen 11. */
private val HERO_COLUMN_WIDTH: Dp = 280.dp

private const val PATH_CLOSE: String = "M6 6l12 12M18 6L6 18"

internal const val PULL_EXPLANATION: String =
    "Dropped Needle looks for the best source it can reach and imports it into your library. " +
        "FLAC first, then MP3 320."

internal const val WAITING_EXPLANATION: String =
    "Waiting for an administrator to approve this pull. Nothing is downloading yet, and there " +
        "is nothing more to do here."

internal const val DOWNLOAD_DISABLED: String =
    "Downloading to this device is turned off for your account on this server. Streaming and " +
        "playback are unaffected."

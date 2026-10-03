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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.needler.core.design.component.NeedlerAlbumBadge
import app.needler.core.design.component.NeedlerAlbumSource
import app.needler.core.design.component.NeedlerButtonSize
import app.needler.core.design.component.NeedlerButtonTone
import app.needler.core.design.component.NeedlerCrateControl
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerLinearProgress
import app.needler.core.design.component.NeedlerMoreIcon
import app.needler.core.design.component.NeedlerOnDeviceIcon
import app.needler.core.design.component.NeedlerPillButton
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerQualityTag
import app.needler.core.design.component.NeedlerQualityTagEmphasis
import app.needler.core.design.component.NeedlerSecondaryButton
import app.needler.core.design.component.NeedlerStateBadge
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PULL_SHEET_EXPLANATION
import app.needler.core.design.component.PathClose
import app.needler.core.design.component.PathPause
import app.needler.core.design.component.PathPlay
import app.needler.core.design.component.PathPull
import app.needler.core.design.component.tagLabel
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.StreamRung
import app.needler.feature.library.common.AlbumArtwork
import app.needler.feature.library.common.FavouriteButton
import app.needler.feature.library.common.LibraryFormat
import app.needler.feature.library.common.RequestSheet
import app.needler.feature.library.common.RequestSheetState
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
 * | `Owned` | The transport control, Shuffle, Pull to device, and the track list |
 * | `Pinned` | The same, with the on-device mark and Remove from device |
 * | `Failed` | What went wrong, in words, and Retry |
 *
 * A part-delivered album is `Owned` and plays what arrived; the tracks that
 * never came are greyed in their right positions, each with its own retry,
 * because there is nothing to stream for them anywhere.
 *
 * ## The star
 *
 * A star sits in the top bar for the album and on every playable track row.
 * `FavouriteRepository` had been complete and uncalled since the data layer was
 * written, so the library's "Starred" sort ordered a list by a property nothing
 * in the app could set. REQUIREMENTS.md "Playlists" is explicit that binary
 * favourites are the mechanism and that star *ratings* must never be offered,
 * which is why there is one two-state control here and no five-star row.
 *
 * ## Pull opens a sheet
 *
 * **Pull this album** no longer requests on the tap; it opens the shared request
 * sheet, which carries the `monitor_artist` toggle REQUIREMENTS.md "Placing a
 * request" asks for. The sheet is drawn as an overlay
 * inside this screen rather than as a Material `ModalBottomSheet` in `:app`,
 * because a modal sheet renders in a window of its own and would be absent from
 * every screenshot of this screen — and these screens are tested by rendering
 * them.
 */
@Composable
fun AlbumScreen(
    state: AlbumUiState,
    widthSizeClass: WindowWidthSizeClass,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (AlbumTrack) -> Unit,
    onAddToCrate: (Boolean) -> Unit,
    onAddTrackToCrate: (AlbumTrack, Boolean) -> Unit,
    onPull: () -> Unit,
    onCancelPull: () -> Unit,
    onRetryPull: () -> Unit,
    onDownloadToDevice: () -> Unit,
    onRemoveFromDevice: () -> Unit,
    onRetryTrack: (AlbumTrack) -> Unit,
    onOpenArtist: () -> Unit,
    onDismissNotice: () -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleTrackFavourite: (AlbumTrack) -> Unit,
    onMonitorArtistChange: (Boolean) -> Unit,
    onConfirmRequest: () -> Unit,
    onDismissRequestSheet: () -> Unit,
    modifier: Modifier = Modifier,
    onOverrideQuality: ((StreamRung) -> Unit)? = null,
    onClearQualityOverride: (() -> Unit)? = null,
) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val wide: Boolean = widthSizeClass == WindowWidthSizeClass.Expanded
    val gutter: Dp = if (wide) spacing.tabletGutter else spacing.phoneGutterWide

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.canvas)
                .safeDrawingPadding(),
        ) {
            AlbumTopBar(
                album = state.album,
                busy = state.busy,
                onBack = onBack,
                onOpenArtist = onOpenArtist,
                onToggleFavourite = onToggleFavourite,
                gutter = gutter,
            )

            when {
                state.loading -> AlbumSkeleton(gutter = gutter)
                state.notFound -> AlbumNotFound(gutter = gutter)
                wide -> TabletAlbum(
                    state = state,
                    gutter = gutter,
                    onPlayPause = onPlayPause,
                    onShuffle = onShuffle,
                    onPlayTrack = onPlayTrack,
                    onAddToCrate = onAddToCrate,
                    onAddTrackToCrate = onAddTrackToCrate,
                    onPull = onPull,
                    onCancelPull = onCancelPull,
                    onRetryPull = onRetryPull,
                    onDownloadToDevice = onDownloadToDevice,
                    onRemoveFromDevice = onRemoveFromDevice,
                    onRetryTrack = onRetryTrack,
                    onOpenArtist = onOpenArtist,
                    onDismissNotice = onDismissNotice,
                    onToggleTrackFavourite = onToggleTrackFavourite,
                    onOverrideQuality = onOverrideQuality,
                    onClearQualityOverride = onClearQualityOverride,
                )
                else -> PhoneAlbum(
                    state = state,
                    gutter = gutter,
                    onPlayPause = onPlayPause,
                    onShuffle = onShuffle,
                    onPlayTrack = onPlayTrack,
                    onAddToCrate = onAddToCrate,
                    onAddTrackToCrate = onAddTrackToCrate,
                    onPull = onPull,
                    onCancelPull = onCancelPull,
                    onRetryPull = onRetryPull,
                    onDownloadToDevice = onDownloadToDevice,
                    onRemoveFromDevice = onRemoveFromDevice,
                    onRetryTrack = onRetryTrack,
                    onOpenArtist = onOpenArtist,
                    onDismissNotice = onDismissNotice,
                    onToggleTrackFavourite = onToggleTrackFavourite,
                    onOverrideQuality = onOverrideQuality,
                    onClearQualityOverride = onClearQualityOverride,
                )
            }
        }

        val sheet: RequestSheetState? = state.requestSheet
        if (sheet != null) {
            RequestSheet(
                sheet = sheet,
                busy = state.busy,
                onMonitorArtistChange = onMonitorArtistChange,
                onConfirm = onConfirmRequest,
                onCancel = onDismissRequestSheet,
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
    onPlayPause: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (AlbumTrack) -> Unit,
    onAddToCrate: (Boolean) -> Unit,
    onAddTrackToCrate: (AlbumTrack, Boolean) -> Unit,
    onPull: () -> Unit,
    onCancelPull: () -> Unit,
    onRetryPull: () -> Unit,
    onDownloadToDevice: () -> Unit,
    onRemoveFromDevice: () -> Unit,
    onRetryTrack: (AlbumTrack) -> Unit,
    onOpenArtist: () -> Unit,
    onDismissNotice: () -> Unit,
    onToggleTrackFavourite: (AlbumTrack) -> Unit,
    onOverrideQuality: ((StreamRung) -> Unit)? = null,
    onClearQualityOverride: (() -> Unit)? = null,
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
                        onOverrideQuality = onOverrideQuality,
                        onClearQualityOverride = onClearQualityOverride,
                        modifier = Modifier.weight(1f),
                    )
                }
                AlbumActions(
                    state = state,
                    onPlayPause = onPlayPause,
                    onShuffle = onShuffle,
                    onAddToCrate = onAddToCrate,
                    onPull = onPull,
                    onCancelPull = onCancelPull,
                    onRetryPull = onRetryPull,
                    onDownloadToDevice = onDownloadToDevice,
                    onRemoveFromDevice = onRemoveFromDevice,
                )
                state.notice?.let { notice ->
                    NoticeCard(
                        notice = notice,
                        // The crate's own count and total duration, as the session holds them,
                        // under the sentence saying what was just added to it.
                        detail = if (notice is AlbumNotice.AddedToCrate) state.crateLine else null,
                        onDismiss = onDismissNotice,
                    )
                }
                PartialDeliveryNote(state)
                Spacer(modifier = Modifier.height(spacing.step2))
            }
        }
        trackRows(
            state = state,
            onPlayTrack = onPlayTrack,
            onAddTrackToCrate = onAddTrackToCrate,
            onRetryTrack = onRetryTrack,
            onToggleTrackFavourite = onToggleTrackFavourite,
        )
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
    onPlayPause: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (AlbumTrack) -> Unit,
    onAddToCrate: (Boolean) -> Unit,
    onAddTrackToCrate: (AlbumTrack, Boolean) -> Unit,
    onPull: () -> Unit,
    onCancelPull: () -> Unit,
    onRetryPull: () -> Unit,
    onDownloadToDevice: () -> Unit,
    onRemoveFromDevice: () -> Unit,
    onRetryTrack: (AlbumTrack) -> Unit,
    onOpenArtist: () -> Unit,
    onDismissNotice: () -> Unit,
    onToggleTrackFavourite: (AlbumTrack) -> Unit,
    onOverrideQuality: ((StreamRung) -> Unit)? = null,
    onClearQualityOverride: (() -> Unit)? = null,
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
            AlbumTitleBlock(
                state = state,
                onOpenArtist = onOpenArtist,
                onOverrideQuality = onOverrideQuality,
                onClearQualityOverride = onClearQualityOverride,
            )
            AlbumActions(
                state = state,
                onPlayPause = onPlayPause,
                onShuffle = onShuffle,
                onAddToCrate = onAddToCrate,
                onPull = onPull,
                onCancelPull = onCancelPull,
                onRetryPull = onRetryPull,
                onDownloadToDevice = onDownloadToDevice,
                onRemoveFromDevice = onRemoveFromDevice,
            )
            state.notice?.let { notice ->
                NoticeCard(
                    notice = notice,
                    detail = if (notice is AlbumNotice.AddedToCrate) state.crateLine else null,
                    onDismiss = onDismissNotice,
                )
            }
            PartialDeliveryNote(state)
            Spacer(modifier = Modifier.height(spacing.step12))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = spacing.step12),
        ) {
            trackRows(
                state = state,
                onPlayTrack = onPlayTrack,
                onAddTrackToCrate = onAddTrackToCrate,
                onRetryTrack = onRetryTrack,
                onToggleTrackFavourite = onToggleTrackFavourite,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

/**
 * The track list.
 *
 * A playable row carries its own star, as a sibling of the row rather than inside
 * it: `NeedlerTrackRow` merges its descendants into one accessibility node, and a
 * star put inside it would stop being reachable as a target of its own. The same
 * arrangement the per-track Retry already uses.
 *
 * Only a **playable** row gets one. Starring goes out as Subsonic `star` on a
 * track id, and a row with no file behind it has no id to send — an un-owned
 * album's list is catalogue metadata, and a track a part-delivered pull never
 * brought exists nowhere. Offering the star there would offer a control that
 * cannot succeed.
 */
private fun LazyListScope.trackRows(
    state: AlbumUiState,
    onPlayTrack: (AlbumTrack) -> Unit,
    onAddTrackToCrate: (AlbumTrack, Boolean) -> Unit,
    onRetryTrack: (AlbumTrack) -> Unit,
    onToggleTrackFavourite: (AlbumTrack) -> Unit,
) {
    items(
        count = state.tracks.size,
        key = { index -> state.tracks[index].key.canonicalString },
    ) { index ->
        val row: AlbumTrack = state.tracks[index]
        val owned: Boolean = state.album?.isOwned == true
        if (row.available) {
            var crateMenuOpen: Boolean by remember(row.key) { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibraryTrackRow(
                    index = row.position,
                    track = row.track,
                    isPlaying = row.key == state.nowPlayingTrackKey,
                    available = true,
                    onClick = { onPlayTrack(row) },
                    modifier = Modifier.weight(1f),
                    // The long press used to play the row and replace the crate, because
                    // `clickable` fires on the release however long the hold was. It opens
                    // the crate menu now; `needlerRowActions` consumes the press, so the
                    // tap-to-play cannot fire behind it.
                    onLongPress = { crateMenuOpen = true },
                )
                // Beside the row rather than in it. The row's gesture modifier clears its
                // own descendants' semantics - see `needlerRowActions` - so a control
                // placed inside would be drawn and unreachable to TalkBack. This is the
                // arrangement the star already uses, for the same reason.
                NeedlerCrateControl(
                    subject = LibraryFormat.trackLabel(row.track.title),
                    expanded = crateMenuOpen,
                    onExpandedChange = { crateMenuOpen = it },
                    onAddToCrate = { onAddTrackToCrate(row, false) },
                    onPlayNext = { onAddTrackToCrate(row, true) },
                    visualSize = 36.dp,
                )
                FavouriteButton(
                    isFavourite = row.track.isFavourite,
                    name = LibraryFormat.trackLabel(row.track.title),
                    onToggle = { onToggleTrackFavourite(row) },
                    visualSize = 36.dp,
                    glyphSize = 18.dp,
                )
            }
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
                    contentDescription = "Retry " + LibraryFormat.trackLabel(row.track.title),
                )
            }
        }
    }
}

/**
 * Back on the left; the star and the overflow on the right.
 *
 * The star is in the top bar rather than in the action block because the action
 * block already changes shape with every [AlbumState] — Play and Shuffle in one
 * state, Cancel in another, nothing at all while waiting for approval — and a
 * favourite applies in all six of them. An album you have not pulled yet can be
 * starred, and `getStarred2` will return it, so the control cannot live somewhere
 * that only owned albums reach.
 */
@Composable
private fun AlbumTopBar(
    album: Album?,
    busy: Boolean,
    onBack: () -> Unit,
    onOpenArtist: () -> Unit,
    onToggleFavourite: () -> Unit,
    gutter: Dp,
) {
    val colors = NeedlerTheme.colors
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (album != null) {
                FavouriteButton(
                    isFavourite = album.isFavourite,
                    name = LibraryFormat.albumLabel(album.title),
                    onToggle = onToggleFavourite,
                    enabled = !busy,
                    visualSize = 44.dp,
                    glyphSize = 22.dp,
                )
            }
            // No overflow menu. It held one item, "Go to artist", which called
            // the same onOpenArtist as the artist link in the header a few dp
            // below it - so the screen offered two routes to one destination and
            // hid one of them behind a tap. A menu earns its place when it has
            // actions with nowhere else to live; this one taught the user to open
            // it and find nothing new.
        }
    }
}

@Composable
private fun AlbumTitleBlock(
    state: AlbumUiState,
    onOpenArtist: () -> Unit,
    modifier: Modifier = Modifier,
    onOverrideQuality: ((StreamRung) -> Unit)? = null,
    onClearQualityOverride: (() -> Unit)? = null,
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
            // Guarded, not raw. `Album.title` can be blank: `ReleaseItemDto.title`
            // is nullable and the catalogue mapper maps it with `.orEmpty()`, so a
            // release group MusicBrainz has no title for reaches this screen with
            // nothing to draw. The same hole put 34 blank rows on the Pulls screen.
            text = LibraryFormat.albumTitle(album.title),
            style = typography.albumTitle,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = LibraryFormat.artistName(album.artistName),
            style = typography.bodyStrong,
            color = colors.accent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .then(
                    if (album.artistMbid != null) {
                        // REQUIREMENTS.md "Accessibility" puts the floor for any control at 48dp,
                        // and one line of `bodyStrong` measures about 20dp - so this link was a
                        // little under half the legal target. The floor goes on before the
                        // `clickable` so that the node taking the gesture is the node that is 48dp
                        // tall; applied afterwards it would raise a parent and leave the target
                        // where it was. `wrapContentHeight` then re-centres the one line inside
                        // that height, because a title block with 28dp of nothing under the artist
                        // reads as a gap rather than as a target.
                        Modifier
                            .defaultMinSize(minHeight = NeedlerTheme.sizes.minTouchTarget)
                            .clickable(role = Role.Button, onClick = onOpenArtist)
                            .wrapContentHeight(Alignment.CenterVertically)
                    } else {
                        Modifier
                    },
                )
                .semantics {
                    if (album.artistMbid != null) {
                        contentDescription = "Go to " + LibraryFormat.artistLabel(album.artistName)
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
        // album's buttons read Play and Pull to device, and a pinned one's read
        // "Device"; repeating the badge above them would say the same thing
        // twice in the same eyeful.
        //
        // A download in flight is the exception, and it is why the full-width
        // progress banner that used to sit under the actions could go. The
        // buttons say where the record will end up, not that bytes are arriving
        // now, so the badge is the only place that is said - and being derived
        // from the pin row on every emission, it clears itself on success,
        // failure, cancellation and a backgrounded app alike.
        val badge = albumBadge(album.state)
        val saidByTheActions: Boolean = album.isOwned &&
            (badge == NeedlerAlbumBadge.InLibrary || badge == NeedlerAlbumBadge.OnDevice)
        if (badge != null && !saidByTheActions) {
            Spacer(modifier = Modifier.height(2.dp))
            NeedlerStateBadge(badge = badge)
        }
        AlbumQualityTags(
            state = state,
            onOverrideQuality = onOverrideQuality,
            onClearQualityOverride = onClearQualityOverride,
        )
    }
}

/**
 * The quality tag pair, and the rung ladder behind the Server tag.
 *
 * `Server: MP3 192` beside `Device: FLAC`, with **whichever is in force drawn as in force**: a local
 * copy always wins, so a downloaded record dims the Server tag rather than showing the two as equals.
 * Showing them level would imply the server rate is what you would hear, which it is not.
 *
 * Only on the detail screen, never in a list row. Two reasons, and the second is the load-bearing one:
 * rows are already tight, and the Server tag is network-dependent, so the whole library would
 * re-label itself when the user left the house. Lists answer *what do I own*; this screen answers
 * *what will I get*.
 *
 * ## What each tag does
 *
 * Tapping **Server** opens the ladder and writes an override for this record, absolute across both
 * connections. Tapping the rung already in force clears it.
 *
 * Tapping **Device** does nothing, and it is not drawn as a control: the action area a few dp below
 * already offers "Pull to device" and "Remove from device" as labelled buttons, with a confirmation
 * on the destructive one. A second, unlabelled way to delete an album's audio is not an improvement.
 *
 * ## The cache-cliff line
 *
 * When the resolved format is a transcode, one line says that streaming this record will not leave a
 * copy behind - REQUIREMENTS.md "Why transcoded bytes are never cached". This is the screen where that
 * can be stated as a fact instead of a conditional: the record's own quality is known here, so the
 * answer is not "it depends which album". Settings carries the general form of the same warning.
 */
@Composable
private fun AlbumQualityTags(
    state: AlbumUiState,
    onOverrideQuality: ((StreamRung) -> Unit)?,
    onClearQualityOverride: (() -> Unit)?,
) {
    val serverValue: String? = state.serverTagValue
    val pulledValue: String? = state.pulledTagValue
    if (serverValue == null && pulledValue == null) return

    var pickerOpen: Boolean by remember { mutableStateOf(false) }
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography

    Spacer(modifier = Modifier.height(2.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pulledValue != null) {
            NeedlerQualityTag(
                label = NeedlerAlbumSource.Device.tagLabel(),
                value = pulledValue,
                source = NeedlerAlbumSource.Device,
                emphasis = NeedlerQualityTagEmphasis.Active,
                contentDescription = state.pulledTagDescription,
            )
        }
        if (serverValue != null) {
            NeedlerQualityTag(
                label = NeedlerAlbumSource.Server.tagLabel(),
                value = serverValue,
                source = NeedlerAlbumSource.Server,
                emphasis = if (state.isPulled) {
                    NeedlerQualityTagEmphasis.Dormant
                } else {
                    NeedlerQualityTagEmphasis.Active
                },
                onClick = if (onOverrideQuality == null) null else { { pickerOpen = !pickerOpen } },
                contentDescription = state.serverTagDescription,
            )
        }
    }
    val select: ((StreamRung) -> Unit)? = onOverrideQuality
    val cacheNotice: String? = state.serverCacheNotice
    if (cacheNotice != null) {
        Text(
            text = cacheNotice,
            style = typography.caption,
            color = colors.textMuted,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (pickerOpen && select != null) {
        Text(
            text = OVERRIDE_EXPLANATION,
            style = typography.caption,
            color = colors.textMuted,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StreamRung.entries.forEach { rung ->
                NeedlerPillButton(
                    text = rungLabel(rung),
                    onClick = {
                        select(rung)
                        pickerOpen = false
                    },
                    selected = rung == state.qualityOverride,
                    contentDescription = "Stream this album at " + rungLabel(rung),
                )
            }
            val clear: (() -> Unit)? = onClearQualityOverride
            if (clear != null && state.qualityOverride != null) {
                NeedlerPillButton(
                    text = "Use my setting",
                    onClick = {
                        clear()
                        pickerOpen = false
                    },
                    contentDescription = "Stop overriding this album and use the setting for this " +
                        "connection",
                )
            }
        }
    }
}

/**
 * The action block.
 *
 * Which buttons appear is read off [AlbumUiState.primaryAction], which is read
 * off `AlbumState.offeredActions` — so the set of actions the UI offers and the
 * set the domain says are legal cannot drift apart.
 *
 * ## The primary control is a transport control
 *
 * In the `PLAY` state its word, its glyph and its spoken description all come off
 * [AlbumUiState.transport]: Pause while this record is the loaded crate and playing, Resume while
 * it is the loaded crate and paused, Play otherwise. Nothing here decides which — this composable
 * renders the value and calls back, because REQUIREMENTS.md "The player boundary" allows a feature
 * module no view of the session at all: it "renders state and calls methods", and every fact about
 * what is loaded arrives through `PlaybackController` in domain types. `:feature:library` takes no
 * Media3 dependency to draw a pause icon.
 *
 * The shuffle control is relabelled from the same value rather than disabled. Why, and the two
 * alternatives rejected, are recorded on [AlbumTransport].
 *
 * ## Where the overflow sits
 *
 * The `PLAY` row reads Play, Shuffle, Pull to device, then the crate menu. The menu was third, between
 * Shuffle and Pull to device, and moving it to the end is deliberate: an overflow is not a peer of the
 * controls beside it. It is the place things go when they have nowhere else, so it belongs where
 * the eye stops rather than interrupting the run of named actions. Third, it also pushed Pull to
 * device — a real, named action that downloads a whole record onto the device — out past the
 * dots, where a named action reads as an afterthought. Last, the row is three actions and then the
 * place the rest of them are kept.
 *
 * Composition order is the only order. [FlowRow] places its children in the order they are
 * declared, and nothing in this row sets a `traversalIndex`, so a screen reader walks Play,
 * Shuffle, Pull to device, the menu — exactly the drawn sequence REQUIREMENTS.md "Accessibility"
 * requires it to match. The alternative, leaving the declarations alone and giving each control an
 * explicit traversal index, was rejected for making the spoken order and the drawn order two
 * separate facts that can drift: the next control added to the row would have to remember to
 * renumber the other four. `AlbumActionOrderTest` asserts the drawn order so that a later edit
 * cannot quietly put the dots back in the middle.
 */
@Composable
private fun AlbumActions(
    state: AlbumUiState,
    onPlayPause: () -> Unit,
    onShuffle: () -> Unit,
    onAddToCrate: (Boolean) -> Unit,
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
    // Every label below names the record, and `Album.title` can be blank - see
    // `LibraryFormat.albumLabel`. Resolved once here rather than guarded at each of
    // the six call sites, because the seventh is the one that gets forgotten.
    val label: String = LibraryFormat.albumLabel(album.title)
    var crateMenuOpen: Boolean by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.step5),
    ) {
        when (state.primaryAction) {
            AlbumPrimaryAction.PLAY -> {
                val pinned: Boolean = album.state is AlbumState.Pinned
                val transport: AlbumTransport = state.transport
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.step4),
                    verticalArrangement = Arrangement.spacedBy(spacing.step4),
                ) {
                    NeedlerPrimaryButton(
                        text = transport.primaryLabel,
                        onClick = onPlayPause,
                        size = NeedlerButtonSize.Medium,
                        // Pause and Resume stay reachable even if the mirror reports no playable
                        // track: sound you can hear must always be stoppable from the screen that
                        // started it, and a part-delivered album mid-sync can briefly have an empty
                        // track list while its own crate is playing.
                        enabled = !state.busy && (transport.isLoaded || state.hasPlayableTracks),
                        leadingIcon = { tint ->
                            NeedlerStrokeIcon(
                                pathData = if (transport == AlbumTransport.PAUSE) {
                                    PathPause
                                } else {
                                    PathPlay
                                },
                                tint = tint,
                                size = 18.dp,
                                filled = true,
                            )
                        },
                        contentDescription = transport.primaryDescription(label),
                    )
                    NeedlerSecondaryButton(
                        text = transport.shuffleLabel,
                        onClick = onShuffle,
                        size = NeedlerButtonSize.Medium,
                        enabled = !state.busy && state.hasPlayableTracks,
                        contentDescription = transport.shuffleDescription(label),
                    )
                    // REQUIREMENTS.md: hide the pin affordance entirely when the
                    // administrator has turned library download off, rather than
                    // letting it fail on a 403 after the tap.
                    if (state.downloadAllowed) {
                        NeedlerSecondaryButton(
                            text = if (pinned) "Device" else "Pull to device",
                            onClick = if (pinned) onRemoveFromDevice else onDownloadToDevice,
                            size = NeedlerButtonSize.Medium,
                            enabled = !state.busy,
                            selected = pinned,
                            reportSelection = true,
                            leadingIcon = { tint -> NeedlerOnDeviceIcon(tint = tint) },
                            contentDescription = if (pinned) {
                                "Device. Remove " + label + " from this device"
                            } else {
                                "Pull to device. Download " + label + " to this device"
                            },
                        )
                    }
                    // Last in the row, after every control that has a name. Why is on
                    // [AlbumActions] under "Where the overflow sits".
                    //
                    // Two actions behind one 48dp target, not two more buttons.
                    // REQUIREMENTS.md "Queue" gives the crate a count and a total duration and
                    // has it persist across restarts, and nothing on this screen could put a
                    // record into it: Play, Shuffle and every track row replace it. Append and
                    // play next are reachable nowhere else on this screen.
                    //
                    // Not reinstated as a top-bar overflow. The one that was removed held a
                    // single item duplicating the artist link a few dp below it; this holds two
                    // actions reachable nowhere else, which is the difference between a menu and
                    // a hidden link. Not five buttons across either: at 200% text the row
                    // already wraps, and a FlowRow of five makes Play one item in a list rather
                    // than the primary action.
                    NeedlerCrateControl(
                        subject = label,
                        expanded = crateMenuOpen,
                        onExpandedChange = { crateMenuOpen = it },
                        onAddToCrate = { onAddToCrate(false) },
                        onPlayNext = { onAddToCrate(true) },
                        enabled = !state.busy && state.hasPlayableTracks,
                        emphasised = true,
                        visualSize = 44.dp,
                    )
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
                    contentDescription = "Pull " + label,
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
                    contentDescription = "Cancel the pull of " + label,
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
                    contentDescription = "Retry the pull of " + label,
                )
            }

            AlbumPrimaryAction.NONE -> Unit
        }
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

/**
 * The result of the last action, dismissible.
 *
 * @param detail a second line under the message, for a figure the message refers to
 *   rather than states - today the crate's count and total duration after an add. It
 *   is part of the spoken reading too, which is the point: REQUIREMENTS.md
 *   "Accessibility" makes the description what a TalkBack user acts on, and an "added
 *   to the crate" that did not say how big the crate now is would be the same
 *   un-confirmable tap for them that a silent screen is for everyone else.
 */
@Composable
private fun NoticeCard(notice: AlbumNotice, detail: String?, onDismiss: () -> Unit) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    val tint = if (notice.isProblem) colors.destructive else colors.positive
    val spoken: String = if (detail == null) {
        notice.message
    } else {
        notice.message + " " + detail.replace(" · ", ", ")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, tint, shape)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Assertive
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = notice.message,
                style = NeedlerTheme.typography.caption,
                color = colors.textSecondary,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = NeedlerTheme.typography.caption,
                    color = colors.textMuted,
                )
            }
        }
        NeedlerIconButton(
            contentDescription = "Dismiss",
            onClick = onDismiss,
            visualSize = 36.dp,
        ) {
            NeedlerStrokeIcon(pathData = PathClose, tint = colors.textMuted, size = 16.dp)
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

/**
 * What the server will do, under the Pull button.
 *
 * One string, shared with the sheet the button opens: it is
 * `NeedlerRequestSheet`'s [PULL_SHEET_EXPLANATION], not a second copy of the same
 * sentence. Two copies would have drifted the first time either was reworded, and
 * the button and the sheet it opens saying slightly different things about the
 * same request is worse than either wording.
 */
internal const val PULL_EXPLANATION: String = PULL_SHEET_EXPLANATION

internal const val WAITING_EXPLANATION: String =
    "Waiting for an administrator to approve this pull. Nothing is downloading yet, and there " +
        "is nothing more to do here."

internal const val DOWNLOAD_DISABLED: String =
    "Downloading to this device is turned off for your account on this server. Streaming and " +
        "playback are unaffected."


/**
 * What the rung ladder does, said before it is used rather than discovered afterwards.
 *
 * Three facts that are not guessable from the chips, in one line: the choice follows this record,
 * it follows it onto every connection, and it takes hold on each track's **next** play rather than
 * the one in progress. The timing is the fact the first draft omitted and a device run caught -
 * `resolveSource` runs inside `NeedlerAudioDataSource.open()`, so a rung is read once per load and
 * an item the player has already prepared keeps the stream it was opened with. The quality tag
 * moves at once, which made the control look instant while the audio did not change.
 *
 * ## Why the retention caveat is not repeated here
 *
 * It used to be a second sentence, and it said the same thing as [AlbumUiState.serverCacheNotice]
 * two lines above - which states it *only* when a transcode is actually in force and the album is
 * not already pulled. Saying it twice when it applies, and once when it does not, is how the block
 * became crowded. The conditional line keeps REQUIREMENTS.md "Why transcoded bytes are never
 * cached" said where it is true; this one no longer competes with it.
 */
private const val OVERRIDE_EXPLANATION: String =
    "This album, every connection, from each track's next play."

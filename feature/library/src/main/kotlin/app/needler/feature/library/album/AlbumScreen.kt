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
import app.needler.core.design.component.NeedlerCrateControl
import app.needler.core.design.component.NeedlerFavouriteButton
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerLinearProgress
import app.needler.core.design.component.NeedlerMoreIcon
import app.needler.core.design.component.NeedlerOnDeviceIcon
import app.needler.core.design.component.NeedlerPillButton
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerQualityTag
import app.needler.core.design.component.NeedlerRowLayout
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
import app.needler.core.domain.model.AlbumAction
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.StreamRung
import app.needler.feature.library.common.AlbumArtwork
import app.needler.feature.library.common.LibraryFormat
import app.needler.feature.library.common.RequestSheet
import app.needler.feature.library.common.RequestSheetState
import app.needler.feature.library.common.albumBadge
import app.needler.feature.library.common.failureExplanation
import app.needler.feature.library.common.favouriteContentDescription
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
 * | `Pinned` | The same, plus Stop while bytes arrive and Remove from device once they have |
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
    onStopDownload: () -> Unit,
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
    // Defaulted so a preview or a bounds test still compiles. `AlbumRoute` passes
    // `AlbumViewModel::reload`, which is the only thing the not-found screen's Try again can do.
    onReload: () -> Unit = {},
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
                state.notFound -> AlbumNotFound(
                    gutter = gutter,
                    busy = state.busy,
                    onReload = onReload,
                    onBack = onBack,
                )
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
                    onStopDownload = onStopDownload,
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
                    onStopDownload = onStopDownload,
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
    onStopDownload: () -> Unit,
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
                    onStopDownload = onStopDownload,
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
 *
 * ## Why the hero column is a share of the pane and not 280dp
 *
 * It was `Modifier.width(HERO_COLUMN_WIDTH)`, a fixed 280dp transcribed from screen 11. On
 * `album-owned-tablet.png` and three others the action row then wrapped inside that column - Play,
 * Shuffle, "Pull to device" and the overflow do not fit across 280dp - while about 1000px of empty
 * pane sat beside it. The artist screen gets this right with the same `FlowRow` of the same buttons,
 * because it lays them out across the whole pane; one component, two widths, two results.
 *
 * So the column takes a **share** of the pane instead. On a 1280dp tablet [HERO_COLUMN_FRACTION]
 * gives it about 530dp, which fits the row on one line with room for the labels to grow at larger
 * text sizes, and the track list keeps the larger share because it is the list.
 *
 * The artwork does **not** grow with it. It is capped at the pack's own 280dp, so the hero still
 * looks like screen 11; what the extra width buys is the action row and the title block beneath it.
 * Rejected: letting the artwork fill the column, which on a wide tablet is a 530dp album cover and
 * pushes Play below the fold - the one thing this layout exists to prevent.
 *
 * Rejected: moving the actions into the right-hand pane, above the track list. That reads as a
 * toolbar over the list rather than as the record's own controls, and it separates them from the
 * title and the quality tags they are about.
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
    onStopDownload: () -> Unit,
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
                .weight(HERO_COLUMN_FRACTION)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.step12),
        ) {
            AlbumArtwork(
                album = album,
                modifier = Modifier
                    .widthIn(max = HERO_ARTWORK_MAX)
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
                onStopDownload = onStopDownload,
            )
            state.notice?.let { notice ->
                NoticeCard(
                    notice = notice,
                    detail = if (notice is AlbumNotice.AddedToCrate) state.crateLine else null,
                    onDismiss = onDismissNotice,
                )
            }
            // No PartialDeliveryNote here. It explains the greyed rows and their Retry pills, and
            // on a tablet those are in the other pane - up to 1000px away, with the artwork between
            // them. A reader who found a greyed track had no reason to look left for the sentence
            // saying why, so it now sits at the head of the list it is about. See [trackRows].
            Spacer(modifier = Modifier.height(spacing.step12))
        }
        LazyColumn(
            modifier = Modifier.weight(TRACK_COLUMN_FRACTION),
            contentPadding = PaddingValues(bottom = spacing.step12),
        ) {
            if (state.isPartiallyDelivered) {
                item(key = "partial-note") {
                    PartialDeliveryNote(state)
                    Spacer(modifier = Modifier.height(NeedlerTheme.spacing.step5))
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
                    // Scaled with the text. `NeedlerRowLayout.controlSize` is what the component's
                    // own default applies; passing a raw 36dp was what stopped these two growing
                    // while the track title beside them doubled.
                    visualSize = NeedlerRowLayout.controlSize(36.dp),
                )
                NeedlerFavouriteButton(
                    isFavourite = row.track.isFavourite,
                    contentDescription = favouriteContentDescription(
                        isFavourite = row.track.isFavourite,
                        name = LibraryFormat.trackLabel(row.track.title),
                    ),
                    onToggle = { onToggleTrackFavourite(row) },
                    visualSize = NeedlerRowLayout.controlSize(36.dp),
                    glyphSize = NeedlerRowLayout.controlSize(18.dp),
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
            // A track a part-delivered pull never brought. Greyed, not tappable, carrying its own
            // retry — the only way to ask the server for the one track that is missing — and now
            // its own star.
            //
            // ## Why the star came back
            //
            // These rows had neither a star nor a crate control, on the rule stated two branches
            // up: starring goes out as Subsonic `star` on a track id, and a row with no file has
            // nothing to send. That rule is right for an **un-owned** album, whose track list is
            // catalogue metadata with no server-side rows at all. It is wrong here. A
            // part-delivered pull is `Owned`; REQUIREMENTS.md "Partial content is a normal state"
            // has these tracks "listed in their right positions" *in the library*, so the server
            // has a row for each of them and `star` has an id to carry. On
            // `album-partial-phone.png` the effect was that two tracks of a fourteen-track record
            // could not be favourited while the other twelve could, for a reason no user could
            // infer.
            //
            // The crate control stays off. Appending a track with no file behind it would queue
            // something unplayable, which is a different thing from recording that you like it.
            //
            // ## Why the 8dp went
            //
            // `Arrangement.spacedBy(8.dp)` was 8dp the playable rows do not have, so every greyed
            // row's duration column sat 8dp left of every other row's - a visible step down the
            // list, on the screen whose whole point is that the missing tracks are in their right
            // positions. Exact alignment also wants the trailing controls to measure the same,
            // which needs `NeedlerTrackRow`'s own column rule; reported.
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                NeedlerFavouriteButton(
                    isFavourite = row.track.isFavourite,
                    contentDescription = favouriteContentDescription(
                        isFavourite = row.track.isFavourite,
                        name = LibraryFormat.trackLabel(row.track.title),
                    ),
                    onToggle = { onToggleTrackFavourite(row) },
                    visualSize = NeedlerRowLayout.controlSize(36.dp),
                    glyphSize = NeedlerRowLayout.controlSize(18.dp),
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
                NeedlerFavouriteButton(
                    isFavourite = album.isFavourite,
                    contentDescription = favouriteContentDescription(
                        isFavourite = album.isFavourite,
                        name = LibraryFormat.albumLabel(album.title),
                    ),
                    onToggle = onToggleFavourite,
                    enabled = !busy,
                    // No size overrides: 44dp and 22dp are this component's own defaults written
                    // out, and the defaults now scale with the text. Passing the raw figures was
                    // the only thing that could stop it.
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
        // An artist the record actually names, and an id to open them by. Both, or neither.
        //
        // `album-untitled-phone.png` drew **"Unknown artist"** in the accent blue, with a 48dp
        // target under it, for a release group the catalogue sent no title and no credit for. The
        // link was live - `artistMbid` can be non-null while `artistName` is blank, because the two
        // come off different columns - so the one affordance on that header opened an artist screen
        // for an artist nobody can name. `LibraryFormat.artistName` exists to stop a blank line
        // being drawn; it was never meant to make a placeholder tappable.
        //
        // Rejected: keep the link and let the destination say "Unknown artist" too. It already
        // does, and that is the dead end `ArtistUiState.notFound` was rewritten to stop being -
        // sending the user there deliberately is not an improvement on not offering it.
        val linkable: Boolean = album.artistMbid != null && album.artistName.isNotBlank()
        Text(
            text = LibraryFormat.artistName(album.artistName),
            style = typography.bodyStrong,
            // Accent is this app's one signal for "you may tap this". A placeholder that cannot be
            // tapped must not wear it.
            color = if (linkable) colors.accent else colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .then(
                    if (linkable) {
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
                    if (linkable) {
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
        // Only the states some other node on this header already names in words.
        //
        // ## What the old condition got wrong
        //
        // It suppressed the badge whenever the album was owned, on the argument that the action row
        // said it: *"an owned album's buttons read Play and Pull to device, and a pinned one's read
        // Device."* Two problems. The pinned button no longer says "Device" - it says **Remove from
        // device**, because a state word on an action is not an action, see [AlbumActions] - so the
        // argument is gone. And for the plain **Server** state it was never true: Play and "Pull to
        // device" are offered on a record that is on the server, and neither of them is the word
        // "Server". `album-owned-phone.png` is the result - a header reading "2024 · 8 tracks ·
        // 28 min · FLAC" and nothing else, so there was no visible difference between a record on
        // the server and one that is **Not retrieved**. The two states that matter most on this
        // screen were drawn identically.
        //
        // ## What suppresses it now
        //
        // The quality tags, and only when they are actually drawn. `Device: FLAC` and the active
        // `Server: FLAC` each spell the state out beside its format; a badge above them would say
        // one word twice in one eyeful. But [AlbumUiState.serverTagValue] is null until the
        // resolver has answered, and [AlbumUiState.pulledTagValue] is null unless the record is
        // fully downloaded - which is exactly why the owned header could end up saying nothing.
        // The badge now fills that gap instead of assuming it away.
        //
        // A download in flight is still drawn, and it is why the full-width progress banner that
        // used to sit under the actions could go. The buttons say where the record will end up, not
        // that bytes are arriving now, so the badge is the only place that is said - and being
        // derived from the pin row on every emission, it clears itself on success, failure,
        // cancellation and a backgrounded app alike.
        val badge = albumBadge(album.state)
        val saidByTheQualityTags: Boolean = when (badge) {
            NeedlerAlbumBadge.OnDevice -> state.pulledTagValue != null
            NeedlerAlbumBadge.InLibrary -> state.serverTagValue != null && !state.isPulled
            else -> false
        }
        if (badge != null && !saidByTheQualityTags) {
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
 * ## The third slot has three faces
 *
 * One control, three states of the same record, because they are three answers to one question -
 * what can be done about this record and this device:
 *
 * | State | Control | What it does |
 * | --- | --- | --- |
 * | `Owned` | **Pull to device** | starts the download |
 * | `Pinned`, download in flight | **Stop** | cancels the job and keeps what landed |
 * | `Pinned`, download resting | **Device** | removes it and reports what that freed |
 *
 * **Stop replaces Remove while a download runs; it never sits beside it.** The row before this drew
 * the Device control in every pinned state, so the only action offered during a download was
 * "Device. Remove … from this device", which deletes - observed on a device under a badge reading
 * "Pulling to device, 31 percent, playing now". REQUIREMENTS.md "The download in flight is a badge,
 * not a banner" is explicit that these are two actions with two outcomes and that "the album offers
 * whichever one can still apply", and `AlbumState.Pinned.offeredActions` agrees: `CANCEL` while the
 * download is in flight, `REMOVE_FROM_DEVICE` once it rests, never both. Offering both would put a
 * delete and a stop side by side under one badge, a thumb's width apart, with only the words to
 * tell them apart - and it would be the UI disagreeing with the domain about what is legal.
 *
 * Stop is deliberately plain: no device glyph, no positive green, no selected state. All three of
 * those say "this record is on the device", which is the thing a download in flight has not finished
 * doing. It is the same plain secondary control the `ACQUIRING` arm offers for stopping the
 * server's acquisition, which is the other transition the user can start and therefore has to be
 * able to end.
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
    onStopDownload: () -> Unit,
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
                // Read off the domain rather than from `state.download`, because
                // `AlbumState.offeredActions` is the one place that decides which of Stop and
                // Remove can still apply, and it is what the widgets and the car tree read as well.
                val stoppable: Boolean = AlbumAction.CANCEL in album.offeredActions
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
                        if (stoppable) {
                            // Stop, in the slot Remove would otherwise hold - never beside it.
                            // Why is on [AlbumActions] under "The third slot has three faces".
                            NeedlerSecondaryButton(
                                text = "Stop",
                                onClick = onStopDownload,
                                size = NeedlerButtonSize.Medium,
                                enabled = !state.busy,
                                // No device glyph, no green, and not reported as a selection: all
                                // three say "this record is here", and the point of this control is
                                // that it is still arriving. What is left is a plain secondary
                                // action, which is what the `Acquiring` arm's own stop control is.
                                contentDescription = "Stop downloading " + label + " to this device",
                            )
                        } else {
                            // Remove, not "Device".
                            //
                            // The button read **Device** with the phone-and-check glyph, drawn as
                            // selected in the positive green, directly below a quality tag already
                            // saying `Device: FLAC`. So the third slot stopped being an action: it
                            // was a state word in the row of verbs, reporting a fact the header had
                            // just reported, and the one thing it actually did - delete every byte
                            // of this record from the phone - was named nowhere on the screen. A
                            // user looking for "Remove from device" could not find it, and a user
                            // reading the row had no reason to think the green badge-looking thing
                            // was a control at all, let alone a destructive one.
                            //
                            // Selection goes with it. `selected` paints the control in the
                            // on-device green and `reportSelection` has TalkBack announce it as
                            // selected; a destructive action is not a toggle that happens to be on.
                            // The glyph goes too - a phone-and-check on a button that empties the
                            // phone is the same error as the download arrow that used to sit on
                            // Retry.
                            //
                            // Rejected: "Remove". The screen holds two removals one tap apart - the
                            // crate and the device - and REQUIREMENTS.md "Offline and caching" is
                            // explicit that this one deletes bytes. The destination is the whole
                            // point of the sentence.
                            NeedlerSecondaryButton(
                                text = if (pinned) "Remove from device" else PULL_TO_DEVICE_LABEL,
                                onClick = if (pinned) onRemoveFromDevice else onDownloadToDevice,
                                size = NeedlerButtonSize.Medium,
                                enabled = !state.busy,
                                leadingIcon = if (pinned) {
                                    null
                                } else {
                                    { tint -> NeedlerOnDeviceIcon(tint = tint) }
                                },
                                contentDescription = if (pinned) {
                                    "Remove " + label + " from this device"
                                } else {
                                    PULL_TO_DEVICE_LABEL + ". Download " + label +
                                        " to this device"
                                },
                            )
                        }
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
                        visualSize = NeedlerRowLayout.controlSize(44.dp),
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
                    // One of the two labels this verb is allowed - see [PULL_LABEL]. "Pull this
                    // album" was a third, on a button that is the full width of a screen headed by
                    // the album's own title and artwork.
                    text = PULL_LABEL,
                    onClick = onPull,
                    modifier = Modifier.fillMaxWidth(),
                    // Accent, not the pack's positive green.
                    //
                    // `album-not-owned-phone.png` drew a full-width **green** button for a record
                    // that is **not** on the device, and green is the one colour in this palette
                    // that means it is: REQUIREMENTS.md "Design system" lists the positive green's
                    // uses as progress, Ready, the on-device check and the FLAC badge. The screen
                    // was painting "you do not have this" in the colour for "you do".
                    //
                    // It also made one verb two colours. The row pills on the artist screen and in
                    // search are `NeedlerPullButton`, which is accent, so `artist-phone.png` drew a
                    // green "Pull all" above a column of blue "Pull" pills.
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
                        // Accent, to match the badge above it.
                        //
                        // `NeedlerLinearProgress` defaults to the positive green, and
                        // `NeedlerAlbumBadge.Pulling` is accent - deliberately, for the reason its
                        // own KDoc gives: a record on its way to the *server* is the server's
                        // colour for the whole journey. So `album-acquiring-phone.png` drew a blue
                        // "Pulling 62%" directly above a green bar measuring the same percentage.
                        // One event, two hues, 2dp apart.
                        color = colors.accent,
                        contentDescription = "Pulling, " +
                            (fraction * 100f).toInt() + " percent complete",
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                NeedlerSecondaryButton(
                    text = "Stop",
                    onClick = onCancelPull,
                    size = NeedlerButtonSize.Medium,
                    enabled = !state.busy,
                    contentDescription = "Stop the pull of " + label,
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
                // No glyph. `album-failed-phone.png` put `PathPull` - a downward arrow onto a
                // baseline, the icon this app uses for "download this" - on a button whose job is
                // to ask the server to look for a source again. Nothing is downloading when it is
                // pressed and nothing may ever; the arrow promised an outcome the button cannot
                // deliver. The icon set has no retry glyph (`Icons.kt` holds check, pull, play,
                // pause, chevrons, close and a clock), and inventing one in a feature module is
                // the wrong place for it, so the word stands alone - which is what the pack's own
                // per-track Retry pill does two inches below.
                NeedlerPrimaryButton(
                    text = "Retry",
                    onClick = onRetryPull,
                    size = NeedlerButtonSize.Medium,
                    enabled = !state.busy,
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
 * The result of the last action, dismissible. **Both detail screens draw this one.**
 *
 * ## Why it is shared rather than copied
 *
 * The artist screen had its own `NoticeLine` for the same job: a grey hairline card with no dismiss
 * control, against this one's coloured outline and close button. So "Added 12 tracks to the crate" -
 * the single piece of feedback in the app that fires identically from both screens - looked like two
 * different features depending on which screen the user had been on, and on one of them it could
 * not be got rid of at all. `ArtistViewModel.onDismissNotice` had existed the whole time with
 * nothing calling it.
 *
 * `NoticeLine` stays on the artist screen for what it is actually for: the standing explanatory
 * lines - the catalogue is unavailable, this list may be short, that is the whole discography. Those
 * are not results of an action and there is nothing to dismiss about them.
 *
 * Rejected: promoting this to `:core:design`. It takes an [AlbumNotice], which is a
 * `:feature:library` type, and the only two screens that draw one are in the same package pair.
 *
 * ## The outline is three-valued
 *
 * See [AlbumNoticeTone]. It was two - destructive red or positive green - which painted five
 * deferrals as successes, `album-queued-offline-phone.png` being the one the audit named.
 *
 * @param detail a second line under the message, for a figure the message refers to
 *   rather than states - today the crate's count and total duration after an add. It
 *   is part of the spoken reading too, which is the point: REQUIREMENTS.md
 *   "Accessibility" makes the description what a TalkBack user acts on, and an "added
 *   to the crate" that did not say how big the crate now is would be the same
 *   un-confirmable tap for them that a silent screen is for everyone else.
 */
@Composable
internal fun NoticeCard(notice: AlbumNotice, detail: String?, onDismiss: () -> Unit) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    val tint = when (notice.tone) {
        AlbumNoticeTone.Problem -> colors.destructive
        AlbumNoticeTone.Done -> colors.positive
        AlbumNoticeTone.Pending -> colors.hairline
    }
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

/**
 * What is coming, drawn in the shape it will arrive in.
 *
 * ## What it used to mis-describe
 *
 * `album-loading-phone.png` drew the artwork, two text bars and six list rows, and **no action
 * row** - although every loaded state of this screen has one: Play, Shuffle, the third slot and the
 * overflow, or a full-width Pull, or a Retry. So the track list was drawn about 190px higher than it
 * would be a moment later, and the content under the reader's thumb moved the instant the mirror
 * answered. A skeleton that is the wrong shape is not a kindness; it is a layout prediction that is
 * wrong, and it is paid for in mis-taps.
 *
 * The four pills are the `PLAY` row, which is the state this screen is in for everything a user
 * already owns. A `PULL` album gets a single full-width button instead and will still move slightly;
 * reserving for both is not possible before the mirror has said which it is, and the common case is
 * the one to be right about.
 */
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
        // The action row, at the height `NeedlerButtonSize.Medium` draws, so the track list below
        // starts where it will stay.
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.step4)) {
            listOf(78.dp, 96.dp, 128.dp, 44.dp).forEach { width ->
                Box(
                    modifier = Modifier
                        .width(width)
                        .height(MEDIUM_BUTTON_HEIGHT)
                        .clip(NeedlerTheme.shapes.pill)
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

/**
 * The mirror has no row for this release group.
 *
 * ## It is no longer a dead end, and it no longer leaks
 *
 * `album-not-found-phone.png` was a heading, two grey lines and about 1,500px of black. No retry, no
 * search, nothing in the body that went anywhere; the only control was the chevron in the top bar.
 * It also said *"It is not in the mirror on this device"* - "the mirror" is this project's internal
 * name for the local database, it appears in REQUIREMENTS.md and in no part of the product a user
 * sees, and a reader who does not know the word is told nothing by the sentence that contains it.
 *
 * Both controls earn their place. **Try again** re-runs [AlbumViewModel.reload], which is the same
 * `refreshAlbum` the screen runs on open - the honest answer when the likely cause is a sync that
 * has not landed. **Go back** is the chevron said in words, in the place the reader is already
 * looking.
 *
 * Rejected: a Pull. Pulling needs an `Album` to build the request from and this screen has none -
 * that is what being not-found means - so the button would be disabled or would fail after the tap.
 *
 * @param onReload asks the mirror for this album again.
 */
@Composable
private fun AlbumNotFound(
    gutter: Dp,
    busy: Boolean,
    onReload: () -> Unit,
    onBack: () -> Unit,
) {
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
            text = ALBUM_NOT_FOUND_REASON,
            style = typography.body,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(NeedlerTheme.spacing.step5))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step4),
            verticalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step4),
        ) {
            NeedlerPrimaryButton(
                text = "Try again",
                onClick = onReload,
                size = NeedlerButtonSize.Medium,
                enabled = !busy,
                contentDescription = "Ask your server for this album again",
            )
            NeedlerSecondaryButton(
                text = "Go back",
                onClick = onBack,
                size = NeedlerButtonSize.Medium,
                contentDescription = "Go back to where you came from",
            )
        }
    }
}

/**
 * Why an album the app navigated to is not there.
 *
 * It says what happened in words a listener owns. "The mirror" was the project's word for the local
 * database; "your library on this device" is the same fact in the vocabulary the rest of the app
 * uses, and the two causes - a sync that has not caught up, a removal on the server - are the two
 * the retry beside it does and does not help with, in that order.
 */
internal const val ALBUM_NOT_FOUND_REASON: String =
    "It is not in your library on this device. A sync may not have caught up with it yet, or it " +
        "was removed from the server."

/**
 * What `NeedlerButtonSize.Medium` draws, for the skeleton to reserve.
 *
 * Transcribed rather than read from the theme because the figure is on the enum and not in
 * `NeedlerSizes`. The pack's own number, from `:core:design`'s `NeedlerButtonSize.Medium`: "52dp
 * minimum, 14dp radius". A skeleton that reserved a guess would be the defect it exists to fix.
 */
internal val MEDIUM_BUTTON_HEIGHT: Dp = 52.dp

/**
 * The artwork-and-actions column's share of a tablet pane.
 *
 * Screen 11 draws it at 280dp and that is what it was, fixed. See [TabletAlbum] for the action row
 * that wrapped inside it with a thousand pixels going spare beside it. 0.42 against 0.58 keeps the
 * list the larger half while giving the controls a line they fit on.
 */
private const val HERO_COLUMN_FRACTION: Float = 0.42f

/** The track list's share. The two are written out rather than `1f - the other`, so they are read. */
private const val TRACK_COLUMN_FRACTION: Float = 0.58f

/**
 * The artwork's ceiling: screen 11's own 280dp.
 *
 * The column is wider than this now and the artwork deliberately is not. A cover scaled to a
 * 530dp column pushes the title, the quality tags and Play below the fold, which is the thing the
 * left column's own scroll exists to prevent.
 */
private val HERO_ARTWORK_MAX: Dp = 280.dp

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

/**
 * The verb, for everything the **server** is asked to acquire.
 *
 * ## Five labels, one verb
 *
 * The audit counted "Pull to device", "Pull all", "Pull this album", "Pull" and "Pull 3" across the
 * two screens for what are only ever two actions: fetch a record onto the server, and copy a record
 * the server has onto this phone. Five words for two things is five things to learn.
 *
 * So there are two labels, and this is the first. It is what the row pills in search and on the
 * artist screen have always said - `NeedlerPullButton` draws exactly "Pull" - so the detail screens
 * now agree with the rows they were reached from rather than each phrasing it afresh.
 *
 * The specifics have not been lost; they have moved to where they can be exact. "Pull this album"
 * sat under the album's own artwork and title, and its spoken description still names the record.
 * "Pull all" became "Pull" with a description that counts the release groups it will ask for.
 *
 * `"Pull 3"` is the request sheet's confirm button, in `:feature:library`'s `common` package, and is
 * not this agent's to change; it is reported instead.
 */
internal const val PULL_LABEL: String = "Pull"

/** The second label: for copying a record the server already has onto **this device**. */
internal const val PULL_TO_DEVICE_LABEL: String = "Pull to device"

/**
 * Why the third action slot is missing.
 *
 * The verb is **Pull**, here as everywhere else. It read "Downloading to this device is turned off",
 * which is a sixth word for the one action whose button two lines above says
 * [PULL_TO_DEVICE_LABEL] - and the one place a user needs the two to match is the sentence
 * explaining why the button is not there.
 */
internal const val DOWNLOAD_DISABLED: String =
    "Pulling to this device is turned off for your account on this server. Streaming and " +
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

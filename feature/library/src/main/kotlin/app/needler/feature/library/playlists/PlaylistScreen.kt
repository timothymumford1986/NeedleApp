@file:OptIn(ExperimentalLayoutApi::class)

package app.needler.feature.library.playlists

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerAlbumRow
import app.needler.core.design.component.NeedlerArtwork
import app.needler.core.design.component.NeedlerButtonSize
import app.needler.core.design.component.NeedlerCheckIcon
import app.needler.core.design.component.NeedlerHairline
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerMoreIcon
import app.needler.core.design.component.NeedlerNowPlayingIcon
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerSearchField
import app.needler.core.design.component.NeedlerSecondaryButton
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.NeedlerTextButton
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.component.PathPlay
import app.needler.core.design.component.albumArtContentDescription
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Playlist
import app.needler.core.domain.model.Track
import app.needler.feature.library.common.LibraryFormat

/**
 * One playlist: its header, its actions and its tracks, editable in place.
 *
 * Laid out as album detail (04 on a phone, 11 on a tablet) with the album header
 * replaced. That is not laziness about a screen the pack does not draw: a
 * playlist is the same shape of thing — artwork, a name, a line of metadata, a
 * block of actions, a numbered list — and giving it a second layout would teach
 * the user two screens where the app has one idea.
 *
 * ## Reordering is reachable without a drag
 *
 * REQUIREMENTS.md "Accessibility": "TalkBack must reach the crate's reordering
 * through an accessible action, not only by dragging." Here reordering is *only*
 * accessible actions and menu items — Move up and Move down, on every row, in the
 * overflow menu and as custom accessibility actions on the row itself. There is no
 * drag handle, for two reasons:
 *
 *  * the crate's drag gesture lives in `:feature:player`, and a feature module
 *    may not depend on another feature module (REQUIREMENTS.md "Modules"), so
 *    reusing it would mean copying 120 lines of pointer maths into a second place
 *    where it could then drift;
 *  * every move here is a *whole-playlist* write to the server — the protocol has
 *    no move, only `createPlaylist` with a new order — so a continuous drag would
 *    fire one write per row crossed. One tap is one write.
 *
 * The rejected alternative is recorded rather than hidden: a drag handle would be
 * the more familiar gesture, and it belongs here if and when the reorder gesture
 * moves into `:core:design` beside `NeedlerQueueRow`, which already supplies the
 * accessible half of the same problem.
 *
 * ## No star ratings, anywhere
 *
 * REQUIREMENTS.md "Playlists": `setRating` validates and persists nothing on this
 * server, so a star control would be a lie the user could tap. Favourites are
 * binary and live elsewhere.
 */
@Composable
fun PlaylistScreen(
    state: PlaylistUiState,
    widthSizeClass: WindowWidthSizeClass,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAddToCrate: () -> Unit,
    onPlayTrack: (PlaylistTrack) -> Unit,
    onRemoveTrack: (PlaylistTrack) -> Unit,
    onMoveUp: (PlaylistTrack) -> Unit,
    onMoveDown: (PlaylistTrack) -> Unit,
    onRetryTrack: (PlaylistTrack) -> Unit,
    onAddTracksClick: () -> Unit,
    onPickerQueryChange: (String) -> Unit,
    onToggleCandidate: (Track) -> Unit,
    onAddSelected: () -> Unit,
    onPickerCancel: () -> Unit,
    onRenameClick: () -> Unit,
    onRenameNameChange: (String) -> Unit,
    onRenameConfirm: () -> Unit,
    onRenameCancel: () -> Unit,
    onDeleteClick: () -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteCancel: () -> Unit,
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
        // With the picker open, back means "leave the picker", not "leave the
        // playlist": the picker is a step inside this screen, and a back gesture
        // that skipped past it would throw away a selection the user has just made.
        PlaylistTopBar(
            canEdit = state.playlist != null && state.picker == null,
            busy = state.busy,
            onBack = if (state.picker == null) onBack else onPickerCancel,
            onAddTracksClick = onAddTracksClick,
            onRenameClick = onRenameClick,
            onDeleteClick = onDeleteClick,
            gutter = gutter,
        )

        val picker: PlaylistTrackPicker? = state.picker
        // Bounded to what the top bar left, rather than each branch filling the
        // whole screen: the picker lays out a search field above a list, and a list
        // measured against the full height would hang its last rows off the bottom.
        Box(modifier = Modifier.weight(1f)) {
            when {
                picker != null -> TrackPickerPane(
                    picker = picker,
                    busy = state.busy,
                    gutter = gutter,
                    onQueryChange = onPickerQueryChange,
                    onToggle = onToggleCandidate,
                    onConfirm = onAddSelected,
                    onCancel = onPickerCancel,
                )

                state.loading -> PlaylistSkeleton(gutter = gutter)
                state.notFound -> PlaylistNotFound(gutter = gutter, onBack = onBack)
                wide -> TabletPlaylist(
                    state = state,
                    gutter = gutter,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    onAddToCrate = onAddToCrate,
                    onPlayTrack = onPlayTrack,
                    onRemoveTrack = onRemoveTrack,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onRetryTrack = onRetryTrack,
                    onAddTracksClick = onAddTracksClick,
                    onRenameNameChange = onRenameNameChange,
                    onRenameConfirm = onRenameConfirm,
                    onRenameCancel = onRenameCancel,
                    onDeleteConfirm = onDeleteConfirm,
                    onDeleteCancel = onDeleteCancel,
                    onDismissNotice = onDismissNotice,
                )

                else -> PhonePlaylist(
                    state = state,
                    gutter = gutter,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    onAddToCrate = onAddToCrate,
                    onPlayTrack = onPlayTrack,
                    onRemoveTrack = onRemoveTrack,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onRetryTrack = onRetryTrack,
                    onAddTracksClick = onAddTracksClick,
                    onRenameNameChange = onRenameNameChange,
                    onRenameConfirm = onRenameConfirm,
                    onRenameCancel = onRenameCancel,
                    onDeleteConfirm = onDeleteConfirm,
                    onDeleteCancel = onDeleteCancel,
                    onDismissNotice = onDismissNotice,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Phone
// ---------------------------------------------------------------------------

@Composable
private fun PhonePlaylist(
    state: PlaylistUiState,
    gutter: Dp,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAddToCrate: () -> Unit,
    onPlayTrack: (PlaylistTrack) -> Unit,
    onRemoveTrack: (PlaylistTrack) -> Unit,
    onMoveUp: (PlaylistTrack) -> Unit,
    onMoveDown: (PlaylistTrack) -> Unit,
    onRetryTrack: (PlaylistTrack) -> Unit,
    onAddTracksClick: () -> Unit,
    onRenameNameChange: (String) -> Unit,
    onRenameConfirm: () -> Unit,
    onRenameCancel: () -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteCancel: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val playlist: Playlist = state.playlist ?: return
    val spacing = NeedlerTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = spacing.step12),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.step11)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.step8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistArtwork(
                        playlist = playlist,
                        modifier = Modifier.size(NeedlerTheme.sizes.artworkDetail),
                        shape = NeedlerTheme.shapes.artworkDetail,
                    )
                    PlaylistTitleBlock(state = state, modifier = Modifier.weight(1f))
                }
                PlaylistActions(
                    state = state,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    onAddToCrate = onAddToCrate,
                )
                PlaylistCardStack(
                    state = state,
                    onRenameNameChange = onRenameNameChange,
                    onRenameConfirm = onRenameConfirm,
                    onRenameCancel = onRenameCancel,
                    onDeleteConfirm = onDeleteConfirm,
                    onDeleteCancel = onDeleteCancel,
                    onDismissNotice = onDismissNotice,
                )
                Spacer(modifier = Modifier.height(spacing.step2))
            }
        }
        entryRows(
            state = state,
            onPlayTrack = onPlayTrack,
            onRemoveTrack = onRemoveTrack,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onRetryTrack = onRetryTrack,
        )
        if (state.isEmpty) {
            item(key = "empty") { PlaylistEmptyTracks(onAddTracks = onAddTracksClick) }
        }
    }
}

// ---------------------------------------------------------------------------
// Tablet
// ---------------------------------------------------------------------------

/**
 * Screen 11's arrangement: artwork, name and actions in a left column that
 * scrolls on its own, the tracks beside them.
 *
 * The left column scrolls separately so that Play never leaves the screen on a
 * long playlist — on a tablet it is the one control that must always be there.
 */
@Composable
private fun TabletPlaylist(
    state: PlaylistUiState,
    gutter: Dp,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAddToCrate: () -> Unit,
    onPlayTrack: (PlaylistTrack) -> Unit,
    onRemoveTrack: (PlaylistTrack) -> Unit,
    onMoveUp: (PlaylistTrack) -> Unit,
    onMoveDown: (PlaylistTrack) -> Unit,
    onRetryTrack: (PlaylistTrack) -> Unit,
    onAddTracksClick: () -> Unit,
    onRenameNameChange: (String) -> Unit,
    onRenameConfirm: () -> Unit,
    onRenameCancel: () -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteCancel: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val playlist: Playlist = state.playlist ?: return
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
            PlaylistArtwork(
                playlist = playlist,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = NeedlerTheme.shapes.artworkHeroTablet,
            )
            PlaylistTitleBlock(state = state)
            PlaylistActions(
                state = state,
                onPlay = onPlay,
                onShuffle = onShuffle,
                onAddToCrate = onAddToCrate,
            )
            PlaylistCardStack(
                state = state,
                onRenameNameChange = onRenameNameChange,
                onRenameConfirm = onRenameConfirm,
                onRenameCancel = onRenameCancel,
                onDeleteConfirm = onDeleteConfirm,
                onDeleteCancel = onDeleteCancel,
                onDismissNotice = onDismissNotice,
            )
            Spacer(modifier = Modifier.height(spacing.step12))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = spacing.step12),
        ) {
            entryRows(
                state = state,
                onPlayTrack = onPlayTrack,
                onRemoveTrack = onRemoveTrack,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRetryTrack = onRetryTrack,
            )
            if (state.isEmpty) {
                item(key = "empty") { PlaylistEmptyTracks(onAddTracks = onAddTracksClick) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

@Composable
private fun PlaylistTopBar(
    canEdit: Boolean,
    busy: Boolean,
    onBack: () -> Unit,
    onAddTracksClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
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
        if (canEdit) {
            Box {
                NeedlerIconButton(
                    contentDescription = "More actions for this playlist",
                    onClick = { menuOpen = true },
                ) {
                    NeedlerMoreIcon(tint = colors.textPrimary, size = 22.dp)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    PlaylistMenuItem(label = "Add tracks", enabled = !busy) {
                        menuOpen = false
                        onAddTracksClick()
                    }
                    PlaylistMenuItem(label = "Rename", enabled = !busy) {
                        menuOpen = false
                        onRenameClick()
                    }
                    PlaylistMenuItem(label = "Delete", enabled = !busy, destructive = true) {
                        menuOpen = false
                        onDeleteClick()
                    }
                }
            }
        }
    }
}

/**
 * A playlist's cover.
 *
 * ## Why it is no longer a flat square
 *
 * `AsyncAlbumArt` alone degrades to `NeedlerColors.artworkPlaceholder`, the same grey as a pressed
 * row, and no mapper resolves an `ArtworkRef` in any module below `:app` yet - so on every render
 * in `screenshots/` a playlist's artwork is an empty box with nothing in it. On a tablet that box
 * is about 280dp and is the first thing the eye reaches on the screen.
 *
 * [NeedlerArtwork] is the layer that already solves this for albums: the derived tint and the
 * name's first letter underneath, the image over it when there is one. The identity is the
 * playlist id rather than the name, for the reason that function gives - two playlists called
 * "Jazz" should be two colours, and renaming one must not change its colour. A provisional
 * `local-` id is a legal identity here and is replaced when the queue replays `createPlaylist`,
 * which is the one case where a playlist does change colour; it is also the case where it changes
 * id, so there is nothing stable to key on until the server has answered.
 */
@Composable
private fun PlaylistArtwork(
    playlist: Playlist,
    modifier: Modifier,
    shape: Shape,
) {
    NeedlerArtwork(
        model = playlist.artwork,
        identity = playlist.id.value,
        name = playlist.name,
        // A playlist has no artist, so the pack's "title by artist" alt text
        // collapses to the name — which `albumArtContentDescription` already does
        // for a null artist rather than printing "by null".
        contentDescription = albumArtContentDescription(playlist.name, null),
        modifier = modifier,
        shape = shape,
    )
}

@Composable
private fun PlaylistTitleBlock(
    state: PlaylistUiState,
    modifier: Modifier = Modifier,
) {
    val playlist: Playlist = state.playlist ?: return
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = playlist.name,
            style = typography.albumTitle,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = state.headerLine,
            style = typography.meta,
            color = colors.textSecondary,
            modifier = Modifier.semantics {
                contentDescription = state.headerLine.replace(" · ", ", ")
            },
        )
        val syncState: PlaylistSyncState = state.syncState ?: PlaylistSyncState.ON_SERVER
        if (syncState.isPending) {
            Spacer(modifier = Modifier.height(2.dp))
            PlaylistSyncBadge(syncState = syncState)
        }
    }
}

/**
 * Play, Shuffle and Add to the crate.
 *
 * All three are disabled when nothing in the playlist can be played, rather than
 * hidden: the buttons are where the user expects them on an empty or broken
 * playlist too, and a disabled control that says why is more use than a gap.
 */
@Composable
private fun PlaylistActions(
    state: PlaylistUiState,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAddToCrate: () -> Unit,
) {
    val playlist: Playlist = state.playlist ?: return
    val spacing = NeedlerTheme.spacing
    val enabled: Boolean = !state.busy && state.hasPlayableTracks
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.step4),
        verticalArrangement = Arrangement.spacedBy(spacing.step4),
    ) {
        NeedlerPrimaryButton(
            text = "Play",
            onClick = onPlay,
            size = NeedlerButtonSize.Medium,
            enabled = enabled,
            leadingIcon = { tint ->
                NeedlerStrokeIcon(pathData = PathPlay, tint = tint, size = 18.dp, filled = true)
            },
            contentDescription = "Play " + playlist.name,
        )
        NeedlerSecondaryButton(
            text = "Shuffle",
            onClick = onShuffle,
            size = NeedlerButtonSize.Medium,
            enabled = enabled,
            contentDescription = "Shuffle " + playlist.name,
        )
        NeedlerSecondaryButton(
            text = "Add to crate",
            onClick = onAddToCrate,
            size = NeedlerButtonSize.Medium,
            enabled = enabled,
            contentDescription = "Add " + playlist.name + " to the crate",
        )
    }
}

/** The stack of cards under the actions: notice, forms, explanations. */
@Composable
private fun PlaylistCardStack(
    state: PlaylistUiState,
    onRenameNameChange: (String) -> Unit,
    onRenameConfirm: () -> Unit,
    onRenameCancel: () -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteCancel: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val playlist: Playlist = state.playlist ?: return
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.step5),
    ) {
        state.notice?.let { PlaylistNoticeCard(notice = it, onDismiss = onDismissNotice) }
        state.rename?.let { draft ->
            PlaylistNameForm(
                draft = draft,
                title = "RENAME PLAYLIST",
                confirmLabel = "Rename",
                onNameChange = onRenameNameChange,
                onConfirm = onRenameConfirm,
                onCancel = onRenameCancel,
            )
        }
        if (state.confirmingDelete) {
            PlaylistConfirmCard(
                message = "Delete " + playlist.name + "? The playlist goes from this device now " +
                    "and from the server on your next connection. The music itself is untouched.",
                confirmLabel = "Delete",
                onConfirm = onDeleteConfirm,
                onCancel = onDeleteCancel,
                enabled = !state.busy,
            )
        }
        // One of the two, never both. `screenshots/playlist-not-sent-phone.png` stacked a
        // "Not sent yet" badge, a 3-line offline card and a 2-line explanation card - 280dp
        // saying one thing three times. The badge is the state's name and the card is what
        // happens next; the generic offline note says the same "it reaches the server when you
        // reconnect" in more words and is redundant the moment a pending state is on screen.
        val pending: String? = state.syncState?.explanation
        if (pending != null) {
            PlaylistInfoCard(message = pending)
        } else if (state.offline) {
            PlaylistOfflineNote()
        }
        if (state.isPartlyUnplayable) UnplayableNote(state)
    }
}

/**
 * The greyed-rows explanation.
 *
 * A playlist with unplayable rows in it looks broken without a sentence saying
 * what they are. Two causes, one line, because the remedy from here is the same
 * for both: the track has no file on the server (the part-delivered pull
 * REQUIREMENTS.md calls a normal state), or the mirror has temporarily lost the
 * album it belongs to and the next sync will bring it back.
 */
@Composable
private fun UnplayableNote(state: PlaylistUiState) {
    val message: String = LibraryFormat.plural(state.unplayableEntries.size.toLong(), "track") +
        " in this playlist cannot be played: either the server has no file for it, or its album " +
        "is not in the copy of your library on this device yet. Each one offers a retry, and the " +
        "rest plays normally."
    PlaylistInfoCard(message = message)
}

private fun LazyListScope.entryRows(
    state: PlaylistUiState,
    onPlayTrack: (PlaylistTrack) -> Unit,
    onRemoveTrack: (PlaylistTrack) -> Unit,
    onMoveUp: (PlaylistTrack) -> Unit,
    onMoveDown: (PlaylistTrack) -> Unit,
    onRetryTrack: (PlaylistTrack) -> Unit,
) {
    items(
        count = state.entries.size,
        // The position, not the track key: a playlist may legitimately hold the
        // same track twice, and two rows with one key make a LazyColumn throw.
        key = { index -> state.entries[index].position },
    ) { index ->
        PlaylistEntryRow(
            row = state.entries[index],
            isFirst = index == 0,
            isLast = index == state.entries.lastIndex,
            isPlaying = state.entries[index].key == state.nowPlayingTrackKey,
            canReorder = state.canReorder,
            busy = state.busy,
            onPlayTrack = onPlayTrack,
            onRemoveTrack = onRemoveTrack,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onRetryTrack = onRetryTrack,
        )
    }
}

/**
 * One track of the playlist: its position, its title, who made it, how long it is, and - when the
 * server has no file for it - what is wrong and what to do about it.
 *
 * ## Why this is no longer `LibraryTrackRow`
 *
 * Three findings met in this row and the shared track row could carry none of them.
 *
 * **A playlist row named no artist.** `screenshots/playlist-holes-phone.png` is an index, a title
 * and a duration, which is album detail's row - and album detail can leave the artist out because
 * the screen above the list already names them. A playlist is the one list in the app that is
 * cross-artist by definition, so the one row that most needed the name was the row without it. The
 * genre row is the right shape and this is now the same shape: title, artist and album, duration.
 *
 * **Greying was the only mark on an unplayable row, and there was no retry.** REQUIREMENTS.md
 * "Partial content is a normal state" asks for both - missing tracks "listed in their right
 * positions, greyed, each with a retry" - and REQUIREMENTS.md "Accessibility" does not accept
 * colour as the only carrier of a state in any case. The row keeps the greying and adds the words
 * and the action beside them; see [MissingTrackLine].
 *
 * **The playing mark moved between screens.** Here it replaced the index, so a playlist read
 * 1, 2, dot, 4, 5 and lost its numbering at the one row the eye goes to; on the genre screen and
 * the Songs tab the same mark sits on the right and the row keeps everything else. Two conventions
 * for one fact, and this was the one that destroyed information, so this is the one that moved.
 * `NeedlerTrackRow` swaps the index for the glyph internally and cannot be told not to; wanting it
 * to is in the handover notes, with album detail as the other caller.
 *
 * The editing is unchanged: an overflow menu with Move up, Move down and Remove, and the same
 * three as custom accessibility actions on the row so that TalkBack reaches them from its local
 * context menu without opening a menu at all. Move up is absent on the first row and Move down on
 * the last, in both the menu and the actions - an action that cannot do anything is worse than no
 * action, because a screen reader still offers it.
 */
@Composable
private fun PlaylistEntryRow(
    row: PlaylistTrack,
    isFirst: Boolean,
    isLast: Boolean,
    isPlaying: Boolean,
    canReorder: Boolean,
    busy: Boolean,
    onPlayTrack: (PlaylistTrack) -> Unit,
    onRemoveTrack: (PlaylistTrack) -> Unit,
    onMoveUp: (PlaylistTrack) -> Unit,
    onMoveDown: (PlaylistTrack) -> Unit,
    onRetryTrack: (PlaylistTrack) -> Unit,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    var menuOpen: Boolean by remember { mutableStateOf(false) }
    val canMoveUp: Boolean = canReorder && !isFirst && !busy
    val canMoveDown: Boolean = canReorder && !isLast && !busy
    val title: String = LibraryFormat.trackTitle(row.track.title)
    val subtitle: String = LibraryFormat.songRowSubtitle(row.track)
    val actions: List<CustomAccessibilityAction> = buildList {
        if (canMoveUp) {
            add(CustomAccessibilityAction("Move up in this playlist") { onMoveUp(row); true })
        }
        if (canMoveDown) {
            add(CustomAccessibilityAction("Move down in this playlist") { onMoveDown(row); true })
        }
        if (!busy) {
            add(CustomAccessibilityAction("Remove from this playlist") { onRemoveTrack(row); true })
        }
    }
    val spoken: String = buildString {
        append(row.displayNumber.toString())
        append(". ")
        append(title)
        if (subtitle.isNotEmpty()) {
            append(", ")
            append(subtitle.replace(SUBTITLE_SEPARATOR, ", "))
        }
        LibraryFormat.spokenDuration(row.track.durationMs)?.let {
            append(", ")
            append(it)
        }
        if (isPlaying) append(", playing")
        if (!row.available) append(", " + MISSING_TRACK)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = NeedlerTheme.sizes.trackRowMinHeight)
                .then(
                    if (row.available) {
                        Modifier.clickable(role = Role.Button) { onPlayTrack(row) }
                    } else {
                        Modifier
                    },
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = spoken
                    if (actions.isNotEmpty()) customActions = actions
                }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // A fixed column, as the shared track row draws it, so the titles line up down the
                // list however many digits a position has.
                text = row.displayNumber.toString(),
                style = typography.trackIndex,
                color = colors.textMuted,
                modifier = Modifier.width(18.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = if (isPlaying) typography.rowTitle else typography.rowTitleRegular,
                    color = when {
                        isPlaying -> colors.accent
                        !row.available -> colors.textMuted
                        else -> colors.textPrimary
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = typography.meta,
                        color = if (row.available) colors.textSecondary else colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!row.available) {
                    MissingTrackLine(busy = busy, onRetry = { onRetryTrack(row) })
                }
            }
            // On the right, where the genre screen and the Songs tab put it, so the row keeps its
            // number. See this function's notes.
            if (isPlaying) NeedlerNowPlayingIcon(tint = colors.accent)
            LibraryFormat.duration(row.track.durationMs)?.let { duration ->
                Text(text = duration, style = typography.duration, color = colors.textMuted)
            }
            Box {
                NeedlerIconButton(
                    contentDescription = "More actions for " + title,
                    onClick = { menuOpen = true },
                    visualSize = 36.dp,
                ) {
                    NeedlerMoreIcon(tint = colors.textMuted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (row.available) {
                        PlaylistMenuItem(label = "Play", enabled = !busy) {
                            menuOpen = false
                            onPlayTrack(row)
                        }
                    }
                    if (canReorder) {
                        PlaylistMenuItem(label = "Move up", enabled = canMoveUp) {
                            menuOpen = false
                            onMoveUp(row)
                        }
                        PlaylistMenuItem(label = "Move down", enabled = canMoveDown) {
                            menuOpen = false
                            onMoveDown(row)
                        }
                    }
                    PlaylistMenuItem(
                        label = "Remove from playlist",
                        enabled = !busy,
                        destructive = true,
                    ) {
                        menuOpen = false
                        onRemoveTrack(row)
                    }
                }
            }
        }
        NeedlerHairline()
    }
}

/**
 * What an unplayable row says, and the one thing that can change it.
 *
 * The words are the second channel the greying needed: REQUIREMENTS.md "Accessibility" forbids
 * colour as the only means, and the muted grey it is drawn in measures 4.21:1 on the canvas, so a
 * reader who does not already know what a dimmed row means has nothing at all to read.
 *
 * The retry asks the server for the album again - `PullRepository.retryRequest`, the same call the
 * Pulls screen's Retry makes - because that is the only thing that can produce the file. A track
 * whose album the mirror has merely lost sight of comes back on the next sync, and the user cannot
 * be expected to know which of the two they are looking at; that is why
 * [PlaylistUiState.unplayableEntries] does not distinguish them either, and why asking again is
 * the right offer in both cases.
 */
@Composable
private fun MissingTrackLine(busy: Boolean, onRetry: () -> Unit) {
    val colors = NeedlerTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = MISSING_TRACK,
            style = NeedlerTheme.typography.caption,
            color = colors.textMuted,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        NeedlerTextButton(
            text = "Retry",
            onClick = onRetry,
            enabled = !busy,
            contentDescription = "Ask the server for this track's album again",
        )
    }
}

/** What a row with no file behind it is called, on screen and aloud. */
private const val MISSING_TRACK: String = "The server has no file for this"

/** The separator `LibraryFormat` joins a row subtitle with, swapped for a comma when spoken. */
private const val SUBTITLE_SEPARATOR: String = " · "

// ---------------------------------------------------------------------------
// Adding tracks
// ---------------------------------------------------------------------------

/**
 * The add-tracks step: search the library, tick what belongs, add it.
 *
 * It replaces the playlist rather than floating over them, for the same reason the
 * name forms are inline: a sheet or a dialog is a separate window and would be
 * absent from every screenshot, and this is the one flow in the feature with enough
 * moving parts to be worth rendering at 200% text.
 *
 * Search, not a list of the library. [PlaylistTrackPicker] records why: the songs
 * query is capped, so a list could not reach past its cap, while the mirror's FTS
 * answers any query offline and inside REQUIREMENTS.md's 50 ms local-search budget.
 *
 * Nothing here can add a track the server has no file for. Those rows are filtered
 * out before they reach the screen — a playlist entry that cannot be played is not
 * something a user meant to choose.
 */
@Composable
private fun TrackPickerPane(
    picker: PlaylistTrackPicker,
    busy: Boolean,
    gutter: Dp,
    onQueryChange: (String) -> Unit,
    onToggle: (Track) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(start = gutter, end = gutter),
            verticalArrangement = Arrangement.spacedBy(spacing.step5),
        ) {
            Text(
                text = "ADD TRACKS",
                style = typography.sectionHeader,
                color = colors.textSecondary,
                modifier = Modifier.semantics { heading() },
            )
            NeedlerSearchField(
                value = picker.query,
                onValueChange = onQueryChange,
                placeholder = "Song, album or artist",
                label = "Search your library",
                enabled = !busy,
                onClear = { onQueryChange("") },
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.step4),
                verticalArrangement = Arrangement.spacedBy(spacing.step4),
            ) {
                NeedlerPrimaryButton(
                    text = picker.confirmLabel,
                    onClick = onConfirm,
                    size = NeedlerButtonSize.Medium,
                    enabled = picker.canSubmit && !busy,
                    contentDescription = if (picker.canSubmit) {
                        picker.confirmLabel + " to this playlist"
                    } else {
                        "Add tracks. Choose at least one song first."
                    },
                )
                NeedlerSecondaryButton(
                    text = "Cancel",
                    onClick = onCancel,
                    size = NeedlerButtonSize.Medium,
                    enabled = !busy,
                )
            }
            if (!picker.hasQuery) {
                Text(
                    text = "Type to find songs in the copy of your library on this device. " +
                        "This works with no connection.",
                    style = typography.caption,
                    color = colors.textSecondary,
                )
            }
            if (picker.foundNothing) {
                Text(
                    text = "Nothing in your library matches that.",
                    style = typography.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.step6))

        LazyColumn(
            // `weight`, not `fillMaxSize`: the search field and the buttons above it
            // are laid out first, and a child that filled the parent's whole height
            // would hang the last results off the bottom of the screen.
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = spacing.step12),
        ) {
            items(items = picker.results, key = { it.key.canonicalString }) { track ->
                CandidateRow(
                    track = track,
                    chosen = picker.isSelected(track.key),
                    onToggle = onToggle,
                )
            }
        }
    }
}

/**
 * One search result, tickable.
 *
 * ## The list has to look selectable before anything is selected
 *
 * It drew a tick on a chosen row and nothing at all on the others, so a user arriving at
 * `screenshots/playlist-add-tracks-empty-phone.png` saw an ordinary list of songs with no sign
 * that tapping one would choose rather than play it - the affordance only appeared after the
 * gesture that needed it. Every row now carries the control in one of its two states: an empty
 * hairline circle, or a filled one with the check. That is the one channel that was missing;
 * `selected` in the semantics tree and the word in the spoken description were already there.
 *
 * ## And it is not the device green
 *
 * The tick was `positive`, which REQUIREMENTS.md "Vocabulary" gives to
 * [app.needler.core.design.component.NeedlerAlbumSource.Device] and nothing else - so on a screen
 * listing tracks, the colour that means "on this device" was being used to mean "I picked this
 * one". The accent is the app's ordinary selection colour and carries no state of its own.
 *
 * The whole row is the target, not the circle: the row is already 64dp tall and a 20dp glyph would
 * fail REQUIREMENTS.md's 48dp minimum.
 */
@Composable
private fun CandidateRow(
    track: Track,
    chosen: Boolean,
    onToggle: (Track) -> Unit,
) {
    val colors = NeedlerTheme.colors
    val subtitle: String = LibraryFormat.songRowSubtitle(track)
    val title: String = LibraryFormat.trackTitle(track.title)
    val duration: String? = LibraryFormat.duration(track.durationMs)
    NeedlerAlbumRow(
        title = title,
        subtitle = subtitle,
        onClick = { onToggle(track) },
        showDivider = true,
        contentDescription = buildString {
            append(title)
            if (subtitle.isNotEmpty()) {
                append(", ")
                append(subtitle.replace(SUBTITLE_SEPARATOR, ", "))
            }
            LibraryFormat.spokenDuration(track.durationMs)?.let {
                append(", ")
                append(it)
            }
            append(if (chosen) ", chosen" else ", not chosen")
        },
        modifier = Modifier.semantics { selected = chosen },
        trailing = {
            // The duration is what tells two songs of the same name apart, and the button above
            // this list counts in tracks, so the rows have to be countable things.
            if (duration != null) {
                Text(
                    text = duration,
                    style = NeedlerTheme.typography.duration,
                    color = colors.textMuted,
                )
            }
            SelectionMark(chosen = chosen)
        },
    )
}

/** The chosen-or-not control on a picker row. See [CandidateRow] for why it is always drawn. */
@Composable
private fun SelectionMark(chosen: Boolean) {
    val colors = NeedlerTheme.colors
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(NeedlerTheme.shapes.circle)
            .then(
                if (chosen) {
                    Modifier.background(colors.accent)
                } else {
                    Modifier.border(
                        width = NeedlerTheme.sizes.hairlineThickness,
                        color = colors.textMuted,
                        shape = NeedlerTheme.shapes.circle,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (chosen) NeedlerCheckIcon(tint = colors.onAccent, size = 14.dp)
    }
}

/**
 * A playlist with nothing in it, which is what a freshly created one is.
 *
 * ## Why the copy stopped naming a control
 *
 * It read "Add tracks with the more-actions button at the top of this screen", which asks the user
 * to map a developer's name for a control onto an unlabelled three-dot glyph about 600dp away, at
 * the opposite corner of the screen from the sentence pointing at it. The button is here instead.
 * It opens the same picker the overflow item does - one callback, so the two cannot come to mean
 * different things - and the overflow keeps its item for the case this empty state is not drawn.
 */
@Composable
private fun PlaylistEmptyTracks(onAddTracks: () -> Unit) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = NeedlerTheme.spacing.step10)
            .widthIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step3),
    ) {
        Text(
            text = "Nothing in this playlist yet",
            style = typography.bodyStrong,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "Add songs from the copy of your library on this device, or from any album.",
            style = typography.caption,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(NeedlerTheme.spacing.step4))
        NeedlerPrimaryButton(
            text = "Add tracks",
            onClick = onAddTracks,
            size = NeedlerButtonSize.Medium,
            contentDescription = "Add tracks to this playlist",
        )
    }
}

/**
 * The playlist this screen was opened for is not in the local copy of the library.
 *
 * Two repairs. The copy said "the mirror on this device", which is the data layer's word for the
 * local store and appears nowhere a user can learn it; every screen in this module now says "the
 * copy of your library on this device", which is what the picker and the offline notes already
 * said. And the screen was a heading, a paragraph and about 1400px of nothing - a dead end with no
 * control on it at all, on a screen whose back button is a 24dp glyph in the top corner. The way
 * back is a button now, because a dead end is the one state that most needs one.
 */
@Composable
private fun PlaylistNotFound(gutter: Dp, onBack: () -> Unit) {
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
            text = "That playlist is not here",
            style = typography.displayCompact,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "It is not in the copy of your library on this device. It may have been " +
                "deleted on the server, or on another client.",
            style = typography.body,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(NeedlerTheme.spacing.step6))
        NeedlerPrimaryButton(
            text = "Back to playlists",
            onClick = onBack,
            size = NeedlerButtonSize.Medium,
        )
    }
}

@Composable
private fun PlaylistSkeleton(gutter: Dp) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter)
            .semantics(mergeDescendants = true) { contentDescription = "Loading this playlist" },
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

/** The width of the artwork-and-actions column on a tablet, from screen 11. */
private val HERO_COLUMN_WIDTH: Dp = 280.dp

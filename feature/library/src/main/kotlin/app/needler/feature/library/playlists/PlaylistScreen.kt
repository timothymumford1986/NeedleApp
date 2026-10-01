@file:OptIn(ExperimentalLayoutApi::class)

package app.needler.feature.library.playlists

import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.AsyncAlbumArt
import app.needler.core.design.component.NeedlerAlbumRow
import app.needler.core.design.component.NeedlerButtonSize
import app.needler.core.design.component.NeedlerCheckIcon
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerMoreIcon
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerSearchField
import app.needler.core.design.component.NeedlerSecondaryButton
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.component.PathPlay
import app.needler.core.design.component.albumArtContentDescription
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Playlist
import app.needler.core.domain.model.Track
import app.needler.feature.library.common.LibraryFormat
import app.needler.feature.library.library.LibraryTrackRow

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
                state.notFound -> PlaylistNotFound(gutter = gutter)
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
        )
        if (state.isEmpty) {
            item(key = "empty") { PlaylistEmptyTracks() }
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
            )
            if (state.isEmpty) {
                item(key = "empty") { PlaylistEmptyTracks() }
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

@Composable
private fun PlaylistArtwork(
    playlist: Playlist,
    modifier: Modifier,
    shape: Shape,
) {
    AsyncAlbumArt(
        model = playlist.artwork,
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
        if (state.offline) PlaylistOfflineNote()
        state.syncState?.explanation?.let { PlaylistInfoCard(message = it) }
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
        "is not in this device's copy of the library yet. The rest plays normally."
    PlaylistInfoCard(message = message)
}

private fun LazyListScope.entryRows(
    state: PlaylistUiState,
    onPlayTrack: (PlaylistTrack) -> Unit,
    onRemoveTrack: (PlaylistTrack) -> Unit,
    onMoveUp: (PlaylistTrack) -> Unit,
    onMoveDown: (PlaylistTrack) -> Unit,
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
        )
    }
}

/**
 * One track of the playlist.
 *
 * The row is the module's shared track row, so a playlist's list and an album's
 * list are the same rhythm, numbering and playing mark. What this adds is the
 * editing: an overflow menu with Move up, Move down and Remove, and the same three
 * as custom accessibility actions on the row so that TalkBack reaches them from
 * its local context menu without opening a menu at all.
 *
 * Move up is absent on the first row and Move down on the last, in both the menu
 * and the actions — an action that cannot do anything is worse than no action,
 * because a screen reader still offers it.
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
) {
    var menuOpen: Boolean by remember { mutableStateOf(false) }
    val canMoveUp: Boolean = canReorder && !isFirst && !busy
    val canMoveDown: Boolean = canReorder && !isLast && !busy
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

    Box {
        LibraryTrackRow(
            index = row.displayNumber,
            track = row.track,
            isPlaying = isPlaying,
            available = row.available,
            onClick = { onPlayTrack(row) },
            onMoreClick = { menuOpen = true },
            modifier = Modifier.semantics {
                if (actions.isNotEmpty()) customActions = actions
            },
        )
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
            PlaylistMenuItem(label = "Remove from playlist", enabled = !busy, destructive = true) {
                menuOpen = false
                onRemoveTrack(row)
            }
        }
    }
}

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
                    text = "Type to find songs in the copy of your library on this device. This " +
                        "works with no connection.",
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
 * The chosen state is carried three ways, because colour alone is no signal at all
 * to a reader who cannot separate the palette's green from its off-white: the check
 * glyph, the `selected` semantics property, and the word in the spoken description.
 * The whole row is the target, not the glyph — it is already 64dp tall and a 16dp
 * tick would fail REQUIREMENTS.md's 48dp minimum.
 */
@Composable
private fun CandidateRow(
    track: Track,
    chosen: Boolean,
    onToggle: (Track) -> Unit,
) {
    val colors = NeedlerTheme.colors
    val subtitle: String = LibraryFormat.songRowSubtitle(track)
    NeedlerAlbumRow(
        title = track.title,
        subtitle = subtitle,
        onClick = { onToggle(track) },
        showDivider = true,
        contentDescription = buildString {
            append(track.title)
            append(", ")
            append(subtitle)
            LibraryFormat.spokenDuration(track.durationMs)?.let {
                append(", ")
                append(it)
            }
            append(if (chosen) ", chosen" else ", not chosen")
        },
        modifier = Modifier.semantics { selected = chosen },
        trailing = {
            if (chosen) NeedlerCheckIcon(tint = colors.positive, size = 20.dp)
        },
    )
}

/** A playlist with nothing in it, which is what a freshly created one is. */
@Composable
private fun PlaylistEmptyTracks() {
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
            text = "Add tracks with the more-actions button at the top of this screen, or from " +
                "any album.",
            style = typography.caption,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun PlaylistNotFound(gutter: Dp) {
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
            text = "It is not in the mirror on this device. It may have been deleted on the " +
                "server, or on another client.",
            style = typography.body,
            color = colors.textSecondary,
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

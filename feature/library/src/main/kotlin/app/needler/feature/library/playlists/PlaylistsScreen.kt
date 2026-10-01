package app.needler.feature.library.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.AsyncAlbumArt
import app.needler.core.design.component.NeedlerAlbumRow
import app.needler.core.design.component.NeedlerButtonSize
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerMoreIcon
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Playlist
import app.needler.feature.library.common.LibraryFormat

/**
 * The playlists list.
 *
 * REQUIREMENTS.md "Library browse" puts Playlists in the browse table, sourced
 * from `getPlaylists` and ordered alphabetically, and "Playlists" adds the two
 * facts that shape this screen: edits made offline queue and replay
 * last-write-wins, and **star ratings must never appear** — `setRating` is a
 * validated no-op on this server, so the only opinion a user can record about
 * music is the binary favourite, and nothing here offers a rating control.
 *
 * ## One list, three sync states
 *
 * A row says what the server knows about it in its own subtitle rather than in a
 * badge column: "12 tracks · 48 min · Not sent yet". That is a deliberate
 * trade. A badge beside the overflow button reads better on a wide screen and
 * squeezes the title to nothing at 200% text on a phone, which REQUIREMENTS.md
 * "Accessibility" does not allow; the subtitle wraps instead. The full sentence —
 * what will happen and when — is on the playlist itself, where there is room to
 * say it properly.
 *
 * ## Why the row has no play button
 *
 * Tapping a row opens the playlist, as tapping an album row on screen 13 opens
 * the album. Play, Shuffle and Add to crate are in the row's overflow menu and on
 * the detail screen. A play button plus an overflow button plus artwork leaves a
 * phone row about 180dp for a title that can be sixty characters long.
 */
@Composable
fun PlaylistsScreen(
    state: PlaylistsUiState,
    widthSizeClass: WindowWidthSizeClass,
    onCreateClick: () -> Unit,
    onDraftNameChange: (String) -> Unit,
    onCreateConfirm: () -> Unit,
    onCreateCancel: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onPlay: (Playlist) -> Unit,
    onAddToCrate: (Playlist) -> Unit,
    onDeleteRequest: (Playlist) -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteCancel: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val wide: Boolean = widthSizeClass != WindowWidthSizeClass.Compact
    val gutter: Dp = if (wide) spacing.tabletGutter else spacing.phoneGutter

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier.padding(
                start = gutter,
                end = gutter,
                top = if (wide) spacing.step12 else spacing.step14,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sectionGap),
        ) {
            PlaylistsHeader(
                state = state,
                onCreateClick = onCreateClick,
            )
            if (state.offline) PlaylistOfflineNote()
            state.notice?.let { PlaylistNoticeCard(notice = it, onDismiss = onDismissNotice) }
            state.draft?.let { draft ->
                PlaylistNameForm(
                    draft = draft,
                    title = "NEW PLAYLIST",
                    confirmLabel = "Create",
                    onNameChange = onDraftNameChange,
                    onConfirm = onCreateConfirm,
                    onCancel = onCreateCancel,
                )
            }
            state.playlistPendingDeletion?.let { playlist ->
                PlaylistConfirmCard(
                    message = "Delete " + playlist.name + "? The playlist goes from this device " +
                        "now and from the server on your next connection. The music itself is " +
                        "untouched.",
                    confirmLabel = "Delete",
                    onConfirm = onDeleteConfirm,
                    onCancel = onDeleteCancel,
                    enabled = !state.busy,
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.step9))

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.loading -> PlaylistsSkeleton(gutter = gutter)
                state.showEmptyState -> PlaylistsEmptyState(
                    onCreateClick = onCreateClick,
                    gutter = gutter,
                )

                else -> PlaylistList(
                    state = state,
                    gutter = gutter,
                    onPlaylistClick = onPlaylistClick,
                    onPlay = onPlay,
                    onAddToCrate = onAddToCrate,
                    onDeleteRequest = onDeleteRequest,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun PlaylistsHeader(
    state: PlaylistsUiState,
    onCreateClick: () -> Unit,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.step4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.step1),
        ) {
            Text(
                text = TITLE,
                style = typography.screenTitle,
                color = colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            // Nothing at all rather than "0 playlists" while the mirror is still
            // answering: the header's job is to describe what is below it.
            if (!state.loading) {
                Text(
                    text = state.headerLine(),
                    style = typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
        NeedlerPrimaryButton(
            text = "New",
            onClick = onCreateClick,
            size = NeedlerButtonSize.Small,
            enabled = state.draft == null,
            leadingIcon = { tint ->
                NeedlerStrokeIcon(pathData = PATH_PLUS, tint = tint, size = 16.dp)
            },
            contentDescription = "New playlist",
        )
    }
}

/**
 * `6 playlists`, or `6 playlists · 2 waiting to be sent`.
 *
 * The second half is the honest summary of the write queue as it affects this
 * screen. It is a count and not a warning: REQUIREMENTS.md treats a queued edit
 * as the normal consequence of editing offline, so the line reports it the way
 * the header reports an album count.
 */
private fun PlaylistsUiState.headerLine(): String {
    val pending: Int = pendingCount
    return if (pending == 0) {
        countLine
    } else {
        countLine + " · " + pending + " waiting to be sent"
    }
}

// ---------------------------------------------------------------------------
// The list
// ---------------------------------------------------------------------------

@Composable
private fun PlaylistList(
    state: PlaylistsUiState,
    gutter: Dp,
    onPlaylistClick: (Playlist) -> Unit,
    onPlay: (Playlist) -> Unit,
    onAddToCrate: (Playlist) -> Unit,
    onDeleteRequest: (Playlist) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            end = gutter,
            bottom = NeedlerTheme.spacing.step12,
        ),
    ) {
        items(
            count = state.playlists.size,
            key = { index -> state.playlists[index].id.value },
        ) { index ->
            PlaylistRow(
                playlist = state.playlists[index],
                busy = state.busy,
                onClick = onPlaylistClick,
                onPlay = onPlay,
                onAddToCrate = onAddToCrate,
                onDelete = onDeleteRequest,
            )
        }
    }
}

/**
 * One playlist.
 *
 * Built on the pack's album row (13), because a playlist in a list is the same
 * object as an album in a list: artwork, a title, a line of metadata, and one
 * overflow control. Its artwork is the [app.needler.core.domain.model.ArtworkRef]
 * the server gave for the playlist, handed to Coil unresolved exactly as album
 * artwork is.
 */
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    busy: Boolean,
    onClick: (Playlist) -> Unit,
    onPlay: (Playlist) -> Unit,
    onAddToCrate: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    val colors = NeedlerTheme.colors
    var menuOpen: Boolean by remember { mutableStateOf(false) }
    NeedlerAlbumRow(
        title = playlist.name,
        subtitle = playlistSubtitle(playlist),
        onClick = { onClick(playlist) },
        showDivider = true,
        contentDescription = playlistRowDescription(playlist),
        artwork = {
            AsyncAlbumArt(
                model = playlist.artwork,
                contentDescription = null,
                modifier = Modifier.size(NeedlerTheme.sizes.artworkRow),
                shape = NeedlerTheme.shapes.artworkThumb,
            )
        },
        trailing = {
            Box {
                NeedlerIconButton(
                    contentDescription = "More actions for " + playlist.name,
                    onClick = { menuOpen = true },
                ) {
                    NeedlerMoreIcon(tint = colors.textMuted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    PlaylistMenuItem(label = "Play", enabled = !busy) {
                        menuOpen = false
                        onPlay(playlist)
                    }
                    PlaylistMenuItem(label = "Add to the crate", enabled = !busy) {
                        menuOpen = false
                        onAddToCrate(playlist)
                    }
                    PlaylistMenuItem(label = "Delete", enabled = !busy, destructive = true) {
                        menuOpen = false
                        onDelete(playlist)
                    }
                }
            }
        },
    )
}

/** `12 tracks · 48 min`, with the pending state appended when there is one. */
internal fun playlistSubtitle(playlist: Playlist): String {
    val parts: List<String> = buildList {
        add(LibraryFormat.plural(playlist.trackCount.toLong(), "track"))
        LibraryFormat.runningTime(playlist.durationMs)?.let { add(it) }
        playlist.syncState.label?.let { add(it) }
    }
    return parts.joinToString(separator = " · ")
}

/**
 * What TalkBack reads for a row.
 *
 * The drawn subtitle uses the badge's short label; the spoken one uses the whole
 * sentence, because "Not sent yet" out of context tells a screen-reader user
 * nothing about what to do. The duration is spelled out for the same reason
 * `LibraryFormat` keeps a [LibraryFormat.spokenDuration]: "48 min" read aloud is
 * fine, "1:04:11" is not.
 */
internal fun playlistRowDescription(playlist: Playlist): String {
    val parts: List<String> = buildList {
        add(playlist.name)
        add(LibraryFormat.plural(playlist.trackCount.toLong(), "track"))
        LibraryFormat.runningTime(playlist.durationMs)?.let { add(it) }
        playlist.syncState.spokenLabel?.let { add(it) }
    }
    return parts.joinToString(separator = ", ")
}

// ---------------------------------------------------------------------------
// Empty and loading
// ---------------------------------------------------------------------------

/**
 * No playlists at all.
 *
 * The copy does not depend on connectivity, and that is the point: a playlist can
 * be made with no connection, so there is nothing to wait for and no sync to
 * suggest. This is the one empty state in the module whose way out works offline.
 */
@Composable
private fun PlaylistsEmptyState(
    onCreateClick: () -> Unit,
    gutter: Dp,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter, vertical = spacing.step20)
            .widthIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.step6),
    ) {
        Text(
            text = "No playlists yet",
            style = typography.displayCompact,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "Playlists you make here are kept on your server and on this device. You can " +
                "make one with no connection; it reaches the server the next time you have one.",
            style = typography.body,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(spacing.step4))
        NeedlerPrimaryButton(
            text = "New playlist",
            onClick = onCreateClick,
        )
    }
}

@Composable
private fun PlaylistsSkeleton(gutter: Dp) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter)
            .semantics(mergeDescendants = true) { contentDescription = "Loading your playlists" },
        verticalArrangement = Arrangement.spacedBy(spacing.step8),
    ) {
        repeat(6) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.step7),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(NeedlerTheme.sizes.artworkRow)
                        .clip(NeedlerTheme.shapes.artworkThumb)
                        .background(colors.surface),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.step3),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(14.dp)
                            .clip(NeedlerTheme.shapes.progress)
                            .background(colors.surface),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.3f)
                            .height(12.dp)
                            .clip(NeedlerTheme.shapes.progress)
                            .background(colors.surface),
                    )
                }
            }
        }
    }
}

private const val TITLE: String = "PLAYLISTS"

/** The pack has no plus glyph; this is one drawn to its 24x24, 1.8-stroke rule. */
private const val PATH_PLUS: String = "M12 5v14M5 12h14"

@file:OptIn(ExperimentalLayoutApi::class)

package app.needler.feature.library.genres

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerAlbumRow
import app.needler.core.design.component.NeedlerButtonSize
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerSecondaryButton
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.component.PathPlay
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Track
import app.needler.feature.library.common.LibraryFormat
import app.needler.feature.library.common.hasPlayableFile

/**
 * One genre's songs.
 *
 * The rows are the Songs tab's rows, down to the spoken description and the
 * duration on the right: a song in a genre is the same object as a song in the
 * library, and REQUIREMENTS.md's identity model is the reason this module never
 * grows a second kind of song row. The header is album detail's, minus artwork —
 * a genre has no cover, and an empty tinted square is worse than none.
 *
 * Two lines of honesty the screen owes the user:
 *
 *  * the list is capped, and says so when it is full, because
 *    `observeTracksByGenre` is bounded rather than paged;
 *  * offline is stated rather than hidden, because everything here came out of the
 *    mirror and reads the same with no connection.
 */
@Composable
fun GenreScreen(
    state: GenreUiState,
    widthSizeClass: WindowWidthSizeClass,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onShowMore: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val wide: Boolean = widthSizeClass != WindowWidthSizeClass.Compact
    val gutter: Dp = if (wide) spacing.tabletGutter else spacing.phoneGutterWide

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gutter - 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeedlerIconButton(contentDescription = "Back", onClick = onBack) {
                NeedlerStrokeIcon(
                    pathData = PathChevronLeft,
                    tint = colors.textPrimary,
                    size = 24.dp,
                )
            }
        }

        Column(
            modifier = Modifier.padding(start = gutter, end = gutter),
            verticalArrangement = Arrangement.spacedBy(spacing.step5),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.step1)) {
                Text(
                    text = state.genre,
                    style = NeedlerTheme.typography.albumTitle,
                    color = colors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                if (!state.loading) {
                    Text(
                        text = state.headerLine,
                        style = NeedlerTheme.typography.meta,
                        color = colors.textSecondary,
                        modifier = Modifier.semantics {
                            contentDescription = state.headerLine.replace(" · ", ", ")
                        },
                    )
                }
            }
            if (!state.loading && !state.showEmptyState) {
                GenreActions(
                    state = state,
                    onPlayAll = onPlayAll,
                    onShuffleAll = onShuffleAll,
                )
            }
            if (state.offline) GenresOfflineNote()
        }

        Spacer(modifier = Modifier.height(spacing.step9))

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.loading -> GenreSkeleton(gutter = gutter)
                state.showEmptyState -> GenreEmptyState(
                    genre = state.genre,
                    offline = state.offline,
                    onSyncNow = onSyncNow,
                    gutter = gutter,
                )
                else -> GenreSongList(
                    state = state,
                    gutter = gutter,
                    onPlayTrack = onPlayTrack,
                    onShowMore = onShowMore,
                )
            }
        }
    }
}

@Composable
private fun GenreActions(
    state: GenreUiState,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
) {
    val spacing = NeedlerTheme.spacing
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.step4),
        verticalArrangement = Arrangement.spacedBy(spacing.step4),
    ) {
        NeedlerPrimaryButton(
            text = "Play",
            onClick = onPlayAll,
            size = NeedlerButtonSize.Medium,
            enabled = state.hasPlayableTracks,
            leadingIcon = { tint ->
                NeedlerStrokeIcon(pathData = PathPlay, tint = tint, size = 18.dp, filled = true)
            },
            contentDescription = "Play everything in " + state.genre,
        )
        NeedlerSecondaryButton(
            text = "Shuffle",
            onClick = onShuffleAll,
            size = NeedlerButtonSize.Medium,
            enabled = state.hasPlayableTracks,
            contentDescription = "Shuffle everything in " + state.genre,
        )
    }
}

/**
 * The Songs tab's list, over one genre's tracks.
 *
 * Deliberately the same composition as `LibraryScreen`'s song list rather than a
 * shared component: that one is private to the library screen, and this module is
 * not allowed to reach into it. The duplication is small, it is named here, and the
 * right answer — one `LibrarySongRow` in `common/` that both call — is in the
 * handover notes.
 */
@Composable
private fun GenreSongList(
    state: GenreUiState,
    gutter: Dp,
    onPlayTrack: (Track) -> Unit,
    onShowMore: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            end = gutter,
            bottom = NeedlerTheme.spacing.step12,
        ),
    ) {
        items(items = state.tracks, key = { it.key.canonicalString }) { track ->
            val playable: Boolean = track.hasPlayableFile
            NeedlerAlbumRow(
                title = track.title,
                subtitle = LibraryFormat.songRowSubtitle(track),
                isPlaying = track.key == state.nowPlayingTrackKey,
                onClick = if (playable) ({ onPlayTrack(track) }) else null,
                showDivider = true,
                // No ", playing" appended here: `NeedlerAlbumRow` adds that to
                // whatever description it is given, and saying it twice is worse
                // than not saying it at all.
                contentDescription = buildString {
                    append(track.title)
                    append(", ")
                    append(LibraryFormat.songRowSubtitle(track))
                    LibraryFormat.spokenDuration(track.durationMs)?.let {
                        append(", ")
                        append(it)
                    }
                    if (!playable) append(", not in your library")
                },
                trailing = {
                    LibraryFormat.duration(track.durationMs)?.let { duration ->
                        Text(
                            text = duration,
                            style = NeedlerTheme.typography.duration,
                            color = NeedlerTheme.colors.textMuted,
                        )
                    }
                },
            )
        }
        if (state.atLimit) {
            item(key = "show-more") { GenreShowMore(onShowMore = onShowMore) }
        }
    }
}

/**
 * The way to the rest of a capped genre.
 *
 * ## What this replaces
 *
 * A banner reading "Showing the first 8 tracks of this genre", sitting directly under a header
 * reading `8 tracks · 28 min` — two adjacent lines disagreeing about whether the number was a
 * total. The header says `First 8 tracks` now, which removes the contradiction, and this is the
 * part the banner never had: something to do about it.
 *
 * It is a button at the end of the list rather than a line at the top, because that is where the
 * user discovers the cap. `observeTracksByGenre` is a cap and not a cursor, so each tap re-reads
 * the genre with a larger bound; see [GenreUiState.headerLine] for why a true total cannot be
 * shown instead, and `GenreViewModel.onShowMore` for what the bound does.
 */
@Composable
private fun GenreShowMore(onShowMore: () -> Unit) {
    val spacing = NeedlerTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.step6, bottom = spacing.step4),
    ) {
        NeedlerSecondaryButton(
            text = "Show more",
            onClick = onShowMore,
            size = NeedlerButtonSize.Medium,
            contentDescription = "Show more tracks in this genre",
        )
    }
}

/**
 * A genre with no playable tracks in it.
 *
 * Reachable in one real case: the genre is tagged on an album the library owns
 * whose files never arrived, so the mirror has the genre and no tracks under it.
 * The copy says that rather than implying the user did something wrong.
 *
 * ## The promise now has a button behind it
 *
 * It read "The next sync should fill it in" and offered no way to have a next sync, which names a
 * cause and withholds the remedy — the same fault `GenresEmptyState` had. `Sync now` is the
 * remedy, disabled with no connection in the pattern
 * `screenshots/library-empty-offline-phone.png` sets, and the sentence is in the future tense
 * rather than the present because nothing has happened yet.
 */
@Composable
private fun GenreEmptyState(
    genre: String,
    offline: Boolean,
    onSyncNow: () -> Unit,
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
            text = "Nothing under " + genre + " yet",
            style = typography.displayCompact,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "The copy of your library on this device has albums tagged with this genre " +
                "but no tracks under it. The next sync will fill it in.",
            style = typography.body,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(spacing.step4))
        NeedlerPrimaryButton(
            text = "Sync now",
            onClick = onSyncNow,
            enabled = !offline,
            contentDescription = if (offline) "Sync now. Unavailable while offline." else null,
        )
    }
}

@Composable
private fun GenreSkeleton(gutter: Dp) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter)
            .semantics(mergeDescendants = true) { contentDescription = "Loading this genre" },
        verticalArrangement = Arrangement.spacedBy(spacing.step10),
    ) {
        repeat(8) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(14.dp)
                    .clip(NeedlerTheme.shapes.progress)
                    .background(colors.surface),
            )
        }
    }
}

package app.needler.feature.library.genres

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerOnDeviceIcon
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerSettingsRow
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Genre

/**
 * The genres list.
 *
 * A genre is a name and a count, so the row is the pack's settings row (12) rather
 * than the album row: label, value, chevron, 48dp and a hairline. The album row
 * would put a 48dp artwork square in front of every genre with nothing to draw in
 * it — a genre has no cover, and the pack tints an empty artwork square rather
 * than hiding it, so the list would be a column of blank tiles.
 *
 * REQUIREMENTS.md "Library browse" orders genres alphabetically. The mirror does
 * that; this screen draws them in the order it is given.
 */
@Composable
fun GenresScreen(
    state: GenresUiState,
    widthSizeClass: WindowWidthSizeClass,
    onGenreClick: (Genre) -> Unit,
    onSyncNow: () -> Unit,
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
            Column(verticalArrangement = Arrangement.spacedBy(spacing.step1)) {
                Text(
                    text = TITLE,
                    style = NeedlerTheme.typography.screenTitle,
                    color = colors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                if (!state.loading) {
                    Text(
                        text = state.countLine,
                        style = NeedlerTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
            if (state.offline) GenresOfflineNote()
        }

        Spacer(modifier = Modifier.height(spacing.step9))

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.loading -> GenresSkeleton(gutter = gutter)
                state.showEmptyState -> GenresEmptyState(
                    offline = state.offline,
                    onSyncNow = onSyncNow,
                    gutter = gutter,
                )
                else -> GenreList(
                    genres = state.genres,
                    gutter = gutter,
                    onGenreClick = onGenreClick,
                )
            }
        }
    }
}

@Composable
private fun GenreList(
    genres: List<Genre>,
    gutter: Dp,
    onGenreClick: (Genre) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            end = gutter,
            bottom = NeedlerTheme.spacing.step12,
        ),
    ) {
        items(items = genres, key = { it.name }) { genre ->
            NeedlerSettingsRow(
                label = genre.name,
                value = genreRowValue(genre),
                onClick = { onGenreClick(genre) },
            )
        }
    }
}

/**
 * Nothing in the mirror carries a genre.
 *
 * Two quite different situations, and the copy covers both without guessing which:
 * the library has not been synced at all, or it has been synced and this server's
 * files have no genre tags.
 *
 * ## Why the sync button is here after all
 *
 * It was left out on the argument that syncing is the library screen's action and two buttons
 * doing one thing in two places is a duplication. That reasoning names a cause the screen then
 * refuses to let the user fix: of the two situations above, one of them — "the library has not
 * been synced" — is undone by exactly one action, and the screen stating the cause and withholding
 * the action leaves the user to guess that the fix is two screens away. `LibraryEmptyState` already
 * carries the same button for the same reason, and `screenshots/library-empty-offline-phone.png`
 * sets how it behaves with no connection: disabled, and saying so in its spoken label rather than
 * being absent.
 *
 * It is not a duplicate control so much as the same control where the cause is named. Both call
 * `SyncRepository.deltaSync(force = true)`, so there is one behaviour behind the two.
 */
@Composable
private fun GenresEmptyState(offline: Boolean, onSyncNow: () -> Unit, gutter: Dp) {
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
            text = "No genres yet",
            style = typography.displayCompact,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "Genres come from the tags on your server's music. Nothing in the copy of " +
                "your library on this device carries one yet — either the library has not been " +
                "synced, or these files are untagged.",
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
private fun GenresSkeleton(gutter: Dp) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter)
            .semantics(mergeDescendants = true) { contentDescription = "Loading genres" },
        verticalArrangement = Arrangement.spacedBy(spacing.step10),
    ) {
        repeat(8) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(14.dp)
                    .clip(NeedlerTheme.shapes.progress)
                    .background(colors.surface),
            )
        }
    }
}

/**
 * The offline line, in the same words the library screen uses.
 *
 * `internal` so the genre screen beside this one says exactly the same thing: a
 * top-level `private` in Kotlin is file-scoped, and two copies of this sentence is
 * how two screens come to describe the same architecture differently.
 */
@Composable
internal fun GenresOfflineNote(modifier: Modifier = Modifier) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = OFFLINE_NOTE
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerOnDeviceIcon(tint = colors.positive)
        Text(
            text = OFFLINE_NOTE,
            style = NeedlerTheme.typography.caption,
            color = colors.textSecondary,
        )
    }
}

private const val TITLE: String = "GENRES"

internal const val OFFLINE_NOTE: String =
    "Offline. Genres come from the copy of your library on this device, so they read the same " +
        "with no connection."

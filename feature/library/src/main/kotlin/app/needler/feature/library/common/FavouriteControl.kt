package app.needler.feature.library.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.theme.NeedlerTheme

/**
 * The star: the one favourites control the app has.
 *
 * `FavouriteRepository` has been complete since the data layer was written -
 * `observeIsFavourite`, `setFavourite`, the `pending_sync` column that lets a star survive being
 * tapped offline, `star`/`unstar` wired to the server - and until now nothing in the UI called any of
 * it. "Starred" was already an option on the library's sort control, so the app could order a list by
 * a property no screen let anyone set.
 *
 * REQUIREMENTS.md "Playlists" is explicit about which mechanism this is: "`setRating` is a deliberate
 * no-op on this server: it validates and returns success without persisting anything. Needler must not
 * offer star ratings. Binary favourites via `star`/`unstar` do persist and are the supported
 * mechanism." So this is a two-state control and there is deliberately no five-star row anywhere near
 * it — a rating UI here would report success and lose the user's input silently, which is the worst
 * failure a control can have.
 *
 * ## Why the glyph is drawn here rather than added to `:core:design`
 *
 * `Icons.kt` says it plainly: "Screens that need icons the design system does not own (nav glyphs,
 * shuffle, the tonearm) can draw them the same way with [NeedlerStrokeIcon]." The design pack draws no
 * star, so there is no pack value to transcribe into the design system, and inventing one there would
 * put a shape with no source in the file whose whole claim is that every shape in it has one.
 *
 * ## Two channels, not one
 *
 * Filled versus outlined carries the state, and the colour changes with it — but colour is never the
 * only signal, for the same reason the playing row draws a record glyph as well as an accent title.
 * The spoken description leads with "Starred" when it is on, matching how the album screen's Pull
 * local button reads "On device. Remove …", and the node reports `selected` so TalkBack announces the
 * state without relying on the wording.
 *
 * @param name what is being starred, for the spoken description. An album title, an artist name, a
 *   track title.
 * @param visualSize the drawn glyph. The touch target is [NeedlerIconButton]'s 48dp minimum whatever
 *   this is, which is what lets a 36dp star sit on a 52dp track row without breaking
 *   REQUIREMENTS.md "Accessibility".
 */
@Composable
internal fun FavouriteButton(
    isFavourite: Boolean,
    name: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    visualSize: Dp = 40.dp,
    glyphSize: Dp = 20.dp,
) {
    val colors = NeedlerTheme.colors
    NeedlerIconButton(
        contentDescription = favouriteContentDescription(isFavourite = isFavourite, name = name),
        onClick = onToggle,
        modifier = modifier.semantics { selected = isFavourite },
        enabled = enabled,
        visualSize = visualSize,
    ) {
        NeedlerStrokeIcon(
            pathData = PATH_STAR,
            tint = when {
                // `textMuted` means disabled, and only disabled. It measures 4.21:1 on the canvas
                // and REQUIREMENTS.md "Accessibility" keeps it as drawn for placeholders and
                // disabled text; using it for an *available* control would have said "you cannot
                // press this" in the one colour the palette reserves for that. An un-starred star
                // is off, not unavailable, so it wears `textSecondary` (7.0:1 on the canvas).
                !enabled -> colors.textMuted
                isFavourite -> colors.accent
                else -> colors.textSecondary
            },
            size = glyphSize,
            filled = isFavourite,
        )
    }
}

/**
 * What TalkBack says.
 *
 * Internal rather than private so the tests can assert on the exact wording: this is the only place in
 * the app where the state of a favourite is announced, and "Starred" versus "Star" is one character
 * away from telling the user the opposite of the truth.
 */
internal fun favouriteContentDescription(isFavourite: Boolean, name: String): String =
    if (isFavourite) {
        "Starred. Remove " + name + " from your favourites"
    } else {
        "Star " + name
    }

/**
 * A five-pointed star on the pack's 24x24 icon viewport.
 *
 * Outer radius 8.5, inner radius 3.9, first point straight up, so it matches the optical weight of the
 * pack's other 24-unit glyphs. Stroked when off and filled when on, which is the same
 * outline-to-solid pair `NeedlerStrokeIcon` already draws for the play triangle.
 */
private const val PATH_STAR: String =
    "M12 3.5L14.29 8.85L20.08 9.37L15.71 13.21L17 18.88L12 15.9L7 18.88L8.29 13.21" +
        "L3.92 9.37L9.71 8.85Z"

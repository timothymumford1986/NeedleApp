package app.needler.core.design.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * The favourite heart: the one favourite glyph the app draws.
 *
 * ## Why it is here and not in `Icons.kt`
 *
 * `Icons.kt` opens by claiming that every shape in it "keeps the pack's own path data on a 24x24
 * viewport", so "an icon here is the same shape the design shows and not a lookalike from an icon
 * set". That claim is worth more than the convenience of having every glyph in one file. The design
 * pack draws no favourite control anywhere - which is why the player shipped without one and the
 * library drew its own - so neither this heart nor the star that preceded it has a `d` attribute to
 * transcribe. It therefore lives in this file, which promises nothing of the kind, rather than
 * weakening the one file whose whole value is that its shapes have a source. Everything else about it
 * is the pack's idiom: the same 24x24 viewport and the same 1.8-unit round-capped stroke
 * [NeedlerStrokeIcon] draws, which is the arrangement that file's own text invites for shapes the pack
 * does not own.
 *
 * ## Why a heart, and why the star lost
 *
 * There were two favourite controls until now, both called `FavouriteButton`: a five-pointed star in
 * `:feature:library` on album and artist, and a heart in `:feature:player` on Now Playing and the
 * tablet sidebar. Neither had pack provenance, so there was no pack value to defer to and the choice
 * was free. It went to the heart on a safety argument, not a visual one.
 *
 * REQUIREMENTS.md "Playlists" is explicit: `setRating` on this server "validates and returns success
 * without persisting anything", so "Needler must not offer star ratings. Binary favourites via
 * `star`/`unstar` do persist and are the supported mechanism." A rating UI would therefore report
 * success and silently lose the user's input, which is the worst failure a control can have. A star is
 * the universal signifier for exactly that forbidden five-point rating, and one star invites the user
 * to look for the other four points - of a feature this server cannot keep. A heart has no second
 * reading: it is on or it is off, which is precisely the shape of `star` and `unstar`. The spec's own
 * hazard table picked the glyph.
 *
 * The vocabulary did not change with the shape. "Starred" and "Star X" match the server's verbs and
 * `getStarred2`, and the library's sort control already offers a "Starred" option, so the spoken
 * description still says star while the drawn shape is a heart. That is deliberate: the hazard is the
 * five-pointed *picture*, not the word.
 *
 * ## One path, two states
 *
 * Stroked when off and filled with the same path when on, rather than an outline glyph and a separate
 * solid one, so the filled heart is exactly the shape the outline encloses and the control does not
 * appear to change size when it is tapped.
 */
const val PathHeart: String =
    "M12 20.6l-7.1-7.1a4.8 4.8 0 0 1 0-6.8 4.8 4.8 0 0 1 6.8 0l0.3 0.3 0.3-0.3" +
        "a4.8 4.8 0 0 1 6.8 0 4.8 4.8 0 0 1 0 6.8z"

/**
 * The favourite control: one heart, every surface.
 *
 * It is here in `:core:design` because two feature modules draw it and a feature module cannot see
 * another feature module - which is how the app came to have two of them drawing two different shapes
 * with two different sets of metrics.
 *
 * Each module briefly kept a thin `FavouriteButton` of its own that forwarded to this, on the grounds
 * that their call sites say different things: the library names the subject ("Star Revolver"), the
 * player has only the track that is playing. Those two files existed to choose between two strings, so
 * they are gone and the five call sites pass [contentDescription] here directly. The library's three
 * take theirs from `favouriteContentDescription`, which is named and tested because interpolating a
 * subject into "Starred" or "Star" is the wording that can go wrong; the player's two pass a fixed
 * pair inline.
 *
 * There is no heart on the mini player, and the merge did not add one. That bar is 64dp holding a
 * title, an artist, a 44dp play button and next; a fourth target in it would be under the thumb that
 * was reaching for play. Now Playing is one tap away and carries the control at full size.
 *
 * ## Two channels, never one
 *
 * Fill carries the state as well as colour, for the same reason the playing row draws a record glyph
 * as well as an accent title: the two colours here are a pale blue and a pale grey-green, and a state
 * told only by colour is a state some people cannot read. The node also reports `selected`, so
 * TalkBack announces on or off whatever the wording does.
 *
 * Fill follows [isFavourite] alone and not `isFavourite && enabled`, which is what the player's copy
 * did. A starred track whose control is briefly disabled while a write is in flight is still starred,
 * and drawing it hollow said the opposite. Enablement is carried by the tint; the state is carried by
 * the fill.
 *
 * @param contentDescription what TalkBack says. Required and not nullable, as on [NeedlerIconButton]:
 *   this control has no visible label. The caller owns the wording because only the caller knows what
 *   is being starred.
 * @param visualSize the drawn control. The touch target is [NeedlerIconButton]'s 48dp minimum
 *   whatever this is, which is what lets a 36dp control sit on a 52dp track row without breaking
 *   REQUIREMENTS.md "Accessibility".
 * @param glyphSize the heart drawn inside it.
 */
@Composable
fun NeedlerFavouriteButton(
    isFavourite: Boolean,
    contentDescription: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    visualSize: Dp = 44.dp,
    glyphSize: Dp = 22.dp,
) {
    val colors = NeedlerTheme.colors
    NeedlerIconButton(
        contentDescription = contentDescription,
        onClick = onToggle,
        modifier = modifier.semantics { selected = isFavourite },
        enabled = enabled,
        visualSize = visualSize,
    ) {
        NeedlerStrokeIcon(
            pathData = PathHeart,
            tint = when {
                // `textMuted` means disabled, and only disabled. It measures 4.21:1 on the canvas
                // and REQUIREMENTS.md "Accessibility" keeps it as drawn for "placeholders,
                // timecodes, disabled text, inactive nav items"; using it for an *available*
                // control would have said "you cannot press this" in the one colour the palette
                // reserves for that. An un-starred heart is off, not unavailable, so it wears
                // `textSecondary` at 7.0:1 - which is also the pair Shuffle and Repeat use two rows
                // below it in the player, so "this is on" looks the same everywhere.
                !enabled -> colors.textMuted
                isFavourite -> colors.accent
                else -> colors.textSecondary
            },
            size = glyphSize,
            filled = isFavourite,
        )
    }
}

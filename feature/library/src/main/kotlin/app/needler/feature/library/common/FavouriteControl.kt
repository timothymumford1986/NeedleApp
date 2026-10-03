package app.needler.feature.library.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerFavouriteButton

/**
 * The library's favourite control: the shared heart, named for what it is starring.
 *
 * `FavouriteRepository` has been complete since the data layer was written -
 * `observeIsFavourite`, `setFavourite`, the `pending_sync` column that lets a favourite survive being
 * tapped offline, `star`/`unstar` wired to the server - and for a long time nothing in the UI called
 * any of it. "Starred" was already an option on the library's sort control, so the app could order a
 * list by a property no screen let anyone set.
 *
 * REQUIREMENTS.md "Playlists" is explicit about which mechanism this is: "`setRating` is a deliberate
 * no-op on this server: it validates and returns success without persisting anything. Needler must not
 * offer star ratings. Binary favourites via `star`/`unstar` do persist and are the supported
 * mechanism." So this is a two-state control and there is deliberately no five-star row anywhere near
 * it — a rating UI here would report success and lose the user's input silently, which is the worst
 * failure a control can have.
 *
 * ## Why this file no longer draws anything
 *
 * It used to draw its own five-pointed star, because the design pack owns no favourite glyph and
 * `:core:design` would not take a shape with no pack source. Meanwhile `:feature:player` drew a heart
 * for the same job, and the device reported the obvious: "some screens have a star for favourites,
 * some have a love heart. Just be consistent." Both glyphs and the colour and sizing rules around them
 * now live in one place, [NeedlerFavouriteButton], whose KDoc records why the heart won and why the
 * star was the riskier shape to keep. What is left here is the part that is genuinely the library's:
 * the subject's name.
 *
 * The rejected alternative was to keep the star and change the player. It lost on the spec: a star
 * glyph signifies the one thing REQUIREMENTS.md forbids this app from offering.
 *
 * @param name what is being starred, for the spoken description. An album title, an artist name, a
 *   track title.
 * @param visualSize the drawn control. The touch target is `NeedlerIconButton`'s 48dp minimum whatever
 *   this is, which is what lets a 36dp control sit on a 52dp track row without breaking
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
    NeedlerFavouriteButton(
        isFavourite = isFavourite,
        contentDescription = favouriteContentDescription(isFavourite = isFavourite, name = name),
        onToggle = onToggle,
        modifier = modifier,
        enabled = enabled,
        visualSize = visualSize,
        glyphSize = glyphSize,
    )
}

/**
 * What TalkBack says.
 *
 * Internal rather than private so the tests can assert on the exact wording: this is the only place in
 * the app where the state of a favourite is announced with the subject's name, and "Starred" versus
 * "Star" is one character away from telling the user the opposite of the truth.
 *
 * The wording stayed when the glyph changed from a star to a heart. It matches the server's own
 * `star`/`unstar` verbs and `getStarred2`, and the library's sort control already offers a "Starred"
 * option, so the spoken vocabulary is consistent with everything around it. The hazard REQUIREMENTS.md
 * "Playlists" describes is a five-pointed *picture* that implies a rating this server silently
 * discards; the verb carries no such implication.
 */
internal fun favouriteContentDescription(isFavourite: Boolean, name: String): String =
    if (isFavourite) {
        "Starred. Remove " + name + " from your favourites"
    } else {
        "Star " + name
    }

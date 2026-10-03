package app.needler.feature.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerFavouriteButton

/**
 * The favourite heart, for the track that is playing.
 *
 * The player had no favourite control at all while `FavouriteRepository` had been fully implemented
 * the whole time, offline `pending_sync` included. REQUIREMENTS.md is explicit about which mechanism
 * this is: "Binary favourites via `star`/`unstar` do persist and are the supported mechanism", and
 * equally explicit about what must never appear beside it - `setRating` on this server "validates and
 * returns success without persisting anything", so a star rating would silently do nothing and there
 * is no rating concept in the domain to build one from. This control is binary, and that is the whole
 * design.
 *
 * ## The glyph and the colours moved out
 *
 * The heart, the accent-when-on and secondary-when-off pair it shares with Shuffle and Repeat two rows
 * below, and the one-path-stroked-or-filled rule are all [NeedlerFavouriteButton] now. They had to
 * move: `:feature:library` drew a five-pointed star for the same job, two feature modules cannot see
 * each other, and the device reported the result as "some screens have a star for favourites, some
 * have a love heart". The heart won the merge - [NeedlerFavouriteButton]'s KDoc has the argument, which
 * is that REQUIREMENTS.md forbids the star *rating* this server cannot persist and a star glyph is that
 * rating's signifier. What is left here is the player's own wording and its own metrics.
 *
 * One behaviour did change in the move: the heart used to go hollow whenever the control was disabled,
 * so a starred track drew as un-starred while a write was in flight. Fill now follows the state alone
 * and the tint carries enablement. No call site passes `enabled = false` today, so nothing on screen
 * moved.
 *
 * ## Where it does not go
 *
 * There is no heart on the mini player. The bar is 64 dp holding a title, an artist, a 44 dp play
 * button and next; a fourth target in it would be under the thumb that was reaching for play. Now
 * Playing is one tap away and carries the control at full size.
 *
 * @param isFavourite whether the track is starred, from `FavouriteRepository.observeIsFavourite`. The
 *   repository writes the mirror before it asks the server, so this flips under the finger whatever the
 *   network is doing.
 */
@Composable
fun FavouriteButton(
    isFavourite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    visualSize: Dp = 44.dp,
    iconSize: Dp = 22.dp,
) {
    NeedlerFavouriteButton(
        isFavourite = isFavourite,
        contentDescription = if (isFavourite) "Remove from favourites" else "Add to favourites",
        onToggle = onToggle,
        modifier = modifier,
        enabled = enabled,
        visualSize = visualSize,
        glyphSize = iconSize,
    )
}

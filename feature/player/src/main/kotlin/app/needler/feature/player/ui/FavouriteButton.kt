package app.needler.feature.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.theme.NeedlerTheme

/**
 * The favourite heart, for the track that is playing.
 *
 * The player had no favourite control at all - no heart anywhere - while `FavouriteRepository` had been
 * fully implemented the whole time, offline `pending_sync` included. REQUIREMENTS.md is explicit about
 * which mechanism this is: "Binary favourites via `star`/`unstar` do persist and are the supported
 * mechanism", and equally explicit about what must never appear beside it - `setRating` on this server
 * "validates and returns success without persisting anything", so a star rating would silently do
 * nothing and there is no rating concept in the domain to build one from. This control is binary, and
 * that is the whole design.
 *
 * ## On and off are the transport's on and off
 *
 * Accent when starred, secondary when not - the same pair Shuffle and Repeat use two rows below, so "this
 * is on" looks the same everywhere in the player. Secondary rather than muted for the same reason those
 * two changed: REQUIREMENTS.md keeps `#6f7a68` as drawn for "placeholders, timecodes, disabled text,
 * inactive nav items", and an unstarred heart is a live control rather than any of those.
 *
 * The shape carries the state as well, filled against stroked, because a state told only by colour is a
 * state some people cannot read - and the two colours here are a pale blue and a pale grey-green.
 *
 * ## Where it does not go
 *
 * There is no heart on the mini player. The bar is 64 dp holding a title, an artist, a 44 dp play button
 * and next; a fourth target in it would be under the thumb that was reaching for play. Now Playing is one
 * tap away and carries the control at full size.
 *
 * It is here in `:feature:player` rather than in `:core:design` because it is the first of its kind. When
 * the second surface wants one - album and artist detail in `:feature:library` - the heart, and
 * [PathHeart] with it, should move to `:core:design` beside the other shared glyphs and both call sites
 * shorten.
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
    val colors = NeedlerTheme.colors
    NeedlerIconButton(
        contentDescription = if (isFavourite) "Remove from favourites" else "Add to favourites",
        onClick = onToggle,
        modifier = modifier,
        enabled = enabled,
        visualSize = visualSize,
    ) {
        PlayerHeartIcon(
            tint = when {
                !enabled -> colors.textMuted
                isFavourite -> colors.accent
                else -> colors.textSecondary
            },
            size = iconSize,
            filled = isFavourite && enabled,
        )
    }
}

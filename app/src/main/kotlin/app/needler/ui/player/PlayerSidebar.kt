package app.needler.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerSectionHeader
import app.needler.core.design.component.NeedlerVerticalHairline
import app.needler.core.design.theme.NeedlerTheme

/**
 * The tablet's permanent right-hand player and crate sidebar, as chrome only.
 *
 * Screen 09 fixes the shape: 400dp wide on the pack's surface with a hairline
 * down its left edge, 36dp of top padding and 32dp either side, holding large
 * artwork, the track and album line, the scrubber, the transport, the output
 * selector and the crate. REQUIREMENTS.md "Tablet layout" makes it permanent -
 * it is on screen at expanded width whichever destination is selected, which is
 * why it lives in the navigation scaffold and not in any one destination.
 *
 * **Everything inside it belongs to `:feature:player`.** Now playing, the
 * crate, the EQ, crossfade and the output picker are that module's screens,
 * driven by `PlaybackController` in `:core:domain`. This file draws the panel
 * and its empty state so the tablet layout is complete and renderable, and
 * stops there: no transport that does nothing, no fake track, no artwork of an
 * album nobody is playing. When `:feature:player` lands, the scaffold's
 * `sidebar` parameter takes its composable and this file goes.
 *
 * The empty state is not throwaway work, incidentally - REQUIREMENTS.md
 * requires the player surfaces to "render sensibly with no network and no
 * active playback, since that is their most common state".
 */
@Composable
fun PlayerSidebar(modifier: Modifier = Modifier) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val sizes = NeedlerTheme.sizes
    val spacing = NeedlerTheme.spacing

    Row(modifier = modifier.fillMaxHeight()) {
        NeedlerVerticalHairline()
        Column(
            modifier = Modifier
                .width(sizes.sidebarWidth)
                .fillMaxHeight()
                .background(colors.surface)
                .padding(
                    start = spacing.tabletSidebarGutter,
                    end = spacing.tabletSidebarGutter,
                    top = 36.dp,
                    bottom = spacing.tabletSidebarGutter,
                ),
            verticalArrangement = Arrangement.spacedBy(spacing.step12),
        ) {
            // The artwork well. Square, as the pack draws it, with the
            // placeholder tint rather than an image: nothing is playing.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(NeedlerTheme.shapes.artworkHeroTablet)
                    .background(colors.artworkPlaceholder),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Nothing playing",
                    style = typography.body,
                    color = colors.textMuted,
                )
            }

            Text(
                text = "The crate is empty",
                style = typography.sidebarTitle,
                color = colors.textPrimary,
            )
            Text(
                text = "Play an album and it lands here.",
                style = typography.meta,
                color = colors.textSecondary,
            )

            NeedlerSectionHeader(title = "IN THE CRATE")
            Text(
                text = "Transport, scrubber, output picker and the crate are built in " +
                    ":feature:player. This panel is the sidebar it slots into.",
                style = typography.meta,
                color = colors.textMuted,
            )
        }
    }
}

package app.needler.screenshot

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
 * The tablet's right-hand player panel as chrome only, for screenshots.
 *
 * The real panel is `:feature:player`'s `PlayerSidebarRoute`, and the running
 * app is given that one - see `NeedlerNavHost`. It cannot be used here: it
 * resolves two Hilt view models and binds them to a live `PlaybackController`,
 * and [NavigationScreenshotTest] renders the scaffold with no Hilt graph and no
 * session precisely so its images are of the *chrome* and nothing else.
 *
 * So this draws the panel's shape and stops: 400dp on the pack's surface with a
 * hairline down its left edge, 36dp of top padding and 32dp either side, per
 * screen 09. What that leaves beside it is the content pane the app really
 * gives a destination at expanded width - 1280 − 96 − 400 = 784dp - which is
 * the measurement the tablet images exist to hold still. `:feature:library`
 * keeps the same kind of stand-in for the same reason, in `TabletFrame`.
 *
 * It lives in the test source set, and that is the point of it being here at
 * all: until this move it was `:app` production code *and* the scaffold's
 * default `sidebar`, so any caller that forgot the parameter shipped it to a
 * real device. One did, and a phone in landscape crosses into the expanded
 * branch, so the panel announced "Nothing playing" over audible playback. A
 * placeholder that only the renderer can reach cannot do that again.
 *
 * The pixels are unchanged from the file this came from
 * (`app/src/main/kotlin/app/needler/ui/player/PlayerSidebar.kt`), deliberately:
 * the committed `nav-*-tablet.png` goldens are of this composition, and moving
 * a file is not a reason to redraw them.
 */
@Composable
internal fun PlayerSidebarPlaceholder(modifier: Modifier = Modifier) {
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

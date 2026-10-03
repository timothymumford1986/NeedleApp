package app.needler.feature.pulls.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerBottomNavBar
import app.needler.core.design.component.NeedlerNavItem
import app.needler.core.design.component.NeedlerSearchIcon
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathPull
import app.needler.core.design.theme.NeedlerTheme

/**
 * The phone chrome the app puts under this screen, for screenshots only.
 *
 * ## Why this exists at all
 *
 * A device report said the last pull row was clipped behind the mini player and its **Cancel**
 * unreachable. Every image in this suite rendered the screen alone on a 390x844 canvas, with
 * nothing below it — so whatever the chrome does to the bottom of the list, no baseline could
 * ever have shown it. [TabletFrame] exists for the same reason at the other width, and its KDoc
 * makes the same argument: without the chrome, a render "would not show the screen the app will
 * actually present".
 *
 * ## What it reproduces, and how faithfully
 *
 * `:app`'s `NeedlerNavigationScaffold` lays the phone out as a `Column` of four things: the content
 * in a `weight(1f)` `Box`, then the update banner, the mini player and the bottom bar. The weighted
 * child is measured with whatever the three unweighted ones leave, which is why the chrome takes
 * space **from** the content pane instead of being drawn over it. This frame is that same `Column`,
 * so an image taken in it shows the viewport the list really gets.
 *
 * The bar is the real `:core:design` component. The mini player is a labelled placeholder of
 * `miniPlayerMinHeight`, for the reason [TabletFrame]'s sidebar is one: `:feature:player` is not
 * built here, and what matters for this question is the height it occupies rather than what it
 * draws inside. The update banner is omitted because it composes to nothing except during an
 * update.
 *
 * What this frame cannot reproduce is window insets: Robolectric renders with none, so the
 * navigation-bar and gesture areas are absent here and present on a device. That is the one gap,
 * and it is why this is evidence about the layout rather than proof about the device.
 */
@Composable
internal fun PhoneFrame(
    badgeCount: Int = 2,
    miniPlayer: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = NeedlerTheme.colors
    Column(modifier = Modifier.fillMaxSize().background(colors.canvas)) {
        // weight(1f), exactly as the scaffold has it: the chrome below is measured first and this
        // takes what is left. A list inside can therefore scroll its last row clear of the chrome,
        // which is the thing the image is being taken to check.
        Box(modifier = Modifier.weight(1f)) { content() }

        if (miniPlayer) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = NeedlerTheme.sizes.miniPlayerMinHeight)
                    .background(colors.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "MINI PLAYER",
                    style = NeedlerTheme.typography.sectionHeader,
                    color = colors.textMuted,
                )
            }
        }

        NeedlerBottomNavBar(
            items = listOf(
                NeedlerNavItem(
                    label = "Library",
                    selected = false,
                    onClick = {},
                    icon = { tint ->
                        NeedlerStrokeIcon(pathData = PHONE_PATH_LIBRARY, tint = tint, size = 24.dp)
                    },
                ),
                NeedlerNavItem(
                    label = "Search",
                    selected = false,
                    onClick = {},
                    icon = { tint -> NeedlerSearchIcon(tint = tint, size = 24.dp) },
                ),
                NeedlerNavItem(
                    label = "Pulls",
                    selected = true,
                    onClick = {},
                    badgeCount = badgeCount,
                    icon = { tint ->
                        NeedlerStrokeIcon(pathData = PathPull, tint = tint, size = 24.dp)
                    },
                ),
                NeedlerNavItem(
                    label = "Settings",
                    selected = false,
                    onClick = {},
                    icon = { tint ->
                        NeedlerStrokeIcon(pathData = PHONE_PATH_SETTINGS, tint = tint, size = 24.dp)
                    },
                ),
            ),
        )
    }
}

private const val PHONE_PATH_LIBRARY: String = "M3 4h18v16H3zM8 4v16M16 4v16"

private const val PHONE_PATH_SETTINGS: String =
    "M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4"

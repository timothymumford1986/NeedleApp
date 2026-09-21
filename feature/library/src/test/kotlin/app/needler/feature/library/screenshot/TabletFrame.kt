package app.needler.feature.library.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerNavItem
import app.needler.core.design.component.NeedlerNavigationRail
import app.needler.core.design.component.NeedlerSearchIcon
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathPull
import app.needler.core.design.theme.NeedlerTheme

/**
 * The tablet chrome from screen 09, for screenshots only.
 *
 * `:app` owns the real navigation scaffold and `:feature:player` owns the
 * sidebar. Neither belongs in this module, and neither is built here — but
 * without them a tablet render of the library would spread four grid cells
 * across the whole 1280dp width, and the resulting PNG could not be compared
 * with `design/png/09-TabletLibrary.png` at all.
 *
 * So the rail is the real `:core:design` component with the pack's four
 * destinations, and the sidebar is a labelled placeholder of the pack's
 * `sidebarWidth`. What that leaves between them is the content pane the app
 * will actually give the library: 1280 − 96 − 400 = 784dp.
 */
@Composable
internal fun TabletFrame(content: @Composable () -> Unit) {
    val colors = NeedlerTheme.colors
    val sizes = NeedlerTheme.sizes
    Row(modifier = Modifier.fillMaxSize().background(colors.canvas)) {
        NeedlerNavigationRail(
            items = listOf(
                NeedlerNavItem(
                    label = "Library",
                    selected = true,
                    onClick = {},
                    icon = { tint -> NeedlerStrokeIcon(pathData = PATH_LIBRARY, tint = tint, size = 24.dp) },
                ),
                NeedlerNavItem(
                    label = "Search",
                    selected = false,
                    onClick = {},
                    icon = { tint -> NeedlerSearchIcon(tint = tint, size = 24.dp) },
                ),
                NeedlerNavItem(
                    label = "Pulls",
                    selected = false,
                    onClick = {},
                    badgeCount = 2,
                    icon = { tint -> NeedlerStrokeIcon(pathData = PathPull, tint = tint, size = 24.dp) },
                ),
                NeedlerNavItem(
                    label = "Settings",
                    selected = false,
                    onClick = {},
                    icon = { tint -> NeedlerStrokeIcon(pathData = PATH_SETTINGS, tint = tint, size = 24.dp) },
                ),
            ),
        )
        Box(modifier = Modifier.weight(1f)) { content() }
        Column(
            modifier = Modifier
                .width(sizes.sidebarWidth)
                .fillMaxHeight()
                .background(colors.surface)
                .padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "PLAYER SIDEBAR",
                style = NeedlerTheme.typography.sectionHeader,
                color = colors.textMuted,
            )
            Text(
                text = "Artwork, transport, output and the crate are built in :feature:player " +
                    "and placed here by :app. This block holds the pack's 400dp so the " +
                    "content pane beside it is the width the library really gets.",
                style = NeedlerTheme.typography.caption,
                color = colors.textMuted,
            )
        }
    }
}

private const val PATH_LIBRARY: String = "M3 4h18v16H3zM8 4v16M16 4v16"

private const val PATH_SETTINGS: String =
    "M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4"

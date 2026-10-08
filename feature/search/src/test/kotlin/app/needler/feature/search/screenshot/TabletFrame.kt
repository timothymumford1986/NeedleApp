package app.needler.feature.search.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerNavItem
import app.needler.core.design.component.NeedlerNavigationRail
import app.needler.core.design.component.NeedlerSearchIcon
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathPull
import app.needler.core.design.theme.NeedlerTheme

/**
 * The tablet chrome from screen 10, for screenshots only.
 *
 * `:app` owns the real navigation scaffold and `:feature:player` owns the
 * sidebar. Neither belongs in this module, and neither is built here — but
 * without them a tablet render of Search would stretch a two-column album grid
 * across the whole 1280dp width, and the resulting PNG could not be compared
 * with `design/png/10-TabletSearch.png` at all.
 *
 * So the rail is the real `:core:design` component with the pack's four
 * destinations and **Search** selected, as screen 10 draws it, and the sidebar
 * is [StubSidebar] at the pack's `sidebarWidth`. What that leaves between them
 * is the content pane the app will actually give this screen:
 * 1280 − 96 − 400 = 784dp.
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
                    selected = false,
                    onClick = {},
                    icon = { tint -> NeedlerStrokeIcon(pathData = PATH_LIBRARY, tint = tint, size = 24.dp) },
                ),
                NeedlerNavItem(
                    label = "Search",
                    selected = true,
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
        StubSidebar(width = sizes.sidebarWidth)
    }
}

/**
 * The 400dp the player sidebar occupies, drawn so that nobody can mistake it for the sidebar.
 *
 * This block is the largest single region of every tablet golden in this module - 400 of 1280dp,
 * very nearly a third of the image - and it is not the product. It is a spacer that holds the
 * pack's sidebar width so the content pane beside it measures 1280 − 96 − 400 = 784dp, which is
 * the width `:app` really gives a destination at expanded width. Nothing inside it is verified by
 * any golden, because there is nothing inside it to verify.
 *
 * It used to say so in prose, in the image: two lines of `:feature:player` and `:app` module paths
 * set in the pack's own muted caption on the pack's own surface colour. That is a stub dressed as
 * product, and a UI review of the committed PNGs read it as module paths shipping to users and
 * raised it as a severity-A defect. The reviewer was wrong about the severity and right about the
 * image: a region that looks like the app, reads like documentation and asserts nothing is the
 * worst of the three options. So the fill is now a magenta hatch behind a dashed border with two
 * words on it, in colours that appear nowhere in the palette, and the explanation lives here -
 * where a reader who wants it will look, and where it cannot be mistaken for copy.
 *
 * **The alternative, rejected.** Rendering the real `PlayerSidebarContent` instead would make the
 * region real pixels and verify them. It is not available: the composable is `:feature:player`'s
 * and this module does not depend on it, and `:feature:player` screenshots the panel for itself at
 * its own width in `player-sidebar-sidebar.png` and `player-sidebar-short-landscape.png`, where it
 * is the whole image rather than a third of someone else's. Duplicating it here would mean two
 * goldens of one composable, the larger of which is harder to read.
 */
@Composable
private fun StubSidebar(width: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clipToBounds()
            .drawBehind {
                drawRect(color = STUB_BACKGROUND)
                var x: Float = -size.height
                while (x < size.width) {
                    drawLine(
                        color = STUB_INK,
                        start = Offset(x, size.height),
                        end = Offset(x + size.height, 0f),
                        strokeWidth = STUB_HATCH_WIDTH,
                    )
                    x += STUB_HATCH_STEP
                }
                drawRect(
                    color = STUB_INK,
                    style = Stroke(
                        width = STUB_BORDER_WIDTH,
                        pathEffect = PathEffect.dashPathEffect(STUB_DASH, 0f),
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = STUB_LABEL,
            style = NeedlerTheme.typography.sectionHeader,
            color = STUB_INK,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(STUB_BACKGROUND)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Two words, and no module path. The reason is in [StubSidebar]'s KDoc, where prose belongs. */
private const val STUB_LABEL: String = "TEST STUB\nNOT THE PLAYER SIDEBAR"

/**
 * Magenta on near-black: deliberately outside the palette.
 *
 * `:core:design`'s `NeedlerColors` is a fixed set of greens, greys and one accent blue, and every
 * one of them would make this block look like a surface the app draws. These two cannot.
 */
private val STUB_INK: Color = Color(0xFFFF2BD1)

private val STUB_BACKGROUND: Color = Color(0xFF14001A)

private const val STUB_HATCH_STEP: Float = 36f

private const val STUB_HATCH_WIDTH: Float = 2f

private const val STUB_BORDER_WIDTH: Float = 6f

private val STUB_DASH: FloatArray = floatArrayOf(24f, 16f)

private const val PATH_LIBRARY: String = "M3 4h18v16H3zM8 4v16M16 4v16"

private const val PATH_SETTINGS: String =
    "M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4"

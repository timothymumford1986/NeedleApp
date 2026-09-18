package app.needler.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.needler.core.design.component.NeedlerBottomNavBar
import app.needler.core.design.component.NeedlerNavItem
import app.needler.core.design.component.NeedlerNavigationRail
import app.needler.core.design.theme.NeedlerTheme
import app.needler.ui.player.PlayerSidebar

/**
 * The app's chrome: navigation on one side of the content, and on a tablet the
 * permanent player sidebar on the other.
 *
 * ## One model, two widths
 *
 * REQUIREMENTS.md "Tablet layout" is explicit that this is "one navigation
 * model at two widths, driven by `WindowSizeClass`" with "nothing tablet-only,
 * so no feature needs building twice". That is why this is a single composable
 * taking [widthSizeClass] rather than a phone scaffold and a tablet scaffold:
 * the destinations, the selection, the badge and the content slot are shared,
 * and only the arrangement differs.
 *
 * | Width | Navigation | Player |
 * | --- | --- | --- |
 * | Compact, Medium | bottom bar (screens 02, 03, 06, 12) | mini-player, then a separate crate screen |
 * | Expanded | 96dp nav rail (screen 09) | permanent 400dp right-hand sidebar (screen 09) |
 *
 * Medium is grouped with Compact deliberately. The pack draws exactly two
 * layouts, and a 700dp-wide foldable cannot hold a 400dp sidebar *and* a
 * four-column grid; it gets the phone arrangement with more room in it, which
 * is what the bottom bar was already designed to do.
 *
 * ## Insets
 *
 * The activity draws edge-to-edge, so the content slot consumes the top and
 * horizontal safe-drawing insets. The bottom bar deliberately does **not**
 * consume the navigation-bar inset: [NeedlerBottomNavBar] already carries the
 * pack's own 24dp bottom inset for the gesture area, and adding the system one
 * on top of it would double the gap.
 *
 * @param pullsBadgeCount the count on the Pulls item. REQUIREMENTS.md calls
 *   this "the reliable channel" for pull state - notifications are best-effort,
 *   this badge is not - so it is plumbed through the scaffold rather than being
 *   drawn from inside one screen. It is a parameter and not a repository read
 *   because `:feature:pulls` owns the source; until that lands the host passes
 *   the real value it has, which is zero.
 * @param sidebar the tablet player sidebar. Defaults to [PlayerSidebar], the
 *   placeholder `:feature:player` replaces.
 */
@Composable
fun NeedlerNavigationScaffold(
    widthSizeClass: WindowWidthSizeClass,
    selected: NeedlerDestination,
    onSelect: (NeedlerDestination) -> Unit,
    modifier: Modifier = Modifier,
    pullsBadgeCount: Int = 0,
    sidebar: @Composable () -> Unit = { PlayerSidebar() },
    content: @Composable () -> Unit,
) {
    val items = NeedlerDestination.entries.map { destination ->
        NeedlerNavItem(
            label = destination.label,
            selected = destination == selected,
            onClick = { onSelect(destination) },
            badgeCount = if (destination == NeedlerDestination.Pulls) pullsBadgeCount else 0,
            icon = { tint -> destination.Icon(tint) },
        )
    }

    val root = modifier
        .fillMaxSize()
        .background(NeedlerTheme.colors.canvas)

    if (widthSizeClass == WindowWidthSizeClass.Expanded) {
        Row(modifier = root) {
            NeedlerNavigationRail(items = items)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                    ),
            ) {
                content()
            }
            sidebar()
        }
    } else {
        Column(modifier = root) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        ),
                    ),
            ) {
                content()
            }
            NeedlerBottomNavBar(items = items)
        }
    }
}

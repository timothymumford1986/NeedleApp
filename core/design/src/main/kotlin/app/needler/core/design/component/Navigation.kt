package app.needler.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * One destination in the bottom bar or the nav rail.
 *
 * The four destinations are fixed by the pack: Library, Search, Pulls, Settings.
 *
 * @param badgeCount the count on the Pulls item. REQUIREMENTS.md calls this "the reliable channel"
 *   for pull state - notifications are best-effort, this badge is not - so it is part of the
 *   destination rather than a decoration the host adds.
 * @param icon handed the item's current tint, so the glyph tracks selection without the caller
 *   having to work out which colour applies.
 */
@Immutable
data class NeedlerNavItem(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val badgeCount: Int = 0,
    val icon: @Composable (tint: Color) -> Unit,
)

/**
 * The phone bottom navigation bar.
 *
 * 84dp tall on the pack's surface with a hairline along its top edge, holding four 56dp items with a
 * 24dp inset at the bottom for the gesture area. The selected item is accent, the rest muted, labels
 * 11sp/600 at `0.02em`.
 *
 * Drawn on screens 02, 03, 06, 12, 13, 19 and 20 - every phone screen that is not a modal.
 *
 * REQUIREMENTS.md's "Tablet layout" pairs this with [NeedlerNavigationRail]: one navigation model at
 * two widths, chosen by `WindowSizeClass` in the host, with neither built twice.
 */
@Composable
fun NeedlerBottomNavBar(
    items: List<NeedlerNavItem>,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        NeedlerHairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .selectableGroup()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalAlignment = Alignment.Top,
        ) {
            items.forEach { item ->
                NavDestination(
                    item = item,
                    modifier = Modifier.weight(1f),
                    selectedColor = colors.accent,
                    unselectedColor = colors.textMutedAccessible,
                )
            }
        }
    }
}

/**
 * The tablet navigation rail.
 *
 * 96dp wide on the pack's surface with a hairline down its right edge: the logo mark at the top,
 * then four 64dp items with an 18dp radius. The selected item sits on
 * [app.needler.core.design.theme.NeedlerColors.surfaceRaised] in the accent colour, which is the one
 * place in the pack where a raised surface marks selection.
 *
 * Drawn on screens 09, 10 and 11.
 */
@Composable
fun NeedlerNavigationRail(
    items: List<NeedlerNavItem>,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = { NeedlerLogoMark(size = 40.dp, ringWidth = 3.dp, dotSize = 12.dp) },
) {
    val colors = NeedlerTheme.colors
    val sizes = NeedlerTheme.sizes
    Row(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(sizes.navRailWidth)
                .fillMaxHeight()
                .background(colors.surface)
                .selectableGroup()
                .padding(horizontal = 16.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (header != null) {
                Box(modifier = Modifier.padding(bottom = 20.dp)) { header() }
            }
            items.forEach { item ->
                NavDestination(
                    item = item,
                    // Width is fixed at the pack's 64dp; height is free to grow with
                    // the label at large font scales.
                    modifier = Modifier.width(sizes.navRailItemSize),
                    selectedColor = colors.accent,
                    unselectedColor = colors.textMutedAccessible,
                    selectedBackground = colors.surfaceRaised,
                )
            }
        }
        NeedlerVerticalHairline()
    }
}

/** A 1dp vertical rule, for the rail's right edge and the sidebar's left one. */
@Composable
fun NeedlerVerticalHairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(NeedlerTheme.sizes.hairlineThickness)
            .background(NeedlerTheme.colors.hairline),
    )
}

/** One item: glyph over label, with the count badge overlapping the glyph's top-right. */
@Composable
private fun NavDestination(
    item: NeedlerNavItem,
    modifier: Modifier,
    selectedColor: Color,
    unselectedColor: Color,
    selectedBackground: Color = Color.Transparent,
) {
    val tint = if (item.selected) selectedColor else unselectedColor
    val spoken = buildString {
        append(item.label)
        if (item.badgeCount > 0) append(", ${item.badgeCount} active")
    }
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = NeedlerTheme.sizes.navItemMinHeight)
            .clip(NeedlerTheme.shapes.extraLarge)
            .background(if (item.selected) selectedBackground else Color.Transparent)
            .selectable(
                selected = item.selected,
                role = Role.Tab,
                onClick = item.onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box {
            item.icon(tint)
            if (item.badgeCount > 0) {
                NeedlerCounterBadge(
                    count = item.badgeCount,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // The pack hangs it off the glyph's corner: top -4px, right -10px.
                        .offset(x = 10.dp, y = (-4).dp),
                )
            }
        }
        Text(
            text = item.label,
            style = NeedlerTheme.typography.navLabel,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

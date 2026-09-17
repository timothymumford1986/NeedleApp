package app.needler.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * The segmented tab control: Albums / Artists / Songs.
 *
 * A 36dp pill on the surface with a hairline border and 3dp of inset, holding 28dp pills. The chosen
 * one is filled with [app.needler.core.design.theme.NeedlerColors.inverseSurface] and labelled in
 * the canvas colour; the others are transparent with a secondary label.
 *
 * Ported from Library (02), the tablet library (09), tablet search (10) and the library list (13).
 *
 * The pack marks the group `role="group"` with an `aria-label` and each segment `aria-pressed`. Here
 * that becomes a `selectableGroup` of `Role.Tab` items, which is what TalkBack expects: it announces
 * "Albums, tab, 1 of 3, selected".
 *
 * @param label names the group for screen readers, e.g. "Browse by".
 */
@Composable
fun NeedlerSegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
) {
    val colors = NeedlerTheme.colors
    val sizes = NeedlerTheme.sizes
    val typography = NeedlerTheme.typography
    val shape = NeedlerTheme.shapes.pill

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = sizes.segmentedHeight)
            .clip(shape)
            .background(colors.surface)
            .border(sizes.hairlineThickness, colors.hairline, shape)
            .padding(sizes.segmentedPadding)
            .selectableGroup()
            .semantics { if (label != null) contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = sizes.segmentedItemHeight)
                    .clip(shape)
                    .background(if (selected) colors.inverseSurface else Color.Transparent)
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = typography.metaStrong,
                    color = when {
                        !enabled -> colors.textMutedAccessible
                        selected -> colors.onInverseSurface
                        else -> colors.textSecondary
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

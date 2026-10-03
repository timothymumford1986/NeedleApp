package app.needler.core.design.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * A dropdown menu wearing the pack.
 *
 * ## Why this exists
 *
 * Material's `DropdownMenu` takes its shape from `MaterialTheme.shapes.extraSmall` and its fill from
 * `MaterialTheme.colorScheme.surfaceContainer`. Needler maps the first to
 * [app.needler.core.design.theme.NeedlerShapes.progress] — a 2dp radius, because that slot is the
 * scrubber track — and does not map the second at all, so an undressed menu comes out with
 * effectively square corners in one of Material's own dark greys rather than a Needler colour. The
 * device reported it as "a flat dark rectangle with sharp corners", under a sort control that is a
 * pill, on a screen where nothing else has a square corner.
 *
 * REQUIREMENTS.md "Design system" gives the radii as "10 px for small chips, 14 px for inputs and
 * cards, 16 to 18 px for buttons and sheets, 999 px for pills"; 2dp is not one of them, and the menu
 * was never meant to land there.
 *
 * ## Which idiom this borrows
 *
 * The 21 screens of the pack draw no open menu, so there is no drawn value to defer to and the
 * choice was made rather than transcribed. A menu is a small floating card, so it takes the card
 * radius, [app.needler.core.design.theme.NeedlerShapes.medium], and the raised surface the pack
 * gives everything that floats above the canvas — the mini-player, a crate row, the emphasised crate
 * control. That is a real luminance step above the canvas rather than a hairline, which matters
 * because REQUIREMENTS.md "Accessibility" records the hairline at 1.20:1 and a menu has to read as a
 * panel in front of the rows it covers.
 *
 * **The alternatives, rejected.** Remapping `extraSmall` in the theme would fix every Material menu
 * at once, and would also move the scrubber track and every other component that reads that slot;
 * the slot is doing two jobs and the menu is the one that can be fixed in its own file. Drawing the
 * menu as a bottom sheet was rejected because the control is anchored and small, and a sheet for a
 * five-item sort would cover the list the sort is about to reorder.
 *
 * Tonal elevation is zero deliberately. Material tints a raised container with `surfaceTint`, which
 * Needler maps to the accent blue, so leaving it at Material's default 3dp would wash a pale blue
 * over a green-black panel. The shadow is zero for the same reason the fill is opaque: the step from
 * canvas to raised surface is the separation.
 */
@Composable
fun NeedlerDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = NeedlerTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = NeedlerTheme.shapes.medium,
        containerColor = colors.surfaceRaised,
        border = BorderStroke(NeedlerTheme.sizes.hairlineThickness, colors.hairline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

package app.needler.core.design.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * The 44dp-wide icon pill is drawn at the pack's own width.
 *
 * `design-html 02-Library`, `09-TabletLibrary` and `13-LibraryList` all give the grid list toggle
 * `width: 44px; height: 36px`, so it is a little wider than it is tall and noticeably narrower than
 * a labelled pill. The number is here rather than in [app.needler.core.design.theme.NeedlerSizes]
 * because it is the width of one control, not a metric anything else in the pack shares.
 */
private val ToolbarIconPillWidth: Dp = 44.dp

/**
 * The pills that sit beside [NeedlerSegmentedTabs] on a control row: the sort control and the grid
 * list toggle on Library (02, 09, 13).
 *
 * REQUIREMENTS.md "Tablet layout" describes that row as "segmented tabs, a sort control and a
 * grid/list toggle" — three peers. They were not built as peers: the tabs were a pack component and
 * these two were private composables inside `LibraryScreen.kt`, which is how a row of three controls
 * came to read as three unrelated controls. Nothing kept the hand-rolled pair in step with the pack,
 * and a device report said so: "it says 'recent' which seems to have adopted a different style".
 *
 * ## The one deliberate departure from the pack
 *
 * The pack draws both of these pills with `background: transparent` and a hairline border, against a
 * segmented control that is filled with [app.needler.core.design.theme.NeedlerColors.surface]. Here
 * they take the same `surface` fill the segmented control has, so the row is three filled pills
 * rather than one container and two outlines.
 *
 * That is a deviation, and the reason is recorded in REQUIREMENTS.md "Accessibility": the hairline,
 * `rgba(242,245,238,0.08)`, "measures 1.20:1 against the canvas, so control boundaries, dividers,
 * input outlines and unselected chip borders are effectively invisible to anyone not already looking
 * for them". A transparent pill whose only boundary is that hairline is, on a real screen, not a
 * pill at all — it is a word floating beside a control that does have a visible container. The fill
 * is the cheapest way to make the three read as one family without touching the hairline token
 * itself, which REQUIREMENTS.md deliberately "carried as an open question rather than quietly
 * patched". Everything else is the pack's: 36dp tall, the pill shape, the same hairline, 13sp at
 * weight 600 in [app.needler.core.design.theme.NeedlerColors.textSecondary], and a 2dp gap before
 * the trailing icon.
 *
 * **The alternative, rejected:** reproduce `background: transparent` exactly. It is what the code
 * already did, pixel for pixel — and it is what produced the report, so matching the pack harder
 * would have been a change that fixed nothing.
 *
 * ## Why this is not [NeedlerPillButton]
 *
 * It is the same pill, and `NeedlerPillButton`'s own documentation already claims "the sort control
 * on Library (02, 09, 13)" among its sources. Two things stop it serving the row today:
 *
 *  - its clickable area is the pill, so the touch target is the drawn 36dp. REQUIREMENTS.md
 *    "Accessibility" asks for 48dp, which
 *    [app.needler.core.design.theme.NeedlerSizes.minTouchTarget] exists to express.
 *  - it has no icon-only form, so it cannot draw the 44x36 toggle at all.
 *
 * The right end state is one pill in the pack: `NeedlerPillButton` gaining
 * `minimumInteractiveComponentSize` and delegating its surface here. `Buttons.kt` is owned by
 * another change in flight, so that merge is handed over rather than done here.
 *
 * ## Drawn at 36dp, touched at 48dp
 *
 * `minimumInteractiveComponentSize` reserves 48dp of layout space and expands the pointer bounds to
 * match, while the pill still draws at the pack's 36dp. The alternative — an outer 48dp `Box`
 * carrying the click — was what the local version did, and it puts the click on an unclipped
 * rectangle, so the press indication is a square around a pill. Clipping before the click is what
 * keeps the ripple the shape of the control.
 *
 * ## The press goes through the shared surface now
 *
 * The clip-fill-border-press sequence below was this file's own, and the paragraph above is the reason
 * it was written out by hand. It is [needlerPressSurface]'s sequence now, which is what the note above
 * said the end state should be — the merge it handed over, taken. The reason it matters beyond tidiness
 * is REQUIREMENTS.md "Motion": press feedback is suppressed to an instant, held state when the system
 * animator duration scale is zero, and a chain that composes its own press is a chain that does not
 * hear about that. See [app.needler.core.design.motion.needlerPressIndication].
 *
 * @param contentDescription what a screen reader announces. Required, not nullable, and separate
 *   from [text]: the pack labels the sort control `aria-label="Sort: recently added"` while drawing
 *   one word, because "Recent" alone says neither what it sorts nor that it is a control.
 * @param trailingIcon drawn after the label and handed the content colour, so a chevron can never
 *   drift away from the word beside it.
 */
@Composable
fun NeedlerToolbarPill(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingIcon: (@Composable (tint: Color) -> Unit)? = null,
) {
    val tint: Color = toolbarPillContentColour(enabled)
    Row(
        modifier = modifier
            .toolbarPillSurface(
                contentDescription = contentDescription,
                onClick = onClick,
                enabled = enabled,
            )
            // The pack's own asymmetry: `padding: 0 8px 0 10px`, which is what stops a chevron
            // sitting further from the pill's edge than the label does from the other.
            .padding(
                start = 10.dp,
                end = if (trailingIcon == null) 10.dp else 8.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = NeedlerTheme.typography.metaStrong,
            color = tint,
            // The pack sets `white-space: nowrap`. The label is one word by design, and the row it
            // sits in wraps the whole pill onto its own line when the text grows, so there is
            // nothing for a second line to buy.
            maxLines = 1,
        )
        trailingIcon?.invoke(tint)
    }
}

/**
 * The icon-only member of the same family: the grid list toggle, 44x36.
 *
 * Everything [NeedlerToolbarPill] says applies. The difference is the pack's fixed width and the
 * absence of a label, which is why [contentDescription] is the only thing a screen reader has and
 * is therefore required.
 *
 * @param icon handed the content colour, so the glyph matches a labelled pill's text exactly.
 */
@Composable
fun NeedlerToolbarIconPill(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (tint: Color) -> Unit,
) {
    val tint: Color = toolbarPillContentColour(enabled)
    Box(
        modifier = modifier.toolbarPillSurface(
            contentDescription = contentDescription,
            onClick = onClick,
            enabled = enabled,
            fixedWidth = ToolbarIconPillWidth,
        ),
        contentAlignment = Alignment.Center,
    ) {
        icon(tint)
    }
}

@Composable
private fun toolbarPillContentColour(enabled: Boolean): Color =
    if (enabled) NeedlerTheme.colors.textSecondary else NeedlerTheme.colors.disabled

/**
 * The shared skeleton: 48dp of touch around a 36dp pill, clipped before it is clickable.
 *
 * @param fixedWidth the icon pill's drawn width. Null lets the pill size to its label.
 */
@Composable
private fun Modifier.toolbarPillSurface(
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean,
    fixedWidth: Dp? = null,
): Modifier {
    val colors = NeedlerTheme.colors
    val sizes = NeedlerTheme.sizes
    val shape = NeedlerTheme.shapes.pill
    return this
        .minimumInteractiveComponentSize()
        .then(if (fixedWidth == null) Modifier else Modifier.width(fixedWidth))
        .defaultMinSize(minHeight = sizes.pillMinHeight)
        .needlerPressSurface(
            shape = shape,
            interaction = { source ->
                Modifier.clickable(
                    interactionSource = source,
                    // Drawn by the helper, above this node and inside the clip. Not suppressed.
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
            },
            background = colors.surface,
            borderColor = colors.componentBorder,
            borderWidth = sizes.hairlineThickness,
        )
        // A merging node, so the pill is one target to a screen reader whatever it holds, and the
        // description below is the whole of what is announced.
        .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
}

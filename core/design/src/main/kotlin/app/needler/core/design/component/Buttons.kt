package app.needler.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * Which of the pack's two fills a button wears.
 *
 * The design uses the accent for anything that plays or proceeds, and the positive green for
 * anything that acquires music - "Pull this album" on screen 05 is the only filled green button in
 * the pack, and it is green precisely because it is not playback.
 */
enum class NeedlerButtonTone {
    /** Pale blue `#aed5f2` on `#071520`. Connect, Play, Pull. */
    Accent,

    /** Pale green `#bbdb9b` on `#0f1a0a`. Pull this album. */
    Positive,

    /** No fill: surface with a hairline border. Shuffle, Sign out. */
    Neutral,
}

/**
 * Button heights and type, from the pack.
 *
 * These are minimums, applied with `defaultMinSize`. REQUIREMENTS.md calls out that "the fixed 54 to
 * 56 px control heights in the design pack will need care to honour" 200% text scaling, so a button
 * here grows rather than clips, and its label wraps to a second line rather than being cut off.
 */
enum class NeedlerButtonSize {
    /** 56dp minimum, 16dp radius, 17sp/700. The Connect button (01, 16). */
    Large,

    /** 52dp minimum, 14dp radius, 14sp/700. Play / Shuffle / Local on album detail (04, 11). */
    Medium,

    /** 40dp minimum, pill, 14sp/700. The Pull action on a search result (03, 10). */
    Small,

    /** 32dp minimum, pill, 13sp/700. Play on a finished pull (06); the output selector (09). */
    Compact,
}

/**
 * A filled button: the pack's primary action.
 *
 * Ported from the Connect button (01, 16), Play and Local on album detail (04, 11), Pull this album
 * (05) and the Pull pill in search results (03, 10).
 *
 * @param leadingIcon drawn before the label and handed the content colour, so the icon always
 *   matches the fill. Most of the pack's primary buttons have one.
 * @param textStyle overrides the size's default type. Pass `NeedlerTheme.typography.rowTitle` with
 *   bold to reproduce the 16sp label on "Pull this album".
 * @param contentDescription overrides the label for screen readers. Leave it `null` when the visible
 *   label already says what the button does, which - given REQUIREMENTS.md's rule that every control
 *   carries a content description - is the usual case for a labelled button.
 */
@Composable
fun NeedlerPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: NeedlerButtonTone = NeedlerButtonTone.Accent,
    size: NeedlerButtonSize = NeedlerButtonSize.Large,
    enabled: Boolean = true,
    leadingIcon: (@Composable (tint: Color) -> Unit)? = null,
    textStyle: TextStyle? = null,
    contentDescription: String? = null,
) {
    val colors = NeedlerTheme.colors
    val background = when {
        !enabled -> colors.surfaceRaised
        tone == NeedlerButtonTone.Accent -> colors.accent
        tone == NeedlerButtonTone.Positive -> colors.positive
        else -> colors.surface
    }
    val content = when {
        !enabled -> colors.textMutedAccessible
        tone == NeedlerButtonTone.Accent -> colors.onAccent
        tone == NeedlerButtonTone.Positive -> colors.onPositive
        else -> colors.textPrimary
    }
    ButtonSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        background = background,
        contentColor = content,
        borderColor = if (tone == NeedlerButtonTone.Neutral) colors.hairline else null,
        size = size,
        text = text,
        leadingIcon = leadingIcon,
        textStyle = textStyle,
        contentDescription = contentDescription,
    )
}

/**
 * An outlined button: the pack's secondary action.
 *
 * Ported from Shuffle on album detail (04, 11) and Sign out on Settings (12).
 *
 * @param selected draw the border and label in the positive green, as screen 04 does for the Local
 *   button on an album already kept on the device. The pack marks that button `aria-pressed="true"`,
 *   so the state is reported to screen readers too.
 * @param filledSurface `true` gives the album-detail look (surface fill plus hairline); `false` the
 *   Sign out look (transparent with a hairline).
 */
@Composable
fun NeedlerSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: NeedlerButtonSize = NeedlerButtonSize.Medium,
    enabled: Boolean = true,
    selected: Boolean = false,
    reportSelection: Boolean = false,
    filledSurface: Boolean = true,
    leadingIcon: (@Composable (tint: Color) -> Unit)? = null,
    textStyle: TextStyle? = null,
    contentDescription: String? = null,
) {
    val colors = NeedlerTheme.colors
    val content = when {
        !enabled -> colors.textMutedAccessible
        selected -> colors.positive
        else -> colors.textPrimary
    }
    ButtonSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        background = if (filledSurface) colors.surface else Color.Transparent,
        contentColor = content,
        borderColor = if (selected) colors.positive else colors.hairline,
        size = size,
        text = text,
        leadingIcon = leadingIcon,
        textStyle = textStyle,
        contentDescription = contentDescription,
        // The pack's outlined buttons are 600, not 700.
        fontWeight = FontWeight.SemiBold,
        selectedState = if (reportSelection) selected else null,
    )
}

/**
 * A pill button, 36dp tall with a hairline border.
 *
 * Ported from Clear done and Retry on Pulls (06), the EQ presets (19) and the sort control on
 * Library (02, 09, 13). The EQ's chosen preset is the [selected] variant: a solid
 * [app.needler.core.design.theme.NeedlerColors.inverseSurface] fill with a canvas-coloured label.
 *
 * @param emphasised use the primary text colour rather than the secondary one, as Retry does.
 */
@Composable
fun NeedlerPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    emphasised: Boolean = false,
    trailingIcon: (@Composable (tint: Color) -> Unit)? = null,
    contentDescription: String? = null,
) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.pill
    val sizes = NeedlerTheme.sizes
    val content = when {
        !enabled -> colors.textMutedAccessible
        selected -> colors.onInverseSurface
        emphasised -> colors.textPrimary
        else -> colors.textSecondary
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = sizes.pillMinHeight)
            .clip(shape)
            .background(if (selected) colors.inverseSurface else Color.Transparent)
            .then(
                if (selected) {
                    Modifier
                } else {
                    Modifier.border(sizes.hairlineThickness, colors.hairline, shape)
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = NeedlerTheme.typography.metaStrong,
            color = content,
            maxLines = 2,
        )
        trailingIcon?.invoke(content)
    }
}

/**
 * A text-only action inside a list, in the accent colour.
 *
 * Ported from Sync now, Change server and Remove all from device (12), and Reset to flat (19). It
 * fills the row's width and aligns left, as those rows do.
 */
@Composable
fun NeedlerTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = NeedlerTheme.colors.accent,
    contentDescription: String? = null,
) {
    val colors = NeedlerTheme.colors
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = NeedlerTheme.sizes.listRowMinHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = NeedlerTheme.typography.body,
            color = if (enabled) color else colors.textMutedAccessible,
        )
    }
}

/**
 * An icon-only control.
 *
 * Back, More, the transport buttons, Clear inside a search field. The pack draws these anywhere
 * between 32dp and 80dp; whatever [visualSize] is asked for, the touch target is never smaller than
 * [app.needler.core.design.theme.NeedlerSizes.minTouchTarget], honouring REQUIREMENTS.md's
 * "transport controls are at least 48 dp".
 *
 * @param contentDescription required, not nullable: this control has no visible label, so a screen
 *   reader has nothing else to announce.
 * @param background pass [app.needler.core.design.theme.NeedlerColors.accent] for the filled
 *   play/pause button; the default is the pack's transparent transport button.
 */
@Composable
fun NeedlerIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    visualSize: Dp = 44.dp,
    background: Color = Color.Transparent,
    shape: Shape = NeedlerTheme.shapes.circle,
    icon: @Composable () -> Unit,
) {
    val touchTarget = maxOf(visualSize, NeedlerTheme.sizes.minTouchTarget)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = touchTarget, minHeight = touchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            // A merging node, so a row that merges its own descendants still leaves this button
            // reachable as a separate target.
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (background == Color.Transparent) {
            icon()
        } else {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = visualSize, minHeight = visualSize)
                    .clip(shape)
                    .background(background),
                contentAlignment = Alignment.Center,
            ) { icon() }
        }
    }
}

/** Shared skeleton for the filled and outlined buttons. */
@Composable
private fun ButtonSurface(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    background: Color,
    contentColor: Color,
    borderColor: Color?,
    size: NeedlerButtonSize,
    text: String,
    leadingIcon: (@Composable (tint: Color) -> Unit)?,
    textStyle: TextStyle?,
    contentDescription: String?,
    fontWeight: FontWeight = FontWeight.Bold,
    selectedState: Boolean? = null,
) {
    val shapes = NeedlerTheme.shapes
    val sizes = NeedlerTheme.sizes
    val typography = NeedlerTheme.typography

    val shape: Shape = when (size) {
        NeedlerButtonSize.Large -> shapes.large
        NeedlerButtonSize.Medium -> shapes.medium
        NeedlerButtonSize.Small, NeedlerButtonSize.Compact -> shapes.pill
    }
    val minHeight = when (size) {
        NeedlerButtonSize.Large -> sizes.primaryButtonMinHeight
        NeedlerButtonSize.Medium -> sizes.buttonMinHeight
        NeedlerButtonSize.Small -> sizes.pullButtonMinHeight
        NeedlerButtonSize.Compact -> sizes.pillSmallMinHeight
    }
    val defaultStyle: TextStyle = when (size) {
        NeedlerButtonSize.Large -> typography.bodyLarge
        NeedlerButtonSize.Medium, NeedlerButtonSize.Small -> typography.bodySmall
        NeedlerButtonSize.Compact -> typography.metaStrong
    }
    val horizontalPadding = when (size) {
        NeedlerButtonSize.Large, NeedlerButtonSize.Small -> 16.dp
        NeedlerButtonSize.Medium -> 10.dp
        NeedlerButtonSize.Compact -> 12.dp
    }
    val gap = if (size == NeedlerButtonSize.Compact) 4.dp else 8.dp

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .clip(shape)
            .background(background)
            .then(
                if (borderColor != null) {
                    Modifier.border(sizes.hairlineThickness, borderColor, shape)
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                if (selectedState != null) this.selected = selectedState
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.invoke(contentColor)
        Text(
            text = text,
            style = (textStyle ?: defaultStyle).copy(fontWeight = fontWeight),
            color = contentColor,
            textAlign = TextAlign.Center,
            // Two lines rather than a clip: the pack sets `white-space: nowrap`, but a label grown
            // to 200% has to go somewhere.
            maxLines = 2,
        )
    }
}

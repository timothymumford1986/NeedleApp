package app.needler.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * The labelled text field from the Connect screen (01, 16).
 *
 * A small uppercase label in the secondary colour, 8dp of air, then a 54dp field on the surface with
 * a 14dp radius and a hairline border that turns accent while focused - the focus treatment the pack
 * shows on the active search field (03).
 *
 * The field's height is a `defaultMinSize` and the label wraps, so the control grows with the user's
 * font size instead of clipping at 200%.
 *
 * REQUIREMENTS.md changes this screen's third field from `APP PASSWORD` to `PASSWORD` with helper
 * text reading *your Dropped Needle account password*; [helperText] is how that is rendered. The
 * field's visual design is unchanged.
 *
 * @param label rendered as given. The pack writes these already uppercase ("SERVER", "USERNAME").
 * @param helperText a quiet line under the field. Guidance, not errors.
 * @param errorText when non-null, replaces [helperText] and is announced through the `error`
 *   semantics property. The pack draws no error colour at all, so the line uses the accent - the
 *   palette's only emphasis colour. A real error treatment needs a design decision.
 */
@Composable
fun NeedlerLabelledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helperText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val sizes = NeedlerTheme.sizes
    val shape = NeedlerTheme.shapes.medium
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val borderColor = if (focused || errorText != null) colors.accent else colors.hairline

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = label, style = typography.fieldLabel, color = colors.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = typography.bodyLarge.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                // Without this the label and the field are unrelated nodes and the field announces
                // as unlabelled. TalkBack reads the description, then the editable text.
                .semantics {
                    contentDescription = label
                    if (errorText != null) error(errorText)
                },
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .clip(shape)
                        .background(colors.surface)
                        .border(sizes.hairlineThickness, borderColor, shape)
                        .defaultMinSize(minHeight = sizes.textFieldMinHeight)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = typography.bodyLarge,
                                // Placeholders carry information here - the URL form the user has
                                // to match - so they use the AA-compliant muted value rather than
                                // the drawn 4.21:1 one.
                                color = colors.textMuted,
                            )
                        }
                        innerTextField()
                    }
                    trailingIcon?.invoke()
                }
            },
        )
        val supporting = errorText ?: helperText
        if (supporting != null) {
            Text(
                text = supporting,
                style = typography.caption,
                color = if (errorText != null) colors.accent else colors.textMuted,
            )
        }
    }
}

/**
 * The search field from Library and Search (02, 03, 09, 10).
 *
 * A 52dp rounded box on the surface with a 16dp radius, a leading search glyph, and - once there is
 * something to clear - the 32dp round clear button from screen 03. The border turns accent while
 * focused, as the active field on 03 is drawn.
 */
@Composable
fun NeedlerSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Artist, album or song",
    label: String = "Search",
    enabled: Boolean = true,
    onClear: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = typography.bodyLarge.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        decorationBox = { innerTextField ->
            SearchFieldFrame(focused = focused) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = typography.bodyLarge,
                            color = colors.textMuted,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
                if (value.isNotEmpty() && onClear != null) {
                    NeedlerIconButton(
                        contentDescription = "Clear search",
                        onClick = onClear,
                        visualSize = 32.dp,
                        background = colors.surfaceRaised,
                    ) {
                        NeedlerStrokeIcon(
                            pathData = PathClose,
                            tint = colors.textSecondary,
                            size = 16.dp,
                        )
                    }
                }
            }
        },
    )
}

/**
 * The same box, but as a button.
 *
 * On Library (02, 09) the search box is not a field: tapping it opens the Search screen. Drawing it
 * as a real text field there would put a cursor and a keyboard where the design has neither.
 */
@Composable
fun NeedlerSearchFieldButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Artist, album or song",
    label: String = "Search",
    enabled: Boolean = true,
) {
    val colors = NeedlerTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = NeedlerTheme.sizes.minTouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        SearchFieldFrame(focused = false) {
            Text(
                text = placeholder,
                style = NeedlerTheme.typography.bodyLarge,
                color = colors.textMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The surface, border, radius and leading glyph shared by the field and its button form. */
@Composable
private fun SearchFieldFrame(
    focused: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = NeedlerTheme.colors
    val sizes = NeedlerTheme.sizes
    val shape = NeedlerTheme.shapes.large
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(
                width = sizes.hairlineThickness,
                color = if (focused) colors.accent else colors.hairline,
                shape = shape,
            )
            .defaultMinSize(minHeight = sizes.searchFieldMinHeight)
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NeedlerSearchIcon(tint = colors.textSecondary)
        content()
    }
}

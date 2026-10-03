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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
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
 * @param focusRequester a handle on the *field*, for a screen that wants the cursor in it on
 *   arrival. It has to be a parameter: [modifier] goes on the outer `Column`, because the label and
 *   the helper line are part of the control and a caller sizing or padding this expects all three to
 *   move together. A requester passed through [modifier] would therefore land on the column rather
 *   than the editor, and raise no keyboard. [NeedlerSearchField] has no such split and puts its
 *   [modifier] straight on the editor, which is why only this one needs the extra handle.
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
    focusRequester: FocusRequester? = null,
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
                .then(
                    if (focusRequester == null) {
                        Modifier
                    } else {
                        Modifier.focusRequester(focusRequester)
                    },
                )
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
                        // The 8dp of vertical air belongs to the text, not to the row. A
                        // [trailingIcon] is a 48dp touch target - the floor REQUIREMENTS.md
                        // "Accessibility" sets - and inside a row that padded itself it would make
                        // the field 64dp tall, ten more than the two fields above it on Connect.
                        // Padding the text alone leaves the 48dp target inside the pack's 54dp box.
                        .padding(start = 16.dp, end = if (trailingIcon == null) 16.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
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
 * [NeedlerLabelledTextField] for a secret, with a reveal toggle.
 *
 * Masked fields on Connect had no way to show what was typed. That screen is reached by someone
 * who is already locked out, on a phone keyboard, often with a generated secret of 32 or 64
 * characters, and a mis-typed character there is indistinguishable from a wrong password: both
 * come back as "that username or password was rejected". The toggle turns a guessing game into
 * something the user can check.
 *
 * ## It is a control, not a decoration
 *
 * The toggle is a [NeedlerIconButton], so it is a `Role.Button` with a 48dp touch target - the
 * floor REQUIREMENTS.md "Accessibility" sets - and its content description comes from
 * [secretRevealContentDescription], which states the current state *and* what a tap does, and
 * which reads differently in the two states. A toggle whose description never changes announces
 * the same thing after being pressed as before it, which tells a screen-reader user nothing about
 * whether the press worked; the device audit reads these labels and that is the defect it looks
 * for.
 *
 * ## Masked by default, and no control on an empty field
 *
 * [VisualTransformation] starts as [PasswordVisualTransformation] on every composition: revealing
 * a secret is a choice the user makes each time, never a state the screen restores for them. And
 * while [value] is empty there is nothing to reveal, so the toggle is not drawn at all rather than
 * offered as a tap that does nothing visible.
 *
 * The reveal choice survives the field going empty and being typed again, which is what someone
 * who cleared a half-typed secret to start over wants: they turned it on to watch themselves type.
 * The toggle is gone while the field is empty, so it is the next character that shows it is still
 * on.
 *
 * ## No placeholder, still
 *
 * This component takes no `placeholder`, and that is deliberate rather than an omission. The
 * design pack draws sixteen bullets inside the password box; transcribed as placeholder text it
 * made the one field that must look obviously empty look obviously full, and made TalkBack read
 * out sixteen bullet characters where a field's supporting text should be. Masking is
 * [PasswordVisualTransformation]'s job, on characters the user actually typed. A reveal toggle
 * does not bring any of that back - it is a sibling control with its own label, not a text node
 * inside the field's decoration - and leaving the parameter off means it cannot be re-introduced
 * through this component by accident.
 *
 * @param secretName the secret's name as a screen reader should say it - "Password", "Proxy
 *   password", "Header 2 value". Not [label], which the pack writes uppercase ("PASSWORD") and
 *   which several fields on one form can repeat; this is spoken, so it is sentence case and
 *   distinct per field.
 */
@Composable
fun NeedlerSecretTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    secretName: String,
    modifier: Modifier = Modifier,
    helperText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var revealRequested by remember { mutableStateOf(false) }
    val revealed: Boolean = revealRequested && value.isNotEmpty()
    val colors = NeedlerTheme.colors

    NeedlerLabelledTextField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        helperText = helperText,
        errorText = errorText,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = if (revealed) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = if (value.isEmpty()) {
            null
        } else {
            {
                NeedlerIconButton(
                    contentDescription = secretRevealContentDescription(secretName, revealed),
                    onClick = { revealRequested = !revealRequested },
                    enabled = enabled,
                    visualSize = NeedlerTheme.sizes.minTouchTarget,
                ) {
                    NeedlerStrokeIcon(
                        pathData = if (revealed) PathEyeOpen else PathEyeClosed,
                        tint = if (enabled) colors.textSecondary else colors.textMuted,
                        size = 20.dp,
                    )
                }
            }
        },
    )
}

/**
 * What a screen reader announces for the reveal toggle on [NeedlerSecretTextField].
 *
 * A plain function, and public, so the two strings can be asserted on in a unit test with no
 * Robolectric and no rendering. What matters about them is not how they look but that they say
 * which state the field is in, say what a tap will do, and **differ** between the two states -
 * and a screenshot cannot check any of the three.
 */
fun secretRevealContentDescription(secretName: String, revealed: Boolean): String =
    if (revealed) {
        secretName + " is showing. Tap to hide it."
    } else {
        secretName + " is hidden. Tap to show it."
    }

/**
 * An open eye, on the pack's 24x24 viewport with its 1.8-unit round-capped stroke: the lens, then
 * the pupil as two half-circle arcs.
 *
 * Drawn when the secret **is** showing. The glyph states the field's current condition rather than
 * the action a tap performs, which is the same convention as the chevron on the proxy disclosure
 * beneath it; the action is in the content description, where a screen reader will actually find
 * it, and the two conventions must not be mixed on one screen.
 *
 * Private to this file rather than added to `Icons.kt`, because the reveal toggle is the only
 * thing in the design that uses an eye. It moves to the shared set the day a second caller wants
 * one.
 */
private const val PathEyeOpen: String =
    "M3 12 q9 -8 18 0 q-9 8 -18 0 M14.5 12 a2.5 2.5 0 1 1 -5 0 a2.5 2.5 0 1 1 5 0"

/** The same eye struck through: the secret is masked, which is every field's starting state. */
private const val PathEyeClosed: String = PathEyeOpen + " M4 20 L20 4"

/**
 * The search field from Library and Search (02, 03, 09, 10).
 *
 * A 52dp rounded box on the surface with a 16dp radius, a leading search glyph, and - once there is
 * something to clear - the 32dp round clear button from screen 03. The border turns accent while
 * focused, as the active field on 03 is drawn.
 *
 * Takes a [String], so the caret and the selection are Compose's business. A caller that has to
 * place either one uses the [TextFieldValue] overload below; nothing else differs between them.
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
            SearchFieldDecoration(
                text = value,
                focused = focused,
                placeholder = placeholder,
                onClear = onClear,
                innerTextField = innerTextField,
            )
        },
    )
}

/**
 * The same field, with the caret and the selection in the caller's hands.
 *
 * ## Why the overload exists
 *
 * The [String] overload cannot say "arrive with the existing text selected", and Search has to.
 * `search` is a bottom-navigation destination, so its back-stack entry and its ViewModel survive a
 * tab switch deliberately - `SearchViewModel.SUBSCRIPTION_TIMEOUT_MS` keeps the query across a
 * rotation or a trip into an album as well - and REQUIREMENTS.md "Search behaviour" has the field
 * running that query live from the first keystroke. So the previous search is still in the field
 * when the user taps the library's search box again, and `BasicTextField`'s [String] overload seeds
 * its selection to `TextRange(0)`: the caret sat at index 0 and the next keystroke **prepended**.
 * Typing "beastie" after a previous "dido" produced "beastiedido", then searched for it and found
 * nothing.
 *
 * A [TextFieldValue] lets the caller select the stale query rather than delete it, so the first
 * keystroke replaces it and a user who came back to keep editing it still can.
 *
 * ## The alternative, rejected
 *
 * Blanking the query on entry instead - an effect on the route telling the ViewModel to clear it -
 * re-fires after a configuration change, so a rotation would wipe the very search
 * `SUBSCRIPTION_TIMEOUT_MS` exists to keep. Stopping that means holding a "fresh arrival" flag in a
 * `SavedStateHandle`: more state, in a second place, to buy back what selecting the text gives for
 * nothing. It also throws away a query the user may have come back to edit, which selection does
 * not.
 */
@Composable
fun NeedlerSearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
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
            SearchFieldDecoration(
                text = value.text,
                focused = focused,
                placeholder = placeholder,
                onClear = onClear,
                innerTextField = innerTextField,
            )
        },
    )
}

/**
 * What is inside the search field: the placeholder while [text] is empty, the editor, and the clear
 * button once there is something to clear.
 *
 * Shared by both [NeedlerSearchField] overloads, and that is the only reason it is a function. The
 * two differ in where their text comes from and in nothing a user can see; a second copy of this
 * decoration would be free to drift, and a placeholder or a clear button fixed on one overload and
 * not the other is exactly the difference a screenshot of either one still passes.
 */
@Composable
private fun SearchFieldDecoration(
    text: String,
    focused: Boolean,
    placeholder: String,
    onClear: (() -> Unit)?,
    innerTextField: @Composable () -> Unit,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    SearchFieldFrame(focused = focused) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) {
                Text(
                    text = placeholder,
                    style = typography.bodyLarge,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            innerTextField()
        }
        if (text.isNotEmpty() && onClear != null) {
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
}

/**
 * The same box, but as a button.
 *
 * On Library (02, 09) the search box is not a field: tapping it opens the Search screen. Drawing it
 * as a real text field there would put a cursor and a keyboard where the design has neither.
 *
 * It is pressed through [needlerPressSurface] on [searchFieldShape], because this box and the frame
 * inside it measure to the same bounds and the frame has 16dp corners. Without the clip the press
 * flashed a rectangle whose four corners stood outside the field it was meant to be filling - the
 * same fault the device audit found on the play button, at a smaller radius. The fill and the
 * outline stay on the frame, which already draws them; only the clip and the gesture are here.
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
            .needlerPressSurface(
                shape = searchFieldShape(),
                interaction = { source ->
                    Modifier.clickable(
                        interactionSource = source,
                        // The helper draws it, clipped to the field's 16dp corners.
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    )
                },
            )
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

/**
 * The search box's radius, read in one place.
 *
 * Both the frame that draws it and [NeedlerSearchFieldButton]'s press clip need the same value, and
 * a press clipped to a shape the frame has since stopped using would reintroduce the rectangle one
 * corner at a time.
 */
@Composable
private fun searchFieldShape(): Shape = NeedlerTheme.shapes.large

/** The surface, border, radius and leading glyph shared by the field and its button form. */
@Composable
private fun SearchFieldFrame(
    focused: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = NeedlerTheme.colors
    val sizes = NeedlerTheme.sizes
    val shape = searchFieldShape()
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

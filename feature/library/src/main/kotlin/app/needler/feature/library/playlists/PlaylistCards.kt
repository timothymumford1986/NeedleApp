package app.needler.feature.library.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerButtonSize
import app.needler.core.design.component.NeedlerClockIcon
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerLabelledTextField
import app.needler.core.design.component.NeedlerOnDeviceIcon
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerSecondaryButton
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathClose
import app.needler.core.design.theme.NeedlerTheme

/**
 * The cards and badges both playlist screens draw.
 *
 * They live in one file because the list and the detail screen say the same
 * things in the same words — a queued edit is a queued edit on either — and a
 * second copy of the sentence is how two screens come to disagree about what the
 * write queue is doing.
 *
 * None of this is in `:core:design`: every piece here is assembled from that
 * module's own components and tokens, and nothing in it is general enough to be
 * a design-system component yet. Two screens is not a pattern.
 */

/**
 * The name form, used to create a playlist and to rename one.
 *
 * Inline rather than a dialog, for the reasons recorded on [PlaylistDraft]: a
 * dialog is a separate window and therefore invisible to the screenshot tests
 * that are this module's only check on layout at 200% text.
 *
 * The keyboard's Done key submits, so the common case — type a name, press the
 * key under your thumb — never needs the button at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaylistNameForm(
    draft: PlaylistDraft,
    title: String,
    confirmLabel: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val shape = NeedlerTheme.shapes.card
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .padding(horizontal = spacing.step8, vertical = spacing.step6),
        verticalArrangement = Arrangement.spacedBy(spacing.step6),
    ) {
        Text(
            text = title,
            style = NeedlerTheme.typography.sectionHeader,
            color = colors.textSecondary,
        )
        NeedlerLabelledTextField(
            label = "NAME",
            value = draft.name,
            onValueChange = onNameChange,
            placeholder = "Sunday morning",
            enabled = !draft.submitting,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (draft.canSubmit) onConfirm() }),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.step4),
            verticalArrangement = Arrangement.spacedBy(spacing.step4),
        ) {
            NeedlerPrimaryButton(
                text = confirmLabel,
                onClick = onConfirm,
                modifier = Modifier.defaultMinSize(minWidth = PRIMARY_BUTTON_MIN_WIDTH),
                size = NeedlerButtonSize.Medium,
                enabled = draft.canSubmit,
                contentDescription = confirmLabel + " this playlist",
            )
            NeedlerSecondaryButton(
                text = "Cancel",
                onClick = onCancel,
                size = NeedlerButtonSize.Medium,
                enabled = !draft.submitting,
            )
        }
    }
}

/**
 * The confirmation before something is destroyed.
 *
 * The destructive colour is used rather than the accent, which is the whole
 * reason REQUIREMENTS.md "Accessibility" added one: "permanent data loss styled
 * exactly like the primary action" was the gap. A deleted playlist cannot be
 * recovered from the app.
 *
 * ## The order and the default
 *
 * It drew `Delete` on the left and `Keep it` on the right, both outlined. Left is where every
 * other card in this module puts the action the user most likely wants - `Create`, `Rename`,
 * `Play` - so the destructive action was sitting in the position the rest of the app has taught is
 * safe, and neither button was styled as the one a confirming tap should land on. Two defects at
 * once: the wrong thing in the easy position, and no easy position at all.
 *
 * Both are fixed by giving the card the default it was missing. `Keep it` is the primary and comes
 * first, because it is what the card is for - the user is being asked to reconsider, and the
 * answer the card exists to make easy is "no". `Delete` follows it, outlined, in the destructive
 * colour, with "This cannot be undone" in its spoken label. Nothing here is a dialog, so there is
 * no platform convention about left and right to honour; the convention being honoured is this
 * module's own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaylistConfirmCard(
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val shape = NeedlerTheme.shapes.card
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.destructive, shape)
            .padding(horizontal = spacing.step8, vertical = spacing.step6)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.spacedBy(spacing.step6),
    ) {
        Text(
            text = message,
            style = NeedlerTheme.typography.body,
            color = colors.textPrimary,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.step4),
            verticalArrangement = Arrangement.spacedBy(spacing.step4),
        ) {
            NeedlerPrimaryButton(
                text = "Keep it",
                onClick = onCancel,
                modifier = Modifier.defaultMinSize(minWidth = PRIMARY_BUTTON_MIN_WIDTH),
                size = NeedlerButtonSize.Medium,
                enabled = enabled,
            )
            DestructiveButton(
                text = confirmLabel,
                onClick = onConfirm,
                enabled = enabled,
                contentDescription = confirmLabel + ". This cannot be undone.",
            )
        }
    }
}

/**
 * The destructive action, in the destructive colour.
 *
 * `:core:design` has no destructive button: the pack draws none, and
 * REQUIREMENTS.md "Accessibility" records why one had to exist anyway — "permanent
 * data loss styled exactly like the primary action" was the gap the `#e8908a` pair
 * was added to fill. `NeedlerSecondaryButton` cannot carry it, because its content
 * colour is fixed to the primary text colour unless the button is *selected*, which
 * would draw this in the positive green.
 *
 * So the pack's outlined-button shape is reproduced here with the destructive colour
 * on both the label and the border. It is a screen-level composable rather than a
 * design-system one on purpose: one destructive confirmation is not a pattern, and
 * the moment a second screen needs it — Settings has three — it belongs beside
 * `NeedlerSecondaryButton` instead. That is in the handover notes.
 *
 * The minimum height is the pack's button height applied as a `defaultMinSize`, so
 * it grows with the user's text rather than clipping at 200%.
 */
@Composable
private fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    contentDescription: String,
) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.large
    val tint = if (enabled) colors.destructive else colors.disabled
    Row(
        modifier = Modifier
            .defaultMinSize(
                minHeight = NeedlerTheme.sizes.buttonMinHeight,
                minWidth = NeedlerTheme.sizes.minTouchTarget,
            )
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, tint, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerStrokeIcon(pathData = PathClose, tint = tint, size = 16.dp)
        Text(
            text = text,
            style = NeedlerTheme.typography.rowTitle,
            color = tint,
            maxLines = 2,
        )
    }
}

/** A one-line result of the last edit, dismissible. Modelled on album detail's. */
@Composable
internal fun PlaylistNoticeCard(
    notice: PlaylistNotice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    val tint = if (notice.isProblem) colors.destructive else colors.positive
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, tint, shape)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = notice.message
                liveRegion = LiveRegionMode.Assertive
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = notice.message,
            style = NeedlerTheme.typography.caption,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        NeedlerIconButton(
            contentDescription = "Dismiss",
            onClick = onDismiss,
            visualSize = 36.dp,
        ) {
            NeedlerStrokeIcon(pathData = PathClose, tint = colors.textMuted, size = 16.dp)
        }
    }
}

/**
 * The offline line.
 *
 * Deliberately not an error and deliberately not dismissible, exactly as on the
 * library screen. REQUIREMENTS.md: "Offline is a first-class state, not an
 * error." Playlists are the one browse surface that can also be *written* with no
 * connection, so the wording says that too — an edit made here is not lost and
 * does not need retrying.
 */
@Composable
internal fun PlaylistOfflineNote(modifier: Modifier = Modifier) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = OFFLINE_NOTE
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerOnDeviceIcon(tint = colors.positive)
        Text(
            text = OFFLINE_NOTE,
            style = NeedlerTheme.typography.caption,
            color = colors.textSecondary,
        )
    }
}

/**
 * The pending badge: a clock and two or three words, in the secondary colour.
 *
 * The pack's own badge treatment — "no chip, no fill and no border, the colour is
 * the whole treatment" — reproduced for a state the pack does not draw, rather
 * than a new visual idea. It is not the positive green: nothing has succeeded
 * yet. It is not the destructive red either: nothing has failed.
 *
 * The words come from [PlaylistSyncState], which is the only place in the module that names this
 * concept; see it for why all of them now say "sent" and "on your next connection".
 */
@Composable
internal fun PlaylistSyncBadge(
    syncState: PlaylistSyncState,
    modifier: Modifier = Modifier,
) {
    val label: String = syncState.label ?: return
    val colors = NeedlerTheme.colors
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = syncState.spokenLabel ?: label
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerClockIcon(tint = colors.textSecondary)
        Text(
            text = label,
            style = NeedlerTheme.typography.metaStrong,
            color = colors.textSecondary,
            maxLines = 2,
        )
    }
}

/** The quiet explanatory card under a header, for a state that needs a sentence. */
@Composable
internal fun PlaylistInfoCard(
    message: String,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = message },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = message,
            style = NeedlerTheme.typography.caption,
            color = colors.textSecondary,
        )
    }
}

/**
 * One item of a playlist's overflow menu.
 *
 * `internal` rather than private because both playlist screens draw the same
 * menus — a top-level `private` in Kotlin is visible only inside its own file,
 * and two copies of a menu item is how two menus come to look different.
 *
 * Material 3's `DropdownMenuItem` is used directly: the design pack draws no menu,
 * and album detail already reached for the same component for "Go to artist". Its
 * rows are 48dp, which is the touch target REQUIREMENTS.md "Accessibility" asks
 * for.
 */
@Composable
internal fun PlaylistMenuItem(
    label: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = NeedlerTheme.colors
    DropdownMenuItem(
        enabled = enabled,
        text = {
            Text(
                text = label,
                style = NeedlerTheme.typography.body,
                color = when {
                    !enabled -> colors.disabled
                    destructive -> colors.destructive
                    else -> colors.textPrimary
                },
            )
        },
        onClick = onClick,
    )
}

internal const val OFFLINE_NOTE: String =
    "Offline. Your playlists are on this device, and anything you change here is sent to the " +
        "server on your next connection."

/**
 * How wide the leading action is, at least.
 *
 * `screenshots/playlists-create-phone.png` measures `Create` at about 60dp and the outlined
 * `Cancel` beside it at about 62dp, so the primary action was the smaller of the two and the
 * dismissive one had a visible border making it look larger still. Both buttons size to their
 * label, and "Create" and "Cancel" are the same length, so nothing about the type was going to fix
 * it. A floor on the primary is the whole of the repair: it is now about twice the width of the
 * control beside it and cannot be the smaller one whatever the two labels say. The same floor is
 * on `Keep it` in [PlaylistConfirmCard], which is that card's default for the same reason.
 *
 * It is a floor rather than a fixed width so that a longer label and 200% text still grow it.
 */
private val PRIMARY_BUTTON_MIN_WIDTH: Dp = 120.dp

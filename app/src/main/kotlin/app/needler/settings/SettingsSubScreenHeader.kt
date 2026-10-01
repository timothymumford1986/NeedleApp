package app.needler.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.theme.NeedlerTheme

/**
 * The back-arrow-and-title header that the screens reached *from* Settings share: Licences and
 * Diagnostics.
 *
 * ## Why it lives in this package
 *
 * Because "a screen you got to from Settings" is exactly what both callers are, and this is the
 * settings package. `:core:design` is where it would belong if it were a design-system component, and
 * it is not one: the design pack draws no such header for either screen — both screens are additions
 * REQUIREMENTS.md requires and the pack has no artboard for — so this is an inference about how they
 * should look, not a transcription, and an inference does not belong in the module that holds the
 * pack's vocabulary.
 *
 * The metrics are borrowed, deliberately and exactly, from `PlayerSettingsHeader` in
 * `:feature:player`, which is the same header for screens 19 and 20 — the two Settings sub-screens the
 * pack *does* draw. Copying those numbers rather than choosing new ones means Licences and
 * Diagnostics look like siblings of Crossfade and Equaliser, which is what they are. It is not
 * shared with that one because `:app` reaching into a feature module for a header would make a
 * navigation-shaped dependency out of a visual one, and `SettingsRoute` already refuses to do that
 * for the two routes themselves.
 *
 * ## Accessibility
 *
 * The back control is a 44dp glyph whose touch target `NeedlerIconButton` floors at 48dp, honouring
 * REQUIREMENTS.md "Accessibility", and it carries a content description because it has no visible
 * label. The title is marked as a heading so a screen-reader user can jump to it, and it wraps rather
 * than truncating, so it survives text scaled to 200%.
 *
 * @param title the screen's name. Drawn uppercase by the display type, as every screen title in the
 *   pack is.
 * @param onBack pops the screen. Never null: a sub-screen with no way off it is the one mistake this
 *   header exists to make impossible.
 */
@Composable
internal fun SettingsSubScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            // The pack's own header padding on screens 19 and 20, minus their hard-coded 52dp
            // status-bar inset: the callers apply safeDrawingPadding, so the inset is measured
            // rather than assumed.
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerIconButton(
            contentDescription = "Back to Settings",
            onClick = onBack,
            visualSize = 44.dp,
        ) {
            NeedlerStrokeIcon(PathChevronLeft, tint = colors.textPrimary, size = 24.dp)
        }
        Text(
            text = title.uppercase(),
            style = NeedlerTheme.typography.screenTitleCompact,
            color = colors.textPrimary,
            // No maxLines: a title that clips at 200% text is the failure REQUIREMENTS.md
            // "Accessibility" names outright. TextOverflow is named anyway so the intent is on the
            // record rather than left to the default.
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
    }
}

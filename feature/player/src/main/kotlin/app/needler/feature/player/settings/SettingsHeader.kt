package app.needler.feature.player.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.theme.NeedlerTheme

/**
 * The back-arrow-and-title header screens 19 and 20 share.
 *
 * 96 dp tall including the pack's own 52 dp status-bar inset, a 44 dp back target, and the title in
 * uppercase display type. The inset is a literal rather than a `WindowInsets` read because these
 * screens are rendered to PNGs at a fixed size, where there is no system bar to measure, and the
 * pack's artboards are what those PNGs are compared against.
 */
@Composable
fun PlayerSettingsHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(start = 12.dp, end = 12.dp, top = 52.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerIconButton(
            contentDescription = "Back",
            onClick = onBack,
            visualSize = 44.dp,
        ) {
            NeedlerStrokeIcon(PathChevronLeft, tint = colors.textPrimary, size = 24.dp)
        }
        Text(
            text = title.uppercase(),
            style = NeedlerTheme.typography.screenTitleCompact,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
    }
}

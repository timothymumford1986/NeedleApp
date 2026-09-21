package app.needler.feature.player.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerChevronDownIcon
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.OutputTarget

/**
 * The "Play on" chip under the transport: a hairline pill naming where sound is going.
 *
 * REQUIREMENTS.md "Output": "The current output is always named in the player - 'Living room
 * speaker', 'This tablet' - so a user never wonders where sound is going." That is this control's
 * whole job, and it is why it is drawn even when nothing is playing.
 *
 * The pack draws it twice, at two weights: accent on Now Playing (07), where it is 36 dp tall and
 * the current output is the thing the eye should find, and secondary in the tablet sidebar (09) at
 * 32 dp, where it sits below a transport that already has the accent. [emphasised] is that
 * difference. The target is padded to 48 dp regardless, because 32 dp is not a tap target.
 */
@Composable
fun OutputChip(
    target: OutputTarget?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = true,
) {
    val colors = NeedlerTheme.colors
    val tint: Color = if (emphasised) colors.accent else colors.textSecondary
    val name: String = PlayerFormat.outputName(target)

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = NeedlerTheme.sizes.minTouchTarget)
            .clip(NeedlerTheme.shapes.pill)
            .clickable(role = Role.Button, onClick = onClick)
            .border(
                width = NeedlerTheme.sizes.hairlineThickness,
                color = colors.hairline,
                shape = NeedlerTheme.shapes.pill,
            )
            .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Playing on " + name + ". Change output"
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (target) {
            is OutputTarget.Bluetooth -> PlayerBluetoothIcon(tint = tint, size = if (emphasised) 16.dp else 14.dp)
            else -> PlayerSpeakerIcon(tint = tint, size = if (emphasised) 16.dp else 14.dp)
        }
        Text(
            text = name,
            style = if (emphasised) NeedlerTheme.typography.metaStrong else NeedlerTheme.typography.caption,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        NeedlerChevronDownIcon(tint = tint, size = if (emphasised) 14.dp else 12.dp)
    }
}

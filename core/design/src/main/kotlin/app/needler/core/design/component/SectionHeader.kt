package app.needler.core.design.component

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
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * The overline that heads a block of rows.
 *
 * Space Grotesk 12sp/700 at `0.14em`, uppercase, in the secondary colour, optionally with a quiet
 * count on the right - "6 tracks - 21 min" (08), "from MusicBrainz" (03), "4 up next" (09).
 *
 * Used on Search (03), Pulls (06), the crate (08), the tablet sidebar (09), Settings (12) and the
 * library list (13), which is every screen in the pack that has more than one block of rows.
 *
 * The title is marked as a heading, so TalkBack's heading navigation can jump between blocks.
 *
 * @param title rendered uppercase. Pass it in whatever case reads best in source; the component
 *   uppercases it, as `text-transform: uppercase` does in the pack.
 */
@Composable
fun NeedlerSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = typography.sectionHeader,
            color = colors.textSecondary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = typography.meta,
                // Counts and durations are information, so they take the AA-compliant muted value.
                color = colors.textMutedAccessible,
            )
        }
    }
}

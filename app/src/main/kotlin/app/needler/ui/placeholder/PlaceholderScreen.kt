package app.needler.ui.placeholder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * A destination that exists so navigation works end to end, and nothing more.
 *
 * Library, Search and Pulls belong to `:feature:library`, `:feature:search` and
 * `:feature:pulls`; Settings belongs to `:app` but is a screen of its own.
 * Until each arrives, its route renders this: the screen's title in the pack's
 * own type, and one bordered panel naming the module that will replace it.
 *
 * It is deliberately, visibly unfinished. The seam is the point - a placeholder
 * that looked like a real screen would be mistaken for one, and a half-built
 * Library is harder to replace than an empty route. Nothing here reads a
 * repository, holds state or has a `ViewModel`: swapping it out is a one-line
 * change in [app.needler.ui.navigation.NeedlerNavHost].
 *
 * @param title the destination's name, rendered as the screen title.
 * @param owner the Gradle path of the module that will replace this, e.g.
 *   `:feature:library`.
 * @param note one sentence on what the real screen shows, so whoever builds it
 *   does not have to go back to REQUIREMENTS.md to find out which one it is.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    owner: String,
    note: String,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.phoneGutterWide, vertical = spacing.step14)
            .semantics { testTag = "placeholder:$owner" },
        verticalArrangement = Arrangement.spacedBy(spacing.step12),
    ) {
        Text(
            text = title.uppercase(),
            style = typography.screenTitle,
            color = colors.textPrimary,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(NeedlerTheme.shapes.large)
                .background(colors.surface)
                .border(
                    width = NeedlerTheme.sizes.hairlineThickness,
                    color = colors.hairline,
                    shape = NeedlerTheme.shapes.large,
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.step4),
        ) {
            Text(
                text = "NOT BUILT YET",
                style = typography.sectionHeader,
                color = colors.textSecondary,
            )
            Text(
                text = "$title is built in $owner.",
                style = typography.rowTitle,
                color = colors.textPrimary,
            )
            Text(
                text = note,
                style = typography.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Start,
            )
        }
    }
}

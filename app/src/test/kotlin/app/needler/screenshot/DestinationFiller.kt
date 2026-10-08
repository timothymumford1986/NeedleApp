package app.needler.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.ui.navigation.NeedlerDestination

/**
 * Something for the navigation chrome to sit around, and nothing more.
 *
 * ## What this replaced, and why it had to go
 *
 * `:app` used to ship a `PlaceholderScreen` in its **main** source: a bordered panel reading
 * `NOT BUILT YET` over `Pulls is built in :feature:pulls.` and a sentence naming the artboard. It
 * was written when none of the four destinations existed, and `NeedlerNavHost` stopped calling it
 * once they all did - leaving two screenshot tests as the only thing keeping it compiled.
 *
 * A reviewer caught the same shape on the tablet set, where four goldens drew
 * *"PLAYER SIDEBAR / Artwork, transport, output and the crate are built in :feature:player and
 * placed here by :app"* across the largest region of the frame, and the verdict applies here:
 * **either that ships, or the region is untested.** `nav-pulls-large-text-phone.png` had it filling
 * the whole content area with a claim that three shipped features do not exist.
 *
 * ## Why filler and not the real screen
 *
 * These two tests are about the bar and the rail. Composing the real destination would need every
 * feature module's fakes inside `:app`'s test source to render a content area none of the
 * assertions look at, and each feature screenshots itself at its own width already.
 *
 * So the content area draws what a destination looks like before its data arrives - a title and
 * three skeleton blocks in the pack's own tokens. It is honest at a glance, it says nothing that
 * can go stale, and it exercises `NeedlerColors.skeleton` while it is there.
 */
@Composable
internal fun DestinationFiller(
    destination: NeedlerDestination,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = destination.label.uppercase(),
            style = NeedlerTheme.typography.screenTitle,
            color = colors.textPrimary,
        )
        repeat(3) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.skeleton)
                    .height(64.dp),
            ) {}
        }
    }
}

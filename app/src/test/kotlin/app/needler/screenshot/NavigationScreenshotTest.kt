package app.needler.screenshot

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import app.needler.ui.navigation.NeedlerDestination
import app.needler.ui.navigation.NeedlerNavigationScaffold
import app.needler.ui.placeholder.DestinationPlaceholder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders each of the four destinations inside the navigation chrome, at both
 * widths.
 *
 * Eight images from one loop, which is the point: REQUIREMENTS.md's "one
 * navigation model at two widths" is only true if the same composable produces
 * both, and here it does. The phone images show the bottom bar from screens 02,
 * 03, 06 and 12; the tablet images show the 96dp rail and the permanent 400dp
 * player sidebar from screen 09.
 *
 * The badge is rendered as 2 - the count the pack draws on every screen's nav -
 * rather than 0, because a badge that is never exercised is a badge that has
 * never been seen to fit. The running app passes the real count, which is zero
 * until `:feature:pulls` supplies one.
 *
 * [NeedlerNavigationScaffold] is rendered directly rather than through
 * `NeedlerNavHost`, so the images show a chosen destination rather than
 * whatever the graph happens to start on, and so no `ViewModel`, Hilt graph or
 * navigation back stack is involved in producing them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class NavigationScreenshotTest {

    @Test
    fun `every destination on a phone`() {
        NeedlerDestination.entries.forEach { destination ->
            capture(destination, NeedlerDevice.Phone)
        }
    }

    @Test
    fun `every destination on a tablet`() {
        NeedlerDestination.entries.forEach { destination ->
            capture(destination, NeedlerDevice.Tablet)
        }
    }

    private fun capture(destination: NeedlerDestination, device: NeedlerDevice) {
        val name = "nav-" + destination.route
        val file = captureNeedlerScreen(name, device) {
            NeedlerNavigationScaffold(
                widthSizeClass = when (device) {
                    NeedlerDevice.Phone -> WindowWidthSizeClass.Compact
                    NeedlerDevice.Tablet -> WindowWidthSizeClass.Expanded
                },
                selected = destination,
                onSelect = {},
                pullsBadgeCount = PULLS_BADGE,
            ) {
                DestinationPlaceholder(destination)
            }
        }
        assertRendered(file, device)
    }

    private companion object {
        /** The count the design pack draws on the Pulls item, on every screen. */
        const val PULLS_BADGE = 2
    }
}

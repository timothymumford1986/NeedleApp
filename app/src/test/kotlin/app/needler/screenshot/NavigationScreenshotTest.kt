package app.needler.screenshot

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import app.needler.ui.navigation.NeedlerDestination
import app.needler.ui.navigation.NeedlerNavigationScaffold
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
 *
 * That last part is why the sidebar is [PlayerSidebarPlaceholder] and not the
 * real `PlayerSidebarRoute` the app is given: the route resolves two Hilt view
 * models and binds them to a live session, neither of which exists in here. The
 * placeholder holds the pack's 400dp, so the content pane beside it is the
 * width a destination really gets, and these images stay a test of the chrome -
 * the rail, the bar, the badge and the measurements - rather than of playback,
 * which `:feature:player` screenshots for itself.
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

    /**
     * The badge at 200% text, with the two-digit count the device was carrying.
     *
     * This is the image the defect had no golden for: the count scales with the text setting and the
     * glyph does not, so at 200% an unscaled `36` sat on top of the download arrow and ran into the
     * label beside it. `NeedlerNavigationScaffold` caps the chrome's type scale now, and this is the
     * render that says whether the cap holds.
     *
     * Phone only. The rail gives an item 64dp and a whole column to grow into; the bar gives it a
     * quarter of a 390dp width, which is where the badge ran out of room.
     */
    @Test
    fun `the pulls badge at two hundred per cent text`() {
        capture(
            destination = NeedlerDestination.Pulls,
            device = NeedlerDevice.Phone,
            name = "nav-pulls-large-text",
            badge = CROWDED_BADGE,
            fontScale = 2f,
        )
    }

    private fun capture(
        destination: NeedlerDestination,
        device: NeedlerDevice,
        name: String = "nav-" + destination.route,
        badge: Int = PULLS_BADGE,
        fontScale: Float = 1f,
    ) {
        val file = captureNeedlerScreen(name, device, fontScale) {
            NeedlerNavigationScaffold(
                widthSizeClass = when (device) {
                    NeedlerDevice.Phone -> WindowWidthSizeClass.Compact
                    NeedlerDevice.Tablet -> WindowWidthSizeClass.Expanded
                },
                selected = destination,
                onSelect = {},
                pullsBadgeCount = badge,
                // Composed at both widths, and drawn at neither unless the
                // scaffold asks for it: the Compact branch never calls the
                // slot. Passing it unconditionally is what keeps the two
                // captures one call.
                sidebar = { PlayerSidebarPlaceholder() },
            ) {
                DestinationFiller(destination)
            }
        }
        assertRendered(file, device)
    }

    private companion object {
        /** The count the design pack draws on the Pulls item, on every screen. */
        const val PULLS_BADGE = 2

        /**
         * Two digits, which is the count the overlap was reported at.
         *
         * A single digit fits at any scale; the pill only outgrows its glyph once it has a second
         * character to hold, so a large-text golden drawn with the pack's `2` would show nothing.
         */
        const val CROWDED_BADGE = 36
    }
}

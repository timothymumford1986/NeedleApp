package app.needler.feature.player.design

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerRequestSheetOverlay
import app.needler.core.design.component.SCRIM_DISMISS_LABEL
import app.needler.core.design.theme.NeedlerTheme
import app.needler.feature.player.screenshot.PlayerScreenshots
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pull sheet's dismiss scrim: still a tap target, no longer a light show.
 *
 * ## What this fixes
 *
 * The scrim is a `fillMaxSize` node, and it carried a plain `clickable`, so the ripple it inherited
 * from `LocalIndication` was a ripple the size of the window. Tapping outside the sheet to close it
 * flashed the entire screen - artwork, rows and all - on the way out. `NeedlerSplash` sets
 * `indication = null` for the same kind of surface and is the precedent this follows.
 *
 * It also announced itself as a `Role.Button` named "Close the pull options for X", which put a
 * screen-sized control in the traversal order in front of the sheet it sits behind. The dismissal is
 * now an `onClickLabel`, which names the action without claiming the role.
 *
 * ## Why this test lives in `:feature:player`
 *
 * The component is `:core:design`'s, and `:core:design` has no Robolectric rig: its test classes are
 * all pure, and adding one to the module every other module depends on is a larger change than the
 * thing being checked. The two places the overlay is rendered from in main source are
 * `:feature:library`'s `common` package and `:feature:search`, neither of which this change may touch,
 * so the overlay is composed here directly from its public API. `:feature:player` is simply the
 * nearest module with a compose rule in it.
 *
 * ## Why pixels, for once
 *
 * `PressIndicationOrderTest` records that an indication is transient and that no screenshot holds it -
 * which is true of a baseline captured at rest. It is not true with the clock stopped: a press can be
 * put down, the frame advanced by hand, and the surface read back. That is the only way to assert the
 * *absence* of an indication, and the absence is the whole change. The pair of tests is deliberate -
 * the gesture still fires and the pixels do not move - because either one alone would pass on a scrim
 * that had stopped working entirely.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [PlayerScreenshots.SDK], application = Application::class)
class RequestSheetScrimTest {

    @get:Rule
    val compose = createComposeRule()

    private var cancelled = 0

    /**
     * `reducedMotion = false` on purpose: the ripple is what this is about, and under reduced motion
     * the theme has already replaced it. The scrim has to be quiet in both cases, and this is the case
     * that was loud.
     */
    @Test
    fun `holding the scrim down draws nothing at all`() {
        showing()
        compose.waitForIdle()

        val before: Color = scrimPixel()

        // The clock is stopped first, so the frames a ripple would animate over are ones this test
        // decides to hand it rather than ones it has to wait out.
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { down(Offset(x = PRESS_X, y = PRESS_Y)) }
        compose.mainClock.advanceTimeBy(RIPPLE_SETTLED_MILLIS)

        assertEquals(
            "the scrim lit up under a press, so tapping outside the sheet still flashes the " +
                "whole screen",
            before,
            scrimPixel(),
        )
    }

    @Test
    fun `tapping the scrim still dismisses the sheet`() {
        showing()

        compose.onRoot().performTouchInput { click(Offset(x = PRESS_X, y = PRESS_Y)) }
        compose.waitForIdle()

        assertEquals(1, cancelled)
    }

    /**
     * The scrim is no longer a button in the accessibility tree.
     *
     * What is asserted is that the old reading is gone, not that the phrase has left the product: it
     * survives as the tap's `onClickLabel`, which is where TalkBack reads a "double tap to ..." from
     * without having to walk past a screen-sized node to get there.
     */
    @Test
    fun `the scrim is not announced as a screen-sized button`() {
        showing()

        assertEquals(
            "a full-screen node named \"" + SCRIM_DISMISS_LABEL + TITLE + "\" is in the way of " +
                "the two buttons the user came for",
            0,
            compose.onAllNodesWithContentDescription(SCRIM_DISMISS_LABEL + TITLE)
                .fetchSemanticsNodes()
                .size,
        )
    }

    private fun scrimPixel(): Color =
        compose.onRoot().captureToImage().toPixelMap()[PRESS_X.toInt(), PRESS_Y.toInt()]

    private fun showing() {
        compose.setContent {
            NeedlerTheme(reducedMotion = false) {
                Box(
                    modifier = Modifier
                        .requiredWidth(PHONE_WIDTH)
                        .requiredHeight(PHONE_HEIGHT),
                ) {
                    NeedlerRequestSheetOverlay(
                        title = TITLE,
                        subtitle = "The Marías",
                        monitorArtist = false,
                        onMonitorArtistChange = {},
                        onConfirm = {},
                        onCancel = { cancelled++ },
                    )
                }
            }
        }
    }

    private companion object {
        const val TITLE = "Submarine"

        /** `design/html/05-PullSheet.html`, the artboard the screenshots render at. */
        val PHONE_WIDTH = 390.dp
        val PHONE_HEIGHT = 844.dp

        /**
         * Near the top of the window, in pixels, which is scrim on any sheet height: the sheet is
         * bottom-aligned and the pack's tallest is under half the screen.
         */
        const val PRESS_X: Float = 120f
        const val PRESS_Y: Float = 40f

        /** Longer than a Material ripple takes to expand, so "nothing happened" means nothing will. */
        const val RIPPLE_SETTLED_MILLIS: Long = 500L
    }
}

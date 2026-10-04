package app.needler.feature.player.nowplaying

import android.app.Application
import android.graphics.Insets
import android.view.View
import android.view.WindowInsets as PlatformWindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.feature.player.PlayerUiState
import app.needler.feature.player.fake.PlayerFixtures
import app.needler.feature.player.screenshot.PlayerScreenshots
import app.needler.feature.player.ui.SleepTimerOptions
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sleep timer's choices, measured inside a phone that has a system bar at the bottom of it.
 *
 * ## Why this is not a screenshot
 *
 * `PlayerScreenshotTest` already renders the panel, and `player-sleep-timer-choices-phone.png` is a
 * perfectly good picture of six pills that proves nothing about this bug: Roborazzi draws a canvas as
 * tall as it is asked for, so a column taller than the screen renders as a column taller than the
 * screen and the image looks right. The device report is a statement about the *viewport*, and the only
 * way to assert it is to lay the real screen out inside one and read the bounds back.
 * [app.needler.feature.player.sidebar.PlayerSidebarMeasureTest] is the precedent, and it exists for
 * the same class of failure: a measure pass nobody had measured.
 *
 * ## Why the system bar is a dispatched inset and not a shorter box
 *
 * This file used to model the navigation bar by shortening the box it laid the screen out in, on the
 * grounds that "Robolectric's window has no insets to read, so a test that faked them would be
 * asserting against the fake". That reasoning was wrong twice, and the result was a test that passed
 * while the device failed.
 *
 *  - A shortened box tests **does it fit**. It cannot test **does the automatic scroll put it there**,
 *    because the thing being asserted - the bottom of the box - is also the bottom of the scrollable's
 *    viewport, so a [androidx.compose.foundation.relocation.BringIntoViewRequester] that brings the
 *    pills no further than the viewport's own edge satisfies the assertion by definition. That is
 *    exactly the defect: on the device the pills landed on the screen's last pixel row, which is inside
 *    the viewport and underneath the gesture bar.
 *  - Robolectric's window has no insets by default, but it takes them: `dispatchApplyWindowInsets` on
 *    the view Compose hangs its `WindowInsetsHolder` off reaches `WindowInsets.safeDrawing` like any
 *    other inset change. So the bar can be the real thing rather than a fake, and the screen reads it
 *    the same way it reads it on a phone. Measured: `safeDrawing` bottom is 0 before the dispatch and
 *    the dispatched pixel count after it.
 *
 * The box is therefore the **whole** screen, bar included, which is what Now Playing is actually given
 * - it is a sibling of Home in the outer nav graph with no scaffold under it - and the assertion is
 * against the safe area inside that box.
 *
 * ## Bounds are read in the phone's coordinates, not the root's
 *
 * `setContent` **centres** a child larger than Robolectric's own 320 by 470 window, so a required-size
 * box of phone dimensions is placed at negative coordinates: measured, the dismiss control of a 844 dp
 * screen reports its top at -135 dp, which is 52 dp less half the 374 dp of overflow. Wrapping the box
 * in a `fillMaxSize` parent aligned to top-start was tried and does not move it by a pixel, so the
 * centring happens above anything this file can reach.
 *
 * The old assertions compared root-space bounds against the box's own height regardless, and that is
 * the second reason they passed while the device failed: every bound had been shifted up by more than
 * the navigation bar they were looking for. So the box reports where it was put, through
 * `onGloballyPositioned`, and every bound below is read relative to that. [assertHeaderIsAtTheTop]
 * fails if the translation is ever wrong, by measuring the one row of this screen whose position the
 * pack fixes.
 *
 * ## What is asserted
 *
 * Every one of the six pills, by the bounds of its own node, against the safe area - not the viewport.
 * Not `assertIsDisplayed`: that test is made against the host *window*, which Robolectric sizes for
 * itself and which is not the box the screen was given. The three failures guarded against are a pill
 * under the system bar - the bug - a pill scrolled off the top, which is what an over-eager scroll
 * would do instead, and a pill measured at zero height, which is the shape of the landscape failure
 * [app.needler.feature.player.sidebar.PlayerSidebarMeasureTest] exists for.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [PlayerScreenshots.SDK], application = Application::class)
class NowPlayingMeasureTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The device the defect was measured on: an OPPO Find X9, 1080 by 2374, with a gesture bar
     * roughly 110 px tall.
     *
     * At 2.75 the screen is 392.7 by 863.3 dp and the expanded column overruns the scrollable by 83 dp,
     * so the automatic scroll runs - and before the fix it stopped with the bottom row of pills at
     * 2275 to 2374, ending on the screen's last pixel row, which is the figure the device audit
     * reported to the pixel. With the fix they land at 2165 to 2264: 110 px higher, which is the bar,
     * and the pack's own 36 dp gap is still below them unspent.
     */
    @Test
    fun `the sleep timer's choices clear the gesture bar on a 1080 by 2374 phone`() =
        assertChoicesClearTheSystemBar(
            widthPx = 1080,
            heightPx = 2374,
            bottomInsetPx = 110,
            density = 2.75f,
            fontScale = 1f,
        )

    /**
     * The pack's own 390 by 844 artboard, with a three-button navigation bar under it.
     *
     * 48 dp is the taller of the two bars - a gesture handle is 24 dp - so a panel that clears this
     * clears a gesture bar on the same screen.
     */
    @Test
    fun `the choices clear a navigation bar on the pack's phone artboard`() =
        assertChoicesClearTheSystemBar(
            widthPx = 390,
            heightPx = 844,
            bottomInsetPx = 48,
            density = 1f,
            fontScale = 1f,
        )

    /**
     * The text scale REQUIREMENTS.md names.
     *
     * "Text must scale to 200% without clipping", and at 200% the pills take more rows and every
     * control above them is taller, so the expansion adds the most height to a column that already did
     * not fit.
     */
    @Test
    fun `the choices still clear it at 200 percent text`() =
        assertChoicesClearTheSystemBar(
            widthPx = 390,
            heightPx = 844,
            bottomInsetPx = 48,
            density = 1f,
            fontScale = 2f,
        )

    /**
     * Lays Now Playing out in a whole phone, hands the window a bottom inset, opens the sleep timer and
     * asserts that every choice came to rest inside the safe area.
     *
     * @param widthPx the screen, in pixels, as a device reports it.
     * @param heightPx the same, including the strip the system bar sits on.
     * @param bottomInsetPx the bar's height. Dispatched as a real `systemBars` inset.
     * @param density the pixel density those figures are in, so the dp the screen lays out in are the
     *   dp the device lays out in.
     * @param fontScale the text scale, independent of [density] exactly as `fontScale` is on a device.
     */
    private fun assertChoicesClearTheSystemBar(
        widthPx: Int,
        heightPx: Int,
        bottomInsetPx: Int,
        density: Float,
        fontScale: Float,
    ) {
        val screenWidth: Dp = (widthPx / density).dp
        val screenHeight: Dp = (heightPx / density).dp
        val safeBottom: Dp = ((heightPx - bottomInsetPx) / density).dp

        // The view Compose reads window insets through: WindowInsetsHolder listens on the
        // AndroidComposeView itself, so this is the one view a dispatch has to reach.
        var host: View? = null
        // Where the root put the phone. Read rather than assumed; see the note above.
        var screenTopInRoot = 0f
        compose.setContent {
            host = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                NeedlerTheme(reducedMotion = true) {
                    Box(
                        modifier = Modifier
                            .requiredWidth(screenWidth)
                            .requiredHeight(screenHeight)
                            .onGloballyPositioned { screenTopInRoot = it.positionInRoot().y },
                    ) {
                        NowPlayingScreen(
                            state = PlayerUiState(
                                item = PlayerFixtures.playingItem,
                                isPlaying = true,
                                durationMs = 200_000L,
                                output = PlayerFixtures.livingRoomSpeaker,
                                upNextCount = 6,
                            ),
                            progress = { PlaybackProgress(positionMs = 76_000L) },
                            onClose = {},
                            onOpenCrate = {},
                            onPlayPause = {},
                            onNext = {},
                            onPrevious = {},
                            onSeek = {},
                            onToggleShuffle = {},
                            onCycleRepeat = {},
                            onChooseOutput = {},
                            onToggleFavourite = {},
                            onChooseSleepTimer = {},
                            onOpenArtist = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        // Before the expansion, so the screen has already reserved the bar by the time anything asks
        // to be scrolled to - which is the order a device does it in.
        compose.runOnUiThread { host!!.dispatchApplyWindowInsets(systemBarsOf(bottomInsetPx)) }
        compose.waitForIdle()

        val origin: Dp = (screenTopInRoot / density).dp
        assertHeaderIsAtTheTop(origin)

        // The semantics action rather than a tap: on a short phone the chip itself starts below the
        // fold, and a click needs coordinates inside the window while this needs none. What is under
        // test is where the panel lands, not whether a pill can be hit.
        compose.onNodeWithContentDescription(CHANGE_THE_TIMER, substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        for (choice in SleepTimerOptions.offered) {
            val label: String = SleepTimerOptions.spokenLabel(choice)
            val bounds = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            val top: Dp = bounds.top - origin
            val bottom: Dp = bounds.bottom - origin
            assertTrue(
                label + " ends at " + bottom + " on a " + screenHeight + " screen whose safe area " +
                    "ends at " + safeBottom + ": " + (bottom - safeBottom) + " under the system bar",
                bottom <= safeBottom,
            )
            assertTrue(
                label + " starts at " + top + ", which is " + (0.dp - top) + " above the top of a " +
                    screenHeight + " screen",
                top >= 0.dp,
            )
            assertTrue(
                label + " was laid out " + (bottom - top) + " tall",
                (bottom - top) >= MIN_PILL_HEIGHT,
            )
        }
    }

    /**
     * Fails if the translation out of root space into the phone's own is wrong.
     *
     * The header is the one part of this screen whose position is fixed by the pack rather than by a
     * scroll - 96 dp tall, with the dismiss control inside it - so it is the cheapest thing to check
     * the origin against. Without it, a wrong origin is a whole suite going quietly green: that is
     * precisely what the shortened-box version of this file did.
     */
    private fun assertHeaderIsAtTheTop(origin: Dp) {
        val bounds = compose
            .onNodeWithContentDescription(CLOSE_NOW_PLAYING)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the header's dismiss control is at " + (bounds.top - origin) + " to " +
                (bounds.bottom - origin) + " in a screen whose header is " + HEADER_HEIGHT +
                " tall, so every bound below is offset",
            (bounds.top - origin) >= 0.dp && (bounds.bottom - origin) <= HEADER_HEIGHT,
        )
    }

    private companion object {

        /**
         * A bottom `systemBars` inset, visible, and nothing else.
         *
         * `safeDrawing` is the union of the system bars, the display cutout and the IME, so setting
         * the bars alone is enough to move it - and leaves the top at zero, which is what the screen's
         * own `only(WindowInsetsSides.Bottom)` filter would reduce it to anyway.
         */
        fun systemBarsOf(bottomPx: Int): PlatformWindowInsets = PlatformWindowInsets.Builder()
            .setInsets(PlatformWindowInsets.Type.systemBars(), Insets.of(0, 0, 0, bottomPx))
            .setVisible(PlatformWindowInsets.Type.systemBars(), true)
            .build()

        /** The pack's header, from [NowPlayingScreen]'s own measurements. */
        val HEADER_HEIGHT: Dp = 96.dp

        /**
         * A pill, less slack for rounding.
         *
         * `NeedlerPillButton` carries a minimum height in the theme's own sizes; 24 dp is well under
         * it and well over the zero the failure this guards against reported.
         */
        val MIN_PILL_HEIGHT: Dp = 24.dp

        /** The half of [app.needler.feature.player.ui.SleepTimerChip]'s label that names the action. */
        const val CHANGE_THE_TIMER: String = "Change the sleep timer"

        /** [NowPlayingScreen]'s dismiss control, used only to find the top of the screen. */
        const val CLOSE_NOW_PLAYING: String = "Close now playing"
    }
}

package app.needler.feature.player.nowplaying

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import app.needler.feature.player.ui.SleepTimerChoice
import app.needler.feature.player.ui.SleepTimerOptions
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sleep timer's choices, measured inside a phone rather than rendered at whatever height they
 * need.
 *
 * ## Why this is not a screenshot
 *
 * `PlayerScreenshotTest` already renders the panel, and `player-sleep-timer-choices-phone.png` is a
 * perfectly good picture of six pills that proves nothing about this bug: Roborazzi draws a canvas as
 * tall as it is asked for, so a column taller than the screen renders as a column taller than the
 * screen and the image looks right. The device report was "they extend below the viewable screen",
 * which is a statement about the *viewport*, and the only way to assert it is to lay the real screen
 * out inside one and read the bounds back.
 * [app.needler.feature.player.sidebar.PlayerSidebarMeasureTest] is the precedent, and it exists for
 * the same class of failure: a measure pass nobody had measured.
 *
 * ## Why two phones, and why the short one is the real test
 *
 * Measured, with the expansion open and nothing scrolled: the pills occupy 627 to 707 dp and the
 * column ends at 743 dp. On the pack's own 390 by 844 artboard that fits above a navigation bar with
 * room to spare, which is why this defect survived both a screenshot and a reading of the layout - at
 * the pack's height there is nothing wrong with it.
 *
 * [SHORT_PHONE_HEIGHT] is where it breaks, and it is not a contrived number: 1080 by 1920 at 420 dpi
 * is 411 by 731 dp, which is the same device `PlayerSidebarMeasureTest` cites for the landscape P0.
 * 731 dp less a navigation bar leaves 683 dp, the bottom row of pills lands at 671 to 707, and a
 * listener watches two of the six choices disappear under the system bar.
 *
 * ## The navigation bar is modelled by shortening the box
 *
 * Not by dispatching real window insets. `windowInsetsPadding` reads the window, and Robolectric's
 * window has no insets to read, so a test that faked them would be asserting against the fake.
 * Shortening the viewport asserts the same thing from the other side and is harder to get wrong:
 * whatever the screen draws, it has to fit in the part of the phone the system bar does not own.
 * 48 dp is the taller of the two bars - a gesture handle is 24 dp - so a panel that fits here fits
 * there.
 *
 * ## What is asserted
 *
 * Every one of the six pills, by the bounds of its own node, against the top and bottom of the
 * viewport. Not `assertIsDisplayed`: that test is made against the host *window*, which Robolectric
 * sizes for itself and which is not the box the screen was given. The two failures guarded against
 * are a pill below the fold - the bug, which `SessionControls` fixes by asking to be scrolled to -
 * and a pill scrolled off the top, which is what an over-eager scroll would do instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [PlayerScreenshots.SDK], application = Application::class)
class NowPlayingMeasureTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The reported case: a 411 by 731 dp phone, where the panel does not fit and has to be scrolled
     * to. Without that scroll the last two choices sit 24 dp under the navigation bar.
     */
    @Test
    fun `the sleep timer's choices fit a 1080 by 1920 phone once they unfold`() =
        assertChoicesFit(width = SHORT_PHONE_WIDTH, height = SHORT_PHONE_HEIGHT, fontScale = 1f)

    /** The pack's own artboard, which is the height at which this was never wrong. */
    @Test
    fun `the choices fit the pack's phone artboard`() =
        assertChoicesFit(width = PHONE_WIDTH, height = PHONE_HEIGHT, fontScale = 1f)

    /**
     * The text scale REQUIREMENTS.md names.
     *
     * "Text must scale to 200% without clipping", and at 200% the pills take more rows and every
     * control above them is taller, so the expansion adds the most height to a column that already
     * did not fit. Measured at 758 to 799 dp unscrolled against a 796 dp viewport: short by 3 dp on
     * the pack's *own* artboard, before any shorter phone is considered.
     */
    @Test
    fun `the choices still fit at 200 percent text`() =
        assertChoicesFit(width = PHONE_WIDTH, height = PHONE_HEIGHT, fontScale = 2f)

    /**
     * Lays Now Playing out in a phone-sized viewport, opens the sleep timer, and asserts that every
     * choice is inside it.
     */
    private fun assertChoicesFit(width: Dp, height: Dp, fontScale: Float) {
        val viewport: Dp = height - NAVIGATION_INSET
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NeedlerTheme(reducedMotion = true) {
                    Box(
                        modifier = Modifier
                            .requiredWidth(width)
                            .requiredHeight(viewport),
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

        // The semantics action rather than a tap: on a short phone the chip itself starts below the
        // fold, and a click needs coordinates inside the window while this needs none. What is under
        // test is where the panel lands, not whether a pill can be hit.
        compose.onNodeWithContentDescription(CHANGE_THE_TIMER, substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        for (choice in SleepTimerOptions.offered) {
            val label: String = SleepTimerOptions.spokenLabel(choice)
            val bounds = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertTrue(
                label + " is " + (bounds.bottom - viewport) + " below a " + viewport + " viewport",
                bounds.bottom <= viewport,
            )
            assertTrue(
                label + " is " + (0.dp - bounds.top) + " above a " + viewport + " viewport",
                bounds.top >= 0.dp,
            )
        }

        // Guards the assertion itself: six pills all reporting zero bounds would satisfy the two
        // above, and that is exactly the shape of the landscape failure PlayerSidebarMeasureTest
        // exists for.
        val first: String = SleepTimerOptions.spokenLabel(SleepTimerChoice.OFF)
        compose.onNodeWithContentDescription(first).getUnclippedBoundsInRoot().let { bounds ->
            assertTrue(
                first + " was laid out " + (bounds.bottom - bounds.top) + " tall",
                (bounds.bottom - bounds.top) >= MIN_PILL_HEIGHT,
            )
        }
    }

    private companion object {
        /** The pack's phone artboard. */
        val PHONE_WIDTH: Dp = 390.dp
        val PHONE_HEIGHT: Dp = 844.dp

        /** 1080 by 1920 at 420 dpi: the phone the expanded panel did not fit on. */
        val SHORT_PHONE_WIDTH: Dp = 411.dp
        val SHORT_PHONE_HEIGHT: Dp = 731.dp

        /** A three-button navigation bar: the tallest thing that takes height off the bottom. */
        val NAVIGATION_INSET: Dp = 48.dp

        /**
         * A pill, less slack for rounding.
         *
         * `NeedlerPillButton` carries a minimum height in the theme's own sizes; 24 dp is well under
         * it and well over the zero the failure this guards against reported.
         */
        val MIN_PILL_HEIGHT: Dp = 24.dp

        /** The half of [app.needler.feature.player.ui.SleepTimerChip]'s label that names the action. */
        const val CHANGE_THE_TIMER: String = "Change the sleep timer"
    }
}

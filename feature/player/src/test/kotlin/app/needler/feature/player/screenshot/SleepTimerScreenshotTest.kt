package app.needler.feature.player.screenshot

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.feature.player.PlayerUiState
import app.needler.feature.player.fake.PlayerFixtures
import app.needler.feature.player.nowplaying.NowPlayingScreen
import app.needler.feature.player.ui.SleepTimerOptions
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sleep timer's choices open on Now Playing, which is the only place a user ever sees them.
 *
 * ## What the golden this replaces showed
 *
 * `player-sleep-timer-choices-phone.png` used to be [app.needler.feature.player.ui.SleepTimerChoices]
 * on its own, centred in an empty 390x844 frame: six pills floating on blank canvas, with no sheet,
 * no scrim, no anchor and no Now Playing behind them. Measured on the committed PNG, content ended
 * at row 923 of 1688 and the remaining 764 rows were canvas. Nothing about that image was wrong, and
 * nothing about it was a test of anything: a panel rendered on its own cannot show where the panel
 * lands, and *where it lands* is the whole of the defect fixed this week - the choices unfolded below
 * the fold and the bottom row sat underneath the gesture bar.
 *
 * So the screen is composed, the chip is pressed, and whatever the screen then looks like is the
 * image. The panel is reached the way a user reaches it rather than called directly, which is why
 * this file needs a compose rule and the rest of [PlayerScreenshotTest] does not: the expansion is
 * `rememberSaveable` state inside
 * [app.needler.feature.player.ui.SessionControls] and deliberately not a parameter - that composable's
 * own KDoc argues why - so there is no state to hand in and the only way in is the control.
 *
 * ## How this divides with NowPlayingMeasureTest
 *
 * [app.needler.feature.player.nowplaying.NowPlayingMeasureTest] owns the **geometry**, and should
 * keep it: it lays the screen out inside three real phones, dispatches a real `systemBars` inset, and
 * asserts every pill's bounds against the safe area in the phone's own coordinates. Its own KDoc
 * explains why that cannot be a screenshot - "Roborazzi draws a canvas as tall as it is asked for, so
 * a column taller than the screen renders as a column taller than the screen and the image looks
 * right".
 *
 * This file therefore asserts no bounds and dispatches no inset, on purpose. What it holds still is
 * the **composition**: that opening the timer draws six pills inline under the chip on a Now Playing
 * that is still there behind them, rather than a sheet, a menu, a dialog or a blank frame. A reader
 * comparing the two images sees what a listener sees; a reader wanting the pixel the bottom pill ends
 * on reads the measure test. Duplicating the inset here would produce a second, weaker copy of that
 * assertion and an image whose correctness could only be judged with a ruler.
 *
 * ## What stops the image being Now Playing at rest
 *
 * The condition the old golden could not detect: if the click stops reaching the chip, or the panel
 * stops drawing, the capture quietly becomes a picture of Now Playing under a name that promises six
 * pills. Every offered choice is therefore asserted **displayed** before the capture - displayed,
 * not merely present, because a pill composed outside the window is the shape of the defect.
 *
 * An earlier version also captured the unopened screen to a scratch file and failed if the golden
 * matched it byte for byte. That was the weaker half of the same check - it could only say the two
 * differed, not that the panel was on screen - and it broke verification outright: Roborazzi decides
 * record-or-verify globally, so under `-Pneedler.screenshots.verify` the scratch capture tried to
 * compare against a temp file that had never been written and threw
 * `NullPointerException: read(...) must not be null`. It passed locally in record mode and failed
 * the release build. The displayed assertions above say everything it said and work in both modes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [PlayerScreenshots.SDK],
    application = Application::class,
    // The pack's phone artboard as the *window*, which is what a compose rule lays out into.
    // `capturePlayerScreen` gets the same size from Roborazzi's own option; a rule-driven test has to
    // ask Robolectric for it, and `NowPlayingMeasureTest` records what happens without it - a child
    // larger than Robolectric's default 320x470 window is centred, at negative coordinates.
    qualifiers = "w390dp-h844dp-xhdpi",
)
class SleepTimerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the sleep timer's choices, open on Now Playing`() {
        capture("player-sleep-timer-choices", fontScale = 1f)
    }

    /**
     * The same at the 200% REQUIREMENTS.md requires every screen to survive.
     *
     * The text scale is where this panel costs the most height: six pills rewrap from two rows to
     * three or four, and every control above them is taller, so the expansion adds the most to a
     * column that already did not fit. `NowPlayingMeasureTest` asserts that they still clear the
     * system bar at this scale; this says what the screen looks like while they do.
     */
    @Test
    fun `the choices at 200 percent text`() {
        capture("player-sleep-timer-choices-large-text", fontScale = 2f)
    }

    /**
     * Composes Now Playing, captures it shut, opens the timer and captures that as `name`.
     *
     * One composition and two captures, because a compose rule takes one `setContent` per test and
     * the baseline has to be the same screen rather than a second one.
     */
    private fun capture(name: String, fontScale: Float) {
        compose.setContent {
            Screen(fontScale = fontScale)
        }
        compose.waitForIdle()

        // The semantics action rather than a tap, for `NowPlayingMeasureTest`'s reason: at 200% text
        // the chip itself starts below the fold, and a click needs coordinates inside the window
        // while this needs none. What is being recorded is where the panel lands, not whether the
        // chip can be hit - `SleepTimerChipTest` and the measure test cover that.
        compose.onNodeWithContentDescription(CHANGE_THE_TIMER, substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        // Displayed, not merely present: a pill composed outside the window is the shape of the
        // defect, and a golden of a screen whose panel is off it would otherwise still be written.
        for (choice in SleepTimerOptions.offered) {
            compose.onNodeWithContentDescription(SleepTimerOptions.spokenLabel(choice))
                .assertIsDisplayed()
        }

        val file = File(PlayerScreenshots.outputDirectory, name + "-phone.png")
        compose.onRoot().captureRoboImage(file = file)

        assertRendered(file, PlayerDevice.Phone)
    }

    @Composable
    private fun Screen(fontScale: Float) {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(base.density, fontScale),
        ) {
            NeedlerTheme(reducedMotion = true) {
                Box(modifier = Modifier.fillMaxSize().background(NeedlerTheme.colors.canvas)) {
                    NowPlayingScreen(
                        state = PLAYING,
                        progress = { PROGRESS },
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

    private companion object {
        /** Screen 07's own state: Sienna, playing on the living room speaker. */
        val PLAYING = PlayerUiState(
            item = PlayerFixtures.playingItem,
            isPlaying = true,
            durationMs = 200_000L,
            output = PlayerFixtures.livingRoomSpeaker,
            upNextCount = 6,
        )

        /** 1:16 of 3:20, the 38% the pack draws the thumb at. */
        val PROGRESS = PlaybackProgress(positionMs = 76_000L)

        /** The half of `SleepTimerChip`'s content description that names the action. */
        const val CHANGE_THE_TIMER: String = "Change the sleep timer"
    }
}

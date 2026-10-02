package app.needler.feature.player.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.feature.player.screenshot.PlayerScreenshots
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The elapsed and remaining readouts are **meant** to be absent from the accessibility tree.
 *
 * [Scrubber] clears them with `clearAndSetSemantics` because the scrub bar beside them already
 * announces the position: left in, TalkBack says the same fact three times on one row. The pair of
 * readouts has been filed as a missing-label defect more than once, and every time the correct
 * finding was that the bar is the label.
 *
 * This is the assertion that says so, so that the next audit reads a deliberate decision rather than
 * rediscovering an absence. It is not a test of the clearing modifier - it is a test that the drawn
 * numbers are still drawn, which is the half that a well-meant "fix" would take away by swapping the
 * modifier for a content description on each label.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [PlayerScreenshots.SDK], application = Application::class)
class ScrubberSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the bar announces the position and the two readouts do not`() {
        showing()

        // The one node that speaks, with the seek action behind it.
        compose.onNodeWithContentDescription("Playback position").assertExists()

        listOf("1:16", "-2:04").forEach { timecode ->
            // Drawn, so a sighted listener still has the numbers.
            compose.onNodeWithText(timecode, useUnmergedTree = true).assertExists()
            // And not spoken, which is the decision this test exists to record.
            assertEquals(
                timecode + " must stay out of the accessibility tree: the bar already says it",
                0,
                compose.onAllNodesWithText(timecode).fetchSemanticsNodes().size,
            )
        }
    }

    private fun showing() {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                Scrubber(
                    // 1:16 of 3:20, which leaves -2:04: the position screen 07 is drawn at.
                    progress = { PlaybackProgress(positionMs = 76_000L) },
                    durationMs = 200_000L,
                    onSeek = {},
                )
            }
        }
    }
}

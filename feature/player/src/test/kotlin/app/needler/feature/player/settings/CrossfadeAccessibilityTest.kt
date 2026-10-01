package app.needler.feature.player.settings

import android.app.Application
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.CrossfadeDuration
import app.needler.core.domain.model.CrossfadeSettings
import app.needler.feature.player.screenshot.PlayerScreenshots
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the crossfade slider announces.
 *
 * ## Why this is a test and not a fix
 *
 * The device audit filed the slider's value as absent from the accessibility tree: "4 s", "Off", "6 s"
 * and "12 s" render on screen and appear nowhere as text or `content-desc`, the SeekBar carries
 * `content-desc=""` and `focusable="false"`, and a sibling carries only "Crossfade length". It filed it
 * as *needing confirmation*, because `uiautomator dump` writes `text` and `content-desc` into its XML
 * and writes neither `stateDescription` nor `RangeInfo` - so the one thing it could not see is exactly
 * where Compose puts a slider's value.
 *
 * It is already there. The slider publishes a four-step [ProgressBarRangeInfo] and a
 * `stateDescription` of [spokenDuration], and the three readouts the audit looked for are
 * `clearAndSetSemantics {}` on purpose, so that TalkBack says the value once rather than four times.
 * These assertions are what settles that without a TalkBack pass, and what stops someone "fixing" it by
 * putting the number back into a content description and making it announce twice.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [PlayerScreenshots.SDK], application = Application::class)
class CrossfadeAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the slider announces its value, its range and its steps`() {
        showing(CrossfadeDuration.FOUR_SECONDS)

        val slider = compose.onNodeWithContentDescription("Crossfade length")
        slider.assertContentDescriptionEquals("Crossfade length")
        // Four stops: index 1 of 0..3, with the two interior stops as steps. Not 4 s of 12 - a range in
        // seconds would let TalkBack's seek gestures land between stops the player cannot hold.
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(current = 1f, range = 0f..3f, steps = 2))
        assertEquals(
            "4 seconds",
            slider.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
    }

    @Test
    fun `off announces as off rather than as zero`() {
        showing(CrossfadeDuration.OFF)

        val slider = compose.onNodeWithContentDescription("Crossfade length")
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(current = 0f, range = 0f..3f, steps = 2))
        assertEquals("Off", slider.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    /**
     * REQUIREMENTS.md "Accessibility": anything reachable by dragging must also be reachable by an
     * accessible action. A 4 dp track is not draggable with TalkBack on.
     */
    @Test
    fun `the slider can be set without a gesture, and snaps to a stop`() {
        val chosen: MutableList<CrossfadeDuration> = mutableListOf()
        showing(CrossfadeDuration.OFF, onDurationChange = { chosen += it })

        val slider = compose.onNodeWithContentDescription("Crossfade length")

        // performSemanticsAction fails the test if the action is absent, so this line is the assertion
        // that it exists. 2.4 is not a stop: TalkBack's own increments need not land on one, so the
        // control rounds to the nearest, which is index 2 - six seconds.
        slider.performSemanticsAction(SemanticsActions.SetProgress) { set -> set(2.4f) }
        assertEquals(listOf(CrossfadeDuration.SIX_SECONDS), chosen)

        // And it clamps rather than throwing on a value off the end of the range.
        slider.performSemanticsAction(SemanticsActions.SetProgress) { set -> set(99f) }
        assertEquals(CrossfadeDuration.TWELVE_SECONDS, chosen.last())
    }

    /**
     * The three readouts stay out of the tree.
     *
     * The audit's finding was correct as an observation - the numbers really are not there as text - and
     * that is the right answer, because the slider says the value itself. Asserting it stops the
     * "absent value" reading from being re-fixed into a double announcement.
     */
    @Test
    fun `the printed readouts are hidden, because the slider already says the value`() {
        showing(CrossfadeDuration.SIX_SECONDS)

        assertEquals(
            "'6 s' must not be announced beside the slider's own value",
            0,
            compose.onAllNodesWithText("6 s").fetchSemanticsNodes().size,
        )
    }

    private fun showing(
        duration: CrossfadeDuration,
        onDurationChange: (CrossfadeDuration) -> Unit = {},
    ) {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                CrossfadeScreen(
                    settings = CrossfadeSettings(duration = duration),
                    onDurationChange = onDurationChange,
                    onSuppressWithinAlbumChange = {},
                    onFadeOnSkipChange = {},
                    onFadeOnPauseChange = {},
                    onBack = {},
                )
            }
        }
    }
}

package app.needler.feature.player.crate

import android.app.Application
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.PlayQueue
import app.needler.feature.player.fake.PlayerFixtures
import app.needler.feature.player.screenshot.PlayerScreenshots
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Removing one track from the crate.
 *
 * The device audit: "Drag reorders, 'Clear' empties everything, nothing removes one row" - so "I do not
 * want to hear this next" was a choice between dragging a track to the end and emptying the queue. The
 * row has a Remove button now, and three things about it are worth asserting rather than drawing:
 *
 *  * it is **named after the track it removes**, so TalkBack does not offer eleven identical "Remove"
 *    buttons on a single-album queue;
 *  * it is a **48 dp target**, which REQUIREMENTS.md asks of controls and which the row gets from
 *    `NeedlerIconButton`'s `max(visualSize, 48dp)` rather than from its own 40 dp `visualSize` - a
 *    distinction a screenshot cannot show;
 *  * it is on the **Playing row too**, because `PlayQueue.withItemRemoved` already defines what removing
 *    what is playing means and leaving the one row out would be the only row you cannot edit.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [PlayerScreenshots.SDK], application = Application::class)
class CrateRemoveTest {

    @get:Rule
    val compose = createComposeRule()

    private val removed: MutableList<String> = mutableListOf()

    @Test
    fun `an up next row removes itself, and says which track it removes`() {
        showing()

        val button = compose.onNodeWithContentDescription("Remove Hamptons from the crate")
        button.assertHeightIsAtLeast(48.dp)
        button.assertWidthIsAtLeast(48.dp)
        button.performClick()

        assertEquals(listOf("q2"), removed)
    }

    @Test
    fun `the playing row can be removed as well`() {
        showing()

        compose.onNodeWithContentDescription("Remove Sienna from the crate").performClick()

        assertEquals(listOf("q1"), removed)
    }

    /**
     * Item 22, on the rendered screen rather than on the state.
     *
     * `CrateUiStateTest` asserts the decision; this asserts that the rows actually read it, which is the
     * part a wiring mistake at one of the two call sites would break silently.
     */
    @Test
    fun `a one-record crate announces each row's place in the record, not the album four times`() {
        showing(PlayerFixtures.albumCrate)

        for (position in 1..4) {
            compose.onNodeWithContentDescription(", Track " + position, substring = true)
                .assertExists()
        }
        assertEquals(
            "a one-record crate must not repeat the artist and album on every row",
            0,
            compose.onAllNodesWithContentDescription("The Marias · Submarine", substring = true)
                .fetchSemanticsNodes()
                .size,
        )
    }

    private fun showing(queue: PlayQueue = PlayerFixtures.crate) {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                CrateScreen(
                    state = CrateUiState(queue = queue, isPlaying = true),
                    onBack = {},
                    onPlayItem = {},
                    onMove = { _, _ -> },
                    onRemove = { id -> removed += id },
                    onClear = {},
                )
            }
        }
    }
}

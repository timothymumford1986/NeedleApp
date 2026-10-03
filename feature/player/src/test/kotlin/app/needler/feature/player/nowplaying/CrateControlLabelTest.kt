package app.needler.feature.player.nowplaying

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.feature.player.PlayerUiState
import app.needler.feature.player.fake.PlayerFixtures
import app.needler.feature.player.screenshot.PlayerScreenshots
import app.needler.feature.player.ui.PathQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The header control that opens the crate, and the two halves of it that used to disagree.
 *
 * ## The disagreement
 *
 * It was drawn as a list **with a plus beside it** and announced as "In the crate". The glyph promised
 * *add to the crate*, the label named *a place*, and the control did neither: it navigates to
 * `CrateScreen`. Three descriptions of one button, no two of them the same.
 *
 * The control views, so both now say so. The glyph lost the plus - the pack's own first path survives
 * unchanged - and the label became a verb.
 *
 * ## Why the path is asserted as a string
 *
 * Because the plus coming back is a two-token edit in a 40-character constant that no screenshot can
 * fail on: the baselines record the glyph, and a baseline is re-recorded by the same command that
 * changes it, so a reinstated plus would be committed as the new truth. The string is the only place
 * the decision is checkable. It is deliberately asserted as "no second subpath" rather than as the
 * exact path, so moving a line by a pixel does not fail a test about a plus.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [PlayerScreenshots.SDK], application = Application::class)
class CrateControlLabelTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the control says it opens the crate, and opens it`() {
        var opened = 0
        showing(onOpenCrate = { opened++ })

        val control = compose.onNodeWithContentDescription(OPEN_CRATE)
        control.assertHasClickAction()
        control.performClick()

        assertEquals(1, opened)
    }

    /**
     * The old label is gone from the tree rather than joined by the new one.
     *
     * "In the crate" is still correct as the crate screen's own *heading* - REQUIREMENTS.md
     * "Vocabulary" fixes it as the word for the play queue - so what is asserted is that it is no
     * longer what this button announces, not that the phrase has left the product.
     */
    @Test
    fun `the control no longer announces itself as a place`() {
        showing()

        assertEquals(
            "the header button must not be announced as \"In the crate\"",
            0,
            compose.onAllNodesWithContentDescription(OLD_LABEL).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `the glyph is a plain list, with no plus promising an add`() {
        assertEquals(
            "the crate glyph should be three strokes - a three-line list - and is: " + PathQueue,
            STROKES_IN_A_LIST,
            PathQueue.count { character -> character == 'M' },
        )
        assertFalse(
            "the crate glyph still draws the pack's plus, which promises an action the control " +
                "does not perform: " + PathQueue,
            PathQueue.contains(PLUS_VERTICAL) || PathQueue.contains(PLUS_HORIZONTAL),
        )
    }

    private fun showing(onOpenCrate: () -> Unit = {}) {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
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
                    onOpenCrate = onOpenCrate,
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

    private companion object {
        const val OLD_LABEL = "In the crate"

        /** Three `M` commands: one per line of the list, and nothing after them. */
        const val STROKES_IN_A_LIST = 3

        /** The two strokes of the pack's plus, which the glyph must not draw. */
        const val PLUS_VERTICAL = "M19 15v6"
        const val PLUS_HORIZONTAL = "M16 18h6"
    }
}

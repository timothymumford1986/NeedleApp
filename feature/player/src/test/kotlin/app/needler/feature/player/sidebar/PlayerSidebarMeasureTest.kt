package app.needler.feature.player.sidebar

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.feature.player.PlayerUiState
import app.needler.feature.player.crate.CrateUiState
import app.needler.feature.player.fake.PlayerFixtures
import app.needler.feature.player.screenshot.PlayerScreenshots
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sidebar's controls, measured rather than calculated.
 *
 * ## Why this file exists beside [PlayerSidebarLayoutTest]
 *
 * [PlayerSidebarLayoutTest] asserts the decision - how much artwork a height can afford - and that is
 * worth asserting on its own. It cannot, however, catch the failure the device audit found, because the
 * failure was not in the decision: it was that the pack's own measurements did not fit the height the
 * panel was given, so `Column` measured the scrubber, the transport, the output chip and the crate
 * against a maximum height of zero and every transport control reported bounds `[0,0][0,0]`.
 *
 * Neither does the Roborazzi render. `player-sidebar-short-landscape.png` is asserted to be 802 by 780
 * pixels, and a panel with nothing in it is also 802 by 780 pixels. So this test lays the real panel out
 * at three heights and reads the bounds back out of the semantics tree - which is the same tree
 * `uiautomator dump` reads, and therefore the same measurement the audit took.
 *
 * ## The three heights
 *
 * 390 dp is a phone in landscape, which is where the P0 was found: 1080 px at 420 dpi is 411 dp, less the
 * status and navigation insets. [sidebarFullHeight] is the threshold at which the panel stops adapting
 * and takes the pack's measurements unscaled, and is therefore the one height at which an error in those
 * measurements shows up as a squeezed control. 800 dp is the pack's own artboard for screen 09.
 *
 * `application = Application::class` keeps Hilt out of it, as in the screenshot tests: the panel renders
 * from a literal [PlayerUiState] with no view model, no session and no Media3.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [PlayerScreenshots.SDK], application = Application::class)
class PlayerSidebarMeasureTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a phone in landscape can still be paused`() = assertOperable(390.dp)

    @Test
    fun `the panel is operable at the height it stops adapting`() = assertOperable(sidebarFullHeight)

    @Test
    fun `a 1024 by 600 tablet in landscape is operable`() = assertOperable(600.dp)

    @Test
    fun `the pack's own artboard height is operable`() = assertOperable(800.dp)

    /**
     * Lays the panel out at [height] and asserts that every control a listener cannot work around was
     * given room.
     *
     * The thresholds are the pack's smaller transport sizes rather than round numbers: the play button is
     * 68 dp on screen 09 and the skips 48 dp, and `NeedlerIconButton` gives everything a 48 dp target, so
     * anything under those is a control that was clipped by the measure pass. 40 dp rather than 48 dp for
     * the play button is deliberate slack for the one dp of rounding a density conversion can cost; the
     * failure being guarded against reported zero, not forty-seven.
     */
    private fun assertOperable(height: Dp) {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                Box(
                    modifier = Modifier
                        .requiredWidth(NeedlerTheme.sizes.sidebarWidth + 1.dp)
                        .requiredHeight(height),
                ) {
                    PlayerSidebarContent(
                        state = PlayerUiState(
                            item = PlayerFixtures.playingItem,
                            isPlaying = true,
                            durationMs = 200_000L,
                            output = PlayerFixtures.thisPhone,
                            upNextCount = 4,
                        ),
                        crate = CrateUiState(queue = PlayerFixtures.crate, isPlaying = true),
                        progress = { PlaybackProgress(positionMs = 76_000L) },
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
                        onPlayItem = {},
                        onMove = { _, _ -> },
                        onRemove = {},
                    )
                }
            }
        }

        // The node that did not exist at all in the audit's dump.
        compose.onNodeWithContentDescription("Pause").assertHeightIsAtLeast(40.dp)
        compose.onNodeWithContentDescription("Next track").assertHeightIsAtLeast(40.dp)
        compose.onNodeWithContentDescription("Previous track").assertHeightIsAtLeast(40.dp)
        compose.onNodeWithContentDescription("Shuffle off").assertHeightIsAtLeast(40.dp)
        compose.onNodeWithContentDescription("Repeat off").assertHeightIsAtLeast(40.dp)

        // REQUIREMENTS.md "Output": the current output is always named in the player.
        compose.onNodeWithContentDescription("Playing on This phone", substring = true)
            .assertHeightIsAtLeast(24.dp)

        // The scrubber, which is also the only way to seek without a gesture.
        compose.onNodeWithContentDescription("Playback position").assertHeightIsAtLeast(24.dp)

        // "In the crate" is a heading, so it must have something under it: one row, at least. Asserted
        // through the row's own Remove button rather than its title, because that is the control the
        // crate gained for punch-list item 16 and it is the part of the row furthest down the panel.
        compose.onNodeWithContentDescription(
            "Remove " + PlayerFixtures.crate.items[1].track.title + " from the crate",
        ).assertHeightIsAtLeast(24.dp)
    }
}

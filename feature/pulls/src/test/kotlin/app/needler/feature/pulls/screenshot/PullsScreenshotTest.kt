package app.needler.feature.pulls.screenshot

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import app.needler.feature.pulls.SamplePulls
import app.needler.feature.pulls.pulls.PullsNotice
import app.needler.feature.pulls.pulls.PullsScreen
import app.needler.feature.pulls.pulls.PullsUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Pulls screen in every state it has.
 *
 * `application = Application::class` keeps Hilt out of it: these render the
 * stateless `PullsScreen` from a literal [PullsUiState], so nothing here needs a
 * dependency graph, a repository or a server.
 *
 * The data is the design pack's own — the same five pulls, the same "2 in
 * progress", the same 62% — so the phone PNG can be put beside
 * `design/html/06-Pulls.html` and compared line for line. The states the pack
 * does not happen to draw are rendered too, because they are the ones
 * REQUIREMENTS.md requires and the ones nobody would otherwise look at: a
 * request parked for an administrator, a pull parked for a manual source pick,
 * a part-delivered album, and the held-for-review notice.
 *
 * ## These images have never been generated
 *
 * There is no Android SDK and no JDK 21 on the machine this module was written
 * on, so no golden PNG is committed with it. The first `recordScreenshots` run
 * on a machine that has them is also the first time anyone sees this screen
 * rendered, and the images it writes are the goldens. Until then these are
 * tests that assert a composable lays out and draws, which is worth having on
 * its own, but they are not yet a comparison against anything.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class PullsScreenshotTest {

    // ---- phone --------------------------------------------------------------

    @Test
    fun `the pack's own queue`() {
        capture("pulls-queue", NeedlerDevice.Phone, PACK)
    }

    @Test
    fun `every state a pull can be in`() {
        capture(
            "pulls-every-state",
            NeedlerDevice.Phone,
            PACK.copy(pulls = SamplePulls.everyState),
        )
    }

    /**
     * The queue a real device drew: mostly pulls the `album` mirror could not
     * name.
     *
     * A screenshot suite built only from well-formed data proves nothing, and this
     * one was. Every fixture had a title, so the render that shipped looked
     * correct while the live screen showed 34 blank title lines and 34 **Cancel**
     * buttons described as "Cancel the pull of ". This image is where that is now
     * visible: the fallback fills the title slot, the one named row is kept beside
     * the unnamed ones so the difference can be seen, and a row with neither title
     * nor artist is included because it is the floor of the layout.
     */
    @Test
    fun `pulls the server could not name`() {
        capture(
            "pulls-missing-titles",
            NeedlerDevice.Phone,
            PACK.copy(pulls = SamplePulls.withMissingTitles, heldCount = 0),
        )
    }

    /** The same rows at 200% text, where a fallback title has the least room. */
    @Test
    fun `pulls the server could not name, at 200 percent text size`() {
        capture(
            "pulls-missing-titles-large-text",
            NeedlerDevice.Phone,
            PACK.copy(pulls = SamplePulls.withMissingTitles, heldCount = 0),
            fontScale = 2f,
        )
    }

    @Test
    fun `nothing in flight, so the header says so`() {
        capture(
            "pulls-nothing-active",
            NeedlerDevice.Phone,
            PACK.copy(
                pulls = listOf(
                    SamplePulls.landedToday,
                    SamplePulls.landedYesterday,
                    SamplePulls.failed,
                ),
                heldCount = 0,
            ),
        )
    }

    @Test
    fun `held items and no connection`() {
        capture(
            "pulls-held-offline",
            NeedlerDevice.Phone,
            PACK.copy(heldCount = 3, offline = true),
        )
    }

    @Test
    fun `an action the server refused`() {
        capture(
            "pulls-problem",
            NeedlerDevice.Phone,
            PACK.copy(
                busy = false,
                notice = PullsNotice.Problem(
                    "The server no longer has that task. It has either finished or been " +
                        "cleared already.",
                ),
            ),
        )
    }

    @Test
    fun `loading, before the mirror has answered`() {
        capture("pulls-loading", NeedlerDevice.Phone, PullsUiState(loading = true))
    }

    @Test
    fun `nothing has ever been pulled`() {
        capture("pulls-empty", NeedlerDevice.Phone, PullsUiState(loading = false))
    }

    @Test
    fun `nothing pulled and no connection to pull with`() {
        capture(
            "pulls-empty-offline",
            NeedlerDevice.Phone,
            PullsUiState(loading = false, offline = true),
        )
    }

    @Test
    fun `at 200 percent text size`() {
        capture("pulls-large-text", NeedlerDevice.Phone, PACK, fontScale = 2f)
    }

    // ---- tablet -------------------------------------------------------------

    @Test
    fun `two panes on a tablet, in the pack's content pane`() {
        val file = captureNeedlerScreen("pulls-queue", NeedlerDevice.Tablet) {
            TabletFrame { Screen(PACK, WindowWidthSizeClass.Expanded) }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `a tablet pane with nothing in it keeps its heading`() {
        val file = captureNeedlerScreen("pulls-nothing-active", NeedlerDevice.Tablet) {
            TabletFrame(badgeCount = 0) {
                Screen(
                    PACK.copy(
                        pulls = listOf(SamplePulls.landedToday, SamplePulls.failed),
                        heldCount = 0,
                        badgeCount = 0,
                    ),
                    WindowWidthSizeClass.Expanded,
                )
            }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `an empty queue on a tablet`() {
        val file = captureNeedlerScreen("pulls-empty", NeedlerDevice.Tablet) {
            TabletFrame(badgeCount = 0) {
                Screen(PullsUiState(loading = false), WindowWidthSizeClass.Expanded)
            }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    // ---- plumbing -----------------------------------------------------------

    private fun capture(
        name: String,
        device: NeedlerDevice,
        state: PullsUiState,
        fontScale: Float = 1f,
    ) {
        val file = captureNeedlerScreen(name, device, fontScale) {
            Screen(
                state = state,
                widthSizeClass = when (device) {
                    NeedlerDevice.Phone -> WindowWidthSizeClass.Compact
                    NeedlerDevice.Tablet -> WindowWidthSizeClass.Expanded
                },
            )
        }
        assertRendered(file, device)
    }

    @Composable
    private fun Screen(state: PullsUiState, widthSizeClass: WindowWidthSizeClass) {
        PullsScreen(
            state = state,
            widthSizeClass = widthSizeClass,
            onClearDone = {},
            onOpenAlbum = {},
            onPlayAlbum = {},
            onCancel = {},
            onRetry = {},
            onDismissNotice = {},
        )
    }

    private companion object {
        /** The pack's own queue, at the pack's own instant. */
        val PACK = PullsUiState(
            loading = false,
            pulls = SamplePulls.pack,
            heldCount = SamplePulls.summary.heldCount,
            badgeCount = 3,
            renderedAt = SamplePulls.renderedAt,
        )
    }
}

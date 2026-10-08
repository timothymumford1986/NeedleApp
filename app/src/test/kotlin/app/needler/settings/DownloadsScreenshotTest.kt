package app.needler.settings

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.screenshot.NeedlerDevice
import app.needler.screenshot.NeedlerScreenshots
import app.needler.screenshot.PlayerSidebarPlaceholder
import app.needler.screenshot.assertRendered
import app.needler.screenshot.captureNeedlerScreen
import app.needler.ui.navigation.NeedlerDestination
import app.needler.ui.navigation.NeedlerNavigationScaffold
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Downloaded albums screen in each state it has to explain.
 *
 * One image per condition that changes what the screen *says*: a library bigger than the Settings
 * section could hold, a device with nothing on it, the row a removal is deleting right now, and what
 * a finished removal leaves behind.
 *
 * `application = Application::class` keeps Hilt out of it, as `SettingsScreenshotTest` does.
 * Everything here renders the stateless [DownloadsScreen] from a literal [DownloadsUiState]; nothing
 * needs a dependency graph, a cache index or a server.
 *
 * ## Why the tablet image has the chrome round it and the phone ones do not
 *
 * The tablet render was of the screen alone on a 1280dp canvas, and it showed a 640dp column floating
 * in the middle of it with the back chevron a quarter of the way across the window and no navigation
 * anywhere - which is not what the app presents. This destination lives in the nested graph under
 * `settings`, so the app draws it in `NeedlerNavigationScaffold`'s content pane with the rail on one
 * side and the player sidebar on the other; [TabletShell] is that, with the same
 * [PlayerSidebarPlaceholder] `NavigationScreenshotTest` uses, so the image shows the 784dp pane the
 * screen really gets.
 *
 * The phone images stay bare, which is the convention every `:app` screenshot already follows
 * (`SettingsScreenshotTest`, `ConnectScreenshotTest`). The defect being fixed was a tablet one: at
 * compact width the content pane is the window less the chrome's height, and nothing about this
 * screen's layout changes with it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class DownloadsScreenshotTest {

    @Test
    fun `the full list on a phone`() {
        capture("downloads", NeedlerDevice.Phone, FULL)
    }

    @Test
    fun `the full list on a tablet, inside the chrome the app puts round it`() {
        capture("downloads", NeedlerDevice.Tablet, FULL)
    }

    @Test
    fun `a device with nothing downloaded explains itself rather than rendering blank`() {
        // Reachable: removing the last album leaves the reader standing here. The Settings row that
        // leads here is not drawn in this state, so this is the only way to see it.
        capture("downloads-empty", NeedlerDevice.Phone, DownloadsUiState(loading = false))
    }

    @Test
    fun `a removal in flight marks the row it is deleting`() {
        // One row reads "Removing" and the other thirteen read "Remove" and do not respond, which is
        // the whole point of naming the album rather than setting a flag: an action that deletes
        // files has to say which files.
        capture(
            "downloads-removing",
            NeedlerDevice.Phone,
            FULL.copy(removing = REMOVED.releaseGroupMbid),
        )
    }

    @Test
    fun `a finished removal says what it freed, and offers the way back`() {
        // REQUIREMENTS.md "Storage, and why there is no budget": "a 'remove' that leaves the usage
        // figure unchanged is the one thing that would make this whole screen untrustworthy". The
        // album is gone from the list and out of the header's total in the same image as the sentence
        // claiming it - the render this replaces reported "128 MB freed" over a header still reading
        // "14 albums · 5.5 GB", which is that failure drawn from a fixture that forgot to remove
        // anything.
        capture(
            "downloads-removed",
            NeedlerDevice.Phone,
            DownloadsUiState(
                loading = false,
                downloaded = DownloadedAlbumFixtures.MANY - REMOVED,
                notice = DownloadsNotice(
                    headline = "Removed " + REMOVED.title,
                    detail = "10 tracks deleted, 584 MB freed.",
                    undo = REMOVED,
                    destructive = true,
                ),
            ),
        )
    }

    private fun capture(name: String, device: NeedlerDevice, state: DownloadsUiState) {
        val file = captureNeedlerScreen(name, device) {
            val widthSizeClass = when (device) {
                NeedlerDevice.Phone -> WindowWidthSizeClass.Compact
                NeedlerDevice.Tablet -> WindowWidthSizeClass.Expanded
            }
            val screen: @Composable () -> Unit = {
                DownloadsScreen(
                    state = state,
                    onRemove = {},
                    onUndo = {},
                    onDismissNotice = {},
                    onBack = {},
                    widthSizeClass = widthSizeClass,
                )
            }
            when (device) {
                NeedlerDevice.Phone -> screen()
                NeedlerDevice.Tablet -> TabletShell(content = screen)
            }
        }
        assertRendered(file, device)
    }

    /**
     * The app's own chrome at expanded width, round this screen.
     *
     * The real [NeedlerNavigationScaffold], not a stand-in for it, because the measurement the image
     * is taken to hold still - 1280 − 96 rail − 400 sidebar = 784dp of content - is the scaffold's
     * arithmetic and a copy of it could drift. **Settings** is the selected destination: Downloads is
     * reached from Settings and sits in that nested graph, so that is the item the rail highlights
     * while this screen is up.
     *
     * The sidebar is [PlayerSidebarPlaceholder] for the reason `NavigationScreenshotTest` gives: the
     * real `PlayerSidebarRoute` resolves two Hilt view models against a live session, and this test
     * has no graph. The placeholder holds the pack's 400dp, which is the part that matters here.
     */
    @Composable
    private fun TabletShell(content: @Composable () -> Unit) {
        NeedlerNavigationScaffold(
            widthSizeClass = WindowWidthSizeClass.Expanded,
            selected = NeedlerDestination.Settings,
            onSelect = {},
            pullsBadgeCount = PULLS_BADGE,
            sidebar = { PlayerSidebarPlaceholder() },
            content = content,
        )
    }

    private companion object {
        val FULL = DownloadsUiState(
            loading = false,
            downloaded = DownloadedAlbumFixtures.MANY,
        )

        /**
         * The album the removal images act on: In Rainbows, 584 MB, the third largest.
         *
         * Named rather than taken from the end of the list because the notice has to read like the
         * app rather than like a fixture. The sentence it replaces was "Removed Dummy: 11 tracks,
         * 128 MB freed." - a real album whose title is also the word for test data, reported about a
         * row still sitting in the list below it.
         */
        val REMOVED: DownloadedAlbum = DownloadedAlbumFixtures.MANY[2]

        /** The count the design pack draws on the Pulls item, on every screen. */
        const val PULLS_BADGE = 2
    }
}

package app.needler.settings

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import app.needler.screenshot.NeedlerDevice
import app.needler.screenshot.NeedlerScreenshots
import app.needler.screenshot.assertRendered
import app.needler.screenshot.captureNeedlerScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Downloaded albums screen in each state it has to explain.
 *
 * One image per condition that changes what the screen *says*: a library bigger than the Settings
 * section could hold, a device with nothing on it, a removal in flight with every remove control
 * stopped, and the sentence a finished removal leaves behind.
 *
 * `application = Application::class` keeps Hilt out of it, as `SettingsScreenshotTest` does.
 * Everything here renders the stateless [DownloadsScreen] from a literal [DownloadsUiState]; nothing
 * needs a dependency graph, a cache index or a server.
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
    fun `the full list on a tablet`() {
        capture("downloads", NeedlerDevice.Tablet, FULL)
    }

    @Test
    fun `a device with nothing downloaded explains itself rather than rendering blank`() {
        // Reachable: removing the last album leaves the reader standing here. The Settings row that
        // leads here is not drawn in this state, so this is the only way to see it.
        capture("downloads-empty", NeedlerDevice.Phone, DownloadsUiState(loading = false))
    }

    @Test
    fun `a removal in flight stops every remove control`() {
        capture("downloads-removing", NeedlerDevice.Phone, FULL.copy(working = true))
    }

    @Test
    fun `a finished removal says what it freed`() {
        // REQUIREMENTS.md "Storage, and why there is no budget": "a 'remove' that leaves the usage
        // figure unchanged is the one thing that would make this whole screen untrustworthy". The
        // sentence is on this screen, under the total it has just changed.
        capture(
            "downloads-removed",
            NeedlerDevice.Phone,
            FULL.copy(notice = "Removed Dummy: 11 tracks, 128 MB freed."),
        )
    }

    private fun capture(name: String, device: NeedlerDevice, state: DownloadsUiState) {
        val file = captureNeedlerScreen(name, device) {
            DownloadsScreen(
                state = state,
                onRemove = {},
                onBack = {},
                widthSizeClass = when (device) {
                    NeedlerDevice.Phone -> WindowWidthSizeClass.Compact
                    NeedlerDevice.Tablet -> WindowWidthSizeClass.Expanded
                },
            )
        }
        assertRendered(file, device)
    }

    private companion object {
        val FULL = DownloadsUiState(
            loading = false,
            downloaded = DownloadedAlbumFixtures.MANY,
        )
    }
}

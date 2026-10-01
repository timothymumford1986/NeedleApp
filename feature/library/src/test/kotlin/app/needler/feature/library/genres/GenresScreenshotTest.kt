package app.needler.feature.library.genres

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import app.needler.core.domain.model.Genre
import app.needler.feature.library.screenshot.NeedlerDevice
import app.needler.feature.library.screenshot.NeedlerScreenshots
import app.needler.feature.library.screenshot.TabletFrame
import app.needler.feature.library.screenshot.assertRendered
import app.needler.feature.library.screenshot.captureNeedlerScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the genres list.
 *
 * The list is alphabetical here because these are the images a reader compares
 * against the product, and REQUIREMENTS.md orders genres alphabetically — unlike
 * the ViewModel tests, which deliberately feed an unsorted list to prove the screen
 * does not sort.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class GenresScreenshotTest {

    @Test
    fun `a shelf of genres`() {
        capture("genres", NeedlerDevice.Phone, LOADED)
    }

    @Test
    fun `on a tablet`() {
        capture("genres", NeedlerDevice.Tablet, LOADED)
    }

    @Test
    fun `offline, where it reads exactly the same`() {
        capture("genres-offline", NeedlerDevice.Phone, LOADED.copy(offline = true))
    }

    @Test
    fun `nothing tagged`() {
        capture("genres-empty", NeedlerDevice.Phone, GenresUiState(loading = false))
    }

    @Test
    fun `loading`() {
        capture("genres-loading", NeedlerDevice.Phone, GenresUiState(loading = true))
    }

    @Test
    fun `at 200 percent text size`() {
        capture("genres-large-text", NeedlerDevice.Phone, LOADED, fontScale = 2f)
    }

    // ---- plumbing -----------------------------------------------------------

    private fun capture(
        name: String,
        device: NeedlerDevice,
        state: GenresUiState,
        fontScale: Float = 1f,
    ) {
        val file = captureNeedlerScreen(name, device, fontScale) {
            when (device) {
                NeedlerDevice.Phone -> Screen(state, WindowWidthSizeClass.Compact)
                NeedlerDevice.Tablet -> TabletFrame {
                    Screen(state, WindowWidthSizeClass.Expanded)
                }
            }
        }
        assertRendered(file, device)
    }

    @Composable
    private fun Screen(state: GenresUiState, widthSizeClass: WindowWidthSizeClass) {
        GenresScreen(
            state = state,
            widthSizeClass = widthSizeClass,
            onGenreClick = {},
        )
    }

    private companion object {
        val LOADED = GenresUiState(
            loading = false,
            genres = listOf(
                Genre(name = "Alternative rock", albumCount = 31),
                Genre(name = "Dream pop", albumCount = 12),
                Genre(name = "Indie folk", albumCount = 8),
                Genre(name = "Jazz", albumCount = 5),
                Genre(name = "Neo-soul", albumCount = 9),
                Genre(name = "Psychedelic soul", albumCount = 4),
                Genre(name = "Shoegaze"),
                Genre(name = "Soul", albumCount = 17),
            ),
        )
    }
}

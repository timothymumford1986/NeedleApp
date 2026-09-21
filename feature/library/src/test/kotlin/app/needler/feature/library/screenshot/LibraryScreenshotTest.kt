package app.needler.feature.library.screenshot

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.library.LibraryScreen
import app.needler.feature.library.library.LibrarySort
import app.needler.feature.library.library.LibraryTab
import app.needler.feature.library.library.LibraryUiState
import app.needler.feature.library.library.LibraryViewMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Library screen in every state it has.
 *
 * `application = Application::class` keeps Hilt out of it: these render the
 * stateless [LibraryScreen] from a literal [LibraryUiState], so nothing here
 * needs a dependency graph, a repository or a server.
 *
 * The data is the design pack's own — the same ten albums, the same
 * "176 albums · 42 GB · last scan 47m ago" — so each PNG can be put beside
 * `design/png/02-Library.png`, `13-LibraryList.png` and `09-TabletLibrary.png`
 * and compared line for line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class LibraryScreenshotTest {

    @Test
    fun `the album grid on a phone`() {
        capture("library-grid", NeedlerDevice.Phone, LOADED)
    }

    @Test
    fun `the list view with its format badges`() {
        capture("library-list", NeedlerDevice.Phone, LOADED.copy(viewMode = LibraryViewMode.LIST))
    }

    @Test
    fun `the artists tab`() {
        capture("library-artists", NeedlerDevice.Phone, LOADED.copy(tab = LibraryTab.ARTISTS))
    }

    @Test
    fun `the songs tab`() {
        capture(
            "library-songs",
            NeedlerDevice.Phone,
            LOADED.copy(tab = LibraryTab.SONGS, songs = SampleLibrary.submarineTracks),
        )
    }

    @Test
    fun `sorted by title`() {
        capture(
            "library-sorted-by-title",
            NeedlerDevice.Phone,
            LOADED.copy(
                sort = LibrarySort.TITLE,
                albums = SampleLibrary.albums.sortedBy { it.title },
            ),
        )
    }

    @Test
    fun `loading, before the mirror has answered`() {
        capture("library-loading", NeedlerDevice.Phone, LibraryUiState(loading = true))
    }

    @Test
    fun `an empty library asks for a sync`() {
        capture(
            "library-empty",
            NeedlerDevice.Phone,
            LibraryUiState(loading = false, stats = null),
        )
    }

    @Test
    fun `an empty library with no connection cannot sync and says so`() {
        capture(
            "library-empty-offline",
            NeedlerDevice.Phone,
            LibraryUiState(loading = false, offline = true),
        )
    }

    @Test
    fun `offline, with the library intact`() {
        capture("library-offline", NeedlerDevice.Phone, LOADED.copy(offline = true))
    }

    @Test
    fun `at 200 percent text size`() {
        capture("library-large-text", NeedlerDevice.Phone, LOADED, fontScale = 2f)
    }

    // ---- tablet -------------------------------------------------------------

    @Test
    fun `four columns on a tablet, in the pack's content pane`() {
        val file = captureNeedlerScreen("library-grid", NeedlerDevice.Tablet) {
            TabletFrame { Screen(LOADED, WindowWidthSizeClass.Expanded) }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `the list view on a tablet`() {
        val file = captureNeedlerScreen("library-list", NeedlerDevice.Tablet) {
            TabletFrame {
                Screen(LOADED.copy(viewMode = LibraryViewMode.LIST), WindowWidthSizeClass.Expanded)
            }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `offline on a tablet`() {
        val file = captureNeedlerScreen("library-offline", NeedlerDevice.Tablet) {
            TabletFrame { Screen(LOADED.copy(offline = true), WindowWidthSizeClass.Expanded) }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    // ---- plumbing -----------------------------------------------------------

    private fun capture(
        name: String,
        device: NeedlerDevice,
        state: LibraryUiState,
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
    private fun Screen(state: LibraryUiState, widthSizeClass: WindowWidthSizeClass) {
        LibraryScreen(
            state = state,
            widthSizeClass = widthSizeClass,
            onTabSelect = {},
            onSortSelect = {},
            onViewModeToggle = {},
            onSearchClick = {},
            onAlbumClick = {},
            onAlbumPlay = {},
            onArtistClick = {},
            onSongPlay = {},
            onSyncNow = {},
        )
    }

    private companion object {
        /** The pack's own library, at the pack's own instant. */
        val LOADED = LibraryUiState(
            loading = false,
            stats = SampleLibrary.stats,
            albums = SampleLibrary.albums,
            artists = SampleLibrary.artists,
            renderedAt = SampleLibrary.renderedAt,
        )
    }
}

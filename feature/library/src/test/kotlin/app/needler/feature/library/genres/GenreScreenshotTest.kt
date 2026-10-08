package app.needler.feature.library.genres

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import app.needler.feature.library.SampleLibrary
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
 * Renders one genre's songs.
 *
 * The capped render is the one worth looking at: `observeTracksByGenre` is bounded
 * rather than paged, and the line admitting it is the only thing between the user
 * and a list that looks like the whole genre and is not.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class GenreScreenshotTest {

    @Test
    fun `a genre of songs`() {
        capture("genre", NeedlerDevice.Phone, LOADED)
    }

    @Test
    fun `on a tablet`() {
        capture("genre", NeedlerDevice.Tablet, LOADED)
    }

    @Test
    fun `capped, and saying so`() {
        capture(
            "genre-capped",
            NeedlerDevice.Phone,
            LOADED.copy(trackLimit = SampleLibrary.submarineTracks.size),
        )
    }

    @Test
    fun `playing one of its songs`() {
        capture(
            "genre-playing",
            NeedlerDevice.Phone,
            LOADED.copy(nowPlayingTrackKey = SampleLibrary.submarineTracks[1].key),
        )
    }

    @Test
    fun `offline`() {
        capture("genre-offline", NeedlerDevice.Phone, LOADED.copy(offline = true))
    }

    @Test
    fun `nothing under it`() {
        capture(
            "genre-empty",
            NeedlerDevice.Phone,
            GenreUiState(loading = false, genre = "Shoegaze"),
        )
    }

    @Test
    fun `at 200 percent text size`() {
        capture("genre-large-text", NeedlerDevice.Phone, LOADED, fontScale = 2f)
    }

    /**
     * One genre whose name does not fit the header.
     *
     * The shelf's version of this is `GenresScreenshotTest`'s `genres-long-names`; this is the other
     * half, because the name arrives here as a *title* rather than as a row - the screen takes it
     * from the route and sets it in `displayCompact` across the top. A row can wrap or ellipsise
     * quietly; a screen title that wraps to four lines moves Play, Shuffle and every track under it.
     *
     * The value is the composite string a server sent unsplit, which is the longest name this screen
     * can really be handed. `GenreCodec` stops it being written now, and a row written before that
     * fix still opens.
     */
    @Test
    fun `a name too long for the header`() {
        capture(
            "genre-long-name",
            NeedlerDevice.Phone,
            LOADED.copy(genre = "Acoustic Rock;Alternative Rock;Folk Rock;Indie Rock;Pop Rock"),
        )
    }

    // ---- plumbing -----------------------------------------------------------

    private fun capture(
        name: String,
        device: NeedlerDevice,
        state: GenreUiState,
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
    private fun Screen(state: GenreUiState, widthSizeClass: WindowWidthSizeClass) {
        GenreScreen(
            state = state,
            widthSizeClass = widthSizeClass,
            onBack = {},
            onPlayAll = {},
            onShuffleAll = {},
            onPlayTrack = {},
            onShowMore = {},
            onSyncNow = {},
        )
    }

    private companion object {
        val LOADED = GenreUiState(
            loading = false,
            genre = "Dream pop",
            tracks = SampleLibrary.submarineTracks,
            trackLimit = GenreViewModel.TRACK_LIMIT,
        )
    }
}

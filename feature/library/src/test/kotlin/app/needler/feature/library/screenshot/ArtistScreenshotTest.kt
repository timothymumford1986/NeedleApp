package app.needler.feature.library.screenshot

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.PullProgress
import app.needler.core.domain.model.PullState
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.artist.ArtistScreen
import app.needler.feature.library.artist.ArtistUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the artist screen, where the mirror and the catalogue meet.
 *
 * The design pack draws no artist screen, so there is nothing to compare these
 * against — which makes them more useful, not less: they are the only way to
 * see that a screen assembled from the pack's parts still reads as part of the
 * pack.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class ArtistScreenshotTest {

    @Test
    fun `owned albums first, then the rest of the discography`() {
        capture("artist", NeedlerDevice.Phone, LOADED)
    }

    @Test
    fun `on a tablet`() {
        val file = captureNeedlerScreen("artist", NeedlerDevice.Tablet) {
            TabletFrame { Screen(LOADED, WindowWidthSizeClass.Expanded) }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `offline, with the catalogue half missing and the owned half intact`() {
        capture(
            "artist-offline",
            NeedlerDevice.Phone,
            LOADED.copy(
                catalogueAlbums = emptyList(),
                discographyUnavailable = true,
                offline = true,
            ),
        )
    }

    @Test
    fun `loading`() {
        capture("artist-loading", NeedlerDevice.Phone, ArtistUiState(loading = true))
    }

    @Test
    fun `at 200 percent text size`() {
        capture("artist-large-text", NeedlerDevice.Phone, LOADED, fontScale = 2f)
    }

    // ---- plumbing -----------------------------------------------------------

    private fun capture(
        name: String,
        device: NeedlerDevice,
        state: ArtistUiState,
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
    private fun Screen(state: ArtistUiState, widthSizeClass: WindowWidthSizeClass) {
        ArtistScreen(
            state = state,
            widthSizeClass = widthSizeClass,
            onBack = {},
            onAlbumClick = {},
            onPull = {},
        )
    }

    private companion object {

        private fun catalogue(
            slug: String,
            title: String,
            year: Int,
            state: AlbumState = AlbumState.NotOwned,
        ): Album = SampleLibrary.album(
            slug = slug,
            title = title,
            artistName = "The Marías",
            artistSlug = "marias",
            year = year,
            state = state,
            format = null,
        )

        val LOADED = ArtistUiState(
            loading = false,
            artist = SampleLibrary.artists.first(),
            ownedAlbums = listOf(
                SampleLibrary.submarine,
                SampleLibrary.album(
                    slug = "cinema",
                    title = "Cinema",
                    artistName = "The Marías",
                    artistSlug = "marias",
                    year = 2021,
                    state = AlbumState.Owned,
                ),
            ),
            catalogueAlbums = listOf(
                catalogue("superclean1", "Superclean Vol. I", 2017),
                catalogue("superclean2", "Superclean Vol. II", 2018),
                // One already on its way, so the row wears the badge instead of
                // a Pull. REQUIREMENTS.md: the two are mutually exclusive.
                catalogue(
                    slug = "submarine-deluxe",
                    title = "Submarine (Deluxe)",
                    year = 2025,
                    state = AlbumState.Acquiring(
                        progress = PullProgress(percent = 41),
                        stage = PullState.DOWNLOADING,
                    ),
                ),
                catalogue("live", "Live at the Hollywood Bowl", 2024),
            ),
        )
    }
}

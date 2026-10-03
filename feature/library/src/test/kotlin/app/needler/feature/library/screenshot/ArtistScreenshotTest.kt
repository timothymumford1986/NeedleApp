package app.needler.feature.library.screenshot

import app.needler.core.domain.model.NeedlerError
import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.PullProgress
import app.needler.core.domain.model.PullState
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.album.AlbumNotice
import app.needler.feature.library.artist.ArtistScreen
import app.needler.feature.library.artist.ArtistUiState
import app.needler.feature.library.common.RequestSheetState
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
 *
 * ## The two cases that were dead ends
 *
 * The last four renders are the ones worth looking at, because both used to be the same
 * screen: "That artist is not here", with the artist unnamed and nothing on it but Back.
 * An artist reached from catalogue search has no mirror row, and an owned artist whose
 * DroppedNeedle id is name-derived can never have a catalogue half. Two causes, one
 * correct presentation: the name is there, the reason is stated honestly, and a pull or a
 * retry is still on screen.
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
                discographyError = NeedlerError.Offline(),
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

    // ---- the crate ----------------------------------------------------------

    /**
     * The action row's fourth control and the one on every owned album row.
     *
     * Two placements in one image, and the reason they differ: the row's tap opens the album
     * and is harmless, so its control sits in the trailing slot beside Play; a track row's tap
     * plays and destroys the crate, so there the long press is claimed instead. Rendering it is
     * how "three controls still fit beside a two-line title" gets checked.
     */
    @Test
    fun `the artist actions and the album rows carry the crate`() {
        capture("artist-crate-control", NeedlerDevice.Phone, LOADED)
    }

    @Test
    fun `the crate controls at 200 percent text size`() {
        capture("artist-crate-control-large-text", NeedlerDevice.Phone, LOADED, fontScale = 2f)
    }

    /** The sentence after an add, with the crate's own count and total duration under it. */
    @Test
    fun `the added-to-the-crate line, with the count and the duration`() {
        capture(
            "artist-crate-added",
            NeedlerDevice.Phone,
            LOADED.copy(
                notice = AlbumNotice.AddedToCrate(trackCount = 8),
                crateTrackCount = 10,
                crateDurationMs = 2_120_000L,
            ),
        )
    }

    @Test
    fun `a starred artist`() {
        capture(
            "artist-starred",
            NeedlerDevice.Phone,
            LOADED.copy(artist = SampleLibrary.artists.first().copy(isFavourite = true)),
        )
    }

    // ---- the discography could not be listed --------------------------------

    /**
     * An owned artist whose id DroppedNeedle derived from their name.
     *
     * The screen names them, lists what is owned, and says plainly that there is no full
     * discography to look up — rather than logging a 400 and showing a silently short list.
     * No "Try again": retrying a name-derived id is retrying a `400`.
     */
    @Test
    fun `an artist whose id was never a MusicBrainz one`() {
        capture("artist-not-in-catalogue", NeedlerDevice.Phone, NAME_DERIVED)
    }

    /**
     * An artist known only from catalogue search: no mirror row, nothing owned.
     *
     * This is the render that proves the device fault is gone. The name is in the header, the
     * subtitle is search's own, and **Pull all** is the primary action.
     */
    @Test
    fun `an artist nobody owns, reached from search`() {
        capture("artist-catalogue-only", NeedlerDevice.Phone, CATALOGUE_ONLY)
    }

    @Test
    fun `an artist nobody owns, at 200 percent text size`() {
        capture("artist-catalogue-only-large-text", NeedlerDevice.Phone, CATALOGUE_ONLY, fontScale = 2f)
    }

    /** The same artist with the discography unlistable, so the only action left is Try again. */
    @Test
    fun `an artist nobody owns whose discography could not be listed`() {
        capture(
            "artist-catalogue-only-offline",
            NeedlerDevice.Phone,
            CATALOGUE_ONLY.copy(
                catalogueAlbums = emptyList(),
                discographyError = NeedlerError.Offline(),
                offline = true,
            ),
        )
    }

    /** The one empty screen left: no row, no name, no records. */
    @Test
    fun `nothing at all`() {
        capture("artist-not-found", NeedlerDevice.Phone, ArtistUiState(loading = false))
    }

    // ---- the pull sheet -----------------------------------------------------

    @Test
    fun `pulling the whole artist, with the monitor toggle`() {
        capture(
            "artist-request-sheet",
            NeedlerDevice.Phone,
            CATALOGUE_ONLY.copy(
                requestSheet = RequestSheetState.forArtist(
                    artistName = SampleLibrary.CATALOGUE_ONLY_ARTIST_NAME,
                    albums = CATALOGUE_ONLY.pullableAlbums,
                ),
            ),
        )
    }

    // ---- nothing the server sent is guaranteed to be there ------------------

    /** An artist and a record the catalogue named with nothing. */
    @Test
    fun `an artist the catalogue named with nothing`() {
        capture(
            "artist-untitled",
            NeedlerDevice.Phone,
            ArtistUiState(
                loading = false,
                knownName = "",
                ownedAlbums = listOf(SampleLibrary.untitledOwnedAlbum),
                catalogueAlbums = listOf(SampleLibrary.untitledAlbum),
            ),
        )
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
            onPullArtist = {},
            onRetryDiscography = {},
            onFindInCatalogue = {},
            onOpenArtist = {},
            onPlay = {},
            onShuffle = {},
            onPlayAlbum = {},
            onAddToCrate = {},
            onAddAlbumToCrate = { _, _ -> },
            onToggleFavourite = {},
            onMonitorArtistChange = {},
            onConfirmRequest = {},
            onDismissRequestSheet = {},
        )
    }

    private companion object {

        private fun catalogue(
            slug: String,
            title: String,
            year: Int,
            state: AlbumState = AlbumState.NotOwned,
            artistName: String = "The Marías",
            artistSlug: String = "marias",
        ): Album = SampleLibrary.album(
            slug = slug,
            title = title,
            artistName = artistName,
            artistSlug = artistSlug,
            year = year,
            state = state,
            format = null,
        )

        val LOADED = ArtistUiState(
            loading = false,
            artist = SampleLibrary.artists.first(),
            mbid = SampleLibrary.artists.first().mbid,
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
            // Something to press Play on.
            playableTracks = SampleLibrary.submarineTracks,
        )

        /** An owned artist whose id can never reach the catalogue. */
        val NAME_DERIVED = ArtistUiState(
            loading = false,
            artist = SampleLibrary.nameDerivedArtist,
            mbid = SampleLibrary.nameDerivedArtist.mbid,
            ownedAlbums = listOf(SampleLibrary.submarine),
            artistNotInCatalogue = true,
            playableTracks = SampleLibrary.submarineTracks,
        )

        /** An artist reached from catalogue search: no mirror row, nothing owned. */
        val CATALOGUE_ONLY = ArtistUiState(
            loading = false,
            artist = null,
            // No mirror row, so no `Artist` and no artwork — but the route still knew
            // which artist this is, which is what tints the letter placeholder the same
            // colour search drew.
            mbid = SampleLibrary.artistMbid("cake"),
            knownName = SampleLibrary.CATALOGUE_ONLY_ARTIST_NAME,
            knownSubtitle = ArtistUiState.NOT_IN_LIBRARY_YET,
            catalogueAlbums = listOf(
                catalogue(
                    slug = "cake-brood",
                    title = "Brood",
                    year = 1994,
                    artistName = SampleLibrary.CATALOGUE_ONLY_ARTIST_NAME,
                    artistSlug = "cake",
                ),
                catalogue(
                    slug = "cake-good-luck",
                    title = "Good Luck",
                    year = 1996,
                    artistName = SampleLibrary.CATALOGUE_ONLY_ARTIST_NAME,
                    artistSlug = "cake",
                ),
                catalogue(
                    slug = "cake-shine",
                    title = "Shine",
                    year = 2000,
                    artistName = SampleLibrary.CATALOGUE_ONLY_ARTIST_NAME,
                    artistSlug = "cake",
                ),
            ),
        )
    }
}

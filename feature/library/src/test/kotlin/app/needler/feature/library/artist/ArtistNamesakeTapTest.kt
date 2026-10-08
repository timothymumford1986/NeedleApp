package app.needler.feature.library.artist

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.screenshot.NeedlerScreenshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a tap on a catalogue namesake hands over, and what the screen it opens says on arrival.
 *
 * ## The defect
 *
 * The namesake rows exist because an artist whose id DroppedNeedle derived from their name can
 * never fetch a discography; they offer MusicBrainz's artists of that name instead, and each row
 * opens this same screen for a real MBID. That MBID has no `artist` row in the mirror and never
 * will — `refreshArtistDiscography` writes album rows and never an artist row — so the arriving
 * screen read "Unknown artist" in the header, and for the frames before the discography landed it
 * read "That artist is not here" over it. Both for an artist whose name the row the user had just
 * tapped was printing an inch above.
 *
 * ## Two halves, asserted as two halves
 *
 * The tap has to **carry** the name, and the arriving screen has to **say** it on the first frame.
 * They are separate failures: the route carried nothing at all, and `notFound` would have claimed
 * the artist was absent even if it had. So the first test clicks a real row and reads what the
 * callback received, and the rest render the state that callback produces — no owned album, no
 * catalogue album, nothing settled, which is exactly the first frame after a navigate — and assert
 * the header text and the absence of [ARTIST_NOT_HERE].
 *
 * Text and not a screenshot, deliberately: the defect is a string, and
 * `artist-catalogue-only-phone.png` is a correctly-sized PNG whether the header says the name or
 * "Unknown artist".
 *
 * `application = Application::class` keeps Hilt out of it, as in [ArtistActionOrderTest]: the
 * screen renders from a literal [ArtistUiState] with no view model and no player.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class ArtistNamesakeTapTest {

    @get:Rule
    val compose = createComposeRule()

    /** Every namesake tap, as the host would receive it: MBID, name, and the comment or null. */
    private val taps: MutableList<Triple<ArtistMbid, String, String?>> = mutableListOf()

    @Test
    fun `a namesake tap carries the MBID, the name and the catalogue's comment`() {
        show(offering(DIDO_SINGER, DIDO_DRUMMER))

        compose.onNodeWithContentDescription(rowLabel(DIDO_SINGER)).performClick()

        assertEquals(
            listOf(Triple(DIDO_SINGER.mbid, DIDO_SINGER.name, "English singer-songwriter")),
            taps,
        )
    }

    /**
     * The comment is the only part of the row that came from the catalogue.
     *
     * [NOT_IN_LIBRARY] is this screen's own wording for a row it knows nothing about, and the album
     * count is the mirror's. Carrying either forward would hand the next screen a fact about the
     * library dressed as a fact about the artist, so a namesake with no comment carries no subtitle
     * at all and `ArtistUiState.subtitle` answers for itself.
     */
    @Test
    fun `a namesake with no comment carries no subtitle, rather than the row's own wording`() {
        show(offering(DIDO_SINGER, DIDO_DRUMMER))

        compose.onNodeWithContentDescription(rowLabel(DIDO_DRUMMER)).performClick()

        assertEquals(1, taps.size)
        assertEquals(DIDO_DRUMMER.name, taps.single().second)
        assertNull(taps.single().third)
    }

    /**
     * The arriving screen, on the frame before any discography has been fetched.
     *
     * This is the state the route's two optional arguments produce and nothing else has filled in
     * yet: no mirror row, no owned album, no catalogue album, no settled lookup. It is the exact
     * state that used to draw "That artist is not here".
     */
    @Test
    fun `the header names the artist immediately, with nothing else loaded yet`() {
        show(arrivedFrom(DIDO_SINGER))

        compose.onNodeWithText(DIDO_SINGER.name).assertExists()
        compose.onNodeWithText("English singer-songwriter").assertExists()
        compose.onNodeWithText(ARTIST_NOT_HERE).assertDoesNotExist()
    }

    /** The same, with no subtitle to show: the name alone is still enough to suppress the claim. */
    @Test
    fun `a named artist with no subtitle is still not reported as absent`() {
        show(arrivedFrom(DIDO_DRUMMER))

        compose.onNodeWithText(DIDO_DRUMMER.name).assertExists()
        compose.onNodeWithText(ArtistUiState.NOT_IN_LIBRARY_YET).assertExists()
        compose.onNodeWithText(ARTIST_NOT_HERE).assertDoesNotExist()
    }

    /**
     * An artist opened the ordinary way, from the library: unaffected by any of this.
     *
     * The route carries no name on that path and does not need to — the mirror has the row — and
     * this is what would break if the two optional arguments were ever made to matter. The header
     * reads the mirror's name, not "Unknown artist".
     */
    @Test
    fun `an artist with a mirror row and no route hints names itself`() {
        show(
            ArtistUiState(
                loading = false,
                artist = OWNED,
                mbid = OWNED.mbid,
                ownedAlbums = listOf(SampleLibrary.submarine),
                playableTracks = SampleLibrary.submarineTracks,
            ),
        )

        compose.onNodeWithText(OWNED.name).assertExists()
        compose.onNodeWithText(ARTIST_NOT_HERE).assertDoesNotExist()
    }

    /** The one empty screen left: no mirror row, no name from the route, and no record of either. */
    @Test
    fun `an artist with no row, no name and nothing to show still says so`() {
        show(ArtistUiState(loading = false, mbid = DIDO_SINGER.mbid, discographySettled = true))

        compose.onNodeWithText(ARTIST_NOT_HERE).assertExists()
    }

    // ---- fixtures ----------------------------------------------------------

    /** The name-derived artist's screen, with the catalogue's namesakes listed on it. */
    private fun offering(vararg candidates: Artist): ArtistUiState = ArtistUiState(
        loading = false,
        artist = OWNED,
        mbid = OWNED.mbid,
        ownedAlbums = listOf(SampleLibrary.submarine),
        playableTracks = SampleLibrary.submarineTracks,
        artistNotInCatalogue = true,
        discographySettled = true,
        catalogueNamesakes = candidates.toList(),
        namesakeSearchDone = true,
    )

    /**
     * What the tapped row's arguments make, and nothing more.
     *
     * Built from the same two fields the callback hands over, so this fixture cannot claim the
     * screen knows something the route did not carry.
     */
    private fun arrivedFrom(candidate: Artist): ArtistUiState = ArtistUiState(
        loading = false,
        mbid = candidate.mbid,
        knownName = candidate.name,
        knownSubtitle = candidate.disambiguation,
    )

    /** A namesake row's spoken label, which is how a click finds it. */
    private fun rowLabel(candidate: Artist): String =
        candidate.name + ", " + (candidate.disambiguation ?: NOT_IN_LIBRARY) +
            ", open in the catalogue"

    private fun show(state: ArtistUiState) {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                Box(
                    modifier = Modifier
                        .requiredWidth(PHONE_WIDTH)
                        .requiredHeight(VIEWPORT_HEIGHT),
                ) {
                    ArtistScreen(
                        state = state,
                        widthSizeClass = WindowWidthSizeClass.Compact,
                        onBack = {},
                        onAlbumClick = {},
                        onPull = {},
                        onPullArtist = {},
                        onRetryDiscography = {},
                        onShowMoreDiscography = {},
                        onFindInCatalogue = {},
                        // Left at the narrow callback so a tap that went to the wrong one shows up
                        // as an empty [taps] rather than as a pass.
                        onOpenArtist = {},
                        onOpenCatalogueArtist = { mbid, name, subtitle ->
                            taps += Triple(mbid, name, subtitle)
                        },
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
            }
        }
    }

    private companion object {
        /** `design/html/04-AlbumOwned.html`'s width, the same artboard the screenshots render at. */
        val PHONE_WIDTH = 390.dp

        /**
         * Taller than any phone, on purpose.
         *
         * The rows under test are in a `LazyColumn`, and at 844dp the namesake section sits below
         * the fold on an artist that also owns records: the row would not be composed, the click
         * would fail, and the failure would say "no node" about a row that is perfectly fine. The
         * geometry of this screen is `ArtistActionOrderTest`'s subject and the screenshots'; what
         * is being asserted here is which callback a tap reaches.
         */
        val VIEWPORT_HEIGHT = 2_400.dp

        /** The artist whose screen the namesakes are offered on: owned, with a name-derived id. */
        val OWNED: Artist = SampleLibrary.marias

        /** The device's own case: several Didos, one of which carries a comment and one not. */
        val DIDO_SINGER = Artist(
            mbid = ArtistMbid("8b1d0b95-6c1b-4ec5-a4b4-0e27b47b5f6b"),
            name = "Dido",
            disambiguation = "English singer-songwriter",
        )

        val DIDO_DRUMMER = Artist(
            mbid = ArtistMbid("3f5c1d5e-5e0a-4a2f-9b1c-7d6e8a9b0c1d"),
            name = "Dido",
        )
    }
}

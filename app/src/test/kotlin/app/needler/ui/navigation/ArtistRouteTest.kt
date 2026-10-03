package app.needler.ui.navigation

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import app.needler.feature.library.artist.ArtistViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The artist destination's two optional arguments, and the four ways in that must keep working.
 *
 * ## The defect
 *
 * `ROUTE_ARTIST` was `artist/{artistId}` and nothing else. `ArtistViewModel` had read
 * `artistName` and `artistSubtitle` from its `SavedStateHandle` since the screen was written, and
 * `ArtistRoute`'s KDoc said this module was supposed to supply them; nobody ever did. The screen's
 * own "Search the catalogue" rows then made the gap reachable: they offer MusicBrainz's artists of
 * a given name, and tapping one opens an MBID the mirror has no `artist` row for, so the header
 * read "Unknown artist" and the frames before the discography landed read "That artist is not
 * here" — for an artist whose name the row the user had just tapped was printing.
 *
 * ## Why these assertions and not a navigation screenshot
 *
 * The bug is a string, so the tests are about strings: what the route builder produces, whether it
 * survives a `Uri` parse, and whether the graph still resolves a bare `artist/{artistId}`. The two
 * halves worth separating are *encoding* (an artist name holds `/`, `?`, `&` and `#`, every one of
 * which would otherwise end the route or start a second argument) and *optionality* (a query
 * argument the navigation library thinks is required stops the bare route matching, which would
 * break the library, search, an album's artist link and a notification tap all at once).
 *
 * [matchedArguments] drives the real graph rather than a copy of it: the same `ROUTE_ARTIST` and
 * the same [artistArguments] the host registers, so an edit that makes either of them required
 * fails here rather than on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ArtistRouteTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * What the artist destination actually receives for a given route string.
     *
     * A real [NavController] with a real graph, registering the route and the arguments the host
     * registers. `ComposeNavigator` because that is what `composable` in the host's graph uses; no
     * composition is entered, because nothing here asks what was drawn — only what was parsed.
     */
    private fun matchedArguments(route: String): Map<String, String?> {
        val controller = NavController(context)
        controller.navigatorProvider.addNavigator(ComposeNavigator())
        controller.graph = controller.createGraph(startDestination = ROUTE_START) {
            composable(ROUTE_START) {}
            composable(route = ROUTE_ARTIST, arguments = artistArguments()) {}
        }
        controller.navigate(route)
        val entry = requireNotNull(controller.currentBackStackEntry) {
            "nothing was pushed for " + route
        }
        assertEquals(ROUTE_ARTIST, entry.destination.route)
        val arguments = requireNotNull(entry.arguments) { "no arguments for " + route }
        return listOf(
            ArtistViewModel.ARTIST_ID_ARG,
            ArtistViewModel.ARTIST_NAME_ARG,
            ArtistViewModel.ARTIST_SUBTITLE_ARG,
        ).associateWith { arguments.getString(it) }
    }

    @Test
    fun `the route's argument names are the ones the view model reads`() {
        assertTrue(ROUTE_ARTIST.startsWith("artist/{" + ArtistViewModel.ARTIST_ID_ARG + "}"))
        assertEquals(ArtistViewModel.ARTIST_NAME_ARG, ARG_ARTIST_NAME)
        assertEquals(ArtistViewModel.ARTIST_SUBTITLE_ARG, ARG_ARTIST_SUBTITLE)
    }

    @Test
    fun `the optional pair are query arguments, so the bare path still stands alone`() {
        assertEquals(
            "artist/{" + ArtistViewModel.ARTIST_ID_ARG + "}" +
                "?" + ArtistViewModel.ARTIST_NAME_ARG +
                "={" + ArtistViewModel.ARTIST_NAME_ARG + "}" +
                "&" + ArtistViewModel.ARTIST_SUBTITLE_ARG +
                "={" + ArtistViewModel.ARTIST_SUBTITLE_ARG + "}",
            ROUTE_ARTIST,
        )
    }

    // ---- the namesake tap --------------------------------------------------

    @Test
    fun `a namesake tap carries the name and the catalogue's comment`() {
        val route: String = catalogueArtistRoute(DIDO, "Dido", "English singer-songwriter")

        assertEquals(
            mapOf(
                ArtistViewModel.ARTIST_ID_ARG to DIDO,
                ArtistViewModel.ARTIST_NAME_ARG to "Dido",
                ArtistViewModel.ARTIST_SUBTITLE_ARG to "English singer-songwriter",
            ),
            matchedArguments(route),
        )
    }

    /**
     * The reason [Uri.encode] is in the builder at all.
     *
     * "AC/DC" ends the path segment and the destination stops matching outright; "&" starts a
     * second query argument, so the name arrives truncated and a subtitle appears that nobody
     * sent; "?" starts the query early and "#" starts a fragment. One name with all four in it,
     * because they fail in four different ways and a test per character would still miss the
     * combination.
     */
    @Test
    fun `a name with a slash, an ampersand, a question mark and a hash survives the route`() {
        val awkward = "AC/DC & Friends? #1"
        val route: String = catalogueArtistRoute(DIDO, awkward, null)

        assertEquals(listOf("artist", DIDO), Uri.parse(route).pathSegments)
        assertEquals(
            mapOf(
                ArtistViewModel.ARTIST_ID_ARG to DIDO,
                ArtistViewModel.ARTIST_NAME_ARG to awkward,
                ArtistViewModel.ARTIST_SUBTITLE_ARG to null,
            ),
            matchedArguments(route),
        )
    }

    /**
     * No subtitle means no argument, rather than an empty one.
     *
     * The catalogue sends a disambiguation comment or it sends nothing, and `ArtistUiState` has its
     * own honest fallback for nothing. A minted subtitle would be a claim no source made, and
     * `?artistSubtitle=` would reach the screen as an empty string, which is the blank-header
     * defect in a second place.
     */
    @Test
    fun `a namesake with no comment carries no subtitle argument at all`() {
        val route: String = catalogueArtistRoute(DIDO, "Dido", null)

        assertEquals(setOf(ArtistViewModel.ARTIST_NAME_ARG), Uri.parse(route).queryParameterNames)
        assertNull(matchedArguments(route)[ArtistViewModel.ARTIST_SUBTITLE_ARG])
    }

    @Test
    fun `a blank name is no name, and produces the ordinary route`() {
        assertEquals(artistRoute(DIDO), catalogueArtistRoute(DIDO, "   ", null))
    }

    // ---- everything that has only an MBID ----------------------------------

    /**
     * The notification path, which is the one with nothing to pass.
     *
     * `NotificationDestination.Artist` carries an MBID and no name — the notification was posted
     * about a delivered album, not about a screen — and it reaches the graph through [artistRoute]
     * like the library, search, an album's artist link and the player sidebar do. If the two
     * optional arguments were ever declared required, or moved into the path, every one of those
     * would navigate to a destination the graph no longer holds.
     */
    @Test
    fun `an artist opened with only an MBID still resolves, with both hints absent`() {
        assertEquals("artist/" + DIDO, artistRoute(DIDO))
        assertNull(Uri.parse(artistRoute(DIDO)).query)
        assertEquals(
            mapOf(
                ArtistViewModel.ARTIST_ID_ARG to DIDO,
                ArtistViewModel.ARTIST_NAME_ARG to null,
                ArtistViewModel.ARTIST_SUBTITLE_ARG to null,
            ),
            matchedArguments(artistRoute(DIDO)),
        )
    }

    private companion object {
        /** A real v4 MusicBrainz MBID, the shape a namesake row hands over. */
        const val DIDO: String = "8b1d0b95-6c1b-4ec5-a4b4-0e27b47b5f6b"

        /** Somewhere for the test graph to start, so the artist route is navigated *to*. */
        const val ROUTE_START: String = "start"
    }
}

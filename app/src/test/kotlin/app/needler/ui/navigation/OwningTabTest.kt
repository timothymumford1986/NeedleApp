package app.needler.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which tab the bottom bar lights, and therefore whether tapping a tab does
 * anything.
 *
 * This is the second time one bug has been reported here, which is why it has a
 * test. Both sightings were the same mistake - answering "which tab does this
 * screen belong to" from the route instead of from the back stack:
 *
 *  1. Settings did nothing when tapped from the Crossfade screen.
 *  2. Search did nothing when tapped from an artist page: *"You're in 'dido' in
 *     the library. its showing dido, 4 albums, cool. You hit 'search' down the
 *     bottom -> nothing happens."*
 *
 * The second one is worth spelling out, because "nothing happens" was literal
 * and the navigation did in fact run. An artist reached by searching sits on the
 * stack as `library -> search -> artist/dido`, and the old `when` over the route
 * lit **Library**. Tapping Search therefore looked like a move to a different
 * tab, took `selectTab`'s `popUpTo(start) { saveState = true } ...
 * restoreState = true` branch, and `NavController` saved `search ->
 * artist/dido` as Search's stack and restored it in the same call. The listener
 * landed back on the artist page they were already looking at, and would have
 * done every time they tried.
 *
 * REQUIREMENTS.md "Tablet layout" keeps the bar and the rail visible on album
 * and artist detail - opening one is "a change of content, not a change of
 * context" - so some tab always has to be lit, and the only source of truth for
 * which is the stack.
 *
 * [owningTab] takes plain routes rather than a `NavController` precisely so this
 * file can exist: the alternative was an instrumented navigation host, which is
 * why neither sighting had a test before.
 */
class OwningTabTest {

    @Test
    fun `a tab lights itself`() {
        assertEquals(NeedlerDestination.Library, owningTab(listOf("library")))
        assertEquals(NeedlerDestination.Search, owningTab(listOf("library", "search")))
        assertEquals(NeedlerDestination.Pulls, owningTab(listOf("library", "pulls")))
        assertEquals(NeedlerDestination.Settings, owningTab(listOf("library", "settings")))
    }

    @Test
    fun `an artist opened from Search belongs to Search`() {
        assertEquals(
            NeedlerDestination.Search,
            owningTab(listOf("library", "search", "artist/{artistId}")),
        )
    }

    @Test
    fun `an artist opened from Library belongs to Library`() {
        assertEquals(
            NeedlerDestination.Library,
            owningTab(listOf("library", "artist/{artistId}")),
        )
    }

    @Test
    fun `an album opened from Pulls belongs to Pulls`() {
        // PullsRoute's onOpenAlbum. Under the old `when` this lit Library, so
        // tapping Pulls from a pull's album restored the album and went nowhere.
        assertEquals(
            NeedlerDestination.Pulls,
            owningTab(listOf("library", "pulls", "album/{albumId}")),
        )
    }

    @Test
    fun `a chain of sub-screens still belongs to the tab it started in`() {
        // Search -> artist -> album -> artist is reachable: ArtistRoute opens an
        // album and AlbumRoute opens an artist. Every one of them is Search's.
        assertEquals(
            NeedlerDestination.Search,
            owningTab(
                listOf(
                    "library",
                    "search",
                    "artist/{artistId}",
                    "album/{albumId}",
                    "artist/{artistId}",
                ),
            ),
        )
    }

    @Test
    fun `the Settings sub-screens belong to Settings`() {
        // These were the one group the old `when` got right, because they really
        // are only reachable from Settings. They must stay right.
        listOf("equaliser", "crossfade", "licences", "diagnostics").forEach { route ->
            assertEquals(
                route,
                NeedlerDestination.Settings,
                owningTab(listOf("library", "settings", route)),
            )
        }
    }

    @Test
    fun `playlists and genres belong to wherever they were opened from`() {
        assertEquals(
            NeedlerDestination.Library,
            owningTab(listOf("library", "playlists", "playlist/{playlistId}")),
        )
        assertEquals(
            NeedlerDestination.Search,
            owningTab(listOf("library", "search", "genres", "genre/{genre}")),
        )
    }

    @Test
    fun `an empty stack falls back to the start destination`() {
        // The state between a controller being created and its start destination
        // being added, and the frame after a graph is replaced. The bar is drawn
        // either way, so it needs an answer rather than a crash.
        assertEquals(NeedlerDestination.Start, owningTab(emptyList()))
        assertEquals(NeedlerDestination.Start, owningTab(listOf(null)))
    }

    @Test
    fun `a sub-screen with no tab under it falls back to the start destination`() {
        // Should not be reachable - every push into the inner graph happens from
        // a tab - but a notification tap navigates straight to an album, and if
        // that ever lands on an empty stack the bar must still light something.
        assertEquals(
            NeedlerDestination.Start,
            owningTab(listOf("album/{albumId}")),
        )
    }
}

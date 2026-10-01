package app.needler.core.domain.usecase

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.CatalogueLaneState
import app.needler.core.domain.model.CatalogueSearchPage
import app.needler.core.domain.model.CatalogueSearchResults
import app.needler.core.domain.model.LocalSearchResults
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.SearchBucket
import app.needler.core.domain.model.ServiceStatus
import app.needler.core.domain.model.UnifiedSearchResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge, the ranking and the paging fold, over the search that shipped wrong.
 *
 * Searching "wonder" on the device returned four artists - "Jr. Wonder", "Wonder", "wonder",
 * "wonder" - all four subtitled "Not in your library yet", and did **not** return "Oh Wonder", three
 * of whose albums were on the server. Three separate defects in one list: the mirror's artists were
 * missing, byte-identical names survived, and MusicBrainz's own relevance decided the order. Every
 * test below is one of those three, stated as the behaviour that replaces it.
 *
 * These are pure functions over lists, so they run on the JVM with no dispatcher, no database and no
 * server.
 */
public class UnifiedSearchMergeTest {

    // ------------------------------------------------------------------- artists

    @Test
    public fun `the artist you own leads, whatever MusicBrainz ranked first`() {
        val merged: List<Artist> = UnifiedSearchUseCase.mergeArtists(
            query = "wonder",
            local = listOf(artist("oh-wonder", "Oh Wonder", owned = 3)),
            catalogue = listOf(
                artist("jr-wonder", "Jr. Wonder"),
                artist("wonder-1", "Wonder"),
            ),
        )

        assertEquals(
            listOf("Oh Wonder", "Wonder", "Jr. Wonder"),
            merged.map { it.name },
        )
    }

    @Test
    public fun `byte-identical catalogue names collapse to one row`() {
        val merged: List<Artist> = UnifiedSearchUseCase.mergeArtists(
            query = "wonder",
            local = emptyList(),
            catalogue = listOf(
                artist("jr-wonder", "Jr. Wonder"),
                artist("wonder-1", "Wonder"),
                // Two further MusicBrainz artists, distinct MBIDs, indistinguishable rows.
                artist("wonder-2", "wonder"),
                artist("wonder-3", "wonder"),
            ),
        )

        assertEquals(listOf("Wonder", "Jr. Wonder"), merged.map { it.name })
    }

    @Test
    public fun `a catalogue copy of an artist you own is dropped on the MBID`() {
        val merged: List<Artist> = UnifiedSearchUseCase.mergeArtists(
            query = "wonder",
            local = listOf(artist("oh-wonder", "Oh Wonder", owned = 3)),
            catalogue = listOf(artist("oh-wonder", "Oh Wonder")),
        )

        assertEquals(1, merged.size)
        // The local row is the one that knows how much of them is on the server.
        assertEquals(3, merged.single().ownedAlbumCount)
    }

    @Test
    public fun `a catalogue namesake of an artist you own is dropped on the name`() {
        val merged: List<Artist> = UnifiedSearchUseCase.mergeArtists(
            query = "oh wonder",
            local = listOf(artist("oh-wonder", "Oh Wonder", owned = 3)),
            // Same name, different MBID: the MBID join cannot see this one.
            catalogue = listOf(artist("oh-wonder-mb", "Oh  WONDER")),
        )

        assertEquals(1, merged.size)
        assertEquals(3, merged.single().ownedAlbumCount)
    }

    @Test
    public fun `two owned artists with the same name are both kept`() {
        val merged: List<Artist> = UnifiedSearchUseCase.mergeArtists(
            query = "wonder",
            local = listOf(
                artist("wonder-a", "Wonder", owned = 1),
                artist("wonder-b", "Wonder", owned = 2),
            ),
            catalogue = emptyList(),
        )

        // Both are music on the server. Collapsing them would hide an album the user has.
        assertEquals(2, merged.size)
    }

    @Test
    public fun `equal scores keep the order they arrived in`() {
        val merged: List<Artist> = UnifiedSearchUseCase.mergeArtists(
            query = "wonder",
            local = emptyList(),
            catalogue = listOf(
                artist("b", "Wonder Boy"),
                artist("a", "Jr. Wonder"),
            ),
        )

        // Both are WordPrefix matches, so MusicBrainz's own order survives.
        assertEquals(listOf("Wonder Boy", "Jr. Wonder"), merged.map { it.name })
    }

    // ----------------------------------------------------------------- relevance

    @Test
    public fun `relevance scores the name against what was typed and nothing else`() {
        assertEquals(
            UnifiedSearchUseCase.ExactName,
            UnifiedSearchUseCase.artistRelevance("wonder", "Wonder"),
        )
        assertEquals(
            UnifiedSearchUseCase.ExactName,
            UnifiedSearchUseCase.artistRelevance("  oh   wonder ", "Oh Wonder"),
        )
        assertEquals(
            UnifiedSearchUseCase.NamePrefix,
            UnifiedSearchUseCase.artistRelevance("wonder", "Wonderland"),
        )
        assertEquals(
            UnifiedSearchUseCase.WordPrefix,
            UnifiedSearchUseCase.artistRelevance("wonder", "Oh Wonder"),
        )
        assertEquals(
            UnifiedSearchUseCase.Substring,
            UnifiedSearchUseCase.artistRelevance("onder", "Oh Wonder"),
        )
        assertEquals(
            UnifiedSearchUseCase.NoRelevance,
            UnifiedSearchUseCase.artistRelevance("wonder", "Khruangbin"),
        )
    }

    /**
     * The predicate `DefaultSearchRepository` filters candidate artists with, before it reads a mirror
     * row for one. An album matches `album_fts` on its title as well as its artist name, so a search
     * for "wonder" that found the album *Wonderland* must not put its artist into the artist results.
     */
    @Test
    public fun `a match is a name that answers the query, not any name at all`() {
        assertTrue(UnifiedSearchUseCase.artistMatches("wonder", "Oh Wonder"))
        assertTrue(UnifiedSearchUseCase.artistMatches("oh won", "Oh Wonder"))
        assertFalse(UnifiedSearchUseCase.artistMatches("wonder", "Taylor Swift"))
        assertFalse(UnifiedSearchUseCase.artistMatches("", "Oh Wonder"))
    }

    // -------------------------------------------------------------------- albums

    @Test
    public fun `albums keep the local record and drop the catalogue copy of it`() {
        val merged: List<Album> = UnifiedSearchUseCase.mergeAlbums(
            local = listOf(album("ultralife", "Ultralife", AlbumState.Owned)),
            catalogue = listOf(album("ultralife", "Ultralife", AlbumState.NotOwned)),
        )

        assertEquals(1, merged.size)
        assertEquals(AlbumState.Owned, merged.single().state)
    }

    @Test
    public fun `a release group the catalogue returned twice is added once`() {
        val duplicate: Album = album("wonder", "Wonder", AlbumState.NotOwned)

        val merged: List<Album> = UnifiedSearchUseCase.mergeAlbums(
            local = emptyList(),
            catalogue = listOf(duplicate, duplicate),
        )

        assertEquals(1, merged.size)
    }

    @Test
    public fun `two different release groups with the same title are both kept`() {
        val merged: List<Album> = UnifiedSearchUseCase.mergeAlbums(
            local = emptyList(),
            catalogue = listOf(
                album("wonder-studio", "Wonder", AlbumState.NotOwned),
                album("wonder-live", "Wonder", AlbumState.NotOwned),
            ),
        )

        // A live record and a studio record of the same name are two albums.
        assertEquals(2, merged.size)
    }

    @Test
    public fun `owned albums lead the merged list`() {
        val merged: List<Album> = UnifiedSearchUseCase.mergeAlbums(
            local = listOf(album("ultralife", "Ultralife", AlbumState.Owned)),
            catalogue = listOf(album("wonderland", "Wonderland", AlbumState.NotOwned)),
        )

        assertEquals(AlbumState.Owned, merged.first().state)
        assertEquals(AlbumState.NotOwned, merged.last().state)
    }

    // ------------------------------------------------------------------ the lane

    @Test
    public fun `merging before the catalogue answers shows library results alone`() {
        val merged: UnifiedSearchResults = UnifiedSearchUseCase.merge(
            query = "wonder",
            local = LocalSearchResults(
                query = "wonder",
                artists = listOf(artist("oh-wonder", "Oh Wonder", owned = 3)),
                albums = listOf(album("ultralife", "Ultralife", AlbumState.Owned)),
            ),
            catalogue = null,
            lane = CatalogueLaneState.Loading,
        )

        assertEquals(1, merged.artists.size)
        assertEquals(1, merged.albums.size)
        assertEquals(CatalogueLaneState.Loading, merged.catalogue)
    }

    @Test
    public fun `an unavailable catalogue lane still merges the library half`() {
        val merged: UnifiedSearchResults = UnifiedSearchUseCase.merge(
            query = "wonder",
            local = LocalSearchResults(
                query = "wonder",
                artists = listOf(artist("oh-wonder", "Oh Wonder", owned = 3)),
            ),
            catalogue = CatalogueSearchResults(query = "wonder"),
            lane = CatalogueLaneState.Unavailable(
                app.needler.core.domain.model.NeedlerError.Offline(),
            ),
        )

        assertEquals("Oh Wonder", merged.artists.single().name)
    }

    // ------------------------------------------------------------------- paging

    @Test
    public fun `a page of artists appends without reordering what is on screen`() {
        val base: UnifiedSearchResults = UnifiedSearchResults(
            query = "wonder",
            artists = listOf(
                artist("oh-wonder", "Oh Wonder", owned = 3),
                artist("jr-wonder", "Jr. Wonder"),
            ),
            catalogue = CatalogueLaneState.Ready(),
        )

        val expanded: UnifiedSearchResults = UnifiedSearchUseCase.expand(
            base = base,
            page = CatalogueSearchPage(
                bucket = SearchBucket.ARTISTS,
                query = "wonder",
                offset = 0,
                artists = listOf(
                    // Already shown, by MBID.
                    artist("jr-wonder", "Jr. Wonder"),
                    // Already shown, by name.
                    artist("oh-wonder-mb", "OH WONDER"),
                    artist("wonder-1", "Wonder"),
                ),
                hasMore = true,
            ),
        )

        // "Wonder" scores higher than everything above it and still arrives last: a page must not
        // shuffle rows the reader is already looking at.
        assertEquals(
            listOf("Oh Wonder", "Jr. Wonder", "Wonder"),
            expanded.artists.map { it.name },
        )
    }

    @Test
    public fun `a page of albums appends only release groups not already shown`() {
        val base: UnifiedSearchResults = UnifiedSearchResults(
            query = "wonder",
            albums = listOf(album("ultralife", "Ultralife", AlbumState.Owned)),
            catalogue = CatalogueLaneState.Ready(),
        )

        val expanded: UnifiedSearchResults = UnifiedSearchUseCase.expand(
            base = base,
            page = CatalogueSearchPage(
                bucket = SearchBucket.ALBUMS,
                query = "wonder",
                offset = 0,
                albums = listOf(
                    // The bucket is walked from the top, so its first rows are ones already merged.
                    album("ultralife", "Ultralife", AlbumState.NotOwned),
                    album("wonderland", "Wonderland", AlbumState.NotOwned),
                ),
                hasMore = false,
            ),
        )

        assertEquals(listOf("Ultralife", "Wonderland"), expanded.albums.map { it.title })
        // The owned row survived the page that claimed not to own it.
        assertEquals(AlbumState.Owned, expanded.albums.first().state)
    }

    @Test
    public fun `a page that reports degradation upgrades the quiet note`() {
        val base: UnifiedSearchResults = UnifiedSearchResults(
            query = "wonder",
            catalogue = CatalogueLaneState.Ready(),
        )

        val expanded: UnifiedSearchResults = UnifiedSearchUseCase.expand(
            base = base,
            page = CatalogueSearchPage(
                bucket = SearchBucket.ALBUMS,
                query = "wonder",
                offset = 0,
                serviceStatus = ServiceStatus(isDegraded = true, message = "upstream slow"),
            ),
        )

        val lane: CatalogueLaneState.Ready = expanded.catalogue as CatalogueLaneState.Ready
        assertEquals("upstream slow", lane.serviceStatus?.message)
    }

    @Test
    public fun `a healthy page does not clear a degradation already reported`() {
        val base: UnifiedSearchResults = UnifiedSearchResults(
            query = "wonder",
            catalogue = CatalogueLaneState.Ready(ServiceStatus(isDegraded = true)),
        )

        val expanded: UnifiedSearchResults = UnifiedSearchUseCase.expand(
            base = base,
            page = CatalogueSearchPage(
                bucket = SearchBucket.ALBUMS,
                query = "wonder",
                offset = 25,
            ),
        )

        assertEquals(base.catalogue, expanded.catalogue)
    }

    // ------------------------------------------------------------------ fixtures

    private fun artist(slug: String, name: String, owned: Int = 0): Artist = Artist(
        mbid = ArtistMbid(slug),
        name = name,
        ownedAlbumCount = owned,
    )

    private fun album(slug: String, title: String, state: AlbumState): Album = Album(
        releaseGroupMbid = ReleaseGroupMbid(slug),
        title = title,
        artistName = "Oh Wonder",
        artistMbid = ArtistMbid("oh-wonder"),
        state = state,
    )
}

package app.needler.core.domain.usecase

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.CatalogueLaneState
import app.needler.core.domain.model.CatalogueSearchPage
import app.needler.core.domain.model.CatalogueSearchResults
import app.needler.core.domain.model.LocalSearchResults
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.PullProgress
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

    /**
     * The device regression, v0.0.12: a library with four Dido albums, searched for `dido`, offered
     * to pull all four of them.
     *
     * Every owned album appeared a second time under "Albums to pull - from MusicBrainz" with a Pull
     * button on it. The two lanes had returned **different ids** for one record, so the MBID join
     * matched nothing; the same screen showed the server itself reporting `in_library: false` for
     * ids its library demonstrably held. The second rule is what catches it: a record the server
     * already has, matched on title and artist.
     *
     * The assertion that matters is the third one. It is not enough that the counts come out right -
     * the un-owned block must contain *only* albums that are genuinely un-owned, which is the
     * sentence REQUIREMENTS.md rule 3 is making.
     */
    @Test
    public fun `a catalogue copy of an owned album is dropped even under a different MBID`() {
        val merged: List<Album> = UnifiedSearchUseCase.mergeAlbums(
            local = listOf(
                album("rg-still", "Still on My Mind", AlbumState.Owned, artist = "Dido"),
                album("rg-life", "Life for Rent", AlbumState.Owned, artist = "Dido"),
                album("rg-angel", "No Angel", AlbumState.Pinned(OfflineDownloadState.Complete), artist = "Dido"),
                // The mirror's row for this one carries no artist and no year, which is why the
                // device drew it with an empty subtitle - and why neither can be part of the key.
                album("rg-safe", "Safe Trip Home", AlbumState.Owned, artist = ""),
            ),
            catalogue = listOf(
                // The same four records under the ids MusicBrainz search returned.
                album("mb-life", "Life for Rent", AlbumState.NotOwned, artist = "Dido", year = 2003),
                album("mb-angel", "No Angel", AlbumState.NotOwned, artist = "Dido", year = 1999),
                album("mb-safe", "Safe Trip Home", AlbumState.NotOwned, artist = "Dido", year = 2008),
                album("mb-still", "Still on My Mind", AlbumState.NotOwned, artist = "Dido", year = 2019),
                // Two unrelated records that merely share the query's spelling. These are the whole
                // point of the catalogue block and must survive.
                album("mb-aria", "Dido", AlbumState.NotOwned, artist = "Aria", year = 2000),
                album("mb-durak", "Dido", AlbumState.NotOwned, artist = "Fırat Durak", year = 2023),
            ),
        )

        assertEquals(
            listOf("Still on My Mind", "Life for Rent", "No Angel", "Safe Trip Home", "Dido", "Dido"),
            merged.map { it.title },
        )
        // The local record survived, states and all; no catalogue copy replaced one.
        assertEquals(
            listOf("rg-still", "rg-life", "rg-angel", "rg-safe"),
            merged.take(4).map { it.releaseGroupMbid.value },
        )
        // And the un-owned half holds only albums the server genuinely does not have.
        assertEquals(
            listOf("Aria", "Fırat Durak"),
            merged.filter { it.state == AlbumState.NotOwned }.map { it.artistName },
        )
    }

    /**
     * A record the server is still acquiring is not offered for acquisition either.
     *
     * The same question the owned block asks - `SearchUiState.libraryAlbums` is every state except
     * [AlbumState.NotOwned] - because a Pull button on an album that is 41% downloaded is as wrong as
     * one on an album that has landed.
     */
    @Test
    public fun `an album already being acquired is not offered for pull under another MBID`() {
        val merged: List<Album> = UnifiedSearchUseCase.mergeAlbums(
            local = listOf(
                album(
                    "rg-girl",
                    "Girl Who Got Away",
                    AlbumState.Acquiring(PullProgress(percent = 41)),
                    artist = "Dido",
                ),
            ),
            catalogue = listOf(
                album("mb-girl", "Girl Who Got Away", AlbumState.NotOwned, artist = "Dido", year = 2013),
            ),
        )

        assertEquals(1, merged.size)
        assertTrue(merged.single().state is AlbumState.Acquiring)
    }

    /**
     * Two catalogue records that share a title and an artist are both kept.
     *
     * The rule is about music the server **already has**, and neither of these is. A reissue, a live
     * record and a remaster are different records a listener may want either of, and the catalogue
     * block is where they are offered; collapsing them would be the title matching this fix exists to
     * avoid.
     */
    @Test
    public fun `two un-owned records sharing a title and an artist are both kept`() {
        val merged: List<Album> = UnifiedSearchUseCase.mergeAlbums(
            local = emptyList(),
            catalogue = listOf(
                album("mb-live-1", "Wonder", AlbumState.NotOwned, artist = "Oh Wonder"),
                album("mb-live-2", "Wonder", AlbumState.NotOwned, artist = "Oh Wonder"),
            ),
        )

        assertEquals(2, merged.size)
    }

    /** An un-owned record by a different artist with the same title survives. */
    @Test
    public fun `a same-titled record by another artist is not mistaken for the one you own`() {
        val merged: List<Album> = UnifiedSearchUseCase.mergeAlbums(
            local = listOf(album("rg-wonder", "Wonder", AlbumState.Owned, artist = "Oh Wonder")),
            catalogue = listOf(
                album("mb-wonder", "Wonder", AlbumState.NotOwned, artist = "Shawn Mendes", year = 2020),
            ),
        )

        assertEquals(2, merged.size)
        assertEquals("Shawn Mendes", merged.last().artistName)
    }

    /**
     * A later page of the albums bucket obeys the same rule.
     *
     * The bucket endpoint answers the same query against the same catalogue, so page two carries the
     * same mismatched ids page one did. `expand` applying only the MBID rule would have put the
     * duplicates back the moment the user tapped "Show all".
     */
    @Test
    public fun `a page of albums drops a catalogue copy of something already owned`() {
        val base: UnifiedSearchResults = UnifiedSearchResults(
            query = "dido",
            albums = listOf(album("rg-life", "Life for Rent", AlbumState.Owned, artist = "Dido")),
            catalogue = CatalogueLaneState.Ready(),
        )

        val expanded: UnifiedSearchResults = UnifiedSearchUseCase.expand(
            base = base,
            page = CatalogueSearchPage(
                bucket = SearchBucket.ALBUMS,
                query = "dido",
                offset = 0,
                albums = listOf(
                    album("mb-life", "Life for Rent", AlbumState.NotOwned, artist = "Dido", year = 2003),
                    album("mb-aria", "Dido", AlbumState.NotOwned, artist = "Aria", year = 2000),
                ),
                hasMore = false,
            ),
        )

        assertEquals(listOf("Life for Rent", "Dido"), expanded.albums.map { it.title })
        assertEquals(AlbumState.Owned, expanded.albums.first().state)
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

    /**
     * One album row.
     *
     * [artist] and [year] are parameters because the Dido regression turns on both: the merge's
     * second rule keys on the artist, and the mirror's row for one of those albums carried no artist
     * and no year at all. A fixture that could not express that could not reproduce the bug.
     */
    private fun album(
        slug: String,
        title: String,
        state: AlbumState,
        artist: String = "Oh Wonder",
        year: Int? = null,
    ): Album = Album(
        releaseGroupMbid = ReleaseGroupMbid(slug),
        title = title,
        artistName = artist,
        artistMbid = if (artist.isBlank()) null else ArtistMbid("oh-wonder"),
        state = state,
        year = year,
    )
}

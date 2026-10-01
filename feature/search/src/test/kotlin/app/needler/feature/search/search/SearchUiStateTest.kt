package app.needler.feature.search.search

import app.needler.core.design.component.artworkPlaceholderInitial
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.CatalogueLaneState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.SearchBucket
import app.needler.core.domain.model.ServiceStatus
import app.needler.core.domain.model.UnifiedSearchResults
import app.needler.feature.search.SampleSearch
import app.needler.feature.search.common.SearchFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the Search screen that are decisions rather than pixels.
 *
 * Every rule REQUIREMENTS.md "Search behaviour" states about *what the screen
 * says* lands in a derived property on [SearchUiState], and a derived property
 * can be asserted on without rendering anything, without a dispatcher and
 * without a server that is broken in exactly the right way. The screenshots
 * prove these states look right; this proves they are reached.
 */
class SearchUiStateTest {

    // ---- which state the screen is in ---------------------------------------

    @Test
    fun `a blank query is the idle state, whatever is in results`() {
        val state = SearchUiState(query = "   ", results = SampleSearch.khruangbinResults)

        assertTrue(state.isIdle)
    }

    @Test
    fun `a query with nothing back from either lane is searching, not empty`() {
        val state = SearchUiState(
            query = "khruangbin",
            results = UnifiedSearchResults(
                query = "khruangbin",
                catalogue = CatalogueLaneState.Loading,
            ),
        )

        assertTrue(state.searching)
        assertFalse(state.showEmptyResult)
    }

    @Test
    fun `nothing found is only claimed once the catalogue lane has settled`() {
        val idle = SearchUiState(
            query = "khruangbim",
            results = UnifiedSearchResults(
                query = "khruangbim",
                catalogue = CatalogueLaneState.Idle,
            ),
        )
        val settled = idle.copy(
            results = idle.results.copy(catalogue = CatalogueLaneState.Ready()),
        )

        assertFalse("the debounce has not even elapsed yet", idle.showEmptyResult)
        assertTrue(settled.showEmptyResult)
    }

    /**
     * Rule 1 and rule 4 together: the library half is shown whatever happened to
     * the other lane, so a failed catalogue search is never an empty screen.
     */
    @Test
    fun `library results survive an unavailable catalogue lane`() {
        val state = SearchUiState(
            query = "khruangbin",
            results = SampleSearch.khruangbinResults.copy(
                catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
            ),
        )

        assertFalse(state.searching)
        assertFalse(state.showEmptyResult)
        assertEquals(4, state.albums.size)
    }

    @Test
    fun `suggestions are offered only when there is nothing else to show`() {
        val withResults = SearchUiState(
            query = "khruangbin",
            results = SampleSearch.khruangbinResults,
            suggestions = SampleSearch.suggestions,
        )
        val withoutResults = SearchUiState(
            query = "khruangbim",
            results = UnifiedSearchResults(
                query = "khruangbim",
                catalogue = CatalogueLaneState.Ready(),
            ),
            suggestions = SampleSearch.suggestions,
        )

        assertFalse(withResults.showSuggestions)
        assertTrue(withoutResults.showSuggestions)
    }

    // ---- what the screen says about the catalogue lane ----------------------

    @Test
    fun `the albums caption claims MusicBrainz only once MusicBrainz has answered`() {
        val base = SearchUiState(query = "khruangbin", results = SampleSearch.khruangbinResults)

        assertEquals(
            FROM_CATALOGUE,
            base.copy(results = base.results.copy(catalogue = CatalogueLaneState.Ready()))
                .albumsSourceNote,
        )
        assertEquals(
            FROM_LIBRARY,
            base.copy(results = base.results.copy(catalogue = CatalogueLaneState.Loading))
                .albumsSourceNote,
        )
        assertEquals(
            FROM_LIBRARY,
            base.copy(
                results = base.results.copy(
                    catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
                ),
            ).albumsSourceNote,
        )
    }

    @Test
    fun `there is nothing to say before the debounce elapses or after a clean answer`() {
        assertNull(noteFor(CatalogueLaneState.Idle))
        assertNull(noteFor(CatalogueLaneState.Ready()))
        assertNull(noteFor(CatalogueLaneState.Ready(ServiceStatus(isDegraded = false))))
    }

    /** REQUIREMENTS.md rule 4, in the two forms the use case can report it. */
    @Test
    fun `offline and an expired session each get their own sentence`() {
        assertEquals(
            CATALOGUE_OFFLINE,
            noteFor(CatalogueLaneState.Unavailable(NeedlerError.Offline())),
        )
        assertEquals(
            CATALOGUE_SESSION_EXPIRED,
            noteFor(CatalogueLaneState.Unavailable(NeedlerError.SessionExpired)),
        )
    }

    @Test
    fun `an unavailable lane is a problem and a loading one is not`() {
        val loading = SearchUiState(
            query = "khruangbin",
            results = UnifiedSearchResults(
                query = "khruangbin",
                catalogue = CatalogueLaneState.Loading,
            ),
        )
        val unavailable = loading.copy(
            results = loading.results.copy(
                catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
            ),
        )

        assertEquals(CATALOGUE_SEARCHING, loading.catalogueNote)
        assertFalse(loading.catalogueNoteIsProblem)
        assertTrue(unavailable.catalogueNoteIsProblem)
    }

    /**
     * `service_status` "should surface as a quiet inline note, not an error
     * dialog" — and in the server's own words when it supplied any, because it
     * knows what upstream is doing and this app does not.
     */
    @Test
    fun `a degraded catalogue prefers the server's own wording`() {
        assertEquals(
            SampleSearch.degraded.message,
            noteFor(CatalogueLaneState.Ready(SampleSearch.degraded)),
        )
        assertEquals(
            CATALOGUE_DEGRADED,
            noteFor(CatalogueLaneState.Ready(ServiceStatus(isDegraded = true, message = "  "))),
        )
    }

    // ---- which block a row belongs in ---------------------------------------

    /**
     * REQUIREMENTS.md "Album states": one `Album` type carries all of them, and the
     * split here is on that state and never on the lane a row arrived from. An
     * album being pulled is the user's own music, not a shopping-list entry.
     */
    @Test
    fun `everything but NotOwned belongs to the library block`() {
        val state = SearchUiState(query = "khruangbin", results = SampleSearch.khruangbinResults)

        assertEquals(
            listOf("Mordechai", "Flyte", "Black Classical Music"),
            state.libraryAlbums.map { it.title },
        )
        assertEquals(listOf("Buzz"), state.catalogueAlbums.map { it.title })
    }

    // ---- the capped blocks and the row under them ---------------------------

    @Test
    fun `a block longer than its preview offers to show the rest`() {
        val state = SearchUiState(query = "wonder", results = SampleSearch.wonderResults)

        assertEquals(ARTIST_PREVIEW, state.visibleArtists.size)
        assertEquals(CATALOGUE_ALBUM_PREVIEW, state.visibleCatalogueAlbums.size)
        assertEquals(
            SearchMoreRow("Show all 5 artists", SearchMoreAction.SHOW_ALL),
            state.moreRow(SearchBucket.ARTISTS),
        )
        assertEquals(
            SearchMoreRow("Show all 10 albums to pull", SearchMoreAction.SHOW_ALL),
            state.moreRow(SearchBucket.ALBUMS),
        )
    }

    /** Expanding is local and instant, and only then is the server worth asking. */
    @Test
    fun `an expanded block shows everything it holds and then offers a page`() {
        val state = expanded(SearchBucket.ARTISTS)

        assertEquals(5, state.visibleArtists.size)
        assertEquals(
            SearchMoreRow(MORE_FROM_CATALOGUE, SearchMoreAction.LOAD_MORE),
            state.moreRow(SearchBucket.ARTISTS),
        )
    }

    /** A block that fits needs no row at all — the pack's own layout, undecorated. */
    @Test
    fun `a block that was never capped offers nothing`() {
        val state = SearchUiState(query = "khruangbin", results = SampleSearch.khruangbinResults)

        assertNull(state.moreRow(SearchBucket.ARTISTS))
        assertNull(state.moreRow(SearchBucket.ALBUMS))
    }

    /**
     * REQUIREMENTS.md rule 4: offline shows library results only. Revealing rows
     * already in hand is fine; offering a call that cannot be made is not.
     */
    @Test
    fun `offline offers the rows already in hand and no page of the catalogue`() {
        val offline = SearchUiState(
            query = "wonder",
            offline = true,
            results = SampleSearch.wonderResults.copy(
                catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
            ),
        )

        assertEquals(
            SearchMoreRow("Show all 10 albums to pull", SearchMoreAction.SHOW_ALL),
            offline.moreRow(SearchBucket.ALBUMS),
        )
        assertNull(
            "no page can be fetched without a connection",
            offline.copy(
                paging = offline.paging.with(SearchBucket.ALBUMS, BucketPaging(expanded = true)),
            ).moreRow(SearchBucket.ALBUMS),
        )
    }

    @Test
    fun `a page in flight says so and accepts no taps`() {
        val state = expanded(SearchBucket.ALBUMS).let {
            it.copy(
                paging = it.paging.with(
                    SearchBucket.ALBUMS,
                    BucketPaging(expanded = true, loading = true),
                ),
            )
        }

        val row: SearchMoreRow = requireNotNull(state.moreRow(SearchBucket.ALBUMS))
        assertEquals(LOOKING_FOR_MORE, row.label)
        assertFalse(row.enabled)
    }

    @Test
    fun `a failed page is a retry and the end of the list is not`() {
        val base = expanded(SearchBucket.ALBUMS)
        val failed = base.copy(
            paging = base.paging.with(
                SearchBucket.ALBUMS,
                BucketPaging(
                    expanded = true,
                    note = pageFailedNote("The server answered 500."),
                    noteIsProblem = true,
                ),
            ),
        )
        val exhausted = base.copy(
            paging = base.paging.with(
                SearchBucket.ALBUMS,
                BucketPaging(
                    expanded = true,
                    hasMore = false,
                    note = catalogueExhaustedNote("wonder"),
                ),
            ),
        )

        val retry: SearchMoreRow = requireNotNull(failed.moreRow(SearchBucket.ALBUMS))
        assertTrue(retry.enabled)
        assertTrue(retry.isProblem)

        val end: SearchMoreRow = requireNotNull(exhausted.moreRow(SearchBucket.ALBUMS))
        assertFalse("there is nothing left to ask for", end.enabled)
        assertFalse(end.isProblem)
    }

    // ---- headings -----------------------------------------------------------

    @Test
    fun `the artist heading agrees with how many artists there are`() {
        val one = SearchUiState(
            query = "khruangbin",
            results = SampleSearch.khruangbinResults,
        )
        val two = one.copy(
            results = one.results.copy(
                artists = one.results.artists + SampleSearch.yussefDayes,
            ),
        )

        assertEquals("Artist", one.artistsHeader)
        assertEquals("Artists", two.artistsHeader)
    }

    // ---- formatting ---------------------------------------------------------

    @Test
    fun `an artist row counts down from the catalogue, as the pack writes it`() {
        assertEquals(
            "5 albums · 2 in your library",
            SearchFormat.artistRowSubtitle(SampleSearch.khruangbin),
        )
    }

    @Test
    fun `an artist with no discography fetched says only what is owned`() {
        val artist = Artist(
            mbid = ArtistMbid("ar-sol"),
            name = "Cleo Sol",
            ownedAlbumCount = 1,
        )

        assertEquals("1 album in your library", SearchFormat.artistRowSubtitle(artist))
    }

    @Test
    fun `an artist you own nothing by does not report zero albums`() {
        val artist = Artist(mbid = ArtistMbid("ar-nobody"), name = "Nobody")

        assertEquals("Not in your library yet", SearchFormat.artistRowSubtitle(artist))
    }

    @Test
    fun `an unknown year is dropped from an album row rather than drawn`() {
        assertEquals(
            "Khruangbin · 2024",
            SearchFormat.albumRowSubtitle(SampleSearch.mordechai),
        )
        assertEquals(
            "Khruangbin",
            SearchFormat.albumRowSubtitle(SampleSearch.mordechai.copy(year = null)),
        )
    }

    @Test
    fun `durations are drawn as digits and spoken as words`() {
        assertEquals("3:47", SearchFormat.duration(227_000L))
        assertEquals("3 minutes 47 seconds", SearchFormat.spokenDuration(227_000L))
        assertNull("unknown is not zero", SearchFormat.duration(null))
        assertNull(SearchFormat.spokenDuration(null))
    }

    /**
     * The avatar letter comes from `:core:design` now, not from a second copy of
     * the rule in this module. Asserted here because the search screen is where
     * the coverless tile is most visible — an artist result has no cover far more
     * often than an owned album does.
     */
    @Test
    fun `an artist tile draws a letter from the shared placeholder`() {
        assertEquals("K", artworkPlaceholderInitial("Khruangbin"))
        assertEquals("T", artworkPlaceholderInitial("  The Marías "))
        assertNull("nothing to draw rather than a question mark", artworkPlaceholderInitial("   "))
    }

    private fun noteFor(lane: CatalogueLaneState): String? = SearchUiState(
        query = "khruangbin",
        results = UnifiedSearchResults(query = "khruangbin", catalogue = lane),
    ).catalogueNote

    /** The "wonder" results with one bucket expanded and nothing paged yet. */
    private fun expanded(bucket: SearchBucket): SearchUiState = SearchUiState(
        query = "wonder",
        results = SampleSearch.wonderResults,
        paging = SearchPaging().with(bucket, BucketPaging(expanded = true)),
    )
}

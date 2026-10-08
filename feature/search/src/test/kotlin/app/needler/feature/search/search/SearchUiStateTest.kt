package app.needler.feature.search.search

import app.needler.core.design.component.artworkPlaceholderInitial
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.CatalogueLaneState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.RequestStatus
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
            FROM_LAST_SYNC,
            base.copy(results = base.results.copy(catalogue = CatalogueLaneState.Loading))
                .albumsSourceNote,
        )
        assertEquals(
            FROM_LAST_SYNC,
            base.copy(
                results = base.results.copy(
                    catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
                ),
            ).albumsSourceNote,
        )
    }

    /**
     * The caption the device contradicted: three rows subtitled "Not in your
     * library yet", under a header reading "Albums to pull - in your library".
     *
     * Asserted as "never the library caption, whatever the lane did" rather than
     * on one wording, because this block's rows are un-owned by definition — it
     * is [SearchUiState.catalogueAlbums] — so no state of the catalogue lane can
     * make that claim true of them.
     */
    @Test
    fun `the to-pull caption never claims the albums are in the library`() {
        val lanes = listOf(
            CatalogueLaneState.Idle,
            CatalogueLaneState.Loading,
            CatalogueLaneState.Ready(),
            CatalogueLaneState.Ready(SampleSearch.degraded),
            CatalogueLaneState.Unavailable(NeedlerError.Offline()),
            CatalogueLaneState.Unavailable(NeedlerError.SessionExpired),
        )

        for (lane in lanes) {
            val state = SearchUiState(
                query = "beastie",
                offline = lane is CatalogueLaneState.Unavailable,
                results = SampleSearch.khruangbinResults.copy(catalogue = lane),
            )

            assertTrue("the fixture has un-owned rows", state.catalogueAlbums.isNotEmpty())
            assertTrue(lane.toString(), state.albumsSourceNote != FROM_LIBRARY)
        }
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

    // ---- what "Nothing found" is allowed to claim ---------------------------

    /**
     * The one that shipped wrong, and the reason the sentence moved onto the
     * state.
     *
     * A phone in aeroplane mode, searching for a string nothing matched, was
     * told: "Nothing in your library or in the MusicBrainz catalogue matches …".
     * MusicBrainz had not been asked. REQUIREMENTS.md "Search behaviour", rule 4
     * requires the screen to "state plainly that catalogue search needs a
     * connection", and reporting a negative for a call that was never made is
     * the opposite of plainly.
     *
     * The assertion is on the absence of the claim rather than on the exact
     * wording, so the sentence can be rewritten without the guarantee moving.
     */
    @Test
    fun `an unreachable catalogue is never reported as a catalogue with nothing in it`() {
        val offline = emptyResultWith(CatalogueLaneState.Unavailable(NeedlerError.Offline()))

        assertTrue(offline.showEmptyResult)
        assertFalse(
            "MusicBrainz was never asked, so it cannot be quoted as having answered",
            offline.emptyResultDetail.contains("MusicBrainz catalogue matches"),
        )
        assertTrue(offline.emptyResultDetail.contains("not searched"))
        assertTrue("the query is still quoted back", offline.emptyResultDetail.contains("\"dido\""))
    }

    /**
     * The same query with the two signals agreeing, which is the state a phone
     * in aeroplane mode is *supposed* to reach.
     *
     * Here so that the guarantee cannot be re-derived from
     * [SearchUiState.offline] by a later author who reads the fixture above and
     * concludes the connectivity flag would have done: it has to hold in both
     * fixtures, and only the lane state holds in both.
     */
    @Test
    fun `offline agreeing with the lane claims no lookup either`() {
        val offline = emptyResultWith(CatalogueLaneState.Unavailable(NeedlerError.Offline()))
            .copy(offline = true)

        assertTrue(offline.showEmptyResult)
        assertEquals(nothingFoundInLibraryOnly("dido"), offline.emptyResultDetail)
        assertEquals(CATALOGUE_OFFLINE, offline.catalogueNote)
    }

    /**
     * The same guarantee for the lane failures that happen while the device is
     * nominally online, which is where [SearchUiState.offline] used to be wrong
     * in the other direction: a stale session or a timed-out call leaves
     * `offline` false and the catalogue just as unsearched.
     */
    @Test
    fun `a failed lane claims no lookup either, whatever the network says`() {
        val expired = emptyResultWith(CatalogueLaneState.Unavailable(NeedlerError.SessionExpired))
        val failed =
            emptyResultWith(CatalogueLaneState.Unavailable(NeedlerError.ServerError(502)))

        assertFalse(expired.offline)
        assertEquals(nothingFoundInLibraryOnly("dido"), expired.emptyResultDetail)
        assertEquals(nothingFoundInLibraryOnly("dido"), failed.emptyResultDetail)
    }

    /** Connected, both lanes ran, both came back empty: the claim is earned. */
    @Test
    fun `a catalogue that answered may be reported as having nothing`() {
        val answered = emptyResultWith(CatalogueLaneState.Ready())

        assertTrue(answered.catalogueAnswered)
        assertEquals(nothingFoundInEitherLane("dido"), answered.emptyResultDetail)
        assertTrue(answered.emptyResultDetail.contains("MusicBrainz catalogue matches"))
    }

    /**
     * A degraded upstream answered, so the claim stands here and
     * [SearchUiState.catalogueNote] is what qualifies it. See
     * [SearchUiState.catalogueAnswered] for the alternative rejected.
     */
    @Test
    fun `a degraded catalogue answered, and the note above says it may be short`() {
        val degraded = emptyResultWith(CatalogueLaneState.Ready(SampleSearch.degraded))

        assertEquals(nothingFoundInEitherLane("dido"), degraded.emptyResultDetail)
        assertEquals(SampleSearch.degraded.message, degraded.catalogueNote)
    }

    /**
     * The path the device gets right today and the easiest one to regress: the
     * mirror answers offline, so there is no empty state to word at all and the
     * results are drawn with the library caption over them.
     */
    @Test
    fun `offline with cached results is a results screen, not an empty one`() {
        val state = SearchUiState(
            query = "khruangbin",
            offline = true,
            results = SampleSearch.khruangbinResults.copy(
                catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
            ),
        )

        assertFalse(state.showEmptyResult)
        assertFalse(state.searching)
        assertEquals(4, state.albums.size)
        assertEquals(FROM_LAST_SYNC, state.albumsSourceNote)
        assertFalse("no page of a catalogue that cannot be reached", state.canPageCatalogue)

        // The fixture holds un-owned rows, so the banner may not say "this is
        // your library only" over a block of records that are not in it. See
        // CATALOGUE_OFFLINE_CACHED.
        assertTrue(state.catalogueAlbums.isNotEmpty())
        assertEquals(CATALOGUE_OFFLINE_CACHED, state.catalogueNote)
        assertEquals(SearchBannerKind.OFFLINE, state.catalogueBanner?.kind)
    }

    // ---- what a Pull offline says before it is tapped -----------------------

    /**
     * REQUIREMENTS.md "Failure handling": offline "queues pulls, playlist edits,
     * favourites and scrobbles for replay". The outcome is supported; it is also
     * a different outcome, and the sheet is where the user still has a choice.
     */
    @Test
    fun `a pull placed offline says so before it is placed`() {
        val state = SearchUiState(
            query = "niki",
            offline = true,
            results = SampleSearch.khruangbinResults.copy(
                catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
            ),
            pullSheetAlbum = SampleSearch.buzz,
        )

        assertEquals(QUEUE_PULL_LABEL, state.pullConfirmLabel)
        assertTrue(
            "the queueing is stated, not left to the notice that follows the tap",
            state.pullSheetNote.orEmpty().contains("queued"),
        )
        assertTrue(state.pullSheetNote.orEmpty().contains("back online"))
    }

    /**
     * With a connection the sheet keeps the pack's label, says where the record
     * lands, and then gets out of the way.
     *
     * The destination is said whether or not the server sent a snapshot, because
     * it is the one fact on the sheet that does not depend on the server: a pull
     * reaches the **server**, and `NeedlerRequestSheet`'s own fallback copy
     * ("imports it into your library") is the phrase that left that open.
     */
    @Test
    fun `a pull placed online says where it lands, then the server's own note`() {
        val summary = "FLAC where available, else MP3 320"
        val state = SearchUiState(
            query = "niki",
            results = SampleSearch.khruangbinResults.copy(catalogue = CatalogueLaneState.Ready()),
            pullSheetAlbum = SampleSearch.buzz.copy(qualityPolicySummary = summary),
        )

        assertEquals(PULL_LABEL, state.pullConfirmLabel)
        assertEquals(PULL_DESTINATION + " " + summary, state.pullSheetNote)
        assertEquals(
            "the destination still, with nothing invented about the download",
            PULL_DESTINATION,
            state.copy(pullSheetAlbum = SampleSearch.buzz).pullSheetNote,
        )
    }

    /**
     * The server's words are kept and the queueing is put in front of them: the
     * snapshot still describes what the request will fetch, once it goes out.
     */
    @Test
    fun `offline, the queueing leads and the quality snapshot follows`() {
        val summary = "FLAC where available, else MP3 320"
        val state = SearchUiState(
            offline = true,
            query = "niki",
            pullSheetAlbum = SampleSearch.buzz.copy(qualityPolicySummary = summary),
        )

        assertEquals(
            PULL_DESTINATION + " " + PULL_QUEUED_OFFLINE + " " + summary,
            state.pullSheetNote,
        )
    }

    /** No album, no sheet, so there is nothing for the caption to be about. */
    @Test
    fun `the sheet note is absent when no sheet is open`() {
        assertNull(SearchUiState(offline = true).pullSheetNote)
    }

    /** Online, with results: unchanged, and the caption claims the lane that ran. */
    @Test
    fun `online with results keeps the MusicBrainz caption`() {
        val state = SearchUiState(
            query = "khruangbin",
            results = SampleSearch.khruangbinResults.copy(catalogue = CatalogueLaneState.Ready()),
        )

        assertFalse(state.showEmptyResult)
        assertEquals(FROM_CATALOGUE, state.albumsSourceNote)
        assertNull(state.catalogueNote)
        assertTrue(state.canPageCatalogue)
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

    // ---- the four banners --------------------------------------------------

    /**
     * Four unlike conditions, four kinds, and one of them with something to press.
     *
     * The kind is what the screen draws a glyph, a hue and a weight from, so it is
     * asserted here rather than left to a render: four PNGs that differ only in
     * their sentence is exactly the state this replaces, and four PNGs cannot say
     * which of them is supposed to look different.
     */
    @Test
    fun `each lane condition gets its own banner, and only one offers an action`() {
        assertEquals(SearchBannerKind.PROGRESS, kindFor(CatalogueLaneState.Loading))
        assertEquals(
            SearchBannerKind.DEGRADED,
            kindFor(CatalogueLaneState.Ready(SampleSearch.degraded)),
        )
        assertEquals(
            SearchBannerKind.EXPIRED,
            kindFor(CatalogueLaneState.Unavailable(NeedlerError.SessionExpired)),
        )
        assertEquals(
            SearchBannerKind.OFFLINE,
            kindFor(CatalogueLaneState.Unavailable(NeedlerError.Offline())),
        )
        assertEquals(
            SearchBannerKind.PROBLEM,
            kindFor(CatalogueLaneState.Unavailable(NeedlerError.ServerError(502))),
        )
        assertNull(kindFor(CatalogueLaneState.Idle))
        assertNull(kindFor(CatalogueLaneState.Ready()))
    }

    /**
     * The remedy is named and now reachable.
     *
     * REQUIREMENTS.md rule 4 leaves the library searchable and the catalogue not
     * until the session is renewed, so this is the one banner on the screen whose
     * condition the user can end.
     */
    @Test
    fun `only the expired session carries a way out of itself`() {
        assertEquals(
            SearchBannerAction.SIGN_IN,
            actionFor(CatalogueLaneState.Unavailable(NeedlerError.SessionExpired)),
        )
        assertNull(actionFor(CatalogueLaneState.Unavailable(NeedlerError.Offline())))
        assertNull(actionFor(CatalogueLaneState.Loading))
        assertNull(actionFor(CatalogueLaneState.Ready(SampleSearch.degraded)))
    }

    /**
     * The sentence that was drawn over six empty skeleton rows.
     *
     * "Your library results are already below" is a claim about the screen, and
     * the state where neither lane has answered is the state where it is false.
     * Both halves are asserted, because the useful sentence has to survive: it is
     * correct, and the only thing telling a reader that the rows under it are not
     * what is being waited for.
     */
    @Test
    fun `the searching banner claims results below only once there are some`() {
        val nothingYet = SearchUiState(
            query = "khruangbin",
            results = UnifiedSearchResults(
                query = "khruangbin",
                catalogue = CatalogueLaneState.Loading,
            ),
        )
        val libraryIn = nothingYet.copy(
            results = SampleSearch.khruangbinResults.copy(catalogue = CatalogueLaneState.Loading),
        )

        assertTrue(nothingYet.searching)
        assertEquals(CATALOGUE_SEARCHING, nothingYet.catalogueNote)
        assertFalse(nothingYet.catalogueNote.orEmpty().contains("already below"))

        assertEquals(CATALOGUE_SEARCHING_WITH_RESULTS, libraryIn.catalogueNote)
        assertTrue(libraryIn.catalogueNote.orEmpty().contains("already below"))
    }

    /**
     * The banner and the caption 1300px below it have to agree.
     *
     * Offline with owned rows only, "this is your library only" is the whole
     * truth. Offline with un-owned rows from the mirror under it, the same
     * sentence contradicts an ALBUMS TO PULL block captioned "from your last
     * sync" that is telling the truth.
     */
    @Test
    fun `the offline banner does not claim the library when un-owned rows are shown`() {
        val ownedOnly = SearchUiState(
            query = "khruangbin",
            offline = true,
            results = SampleSearch.khruangbinResults.copy(
                albums = listOf(SampleSearch.mordechai, SampleSearch.flyte),
                catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
            ),
        )
        val withCached = ownedOnly.copy(
            results = SampleSearch.khruangbinResults.copy(
                catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
            ),
        )

        assertTrue(ownedOnly.catalogueAlbums.isEmpty())
        assertEquals(CATALOGUE_OFFLINE, ownedOnly.catalogueNote)

        assertTrue(withCached.catalogueAlbums.isNotEmpty())
        assertEquals(CATALOGUE_OFFLINE_CACHED, withCached.catalogueNote)
        assertFalse(
            "the block below it is captioned from your last sync, not in your library",
            withCached.catalogueNote.orEmpty().contains("your library only"),
        )
        assertEquals(FROM_LAST_SYNC, withCached.albumsSourceNote)
    }

    // ---- a pull already placed ----------------------------------------------

    /**
     * The duplicate the device audit found: a banner saying "Pulling" over a row
     * still offering Pull.
     *
     * The album's state lives in the mirror and the receipt had not reached it,
     * so this is not a stale golden - it is the window between the server saying
     * yes and the sync recording it, and a second tap in that window placed a
     * second request.
     */
    @Test
    fun `a request the server took stops the row offering another`() {
        val placed = SearchUiState(
            query = "khruangbin",
            results = SampleSearch.khruangbinResults,
            placedPulls = mapOf(
                SampleSearch.buzz.releaseGroupMbid to RequestStatus.ACCEPTED,
            ),
        )

        assertEquals(AlbumState.NotOwned, SampleSearch.buzz.state)
        assertTrue("the mirror still has it un-owned", SampleSearch.buzz in placed.catalogueAlbums)
        assertFalse(placed.offersPull(SampleSearch.buzz))
        assertEquals(RequestStatus.ACCEPTED, placed.placedPull(SampleSearch.buzz))

        val untouched = SearchUiState(results = SampleSearch.khruangbinResults)
        assertTrue("every other un-owned row still offers one", untouched.offersPull(SampleSearch.buzz))
    }

    /**
     * Two of the five answers leave the row exactly as it was, on purpose.
     *
     * A rejection is one the user may reasonably try again, and suppressing its
     * Pull would strand them on a row with no action at all.
     */
    @Test
    fun `only the three answers that mean the server has it count as placed`() {
        assertTrue(RequestStatus.ACCEPTED.isPlaced)
        assertTrue(RequestStatus.PENDING_APPROVAL.isPlaced)
        assertTrue(RequestStatus.QUEUED_OFFLINE.isPlaced)
        assertFalse(RequestStatus.REJECTED.isPlaced)
        assertFalse(RequestStatus.ALREADY_PRESENT.isPlaced)
    }

    // ---- the offline no-results screen --------------------------------------

    /**
     * The screen that was a heading and three lines of prose.
     *
     * Online it offers three completions; offline `suggest` is a network call and
     * returns nothing, so there was nothing on the screen to press at all. The
     * retry is offered because a lane did not run, and the recent searches are
     * offered because they are the only candidates that need no connection.
     */
    @Test
    fun `an empty result with a lane that never ran offers a retry and local history`() {
        val offline = SearchUiState(
            query = "beastiedido",
            results = UnifiedSearchResults(
                query = "beastiedido",
                catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
            ),
            recentQueries = SampleSearch.recentQueries,
        )

        assertTrue(offline.showEmptyResult)
        assertFalse(offline.showSuggestions)
        assertTrue(offline.showRetry)
        assertEquals(SampleSearch.recentQueries, offline.emptyResultRecents)
    }

    /** Both lanes answered, so there is nothing to try again and no gap to fill. */
    @Test
    fun `an empty result both lanes answered offers no retry`() {
        val answered = SearchUiState(
            query = "khruangbim",
            results = UnifiedSearchResults(
                query = "khruangbim",
                catalogue = CatalogueLaneState.Ready(),
            ),
            suggestions = SampleSearch.suggestions,
            recentQueries = SampleSearch.recentQueries,
        )

        assertFalse(answered.showRetry)
        assertTrue(answered.showSuggestions)
        assertTrue(
            "the server's completions are about what was typed; history is not",
            answered.emptyResultRecents.isEmpty(),
        )
    }

    /** Offering back the query the screen is reporting on is the retry, not a suggestion. */
    @Test
    fun `the query that just failed is not offered back as history`() {
        val state = SearchUiState(
            query = "two star",
            results = UnifiedSearchResults(
                query = "two star",
                catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
            ),
            recentQueries = SampleSearch.recentQueries,
        )

        assertTrue("two star" in SampleSearch.recentQueries)
        assertFalse("two star" in state.emptyResultRecents)
        assertEquals(SampleSearch.recentQueries.size - 1, state.emptyResultRecents.size)
    }

    private fun kindFor(lane: CatalogueLaneState): SearchBannerKind? = SearchUiState(
        query = "khruangbin",
        results = SampleSearch.khruangbinResults.copy(catalogue = lane),
    ).catalogueBanner?.kind

    private fun actionFor(lane: CatalogueLaneState): SearchBannerAction? = SearchUiState(
        query = "khruangbin",
        results = SampleSearch.khruangbinResults.copy(catalogue = lane),
    ).catalogueBanner?.action

    private fun noteFor(lane: CatalogueLaneState): String? = SearchUiState(
        query = "khruangbin",
        results = UnifiedSearchResults(query = "khruangbin", catalogue = lane),
    ).catalogueNote

    /**
     * A settled search for "dido" that nothing matched, with the lane in [lane].
     *
     * `offline` is left false on purpose, including for the offline lane. That is
     * exactly the disagreement the device produced — connectivity reading online
     * while the lane had already recorded that it never ran — and a fixture that
     * set both would pass whichever signal the copy read.
     */
    private fun emptyResultWith(lane: CatalogueLaneState): SearchUiState = SearchUiState(
        query = "dido",
        results = UnifiedSearchResults(query = "dido", catalogue = lane),
    )

    /** The "wonder" results with one bucket expanded and nothing paged yet. */
    private fun expanded(bucket: SearchBucket): SearchUiState = SearchUiState(
        query = "wonder",
        results = SampleSearch.wonderResults,
        paging = SearchPaging().with(bucket, BucketPaging(expanded = true)),
    )
}

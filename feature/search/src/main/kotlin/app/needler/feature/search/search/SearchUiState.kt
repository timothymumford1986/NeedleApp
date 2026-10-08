package app.needler.feature.search.search

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.CatalogueLaneState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.SearchBucket
import app.needler.core.domain.model.SearchSuggestion
import app.needler.core.domain.model.ServiceStatus
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.UnifiedSearchResults
import app.needler.feature.search.common.SearchFormat
import app.needler.feature.search.common.problemMessage

/**
 * Everything the Search screen renders — screen 03 on a phone and screen 10 on
 * a tablet.
 *
 * The screen is stateless: it is handed one of these plus callbacks, so every
 * state it can be in — nothing typed, searching, results, no results, catalogue
 * unreachable, catalogue degraded — can be screenshotted and asserted on
 * without a repository, a database or a server.
 *
 * ## One merged list, split by state and never by lane
 *
 * [results] is a [UnifiedSearchResults], which is already merged. REQUIREMENTS.md
 * "Search behaviour", rule 3: "Merge on release-group MBID. An album present
 * locally takes the local record and is badged as owned; the catalogue copy is
 * discarded." That merge happens in `UnifiedSearchUseCase`, below this screen
 * and below the ViewModel, so nothing here asks which lane a row arrived from.
 *
 * What the screen does ask is what a row's
 * [app.needler.core.domain.model.AlbumState] is, and [libraryAlbums] /
 * [catalogueAlbums] split the one merged list on exactly that. This is not the
 * lane pair rule 3 forbids: a `NotOwned` row is one the mirror holds only as a
 * cached catalogue record, and an `Acquiring` or `PendingApproval` row that came
 * back from MusicBrainz half a second ago still belongs with the user's own
 * music, because they asked for it. The split is "what you have" against "what
 * you could pull", which is the question the two blocks answer.
 *
 * The reason it is split at all is that the un-split list shipped and was
 * unusable: a search returned two owned albums, then twenty catalogue albums,
 * and the Songs block landed sixteen swipes below the fold. Owned results lead
 * now — artists, then albums in the library, then songs, then everything that
 * would have to be pulled.
 *
 * ## Results may lag the field by one keystroke
 *
 * [query] is what is in the text field right now; [results] is the most recent
 * answer, which for a few milliseconds after a keystroke is still the previous
 * query's. That is deliberate. Blanking the list on every keystroke and
 * re-filling it a frame later would make a fast typist's screen strobe, and the
 * local FTS lane answers so quickly that the stale window is rarely visible at
 * all. The one place it matters is the empty-query case, and there
 * `UnifiedSearchUseCase` emits an empty result synchronously, so clearing the
 * field clears the list at once.
 */
data class SearchUiState(

    /** What is in the field. The source of truth for the text the user sees. */
    val query: String = "",

    /** The merged answer: library and catalogue in one list, plus what the slow lane is doing. */
    val results: UnifiedSearchResults = UnifiedSearchResults(query = ""),

    /**
     * Previous searches, newest first, for the state before anything is typed.
     *
     * `SearchRepository.observeRecentQueries` describes itself as being "for the
     * empty-search state", and that is the only place this is drawn: once there
     * is a query, the results are more useful than the history of getting there.
     */
    val recentQueries: List<String> = emptyList(),

    /**
     * Completions from `GET /api/v1/search/suggest` (REQUIREMENTS.md rule 2).
     *
     * Drawn only on the no-results screen; see [showSuggestions] for why.
     */
    val suggestions: List<SearchSuggestion> = emptyList(),

    /** The track the player is on, so a Songs row can mark itself. */
    val nowPlayingTrackKey: TrackKey? = null,

    /**
     * The server is unreachable.
     *
     * Not an error state. The local FTS lane needs no connection at all, so an
     * offline search still returns the library; what changes is that the
     * catalogue half is missing, which [catalogueNote] states plainly.
     *
     * This answers "is there a network right now" and nothing else. It must not
     * be used to describe what a lane did or did not do — see
     * [catalogueAnswered] for why, and for the device report that forced the
     * distinction. Its legitimate uses are the two questions about a call the
     * screen is *about to make* rather than about one it already made:
     * [canPageCatalogue], which decides whether another page may be asked for,
     * and [pullConfirmLabel] with [pullSheetNote], which say what a **Pull**
     * tapped right now will do.
     */
    val offline: Boolean = false,

    /** A pull is in flight, so the Pull buttons are disabled rather than tappable twice. */
    val busy: Boolean = false,

    /**
     * The album whose request sheet is open, or null when none is.
     *
     * REQUIREMENTS.md "Placing a request" requires the `monitor_artist` flag to be
     * "a secondary toggle on the request sheet", so a **Pull** on a result opens
     * `:core:design`'s [app.needler.core.design.component.NeedlerRequestSheet]
     * rather than firing the request where it is tapped. It is still one tap to
     * reach — the requirement's "requesting is one tap on any un-owned album" — and
     * the confirm is one thumb-movement away.
     */
    val pullSheetAlbum: Album? = null,

    /**
     * The `monitor_artist` toggle on that sheet.
     *
     * Held here rather than inside the sheet because the sheet component hoists it
     * deliberately: the flag has to reach `RequestAlbumUseCase`, and a value that
     * lived in the sheet would be lost to a rotation halfway through a decision.
     */
    val monitorArtist: Boolean = false,

    /**
     * What the per-bucket "show all" path has done so far, one entry per bucket
     * REQUIREMENTS.md rule 5 can page.
     */
    val paging: SearchPaging = SearchPaging(),

    /** The result of the last action, or an explanation the screen owes the user. */
    val notice: SearchNotice? = null,

    /**
     * How many rows the crate holds, from `PlaybackController.observeQueue`.
     *
     * The session's own figure rather than one this screen works out: the controller
     * owns the crate, and a predicted count could disagree with the crate screen about
     * what the user is holding.
     */
    val crateTrackCount: Int = 0,

    /** The crate's total running time in milliseconds, from the same flow. */
    val crateDurationMs: Long = 0L,

    /**
     * The release groups this session has already placed a request for, and what
     * the server said about each.
     *
     * ## Why the screen cannot wait for the mirror
     *
     * A request answered `ACCEPTED` changes the album's state on the **server**.
     * It reaches [results] only when the next sync writes it into the mirror,
     * which is seconds away at best and a reconnect away for a pull placed
     * offline. Until then [Album.state] is still [AlbumState.NotOwned] and the
     * row still draws a **Pull** pill, so a second tap places a second request
     * for the same record — the duplicate the device audit found under the
     * banner that had just said "Pulling. Track it on the Pulls tab".
     *
     * So the outcome is held here, keyed on the release group, and
     * [placedPull] is what the row reads instead of asking [Album.state] alone.
     * It is the server's own [RequestStatus] rather than a boolean, because the
     * three answers that mean "placed" are three different states to draw:
     * accepted is pulling, pending approval is waiting, and a queued offline
     * pull is waiting for a connection.
     *
     * Dropped whenever the query changes, with the pages and the notice: a
     * receipt for one search has nothing to say about the next.
     */
    val placedPulls: Map<ReleaseGroupMbid, RequestStatus> = emptyMap(),
) {

    val artists: List<Artist> get() = results.artists

    /** Owned and un-owned in one list, already merged on release-group MBID. */
    val albums: List<Album> get() = results.albums

    /**
     * The albums this server already has, in any state the user put them in:
     * owned, pinned, being pulled, waiting for approval, or failed.
     *
     * Computed once per state instance rather than on every read: the screen
     * reaches for it from inside a `LazyListScope` key lambda, which runs per
     * row, and a getter that filtered the whole list there would be quadratic on
     * a long result.
     */
    val libraryAlbums: List<Album> by lazy {
        results.albums.filter { album -> album.state != AlbumState.NotOwned }
    }

    /** The albums that would have to be pulled. Same reasoning as [libraryAlbums] for the `lazy`. */
    val catalogueAlbums: List<Album> by lazy {
        results.albums.filter { album -> album.state == AlbumState.NotOwned }
    }

    /**
     * What this session's own request for [album] came back as, or null when no
     * request has been placed for it.
     *
     * The row asks this before it asks [Album.state], because between a receipt
     * and the sync that records it the two disagree and only this one has heard
     * from the server. See [placedPulls].
     */
    fun placedPull(album: Album): RequestStatus? = placedPulls[album.releaseGroupMbid]

    /**
     * Whether a **Pull** may still be offered on [album].
     *
     * Not a question about the album's state on its own: a record whose request
     * this session already placed is one the server is dealing with, whatever
     * the mirror still says.
     */
    fun offersPull(album: Album): Boolean =
        album.state == AlbumState.NotOwned && placedPull(album) == null

    /**
     * The artists to draw, capped until the user asks for all of them.
     *
     * The cap is the affordance rule 5 needed and never had: a block that shows
     * everything it holds has nowhere to put a "show all", and without one the
     * bucket endpoint's `limit` and `offset` are unreachable from the UI.
     */
    val visibleArtists: List<Artist>
        get() = if (paging.isExpanded(SearchBucket.ARTISTS)) {
            artists
        } else {
            artists.take(ARTIST_PREVIEW)
        }

    /** The un-owned albums to draw, capped the same way and for the same reason. */
    val visibleCatalogueAlbums: List<Album>
        get() = if (paging.isExpanded(SearchBucket.ALBUMS)) {
            catalogueAlbums
        } else {
            catalogueAlbums.take(CATALOGUE_ALBUM_PREVIEW)
        }

    /**
     * Songs, which are library-only.
     *
     * Catalogue search returns artists and albums; MusicBrainz recordings are
     * not searched, and the server's `/api/v1/search` does not return them, so
     * the Songs block on screen 03 is captioned "in your library" in the pack
     * because that is literally all it can ever hold.
     */
    val tracks: List<Track> get() = results.tracks

    val catalogue: CatalogueLaneState get() = results.catalogue

    /** Nothing has been typed, so the screen shows recent searches rather than results. */
    val isIdle: Boolean get() = query.isBlank()

    /** The catalogue lane has finished, one way or the other. */
    val catalogueSettled: Boolean
        get() = catalogue is CatalogueLaneState.Ready || catalogue is CatalogueLaneState.Unavailable

    /**
     * Something is typed and nothing has come back yet from either lane.
     *
     * This is a genuinely brief state — local FTS is a Room query with a 50 ms
     * budget — which is why the screen draws skeleton rows rather than a
     * spinner. A spinner would flash and be gone; the rows hold the layout still
     * so the list does not jump when the first result lands.
     */
    val searching: Boolean get() = !isIdle && results.isEmpty && !catalogueSettled

    /** Both lanes have answered and neither had anything. */
    val showEmptyResult: Boolean get() = !isIdle && results.isEmpty && catalogueSettled

    /**
     * Whether MusicBrainz was actually asked and actually replied.
     *
     * The lane's own record, and the only thing on this screen allowed to answer
     * that question. REQUIREMENTS.md "Search behaviour", rule 4: offline or with
     * a stale session, "show library results only and state plainly that
     * catalogue search needs a connection" — plainly, which means the screen may
     * not report a negative for a call it never made.
     *
     * [offline] is deliberately not consulted. It is a live answer to "is there
     * a network right now", from `SessionRepository.observeConnectivity`, and
     * that is a different question from "did the catalogue lane run", which
     * `UnifiedSearchUseCase` settles once per query from the connectivity and
     * session state it read at the time. The two disagree in every state where
     * the lane failed while nominally online — an expired session, a timeout, a
     * 5xx, a proxy — and they disagreed on a device in aeroplane mode, where
     * [offline] read false and this screen told the user MusicBrainz had been
     * searched and had nothing. Asserting absence from silence is the failure
     * this codebase has been bitten by most often, and the lane state is the one
     * signal on this screen that cannot do it: it is set on the request path, by
     * the code that either made the call or decided not to.
     *
     * A degraded [CatalogueLaneState.Ready] counts as answered, because it was:
     * the call went out and MusicBrainz replied. The alternative considered was
     * treating degradation as not-answered, and it was rejected because it
     * swaps one falsehood for another — the lane did run — and because
     * [catalogueNote] already carries [CATALOGUE_DEGRADED] directly above this
     * sentence, saying results may be short in the one place a reader of those
     * results will see it.
     */
    val catalogueAnswered: Boolean get() = catalogue is CatalogueLaneState.Ready

    /**
     * The sentence under "Nothing found".
     *
     * Two forms, and which one is drawn turns on [catalogueAnswered] alone: this
     * screen may describe a negative from MusicBrainz only when MusicBrainz
     * returned one. What it may always describe is the library, because the
     * local FTS lane needs no connection and therefore always ran.
     *
     * Neither form says *why* the catalogue is missing, and that is deliberate.
     * [catalogueNote] is drawn directly above this block and already says why,
     * in the words the particular cause deserves — no connection, an expired
     * sign-in, or a problem the server named. Repeating the cause here would put
     * one reason on screen twice, and the copy this replaces hard-coded "without
     * a connection", which is wrong for the expired-session case that reaches
     * exactly the same branch.
     *
     * A property on the state rather than a branch inside the screen, for the
     * reason [catalogueNote] is one: the copy is the thing that lied, so it has
     * to be assertable without rendering anything.
     */
    val emptyResultDetail: String
        get() = if (catalogueAnswered) {
            nothingFoundInEitherLane(query)
        } else {
            nothingFoundInLibraryOnly(query)
        }

    /**
     * Whether to offer completions.
     *
     * The design pack draws no suggestions surface anywhere, so this is the one
     * place the requirement and the pack had to be reconciled: completions are
     * offered where they are useful and where they compete with nothing, which
     * is the screen that otherwise says only "nothing found". Drawing them under
     * the field while results exist would put an invented list on top of the
     * pack's own layout.
     */
    val showSuggestions: Boolean get() = showEmptyResult && suggestions.isNotEmpty()

    /**
     * The previous searches to offer when there are no completions to offer.
     *
     * `suggest` is `GET /api/v1/search/suggest` — a network call — so the
     * no-results screen reached with no connection had nothing tappable on it at
     * all: a heading and three lines of prose, where the same screen online
     * offers three completions. The one thing a user can do from there is search
     * for something else, and the list of things they have searched for before is
     * already in hand, local, and the only source of candidates that needs no
     * connection.
     *
     * The query that just failed is filtered out. Offering it back as a
     * suggestion would be offering to repeat the search the screen is reporting
     * on, which is what [showRetry] is for and is a different thing.
     *
     * Only when there are no completions, never beside them: the server's
     * completions are about what was typed and these are about what was typed
     * before, so a screen showing both would be ranking history against
     * relevance.
     */
    val emptyResultRecents: List<String>
        get() = if (!showEmptyResult || suggestions.isNotEmpty()) {
            emptyList()
        } else {
            recentQueries.filter { !it.equals(query.trim(), ignoreCase = true) }
        }

    /**
     * Whether the empty-result screen offers to run the search again.
     *
     * Only when a lane did not run. A search both lanes answered has been
     * answered, and a retry there would ask the same question of the same two
     * sources and redraw the same screen — an affordance whose only outcome is
     * the state the user is already in. When the catalogue never ran, the half
     * that is missing is missing because of a connection or a session, either of
     * which may since have changed, so trying again is a real next step.
     *
     * Reads [catalogueAnswered] and not [offline], for the whole of the reasoning
     * on that property: the question is what the lane did, and only the lane
     * knows.
     */
    val showRetry: Boolean get() = showEmptyResult && !catalogueAnswered

    /** `Artist` for one, `Artists` for several — the pack writes the singular on screens 03 and 10. */
    val artistsHeader: String get() = if (artists.size == 1) "Artist" else "Artists"

    /**
     * The quiet caption on the right of the ALBUMS header.
     *
     * The pack writes "from MusicBrainz" (03, 10), and that is right whenever
     * the catalogue lane actually answered. When it did not — offline, stale
     * session, still in flight — claiming MusicBrainz would be a small, constant
     * lie on the one screen whose whole job is to be honest about which lane a
     * result came from. What the block then holds is the un-owned catalogue
     * records the mirror happens to carry, which arrived with a sync, so the
     * caption names that source instead: [FROM_LAST_SYNC].
     *
     * It captions the *to pull* block, which is why [FROM_LIBRARY] is no longer
     * the fallback. A phone in aeroplane mode drew three rows under "Albums to
     * pull", each subtitled "Not in your library yet", under a header captioned
     * "in your library" — one half of a header contradicting the other. The
     * caption was written as a claim about where the list came from and reads as
     * a claim about the albums, and on this block that claim is the one thing it
     * must not make. The library block above is captioned [FROM_LIBRARY]
     * unconditionally, because there it is true of the rows as well as of the
     * source.
     *
     * It also carries, quietly and where the consequence is, the half of
     * REQUIREMENTS.md "Failure handling" — "Offline is a first-class state, not
     * an error" — that this block owes an offline reader: the list is as old as
     * the last sync, because the lane that would have refreshed it was not
     * reachable. [catalogueNote] says that at length under the field; this is
     * the one word of it that survives next to the rows themselves.
     *
     * Rejected: dropping the caption entirely when the lane has not answered.
     * That header would then be the only one on the screen with nothing on its
     * right, and a reader would be left to infer the missing lane from a blank
     * space, which is the inference REQUIREMENTS.md "Search behaviour", rule 4
     * asks the screen to make unnecessary.
     */
    val albumsSourceNote: String
        get() = if (catalogue is CatalogueLaneState.Ready) FROM_CATALOGUE else FROM_LAST_SYNC

    /**
     * Whether the catalogue can be asked for another page at all.
     *
     * REQUIREMENTS.md rule 4: offline, or with a stale session, "show library
     * results only and state plainly that catalogue search needs a connection".
     * A "more from MusicBrainz" row in that state would offer a call that cannot
     * be made, so the affordance is absent rather than tappable-and-failing, and
     * [catalogueNote] is what says why.
     */
    val canPageCatalogue: Boolean
        get() = !offline && catalogue is CatalogueLaneState.Ready

    /**
     * The one row under a capped block: what it says and whether it does
     * anything, or null when the block needs no such row.
     *
     * Four states in one place, because they are mutually exclusive and the row
     * has to pick one: a page in flight, a note left by the last page, rows held
     * back by the preview cap, and "there may be more upstream". The order
     * matters — a note from a failed page must not be replaced by a cheerful
     * "show all" the next recomposition.
     *
     * "More from MusicBrainz" is offered only once the block has been expanded,
     * which is the same as saying only once the block was long enough to be
     * capped. A search that found one artist is a search that found the artist;
     * putting a "more from MusicBrainz" under it would decorate every result on
     * the screen with an invitation to go looking for a worse match.
     */
    fun moreRow(bucket: SearchBucket): SearchMoreRow? {
        val bucketPaging: BucketPaging = paging.of(bucket)
        val held: Int = when (bucket) {
            SearchBucket.ARTISTS -> artists.size - visibleArtists.size
            SearchBucket.ALBUMS -> catalogueAlbums.size - visibleCatalogueAlbums.size
        }
        val total: Int = when (bucket) {
            SearchBucket.ARTISTS -> artists.size
            SearchBucket.ALBUMS -> catalogueAlbums.size
        }
        val note: String? = bucketPaging.note
        return when {
            bucketPaging.loading -> SearchMoreRow(label = LOOKING_FOR_MORE, enabled = false)

            // A failure is tappable — tapping retries the same page. The end of
            // the list is not: there is nothing left to ask for.
            note != null -> SearchMoreRow(
                label = note,
                enabled = bucketPaging.noteIsProblem,
                isProblem = bucketPaging.noteIsProblem,
            )

            held > 0 -> SearchMoreRow(
                label = showAllLabel(bucket, total),
                action = SearchMoreAction.SHOW_ALL,
            )

            bucketPaging.expanded && bucketPaging.hasMore && canPageCatalogue ->
                SearchMoreRow(label = MORE_FROM_CATALOGUE)

            else -> null
        }
    }

    /** `Show all 14 albums` — the count is the whole block, not the hidden remainder. */
    private fun showAllLabel(bucket: SearchBucket, total: Int): String = when (bucket) {
        SearchBucket.ARTISTS -> "Show all $total artists"
        SearchBucket.ALBUMS -> "Show all $total albums to pull"
    }

    /**
     * The one-line note under the field about the state of the catalogue lane,
     * or null when there is nothing worth saying.
     *
     * REQUIREMENTS.md rule 4: "Offline, or when the session has expired, show
     * library results only and state plainly that catalogue search needs a
     * connection." And, on `service_status`: it "should surface as a quiet
     * inline note, not an error dialog". Both are this one line. Nothing here
     * ever blocks, dismisses or replaces the results — the library half of the
     * screen is unaffected by everything this note can report.
     */
    val catalogueBanner: SearchBanner?
        get() = when (val lane: CatalogueLaneState = catalogue) {
            // Before the 300 ms debounce elapses, and for a query too short to
            // send. Saying "not started yet" would be noise about a wait the
            // user cannot perceive.
            CatalogueLaneState.Idle -> null

            CatalogueLaneState.Loading -> SearchBanner(
                // "Your library results are already below" is a claim about the
                // screen, and it was drawn over six empty skeleton rows: the
                // state where neither lane has answered is exactly the state
                // where the sentence is false. It is added only once there is
                // something below to point at, which is the state
                // `search-catalogue-loading-phone.png` shows and the only one it
                // was ever written for.
                message = if (results.isEmpty) {
                    CATALOGUE_SEARCHING
                } else {
                    CATALOGUE_SEARCHING_WITH_RESULTS
                },
                kind = SearchBannerKind.PROGRESS,
            )

            is CatalogueLaneState.Ready -> {
                val status: ServiceStatus? = lane.serviceStatus
                if (status == null || !status.isDegraded) {
                    null
                } else {
                    SearchBanner(
                        // The server's own words when it supplied any, because it
                        // knows what upstream is doing and this app does not.
                        message = status.message?.takeIf { it.isNotBlank() } ?: CATALOGUE_DEGRADED,
                        kind = SearchBannerKind.DEGRADED,
                    )
                }
            }

            is CatalogueLaneState.Unavailable -> when (val error: NeedlerError = lane.error) {
                is NeedlerError.Offline -> SearchBanner(
                    // The honest form depends on what is under the banner. With
                    // owned rows only, "this is your library only" is the whole
                    // truth. With un-owned rows from the last sync under it -
                    // which is the screen `search-offline-cached-catalogue` draws,
                    // captioned "from your last sync" - the same sentence
                    // contradicts the block 1300px below it.
                    message = if (catalogueAlbums.isEmpty()) {
                        CATALOGUE_OFFLINE
                    } else {
                        CATALOGUE_OFFLINE_CACHED
                    },
                    kind = SearchBannerKind.OFFLINE,
                )

                NeedlerError.SessionExpired -> SearchBanner(
                    message = CATALOGUE_SESSION_EXPIRED,
                    kind = SearchBannerKind.EXPIRED,
                    // The sentence named Settings and gave the reader nothing to
                    // press. The remedy is two taps away and the banner is where
                    // the user is standing, so it carries the way there.
                    action = SearchBannerAction.SIGN_IN,
                )

                else -> SearchBanner(message = problemMessage(error), kind = SearchBannerKind.PROBLEM)
            }
        }

    /**
     * The banner's sentence on its own.
     *
     * Kept as a property of its own because the copy is the thing that has lied
     * on this screen twice, and a test that reads one string is the cheapest
     * guard there is against it lying a third time.
     */
    val catalogueNote: String? get() = catalogueBanner?.message

    /** True when the catalogue note is bad news rather than progress, which the screen tints. */
    val catalogueNoteIsProblem: Boolean get() = catalogue is CatalogueLaneState.Unavailable

    /**
     * The confirm button on the pull sheet: **Pull**, or **Queue the pull** with
     * no connection.
     *
     * REQUIREMENTS.md "Failure handling": "Offline is a first-class state, not
     * an error. When the server is unreachable, the app plays on-device music,
     * browses the full mirror, and queues pulls, playlist edits, favourites and
     * scrobbles for replay." A queued pull is therefore a supported outcome and
     * not a failure — but it is a *different* outcome from one the server hears
     * about now, and this button is the last thing read before the user chooses
     * it.
     *
     * Said before the tap, which is the whole point. The app already words the
     * outcome afterwards — `SearchNotice.forRequest` handles
     * [RequestStatus.QUEUED_OFFLINE] — and a consequence announced only once it
     * has happened is one the user had no chance to take into account.
     *
     * Rejected: changing the **Pull** button on every result row instead. There
     * are up to eight of them on screen, the row button is the tap that opens
     * this sheet rather than the one that writes anything, and relabelling all
     * of them would turn one quiet statement into a column of them.
     */
    val pullConfirmLabel: String get() = if (offline) QUEUE_PULL_LABEL else PULL_LABEL

    /**
     * The caption line on the pull sheet: where the record lands, — with no
     * connection — when it will be asked for, and what will be downloaded.
     *
     * The sheet has one caption slot, so the three facts are ordered by how much
     * the user can still decide with them. [PULL_DESTINATION] leads and is always
     * said, because it is the question the sheet never answered and the answer
     * does not depend on anything. The offline sentence comes next: it changes
     * *when* the request goes out, which is the other thing the tap buys.
     * The server's own `quality_snapshot_summary` is last — it is the most
     * precise of the three and the least decisive, and it describes a download
     * nobody has been asked for yet.
     *
     * Never null while a sheet is open, which means the sheet's own
     * [app.needler.core.design.component.PULL_SHEET_EXPLANATION] fallback is
     * never reached from this screen. That is the point: the fallback says the
     * server "imports it into your library", and the whole of [PULL_DESTINATION]
     * is about why that sentence is the ambiguous one here.
     *
     * Rejected: a parameter of its own on the sheet. `:core:design` offers one
     * caption there and has three callers — this screen and two in
     * `:feature:library` — so a second slot would be a shared-component change
     * made for one screen, and the two sentences read as one line.
     */
    val pullSheetNote: String?
        get() {
            // No sheet, nothing for the line to be about. Guarded rather than
            // left to the screen, which only reads this inside the `!= null`
            // branch that draws the sheet: a property that answered for a sheet
            // that is not open would be true of nothing and assertable as such.
            val album: Album = pullSheetAlbum ?: return null
            val quality: String? = album.qualityPolicySummary?.takeIf { it.isNotBlank() }
            val parts: List<String> = buildList {
                // Where it lands, first and always. The sheet's own fallback
                // copy says the server "imports it into your library", and
                // "your library" is the one phrase on this screen that does not
                // say which of the two places a record can be: REQUIREMENTS.md
                // "Vocabulary" fixes Server and Device as different states, the
                // badges beside every row draw them in different hues, and the
                // sheet was the only surface naming neither. A pull reaches the
                // server; getting it onto the phone is a second, separate act.
                add(PULL_DESTINATION)
                if (offline) add(PULL_QUEUED_OFFLINE)
                if (quality != null) add(quality)
            }
            return parts.joinToString(separator = " ")
        }

    /**
     * `19 in the crate · 1 hr 14 min`, or null when the crate is empty.
     *
     * Drawn under a notice that says something went into the crate, and nowhere else:
     * adding to a queue with no visible change is indistinguishable from a tap that did
     * not register, and these are the two figures REQUIREMENTS.md "Queue" asks the crate
     * screen itself to carry. Live from [crateTrackCount], so it corrects itself when the
     * session confirms rather than reporting what this screen expected.
     */
    val crateLine: String? get() = SearchFormat.crateLine(crateTrackCount, crateDurationMs)
}

/**
 * The line under the field about the catalogue lane: what it says, which of four
 * conditions it is reporting, and what the user can do about it.
 *
 * ## Why the kind is on the state and not decided by the screen
 *
 * Four unlike conditions — a search in progress, a degraded upstream, an expired
 * sign-in and no connection — were drawn as the identical rounded grey block
 * with no icon, no action and no difference in weight, so the one state the user
 * has to act on looked exactly like the one they only have to wait out. Which
 * condition a banner reports is a fact about the lane, which is what this type
 * carries; how heavily each is drawn is [SearchBannerKind]'s own table in
 * `SearchScreen`.
 *
 * REQUIREMENTS.md "Failure handling" still applies to all four: none of them is
 * a dialog, none blocks, and none hides the library results below, because the
 * local lane made no network call and has nothing for a connection to break.
 */
data class SearchBanner(
    val message: String,
    val kind: SearchBannerKind,
    /** The one thing the user can do about it, or null when there is nothing to offer. */
    val action: SearchBannerAction? = null,
)

/**
 * Which condition a [SearchBanner] reports.
 *
 * Ordered by how much it asks of the reader: [PROGRESS] asks for nothing,
 * [DEGRADED] warns, and the last three say a capability is missing until
 * something changes.
 */
enum class SearchBannerKind {
    /** The catalogue lane is in flight. It will resolve itself. */
    PROGRESS,

    /** MusicBrainz answered and said it is struggling, so the results may be short. */
    DEGRADED,

    /** The sign-in expired. Nothing will search the catalogue until it is renewed. */
    EXPIRED,

    /** No connection. The library half is untouched; see REQUIREMENTS.md "Failure handling". */
    OFFLINE,

    /** Anything else the server named, in its own words. */
    PROBLEM,
}

/**
 * What a banner offers to do about itself.
 *
 * An enum rather than a lambda on the banner because [SearchUiState] is a value
 * the screenshot tests build literally and compare: a state carrying a function
 * is one no test can assert on and no `data class` can compare. The screen turns
 * the member into the one callback the route wired for it.
 */
enum class SearchBannerAction {
    /**
     * Take the user to Settings, where the session is renewed.
     *
     * REQUIREMENTS.md "Failure handling" has an expired session leaving the
     * library searchable and the catalogue not, which makes signing in again the
     * only thing that restores half this screen — and the banner was naming it
     * in prose with nothing tappable beside it.
     */
    SIGN_IN,
}

/**
 * Something the screen has to tell the user after an action.
 *
 * Deliberately a small data class rather than the sealed hierarchy
 * `:feature:library` uses for album detail. That screen has eight distinct
 * outcomes with eight different next steps — downloads, pins, retries — because
 * it is where all of them happen. Search has exactly one action, **Pull**, so a
 * sealed interface here would model a variety this screen does not have.
 */
data class SearchNotice(
    val message: String,
    /** True for the ones that are bad news, which the screen tints differently. */
    val isProblem: Boolean = false,
    /**
     * True when this notice is about the crate, so the screen draws
     * [SearchUiState.crateLine] under it.
     *
     * A flag rather than the line itself, because the figures have to be read at render
     * time: the session applies the add asynchronously, and a count baked into the notice
     * would be this screen's guess at a number the controller owns.
     */
    val showsCrate: Boolean = false,
) {
    companion object {
        /**
         * Tracks went into the crate, and which of the three ways it happened.
         *
         * The same three outcomes, in the same words, as `AlbumNotice.AddedToCrate` in
         * `:feature:library` - the wording is chosen to agree for one track as well as
         * twenty, so there is one sentence per outcome rather than a singular and a plural
         * of each. [started] is the case `PlaybackController.enqueue` cannot cover: it never
         * starts sound, so an add to an empty crate would otherwise leave silence and a
         * screen that looked like it had ignored the tap.
         */
        fun addedToCrate(
            trackCount: Int,
            playNext: Boolean,
            started: Boolean,
        ): SearchNotice {
            val tracks: String = SearchFormat.plural(trackCount.toLong(), "track")
            val message: String = when {
                started -> "The crate was empty, so " + tracks + " started playing."
                playNext -> "Added " + tracks + " to the crate, to play next."
                else -> "Added " + tracks + " to the crate."
            }
            return SearchNotice(message = message, showsCrate = true)
        }

        /**
         * What to say about a request the server has accepted, refused or
         * parked.
         *
         * REQUIREMENTS.md: "Needler must render the status the server returned
         * rather than inferring it from the cached role, because the role may
         * have changed moments earlier." So this maps the server's own
         * [RequestStatus] and never consults the user's role.
         */
        fun forRequest(status: RequestStatus): SearchNotice = when (status) {
            RequestStatus.ACCEPTED ->
                SearchNotice("Pulling. Track it on the Pulls tab; you will be told when it lands.")

            RequestStatus.PENDING_APPROVAL ->
                SearchNotice("Requested. An administrator has to approve this before it is acquired.")

            RequestStatus.QUEUED_OFFLINE ->
                SearchNotice(
                    "No connection, so this pull is queued. It is sent as soon as you are back " +
                        "online.",
                )

            RequestStatus.ALREADY_PRESENT ->
                SearchNotice("This album is already in your library.")

            RequestStatus.REJECTED ->
                SearchNotice("The server rejected this request.", isProblem = true)
        }

        /** A pull in flight was stopped from its row's `⋯`. */
        fun pullStopped(albumTitle: String): SearchNotice =
            SearchNotice("Stopped pulling " + SearchFormat.albumTitle(albumTitle) + ".")
    }
}

/**
 * Whether the server has taken this request on.
 *
 * The three that mean "placed" are the three that change what the row should
 * draw: the record is being acquired, waiting for an administrator, or waiting
 * for a connection. [RequestStatus.ALREADY_PRESENT] and
 * [RequestStatus.REJECTED] are deliberately not among them — the first leaves a
 * row that was already owned, and the second leaves one the user may reasonably
 * try again, so suppressing its **Pull** would strand them.
 */
internal val RequestStatus.isPlaced: Boolean
    get() = when (this) {
        RequestStatus.ACCEPTED,
        RequestStatus.PENDING_APPROVAL,
        RequestStatus.QUEUED_OFFLINE,
        -> true

        RequestStatus.ALREADY_PRESENT,
        RequestStatus.REJECTED,
        -> false
    }

/**
 * The row under a capped results block.
 *
 * One type for four different things — show all, load more, loading, and a quiet
 * failure — because the block draws exactly one row there and the screen should
 * not be the thing that decides which. See [SearchUiState.moreRow].
 */
data class SearchMoreRow(
    val label: String,
    /** What a tap means. The state decides this, not the screen. */
    val action: SearchMoreAction = SearchMoreAction.LOAD_MORE,
    /** False while a page is in flight, and for a note that is not a retry. */
    val enabled: Boolean = true,
    /** True when the label is bad news, which the screen tints differently. */
    val isProblem: Boolean = false,
)

/**
 * The two things the row under a block can do.
 *
 * They are genuinely different operations and not one with a flag: [SHOW_ALL]
 * reveals rows that are already in hand and works offline, while [LOAD_MORE] is a
 * call to the server through the bucket endpoint of REQUIREMENTS.md rule 5. A
 * single "more" intent would have made the first one wait on a network it does
 * not need.
 */
enum class SearchMoreAction {
    SHOW_ALL,
    LOAD_MORE,
}

/**
 * How many artists a collapsed block shows.
 *
 * Four, because the artist block sits above the albums and the songs and its job
 * is to answer "is this the artist I meant", not to be browsed. A search for
 * "wonder" has one owned artist and two or three catalogue namesakes, and four
 * rows holds that whole answer without pushing the library albums off the
 * screen.
 */
internal const val ARTIST_PREVIEW: Int = 4

/**
 * How many un-owned albums a collapsed block shows.
 *
 * Eight. This block is last precisely because it is the longest and the least
 * urgent, and the cap keeps a twenty-row MusicBrainz tail from being the end of
 * every search.
 */
internal const val CATALOGUE_ALBUM_PREVIEW: Int = 8

/** The label on the owned-albums block. Vocabulary: "Server — on the server, not on the device". */
internal const val LIBRARY_ALBUMS_HEADER: String = "Albums"

/** The label on the un-owned block. Vocabulary: "Pull — ask the server to acquire an album". */
internal const val CATALOGUE_ALBUMS_HEADER: String = "Albums to pull"

internal const val MORE_FROM_CATALOGUE: String = "More from MusicBrainz"

internal const val LOOKING_FOR_MORE: String = "Looking for more in MusicBrainz…"

/**
 * What the row says once a page comes back short.
 *
 * Said rather than left silent: the affordance the user just tapped has to
 * disappear, and a control that vanishes with no word looks like a bug. The
 * query is quoted because "everything" is only true of this search.
 */
internal fun catalogueExhaustedNote(query: String): String =
    "That is everything MusicBrainz has for \"" + query.trim() + "\"."

/** A failed page is a retry, not a dead end, so the line says what to do about it. */
internal fun pageFailedNote(problem: String): String = "$problem Tap to try again."

/**
 * Both lanes ran and neither matched.
 *
 * The only case in which this app may report a MusicBrainz negative, because it
 * is the only case in which MusicBrainz gave it one. The advice is attached to
 * this form alone: shortening a query is what helps when the search really did
 * run, and offering it when half the search never happened would send the user
 * to retype something that was never the problem.
 */
internal fun nothingFoundInEitherLane(query: String): String =
    "Nothing in your library or in the MusicBrainz catalogue matches \"" + query.trim() +
        "\". A shorter query, or the artist's name on its own, usually finds more."

/**
 * The library ran, the catalogue did not.
 *
 * It states what was searched, what was not, and what that costs, then stops.
 * The line directly above it is [SearchUiState.catalogueNote], which is where
 * the reason lives and is why no reason is given twice.
 */
internal fun nothingFoundInLibraryOnly(query: String): String =
    "Nothing in your library matches \"" + query.trim() + "\". The MusicBrainz catalogue was " +
        "not searched, and it is the half that holds everything you do not own yet, so there " +
        "may be more to find."

/** The pack's caption on the ALBUMS header once MusicBrainz has answered. */
internal const val FROM_CATALOGUE: String = "from MusicBrainz"

/** The same caption when only the mirror has contributed, which is what the list then holds. */
internal const val FROM_LIBRARY: String = "in your library"

/**
 * The caption on the to-pull block when the catalogue lane has not answered.
 *
 * "sync" rather than "cache" or "mirror": it is the word this screen already
 * uses to the user, in [CATALOGUE_OFFLINE]'s "everything already synced is
 * searchable", and the only one of the three that is not internal vocabulary.
 */
internal const val FROM_LAST_SYNC: String = "from your last sync"

/**
 * The confirm button on the pull sheet with a connection.
 *
 * Repeats `NeedlerRequestSheet`'s own default rather than relying on it: this
 * screen now has two labels for that button and the choice between them belongs
 * in one place, next to the one it picks instead.
 */
internal const val PULL_LABEL: String = "Pull"

/** The same button with no connection. The verb changes because the outcome does. */
internal const val QUEUE_PULL_LABEL: String = "Queue the pull"

/**
 * Where a pulled record ends up, said before the tap that asks for it.
 *
 * `NeedlerRequestSheet`'s own fallback copy says Dropped Needle "imports it into
 * your library", and on this screen "your library" is the one phrase that does
 * not say which half: REQUIREMENTS.md "Vocabulary" fixes **Server** and
 * **Device** as different states, every row on the list behind the sheet wears
 * one of the two in its own hue, and the sheet named neither. A reader who had
 * learned the row vocabulary could reasonably read a green confirm button over
 * "your library" as "this will be on my phone".
 *
 * It will not be. A pull is a request to the server, and REQUIREMENTS.md
 * "Album states" makes getting the result onto the device a separate act with a
 * separate badge. So the first thing the caption says is which of the two this
 * buys, and the second is that the other one is still available afterwards -
 * because the honest answer to "will I have this offline" is "not yet, and
 * here is how".
 *
 * It replaces the sheet's fallback rather than preceding it: two sentences about
 * sources and formats under two about destinations is more caption than a sheet
 * with one slot can hold, and the server's own `quality_snapshot_summary`
 * follows this whenever there is one, which is the only account of what will be
 * downloaded that is guaranteed to be true.
 */
internal const val PULL_DESTINATION: String =
    "It lands on the server, where it plays at once. Keeping a copy on this device is a " +
        "separate step afterwards."

/**
 * What the sheet says a pull placed offline will do, before it is placed.
 *
 * The same two facts, in the same order and nearly the same words, as the notice
 * `SearchNotice.forRequest` draws afterwards for
 * [app.needler.core.domain.model.RequestStatus.QUEUED_OFFLINE] - queued now,
 * sent on reconnect. Deliberately not the identical string: this one is about
 * what a tap would do and that one reports what a tap did, and a sentence that
 * has to read correctly in both tenses ends up reading well in neither.
 */
internal const val PULL_QUEUED_OFFLINE: String =
    "No connection, so this pull is queued on the device and sent as soon as you are back online."

/**
 * The catalogue lane is in flight and the library lane has not answered either.
 *
 * It says what is happening and stops. The longer form below claims something
 * about the screen, and on this one there is nothing on the screen yet to claim
 * it about.
 */
internal const val CATALOGUE_SEARCHING: String = "Searching the MusicBrainz catalogue."

/**
 * The same wait, with library results already drawn under it.
 *
 * The second sentence is the useful half — it tells a reader that the rows below
 * are not what is being waited for — and it is true only here. Drawn over the
 * six skeleton rows of a search that has returned nothing yet, it was a plain
 * falsehood, which is why there are two constants rather than one.
 */
internal const val CATALOGUE_SEARCHING_WITH_RESULTS: String =
    "Searching the MusicBrainz catalogue. Your library results are already below."

internal const val CATALOGUE_OFFLINE: String =
    "No connection, so this is your library only. Catalogue search needs one; everything " +
        "already synced is searchable and plays as usual."

/**
 * The same condition with un-owned rows from the mirror under it.
 *
 * [CATALOGUE_OFFLINE] says "this is your library only", and the screen it was
 * drawn on also held an `ALBUMS TO PULL / from your last sync` block — records
 * that are, by construction, not in the library. The caption on that block was
 * honest and the banner above it was not.
 *
 * So the offline banner says which half is missing rather than claiming the
 * whole screen: the records already synced are searchable and pullable, and what
 * no connection costs is everything synced since. The word "sync" matches
 * [FROM_LAST_SYNC], which is the caption the reader meets 1300px further down.
 */
internal const val CATALOGUE_OFFLINE_CACHED: String =
    "No connection. Your library and everything from your last sync are below; the rest of " +
        "the MusicBrainz catalogue needs one."

internal const val CATALOGUE_SESSION_EXPIRED: String =
    "Your sign-in has expired, so only your library is searched. Sign in again from Settings " +
        "to search the catalogue."

internal const val CATALOGUE_DEGRADED: String =
    "MusicBrainz is degraded right now, so catalogue results may be short. Your library " +
        "results are unaffected."

package app.needler.feature.search.search

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.CatalogueLaneState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.SearchBucket
import app.needler.core.domain.model.SearchSuggestion
import app.needler.core.domain.model.ServiceStatus
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.UnifiedSearchResults
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

    /** `Artist` for one, `Artists` for several — the pack writes the singular on screens 03 and 10. */
    val artistsHeader: String get() = if (artists.size == 1) "Artist" else "Artists"

    /**
     * The quiet caption on the right of the ALBUMS header.
     *
     * The pack writes "from MusicBrainz" (03, 10), and that is right whenever
     * the catalogue lane actually answered. When it did not — offline, stale
     * session, still in flight — the list holds library rows only, and claiming
     * MusicBrainz for them would be a small, constant lie on the one screen
     * whose whole job is to be honest about which lane a result came from.
     *
     * It captions the *to pull* block. The library block is captioned
     * [FROM_LIBRARY] unconditionally, because that is what being in the library
     * means.
     */
    val albumsSourceNote: String
        get() = if (catalogue is CatalogueLaneState.Ready) FROM_CATALOGUE else FROM_LIBRARY

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
    val catalogueNote: String?
        get() = when (val lane: CatalogueLaneState = catalogue) {
            // Before the 300 ms debounce elapses, and for a query too short to
            // send. Saying "not started yet" would be noise about a wait the
            // user cannot perceive.
            CatalogueLaneState.Idle -> null

            CatalogueLaneState.Loading -> CATALOGUE_SEARCHING

            is CatalogueLaneState.Ready -> {
                val status: ServiceStatus? = lane.serviceStatus
                if (status == null || !status.isDegraded) {
                    null
                } else {
                    // The server's own words when it supplied any, because it
                    // knows what upstream is doing and this app does not.
                    status.message?.takeIf { it.isNotBlank() } ?: CATALOGUE_DEGRADED
                }
            }

            is CatalogueLaneState.Unavailable -> when (val error: NeedlerError = lane.error) {
                is NeedlerError.Offline -> CATALOGUE_OFFLINE
                NeedlerError.SessionExpired -> CATALOGUE_SESSION_EXPIRED
                else -> problemMessage(error)
            }
        }

    /** True when the catalogue note is bad news rather than progress, which the screen tints. */
    val catalogueNoteIsProblem: Boolean get() = catalogue is CatalogueLaneState.Unavailable
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
) {
    companion object {
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
    }
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

/** The label on the owned-albums block. Vocabulary: "In library — owned by the server". */
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

/** The pack's caption on the ALBUMS header once MusicBrainz has answered. */
internal const val FROM_CATALOGUE: String = "from MusicBrainz"

/** The same caption when only the mirror has contributed, which is what the list then holds. */
internal const val FROM_LIBRARY: String = "in your library"

internal const val CATALOGUE_SEARCHING: String =
    "Searching the MusicBrainz catalogue. Your library results are already below."

internal const val CATALOGUE_OFFLINE: String =
    "No connection, so this is your library only. Catalogue search needs one; everything " +
        "already synced is searchable and plays as usual."

internal const val CATALOGUE_SESSION_EXPIRED: String =
    "Your sign-in has expired, so only your library is searched. Sign in again from Settings " +
        "to search the catalogue."

internal const val CATALOGUE_DEGRADED: String =
    "MusicBrainz is degraded right now, so catalogue results may be short. Your library " +
        "results are unaffected."

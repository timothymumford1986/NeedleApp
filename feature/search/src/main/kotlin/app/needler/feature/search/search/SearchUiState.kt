package app.needler.feature.search.search

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.CatalogueLaneState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.RequestStatus
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
 * ## One list, not two
 *
 * [results] is a [UnifiedSearchResults], which is already merged. REQUIREMENTS.md
 * "Search behaviour", rule 3: "Merge on release-group MBID. An album present
 * locally takes the local record and is badged as owned; the catalogue copy is
 * discarded." That merge happens in `UnifiedSearchUseCase`, below this screen
 * and below the ViewModel, so there is deliberately **no** `localAlbums` /
 * `catalogueAlbums` pair here and no branch on the screen that asks which lane a
 * row came from. A row knows its [app.needler.core.domain.model.AlbumState] and
 * that is the whole of it.
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

    /** The result of the last action, or an explanation the screen owes the user. */
    val notice: SearchNotice? = null,
) {

    val artists: List<Artist> get() = results.artists

    /** Owned and un-owned in one list, already merged on release-group MBID. */
    val albums: List<Album> get() = results.albums

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
     */
    val albumsSourceNote: String
        get() = if (catalogue is CatalogueLaneState.Ready) FROM_CATALOGUE else FROM_LIBRARY

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

@file:OptIn(ExperimentalCoroutinesApi::class)

package app.needler.feature.search.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.SearchSuggestion
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.UnifiedSearchResults
import app.needler.core.domain.model.getOrDefault
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SearchRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.domain.usecase.RequestAlbumUseCase
import app.needler.core.domain.usecase.UnifiedSearchUseCase
import app.needler.feature.search.common.hasPlayableFile
import app.needler.feature.search.common.problemMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the one unified search field.
 *
 * REQUIREMENTS.md "Search behaviour" numbers six rules. Five of them are
 * already settled below this class, and that is the point of how thin this
 * ViewModel is:
 *
 *  1. local FTS on the first keystroke with no network call — `searchLocal` is a
 *     `Flow` and [UnifiedSearchUseCase] collects it the moment the query
 *     changes;
 *  2. the catalogue after a 300 ms debounce — [UnifiedSearchUseCase] owns that
 *     delay, and [flatMapLatest] restarts it on every keystroke, which is what
 *     makes it a debounce rather than a throttle;
 *  3. merge on release-group MBID — `UnifiedSearchUseCase.merge`, in
 *     `:core:domain`;
 *  4. offline or expired session shows library results only, and says so — the
 *     use case reports it as `CatalogueLaneState.Unavailable` and
 *     [SearchUiState.catalogueNote] turns that into a sentence;
 *  6. cover art per lane — an `ArtworkRef` carried on each result and resolved
 *     by `:app`'s Coil mapper.
 *
 * What is left here is rule 2's other half, the `suggest` call, and the
 * plumbing: one query flow, recent searches, and the single action this screen
 * offers.
 *
 * ## Rule 5 is deliberately not implemented
 *
 * "Paginate a single bucket through `GET /api/v1/search/{artists|albums}`" is
 * `SearchRepository.searchCatalogueBucket`, and nothing calls it. Neither
 * screen 03 nor screen 10 draws a "more results" affordance, a bucket screen or
 * an infinite list — both show one capped block per kind — so there is nowhere
 * in the design for a second page to go, and building the paging without the
 * screen that reveals it would be building a mechanism no one can reach. It is
 * in the handover notes: a "See all albums" row under the Albums block is the
 * natural home, and the repository call it needs already exists.
 *
 * ## Why the query is not debounced for the local lane
 *
 * It deliberately is not. Rule 1 says library results appear on the *first*
 * keystroke, and a debounce in front of [flatMapLatest] would delay both lanes
 * equally. The only debounced things are the two that cost a network round
 * trip: the catalogue search, inside the use case, and [suggestions], below.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val search: SearchRepository,
    library: LibraryRepository,
    pulls: PullRepository,
    sessions: SessionRepository,
    private val playback: Optional<PlaybackController>,
) : ViewModel() {

    /**
     * Search is the one screen that genuinely needs both lanes, and
     * [UnifiedSearchUseCase] is the one class allowed to know that. It is
     * constructed rather than injected for the same reason `AlbumViewModel`
     * constructs its use cases: a use case is a plain class over two repository
     * interfaces, so a Hilt binding for it would be a line of ceremony that
     * makes the graph larger and tells no one anything.
     */
    private val unifiedSearch = UnifiedSearchUseCase(search, sessions)

    private val requestAlbum = RequestAlbumUseCase(library, pulls, sessions)

    private val query = MutableStateFlow("")
    private val busy = MutableStateFlow(false)
    private val notice = MutableStateFlow<SearchNotice?>(null)

    /**
     * The trimmed query, changing no more often than the text actually does.
     *
     * `distinctUntilChanged` after the trim matters: typing a trailing space
     * would otherwise cancel the in-flight catalogue call and restart the 300 ms
     * debounce for a query that is, to the server, identical.
     */
    private val committedQuery: Flow<String> =
        query.map { raw -> raw.trim() }.distinctUntilChanged()

    private val results: Flow<UnifiedSearchResults> =
        committedQuery.flatMapLatest { text -> unifiedSearch(text) }

    /**
     * Completions, debounced by hand.
     *
     * The same 300 ms as the catalogue lane, and for the same reason: this is a
     * network round trip and a fast typist would otherwise fire one per
     * keystroke. It is written as a `flow { delay(...) }` inside
     * [flatMapLatest] rather than with the `debounce` operator because that
     * operator is still opt-in preview API, and this is three lines.
     *
     * Failures are swallowed to an empty list on purpose. A completion is a
     * convenience on top of a search that has already returned its real answer;
     * a failed `suggest` has nothing to say to the user that
     * [SearchUiState.catalogueNote] is not already saying about the same
     * connection.
     */
    private val suggestions: Flow<List<SearchSuggestion>> =
        committedQuery.flatMapLatest { text ->
            if (text.length < MIN_SUGGEST_LENGTH) {
                flowOf(emptyList<SearchSuggestion>())
            } else {
                flow<List<SearchSuggestion>> {
                    // Clear the previous query's completions at once rather than
                    // leaving them under a field that no longer matches them.
                    emit(emptyList())
                    delay(SUGGEST_DEBOUNCE_MS)
                    emit(search.suggest(text, SUGGEST_LIMIT).getOrDefault(emptyList()))
                }
            }
        }

    private val nowPlayingKey: Flow<TrackKey?> =
        playback.orElse(null)
            ?.observeState()
            ?.map { playbackState -> playbackState.currentItem?.track?.key }
            ?: flowOf(null)

    private val lanes: Flow<Lanes> = combine(
        results,
        suggestions,
        search.observeRecentQueries(RECENT_QUERY_LIMIT),
    ) { merged, completions, recents ->
        Lanes(results = merged, suggestions = completions, recentQueries = recents)
    }

    private val status: Flow<Status> = combine(
        sessions.observeConnectivity(),
        nowPlayingKey,
    ) { connectivity, playingKey ->
        Status(offline = !connectivity.isOnline, nowPlayingTrackKey = playingKey)
    }

    val state: StateFlow<SearchUiState> = combine(
        query,
        lanes,
        status,
        busy,
        notice,
    ) { text, lane, current, isBusy, currentNotice ->
        SearchUiState(
            query = text,
            results = lane.results,
            recentQueries = lane.recentQueries,
            suggestions = lane.suggestions,
            nowPlayingTrackKey = current.nowPlayingTrackKey,
            offline = current.offline,
            busy = isBusy,
            notice = currentNotice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = SearchUiState(),
    )

    // ---- intents ------------------------------------------------------------

    fun onQueryChange(text: String) {
        query.value = text
        // A new search invalidates whatever the last one's action said. Leaving
        // "Pulling…" on screen while the user types something unrelated would
        // attach the message to the wrong album.
        notice.value = null
    }

    /** The clear button inside the field, and the empty state it returns to. */
    fun onClearQuery() {
        onQueryChange("")
    }

    /**
     * The keyboard's search key.
     *
     * There is nothing to submit — results are already live — so all this does
     * is record the query as recent. Recording on every keystroke would fill the
     * history with the prefixes of one search; recording when the user presses
     * *search* records what they meant.
     */
    fun onSubmitQuery() {
        recordCurrentQuery()
    }

    /** A row in the recent-searches list. Puts the text back in the field and re-runs it. */
    fun onRecentQuerySelect(text: String) {
        onQueryChange(text)
    }

    fun onClearRecentQueries() {
        viewModelScope.launch { search.clearRecentQueries() }
    }

    /**
     * A completion from `suggest`.
     *
     * [SearchSuggestion] can name a specific release group or artist, which
     * would let a tap go straight to that screen. It deliberately does not:
     * navigation is the host's business and this ViewModel cannot navigate, and
     * a suggestion that jumped past the results would skip the merge that tells
     * the user whether they already own the thing. Putting the text in the field
     * runs the real search instead, which is one extra tap and no surprises.
     */
    fun onSuggestionSelect(suggestion: SearchSuggestion) {
        onQueryChange(suggestion.text)
    }

    /**
     * A result was opened, so the query that found it is worth remembering.
     *
     * Called by the route on its way to navigating, which is the strongest
     * available signal that a search succeeded — stronger than the keyboard's
     * search key, which a user often never presses because the results arrived
     * while they were still typing.
     */
    fun onResultOpened() {
        recordCurrentQuery()
    }

    /**
     * Ask the server to acquire an un-owned album: the **Pull** button on
     * screens 03 and 10.
     *
     * The album is handed to the use case as `known`, which saves it a mirror
     * read and — more importantly — supplies the title, artist and year hints
     * the request body carries. Those are what the server uses to disambiguate a
     * release group whose MusicBrainz entry is thin, and a catalogue search
     * result is precisely the case where it often is.
     */
    fun onPull(album: Album) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val result: Outcome<RequestReceipt> =
                    requestAlbum(album.releaseGroupMbid, known = album)
                notice.value = when (result) {
                    is Outcome.Success -> SearchNotice.forRequest(result.value.status)
                    is Outcome.Failure -> SearchNotice(
                        message = problemMessage(result.error),
                        isProblem = true,
                    )
                }
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * Play one song from the Songs block.
     *
     * A track with no file behind it — a part-delivered pull — is not playable
     * and its row does not offer the tap, but the check is repeated here,
     * because a ViewModel that trusts its screen to have filtered correctly is
     * one refactor away from a crash.
     */
    fun onPlayTrack(track: Track) {
        if (!track.hasPlayableFile) return
        val controller: PlaybackController = playback.orElse(null) ?: return
        viewModelScope.launch { controller.playTracks(listOf(track)) }
    }

    fun onDismissNotice() {
        notice.value = null
    }

    // ---- internals ----------------------------------------------------------

    private fun recordCurrentQuery() {
        val text: String = query.value.trim()
        if (text.length < MIN_RECORDED_LENGTH) return
        viewModelScope.launch { search.recordRecentQuery(text) }
    }

    private data class Lanes(
        val results: UnifiedSearchResults,
        val suggestions: List<SearchSuggestion>,
        val recentQueries: List<String>,
    )

    private data class Status(
        val offline: Boolean,
        val nowPlayingTrackKey: TrackKey?,
    )

    private companion object {
        /**
         * The same 300 ms REQUIREMENTS.md gives the catalogue lane, applied to
         * the completions call for the same reason.
         *
         * Taken from [UnifiedSearchUseCase.DefaultCatalogueDebounce] rather than
         * written out again, so the two network calls rule 2 asks for on the
         * same tick cannot drift apart.
         */
        val SUGGEST_DEBOUNCE_MS: Long =
            UnifiedSearchUseCase.DefaultCatalogueDebounce.inWholeMilliseconds

        /**
         * A single character is enough to search the mirror but not enough to
         * ask for completions of: "a" completes to everything, which is the same
         * as completing to nothing.
         */
        const val MIN_SUGGEST_LENGTH: Int = 2

        /** `GET /api/v1/search/suggest`'s own default. Eight fits the no-results screen. */
        const val SUGGEST_LIMIT: Int = 8

        /** How many previous searches the empty state offers. */
        const val RECENT_QUERY_LIMIT: Int = 8

        /** One-character queries are not worth remembering; they are almost always a typo. */
        const val MIN_RECORDED_LENGTH: Int = 2

        /**
         * Keep the query alive briefly after the last subscriber leaves, so a
         * rotation, or a trip into an album and straight back, does not lose the
         * user's search and re-run both lanes from nothing.
         */
        const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L
    }
}

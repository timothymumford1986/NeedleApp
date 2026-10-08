@file:OptIn(ExperimentalCoroutinesApi::class)

package app.needler.feature.search.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.CatalogueSearchPage
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.SearchBucket
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
import app.needler.feature.search.common.addTracksToCrate
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
import kotlinx.coroutines.flow.first
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
 * What is left here is rule 2's other half, the `suggest` call, rule 5's paging,
 * and the plumbing: one query flow, recent searches, and the single action this
 * screen offers.
 *
 * ## Rule 5, and where the second page goes
 *
 * "Paginate a single bucket through `GET /api/v1/search/{artists|albums}`" is
 * `SearchRepository.searchCatalogueBucket`, which was written and then called
 * from nowhere, because neither screen 03 nor screen 10 draws a "more results"
 * affordance and there was nowhere in the design for a second page to go. There
 * is now: each block is capped at a preview, and the row under it either reveals
 * what is already in hand or asks the bucket endpoint for the next page. The cap
 * earns its place twice over — it is also what stopped a twenty-row MusicBrainz
 * tail from burying the Songs block.
 *
 * Pages are accumulated here and folded onto the merged result by
 * [UnifiedSearchUseCase.expand], so the deduplication rules live in one place
 * and a paged-in row cannot appear twice or arrive above an owned one. They are
 * dropped the moment the query changes: a page of "wonder" has nothing to say
 * about "wonderland".
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
    private val library: LibraryRepository,
    // Held, not only handed to the use case: a row whose pull is in flight now
    // offers to stop it, and `cancelRequest` is where that goes. See [onStopPull].
    private val pulls: PullRepository,
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

    /** Which buckets the user has expanded, and how far each has been paged. */
    private val paging = MutableStateFlow(SearchPaging())

    /** The album whose request sheet is open, and the `monitor_artist` flag on it. */
    private val pullTarget = MutableStateFlow<Album?>(null)
    private val monitorArtist = MutableStateFlow(false)

    /**
     * What this session's own requests came back as, keyed on release group.
     *
     * The receipt arrives from the server; the album's state in [results] only
     * changes when the next sync writes it into the mirror. Between the two the
     * row would still draw a **Pull** pill under a banner saying the pull had
     * started, and a second tap placed a second request. See
     * [SearchUiState.placedPulls].
     */
    private val placedPulls = MutableStateFlow<Map<ReleaseGroupMbid, RequestStatus>>(emptyMap())

    /**
     * Bumped to re-run a search that is already in the field.
     *
     * [committedQuery] is `distinctUntilChanged`, so setting the query to the
     * text it already holds does nothing at all — which is exactly what a retry
     * does. The tick rides alongside it into [flatMapLatest], so a retry restarts
     * both lanes without the field or the history noticing.
     */
    private val retries = MutableStateFlow(0)

    /**
     * The bucket pages fetched for the current query, in the order they arrived.
     *
     * Held as the pages themselves rather than as an already-merged list, so the
     * fold onto a fresh [UnifiedSearchResults] happens again whenever the base
     * result changes — the mirror updating mid-search, or the catalogue lane
     * landing after the first page was asked for.
     */
    private val pages = MutableStateFlow<List<CatalogueSearchPage>>(emptyList())

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
        combine(committedQuery, retries) { text, attempt -> text to attempt }
            .distinctUntilChanged()
            .flatMapLatest { (text, _) -> unifiedSearch(text) }

    /**
     * The merged result with every bucket page the user has asked for folded in.
     *
     * The fold is in [UnifiedSearchUseCase.expand] and not here: appending a page
     * means applying the same MBID and name rules the first merge applied, and a
     * second implementation of those in a ViewModel is how a paged-in row ends up
     * above an album the user already owns.
     */
    private val paged: Flow<UnifiedSearchResults> =
        combine(results, pages) { base: UnifiedSearchResults, fetched: List<CatalogueSearchPage> ->
            fetched.fold(base) { merged, page -> UnifiedSearchUseCase.expand(merged, page) }
        }

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
        paged,
        suggestions,
        search.observeRecentQueries(RECENT_QUERY_LIMIT),
        paging,
    ) { merged, completions, recents, bucketPaging ->
        Lanes(
            results = merged,
            suggestions = completions,
            recentQueries = recents,
            paging = bucketPaging,
        )
    }

    /**
     * The live crate, for the count and total duration an "added" notice is confirmed by.
     *
     * `observeQueue` and not a figure this screen keeps: `PlaybackController`'s "Who owns
     * the crate" makes the session the authority on it. With no controller bound the crate
     * reads as empty, which is also what a screenshot renders.
     */
    private val crate: Flow<PlayQueue> =
        playback.orElse(null)?.observeQueue() ?: flowOf(PlayQueue.Empty)

    private val status: Flow<Status> = combine(
        sessions.observeConnectivity(),
        nowPlayingKey,
        crate,
    ) { connectivity, playingKey, queue ->
        Status(
            offline = !connectivity.isOnline,
            nowPlayingTrackKey = playingKey,
            crateTrackCount = queue.items.size,
            crateDurationMs = queue.totalDurationMs,
        )
    }

    /**
     * The one action this screen offers, in one flow.
     *
     * Grouped for the same reason [Lanes] and [Status] are: `combine` takes five
     * flows, and the sheet's album, its toggle and the in-flight flag are three
     * facts about the same tap.
     */
    private val pull: Flow<PullSheet> = combine(
        pullTarget,
        monitorArtist,
        busy,
        placedPulls,
    ) { album, monitor, isBusy, placed ->
        PullSheet(album = album, monitorArtist = monitor, busy = isBusy, placed = placed)
    }

    val state: StateFlow<SearchUiState> = combine(
        query,
        lanes,
        status,
        pull,
        notice,
    ) { text, lane, current, sheet, currentNotice ->
        SearchUiState(
            query = text,
            results = lane.results,
            recentQueries = lane.recentQueries,
            suggestions = lane.suggestions,
            nowPlayingTrackKey = current.nowPlayingTrackKey,
            offline = current.offline,
            busy = sheet.busy,
            pullSheetAlbum = sheet.album,
            monitorArtist = sheet.monitorArtist,
            paging = lane.paging,
            notice = currentNotice,
            crateTrackCount = current.crateTrackCount,
            crateDurationMs = current.crateDurationMs,
            placedPulls = sheet.placed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = SearchUiState(),
    )

    // ---- intents ------------------------------------------------------------

    fun onQueryChange(text: String) {
        // Compared on the trimmed text, for the same reason `committedQuery` is:
        // typing a trailing space is not a new search, and throwing away the
        // pages the user paged in would be a visible loss for no reason.
        if (text.trim() != query.value.trim()) {
            pages.value = emptyList()
            paging.value = SearchPaging()
            // A receipt for one search has nothing to say about the next, and a
            // row kept out of the Pull state by a stale receipt would be a row
            // the user cannot pull.
            placedPulls.value = emptyMap()
        }
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
     * "Show all N" under a capped block.
     *
     * Local and instant: these rows are already in hand, and revealing them needs
     * no network, which is why it is a separate intent from
     * [onLoadMoreFromCatalogue] and why it still works offline.
     */
    fun onShowAll(bucket: SearchBucket) {
        val current: BucketPaging = paging.value.of(bucket)
        if (current.expanded) return
        paging.value = paging.value.with(bucket, current.copy(expanded = true))
    }

    /**
     * "More from MusicBrainz", and the retry on a page that failed: one page of
     * one bucket, per REQUIREMENTS.md rule 5.
     *
     * Three things this deliberately does not do. It does not fire while a page is
     * already in flight, because two identical calls to a slow upstream is the
     * worst possible answer to an impatient second tap. It does not apply the
     * result if the query changed while the call was open — a page of "wonder"
     * folded into a search for "wonderland" would put rows on screen that match
     * nothing in the field. And it does not advance the offset on a failure, so a
     * retry asks for the page that was lost rather than the one after it.
     */
    fun onLoadMoreFromCatalogue(bucket: SearchBucket) {
        val asked: String = query.value.trim()
        if (asked.isEmpty()) return
        val current: BucketPaging = paging.value.of(bucket)
        if (current.loading || !current.hasMore) return

        paging.value = paging.value.with(
            bucket,
            current.copy(expanded = true, loading = true, note = null, noteIsProblem = false),
        )
        viewModelScope.launch {
            val result: Outcome<CatalogueSearchPage> = search.searchCatalogueBucket(
                bucket = bucket,
                query = asked,
                limit = PAGE_SIZE,
                offset = current.nextOffset,
            )
            if (query.value.trim() != asked) return@launch
            val latest: BucketPaging = paging.value.of(bucket)
            paging.value = paging.value.with(
                bucket,
                when (result) {
                    is Outcome.Success -> {
                        pages.value = pages.value + result.value
                        latest.copy(
                            loading = false,
                            nextOffset = current.nextOffset + PAGE_SIZE,
                            hasMore = result.value.hasMore,
                            // There is no total on this endpoint, so a short page
                            // is the only end-of-list signal there is.
                            note = if (result.value.hasMore) {
                                null
                            } else {
                                catalogueExhaustedNote(asked)
                            },
                            noteIsProblem = false,
                        )
                    }

                    is Outcome.Failure -> latest.copy(
                        loading = false,
                        note = pageFailedNote(problemMessage(result.error)),
                        noteIsProblem = true,
                    )
                },
            )
        }
    }

    /**
     * The **Pull** button on screens 03 and 10: opens the request sheet.
     *
     * It does not place the request. REQUIREMENTS.md "Placing a request" requires
     * the `monitor_artist` flag to be "a secondary toggle on the request sheet",
     * and `:core:design`'s `NeedlerRequestSheet` is where that toggle lives — the
     * same sheet the library screens open, so a pull is the same thing wherever it
     * is started. Requesting is still one tap to reach, which is what the
     * requirement asks; the confirm is one thumb-movement away.
     *
     * The toggle resets per album. It is a decision about this artist, and carrying
     * a yes over to the next pull would subscribe the user to an artist they never
     * agreed to follow.
     */
    fun onPull(album: Album) {
        if (busy.value) return
        monitorArtist.value = false
        pullTarget.value = album
    }

    /** The `monitor_artist` toggle on the sheet. */
    fun onMonitorArtistChange(enabled: Boolean) {
        monitorArtist.value = enabled
    }

    /** Dismissed without requesting: the sheet's "Not now", and a tap on the scrim. */
    fun onCancelPull() {
        pullTarget.value = null
    }

    /**
     * The sheet's **Pull**: place the request.
     *
     * The album is handed to the use case as `known`, which saves it a mirror
     * read and — more importantly — supplies the title, artist and year hints
     * the request body carries. Those are what the server uses to disambiguate a
     * release group whose MusicBrainz entry is thin, and a catalogue search
     * result is precisely the case where it often is.
     *
     * The sheet stays up with its buttons inert while the call is open, and closes
     * when the server has answered, so the notice it leaves behind is read against
     * the results rather than against a sheet that is no longer about anything.
     */
    fun onConfirmPull() {
        val album: Album = pullTarget.value ?: return
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val result: Outcome<RequestReceipt> = requestAlbum(
                    releaseGroupMbid = album.releaseGroupMbid,
                    monitorArtist = monitorArtist.value,
                    known = album,
                )
                notice.value = when (result) {
                    is Outcome.Success -> {
                        // Recorded before the notice, so the row and the sentence
                        // about it change in the same emission. The three statuses
                        // below mean the server has the request; the other two -
                        // already present, and rejected - leave the row exactly as
                        // it was, which for a rejection is a Pull the user may
                        // reasonably try again.
                        val status: RequestStatus = result.value.status
                        if (status.isPlaced) {
                            placedPulls.value =
                                placedPulls.value + (album.releaseGroupMbid to status)
                        }
                        SearchNotice.forRequest(status)
                    }

                    is Outcome.Failure -> SearchNotice(
                        message = problemMessage(result.error),
                        isProblem = true,
                    )
                }
            } finally {
                busy.value = false
                pullTarget.value = null
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

    /**
     * Put one song from the Songs block into the crate, at the end or next.
     *
     * The Songs block is the local lane - catalogue search returns artists and albums only -
     * so the track in hand is a library track with a file behind it, and nothing has to be
     * fetched. The playability check is still repeated, for the reason [onPlayTrack] repeats
     * it: a ViewModel that trusts its screen to have filtered correctly is one refactor away
     * from handing the session a track that cannot play.
     */
    fun onAddTrackToCrate(track: Track, playNext: Boolean) {
        if (!track.hasPlayableFile) return
        val controller: PlaybackController = playback.orElse(null) ?: return
        viewModelScope.launch {
            notice.value = controller.addTracksToCrate(listOf(track), playNext)
        }
    }

    /**
     * Put one album from the results into the crate.
     *
     * Only an album the server already has can be queued, and the screen only offers the
     * control there: a catalogue result is metadata with no files anywhere, so its rows exist
     * in MusicBrainz and nothing else. Its action is **Pull**, which is already in the same
     * trailing slot and is mutually exclusive with this by [Album.state].
     *
     * The tracks are read from the mirror because `PlaybackController.enqueue` takes tracks and
     * an [Album] carries none. It is a Room query behind the repository interface - the mirror
     * is the read path - so it works with no connection, which is also when this screen is most
     * likely to be showing library results only.
     */
    fun onAddAlbumToCrate(album: Album, playNext: Boolean) {
        val controller: PlaybackController = playback.orElse(null) ?: return
        viewModelScope.launch {
            val tracks: List<Track> = library.observeAlbumTracks(album.releaseGroupMbid)
                .first()
                .filter { it.hasPlayableFile }
            if (tracks.isEmpty()) return@launch
            notice.value = controller.addTracksToCrate(tracks, playNext)
        }
    }

    /**
     * Stop a pull that is already running: the one action the `⋯` on a
     * **Pulling** row offers.
     *
     * Those rows were the only rows on this screen with no trailing control at
     * all. An owned row has the crate menu, an un-owned one has **Pull**, and the
     * record in between - the one the user is actually waiting on, and the only
     * one they might have asked for by mistake - had neither, so a mis-tapped
     * pull could only be undone from another tab.
     *
     * `cancelRequest` and not `cancelTask`: the user is cancelling the *request*
     * they placed, and the server owns however many download tasks it split that
     * into. The failure is reported as a notice rather than swallowed, because a
     * cancel that silently did nothing is indistinguishable from one that worked
     * until the row is still there a minute later.
     */
    fun onStopPull(album: Album) {
        viewModelScope.launch {
            when (val result: Outcome<Unit> = pulls.cancelRequest(album.releaseGroupMbid)) {
                is Outcome.Success -> {
                    placedPulls.value = placedPulls.value - album.releaseGroupMbid
                    notice.value = SearchNotice.pullStopped(album.title)
                }

                is Outcome.Failure -> notice.value = SearchNotice(
                    message = problemMessage(result.error),
                    isProblem = true,
                )
            }
        }
    }

    /**
     * Run the search in the field again.
     *
     * Offered by the banner on a search that found nothing with a lane that never
     * ran, which is the one empty state where trying again is a reasonable thing
     * to do: the library half already answered and cannot answer differently, and
     * the half that is missing is missing because of a connection that may since
     * have come back.
     *
     * The query is not touched, so the field, the caret and the recent-search
     * history all stay as they were; [retries] is what [results] restarts on.
     */
    fun onRetrySearch() {
        if (query.value.trim().isEmpty()) return
        retries.value += 1
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
        val paging: SearchPaging,
    )

    private data class Status(
        val offline: Boolean,
        val nowPlayingTrackKey: TrackKey?,
        val crateTrackCount: Int,
        val crateDurationMs: Long,
    )

    private data class PullSheet(
        val album: Album?,
        val monitorArtist: Boolean,
        val busy: Boolean,
        val placed: Map<ReleaseGroupMbid, RequestStatus>,
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

        /**
         * One page of a bucket.
         *
         * Larger than either limit the combined search of rule 2 uses, because a
         * page walks the bucket from the top and its first rows are ones already
         * on screen: a page of ten would spend most of itself on duplicates. The
         * endpoint's own default page is twenty.
         */
        const val PAGE_SIZE: Int = 25

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

@file:OptIn(ExperimentalCoroutinesApi::class)

package app.needler.feature.library.artist

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistDiscographyPage
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.CatalogueSearchPage
import app.needler.core.domain.model.SearchBucket
import app.needler.core.domain.model.FavouriteTarget
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.Track
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.repository.FavouriteRepository
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SearchRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.domain.usecase.RequestAlbumUseCase
import app.needler.core.domain.usecase.UnifiedSearchUseCase
import app.needler.feature.library.album.AlbumNotice
import app.needler.feature.library.common.RequestSheetState
import app.needler.feature.library.common.addTracksToCrate
import app.needler.feature.library.common.hasPlayableFile
import app.needler.feature.library.common.problemMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the artist screen, where the mirror and the catalogue meet.
 *
 * The two lists are fetched separately and merged on release-group MBID, which
 * is the join key the whole architecture turns on: an album the mirror knows
 * and the same album in the catalogue are one release group, so the catalogue
 * copy is simply dropped. REQUIREMENTS.md: "An album present locally takes the
 * local record and is badged as owned; the catalogue copy is discarded."
 *
 * The discography refresh is a network call and it is allowed to fail. The
 * owned half of the screen does not depend on it.
 *
 * ## Some artists have no catalogue half at all
 *
 * DroppedNeedle mints a name-derived UUID for an artist it could not match to
 * MusicBrainz, and the discography route rejects one with
 * `400 Use the local library artist route for a DroppedNeedle artist ID`. Before
 * this change every such artist produced a logged warning and a short list on
 * screen, which reads as an artist with two records rather than as an artist whose
 * discography was never obtainable. `ArtistMbid.isCatalogueIdentifier` is checked
 * before the call is made, and the screen says so in words instead.
 *
 * ## The screen may never be silent about the catalogue half
 *
 * Observed on a device at v0.0.12: an artist with four owned albums and a dozen un-owned ones showed
 * the four and nothing else - no discography, no sentence, no retry, nothing in logcat. The three
 * reasons a user might see that are "we did not try", "we could not look it up" and "that is all
 * there is", and they had exactly one appearance between them. [DiscographyLookup] is what tells
 * them apart: the error says which failure, and `settled` says whether there has been an answer at
 * all, so `ArtistUiState.discographyEmpty` can mean what it says. REQUIREMENTS.md "Library browse"
 * makes this screen "where the two lanes meet visibly"; a lane that is missing without comment is
 * not visible.
 *
 * ## The discography arrives a page at a time
 *
 * `GET /api/v1/artists/{mbid}/releases` is paged and reports `has_more`, `next_offset` and
 * `source_total_count`. All three were being discarded, so a prolific artist's discography stopped
 * silently at the endpoint's first fifty release groups. [onShowMoreDiscography] continues the walk
 * from the cursor the server named, and the counts it keeps are what let the row under the list say
 * how much of the discography has been looked up rather than implying it is all of it.
 *
 * ## This screen plays
 *
 * Play and Shuffle were missing here while the album screen had both, which the
 * device audit called out as the starker for it. An artist is not one album, so
 * `playAlbum` cannot serve: [PlaybackController.playTracks] takes the owned
 * albums' tracks in the order the screen lists them, and Shuffle hands over the
 * same list shuffled rather than switching the session's global shuffle mode on -
 * the same distinction the album screen's Shuffle makes.
 */
@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    private val favourites: FavouriteRepository,
    private val playback: Optional<PlaybackController>,
    /**
     * The catalogue lane, for one question only: who else goes by this artist's name.
     *
     * This screen does not search. It asks [onFindInCatalogue]'s single question, which is the only
     * recourse a name-derived artist has, and nothing else here touches this repository.
     */
    private val search: SearchRepository,
    pulls: PullRepository,
    sessions: SessionRepository,
) : ViewModel() {

    /** The artist this screen is showing; `:app`'s route must use [ARTIST_ID_ARG]. */
    private val artistMbid: ArtistMbid =
        ArtistMbid(requireNotNull(savedStateHandle.get<String>(ARTIST_ID_ARG)) {
            "ArtistViewModel needs an '" + ARTIST_ID_ARG + "' navigation argument"
        })

    /**
     * The name and subtitle the caller already knew, read from the route.
     *
     * Optional arguments, because most navigations here come from the Artists tab, where
     * the mirror certainly has a row. The one that does not is catalogue search: it finds
     * an artist the library has never heard of, labels the row "Not in your library yet",
     * and tapping it landed on a screen that never said the name - `refreshArtistDiscography`
     * writes album rows and no artist row, so the mirror stays empty of the artist however
     * well the fetch went.
     *
     * Read from [SavedStateHandle] rather than taken as a composable parameter, so the name
     * survives a rotation and process death. The argument names are constants here so that
     * the route builder and this cannot drift apart silently.
     */
    private val knownName: String? =
        savedStateHandle.get<String>(ARTIST_NAME_ARG)?.trim()?.takeIf { it.isNotEmpty() }

    private val knownSubtitle: String? =
        savedStateHandle.get<String>(ARTIST_SUBTITLE_ARG)?.trim()?.takeIf { it.isNotEmpty() }

    private val requestAlbum = RequestAlbumUseCase(library, pulls, sessions)

    private val busy = MutableStateFlow(false)
    private val notice = MutableStateFlow<AlbumNotice?>(null)
    private val requestSheet = MutableStateFlow<RequestSheetState?>(null)

    /**
     * What the catalogue lookup has done so far: whether it has finished, and what it said.
     *
     * One flow rather than two because the screen's question is a single one with three answers -
     * still looking, could not look, looked and there is nothing - and two booleans combined
     * elsewhere can represent a fourth that does not exist. It also keeps the state `combine` at
     * four arguments, which is the last width that has a typed overload.
     */
    private val lookup = MutableStateFlow(DiscographyLookup())

    /** The catalogue namesakes offered to a name-derived artist, and whether that look-up is running. */
    private val namesakes = MutableStateFlow(Namesakes())

    /**
     * Every playable track of every owned album, in the order the owned list is drawn.
     *
     * `flatMapLatest` over the owned albums rather than a suspend read inside the
     * content combine: the tracks of one album change when a sync lands or a
     * part-delivered pull completes, and this screen's Play button must not offer a
     * track list assembled before that. Re-subscribing when the *album list* changes
     * is the cost, and it is the right cost - a one-off read would have to be redone
     * on every album change anyway.
     *
     * Unplayable tracks are filtered out here rather than at the call site.
     * REQUIREMENTS.md "Partial content is a normal state": an owned album can have
     * holes in it, and handing the session a queue with unplayable entries in it
     * would stall playback at the first one.
     */
    private val playableTracks: Flow<List<Track>> =
        library.observeOwnedAlbumsByArtist(artistMbid).flatMapLatest { owned ->
            if (owned.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    owned.map { library.observeAlbumTracks(it.releaseGroupMbid) },
                ) { perAlbum: Array<List<Track>> ->
                    perAlbum.toList().flatten().filter { it.hasPlayableFile }
                }
            }
        }

    private val content: Flow<Content> = combine(
        library.observeArtist(artistMbid),
        library.observeOwnedAlbumsByArtist(artistMbid),
        library.observeArtistDiscography(artistMbid),
        playableTracks,
    ) { artist, owned, discography, tracks ->
        // Merge on release-group MBID: an album the mirror already has wins,
        // and the catalogue's copy of it is discarded rather than drawn twice.
        val ownedKeys: Set<String> = owned.map { it.releaseGroupMbid.value }.toSet()
        Content(
            artist = artist,
            owned = owned,
            catalogue = discography.filter { it.releaseGroupMbid.value !in ownedKeys },
            playableTracks = tracks,
        )
    }

    /**
     * The live crate, for the count and total duration the "added" notice is confirmed by.
     *
     * `observeQueue` rather than a figure this screen works out for itself: the session is the
     * authority on the crate - `PlaybackController`'s "Who owns the crate" - so the only honest
     * count is the one it has actually applied. With no controller bound the crate reads as empty,
     * which is also what a screenshot renders.
     */
    private val crate: Flow<PlayQueue> =
        playback.orElse(null)?.observeQueue() ?: flowOf(PlayQueue.Empty)

    /**
     * The flows that change because the user did something, plus the crate they changed.
     *
     * Grouped rather than combined individually because `combine` has typed
     * overloads up to five flows and this ViewModel now has more than that; a
     * `vararg` combine would type every one of them as `Any?`.
     */
    private val interaction: Flow<Interaction> = combine(
        busy,
        notice,
        requestSheet,
        crate,
        namesakes,
    ) { isBusy, currentNotice, sheet, queue, found ->
        Interaction(
            busy = isBusy,
            notice = currentNotice,
            requestSheet = sheet,
            crateTrackCount = queue.items.size,
            crateDurationMs = queue.totalDurationMs,
            namesakes = found,
        )
    }

    val state: StateFlow<ArtistUiState> = combine(
        content,
        sessions.observeConnectivity(),
        lookup,
        interaction,
    ) { current, connectivity, catalogueLookup, acted ->
        val failure: NeedlerError? = catalogueLookup.error
        ArtistUiState(
            loading = false,
            artist = current.artist,
            mbid = artistMbid,
            knownName = knownName,
            knownSubtitle = knownSubtitle,
            ownedAlbums = current.owned,
            catalogueAlbums = current.catalogue,
            discographyError = failure.takeIf { current.catalogue.isEmpty() },
            // Only when there is genuinely nothing to show. A name-derived artist can
            // still have un-owned rows in the mirror, written there by a search, and
            // telling the user the discography is unavailable while it is on screen
            // above the sentence would be worse than saying nothing.
            artistNotInCatalogue = artistMbid.isNameDerived && current.catalogue.isEmpty(),
            discographySettled = catalogueLookup.settled,
            discographyHasMore = catalogueLookup.nextOffset != null,
            loadingMoreDiscography = catalogueLookup.loadingMore,
            moreDiscographyFailed = catalogueLookup.moreFailed,
            discographyFetched = catalogueLookup.fetched,
            discographyTotal = catalogueLookup.total,
            // Kept whether or not [discographyError] was suppressed above. A discography that is
            // on screen *and* failed to refresh is a cached list that may be short, and that is a
            // different sentence from the one a screen with no discography at all needs.
            discographyFetchFailed = failure != null,
            offline = !connectivity.isOnline,
            busy = acted.busy,
            notice = acted.notice,
            requestSheet = acted.requestSheet,
            catalogueNamesakes = acted.namesakes.found,
            searchingCatalogue = acted.namesakes.searching,
            namesakeSearchFailed = acted.namesakes.failed,
            namesakeSearchDone = acted.namesakes.searched,
            playableTracks = current.playableTracks,
            crateTrackCount = acted.crateTrackCount,
            crateDurationMs = acted.crateDurationMs,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = ArtistUiState(),
    )

    init {
        refreshDiscography()
        reportMissingMirrorRow()
    }

    /**
     * Say in the log when this screen has no mirror row for its artist.
     *
     * This path failed completely silently. An artist opened from catalogue search has no
     * `artist` row - `refreshArtistDiscography` writes albums and never an artist - so the
     * screen used to declare the artist absent and blame the network, and nothing at all
     * reached logcat to say which of the several possible reasons it was. The owned-artist
     * discography failure beside it logs; this did not.
     *
     * REQUIREMENTS.md "Security" rule 1 is why the line carries what it carries: an MBID,
     * two booleans and nothing else. No URL appears in it, deliberately - the Subsonic lane
     * puts its app-password in the query string, so a logged request URL is a logged
     * credential, and the safe rule is never to log one from here at all.
     */
    private fun reportMissingMirrorRow() {
        viewModelScope.launch {
            if (library.observeArtist(artistMbid).first() != null) return@launch
            Log.w(
                TAG,
                "no mirror row for artist " + artistMbid.value +
                    "; nameFromRoute=" + (knownName != null) +
                    ", nameDerivedId=" + artistMbid.isNameDerived,
            )
        }
    }

    // ---- playback -----------------------------------------------------------

    /** Play everything this artist has in the library, in listed order. */
    fun onPlay() {
        val controller: PlaybackController = playback.orElse(null) ?: return
        val tracks: List<Track> = state.value.playableTracks
        if (tracks.isEmpty()) return
        viewModelScope.launch { controller.playTracks(tracks, startIndex = 0) }
    }

    /**
     * Shuffle everything this artist has in the library.
     *
     * The list is shuffled here and handed over in that order, rather than turning
     * on the session's shuffle mode. `PlaybackController.playAlbum` documents the
     * same distinction for the album screen's Shuffle: it "shuffles the album's own
     * tracks rather than turning on the global shuffle mode for everything that
     * follows", and a Shuffle on this screen that left the mode on would change what
     * every later Play did.
     */
    fun onShuffle() {
        val controller: PlaybackController = playback.orElse(null) ?: return
        val tracks: List<Track> = state.value.playableTracks
        if (tracks.isEmpty()) return
        viewModelScope.launch { controller.playTracks(tracks.shuffled(), startIndex = 0) }
    }

    /**
     * Play one owned album from its row, from track one.
     *
     * `playAlbum` rather than `playTracks`, unlike [onPlay]: the controller resolves the
     * album's own tracks in disc-and-track order for itself, which is the same call the
     * library grid's play affordance makes. Two screens playing one album two different
     * ways is how a crate ends up in a different order depending on where the tap came
     * from.
     */
    fun onPlayAlbum(mbid: ReleaseGroupMbid) {
        val controller: PlaybackController = playback.orElse(null) ?: return
        viewModelScope.launch { controller.playAlbum(mbid) }
    }

    /**
     * Put everything this artist has in the library into the crate, at the end or next.
     *
     * The same track list [onPlay] would play, in the same order, which is the point: a user who
     * pressed Play here and liked the order should get that order when they queue it behind
     * something else rather than a differently-assembled one.
     */
    fun onAddToCrate(playNext: Boolean) {
        addToCrate(playNext) { state.value.playableTracks }
    }

    /**
     * Put one owned album from the list into the crate.
     *
     * The tracks are read from the mirror here rather than taken from the row, because a row is an
     * [Album] and carries no tracks - and `PlaybackController.enqueue` takes tracks, where
     * `playAlbum` resolves them inside the session for itself. The read is a Room query behind the
     * repository, so it works offline like the rest of this screen.
     *
     * Unplayable tracks are filtered out for the reason `playableTracks` filters them out:
     * REQUIREMENTS.md "Partial content is a normal state" means an owned album can have holes, and
     * a crate with a hole in it stalls at the hole. An album that is nothing but holes adds nothing
     * and says nothing, exactly as its Play button does nothing.
     */
    fun onAddAlbumToCrate(mbid: ReleaseGroupMbid, playNext: Boolean) {
        addToCrate(playNext) {
            library.observeAlbumTracks(mbid).first().filter { it.hasPlayableFile }
        }
    }

    // ---- favourites ---------------------------------------------------------

    /**
     * Star or unstar this artist.
     *
     * REQUIREMENTS.md "Playlists" makes binary favourites the only mechanism this
     * server persists, and `getStarred2` returns starred artists alongside albums and
     * tracks - so an artist is as starrable as a record, and this is the only control
     * in the app that says so.
     *
     * Outside the [busy] gate, for the reasons `AlbumViewModel.onToggleFavourite`
     * sets out: the mirror is written first, so the star has already moved by the
     * time the server call goes out.
     */
    fun onToggleFavourite() {
        val artist: Artist = state.value.artist ?: return
        viewModelScope.launch {
            val result: Outcome<Unit> = favourites.setFavourite(
                target = FavouriteTarget.OfArtist(artist.mbid),
                starred = !artist.isFavourite,
            )
            if (result is Outcome.Failure) {
                notice.value = AlbumNotice.Problem(problemMessage(result.error))
            }
        }
    }

    // ---- pulls --------------------------------------------------------------

    /**
     * Open the pull sheet for one un-owned album from the discography.
     *
     * The tap no longer requests. REQUIREMENTS.md "Placing a request" puts the
     * `monitor_artist` toggle on a request sheet, and this screen is where it earns
     * its keep most obviously: the user is looking at an artist's discography, which
     * is exactly the moment "tell me about their next record" is worth asking.
     */
    fun onPull(album: Album) {
        requestSheet.value = RequestSheetState.forAlbum(album)
    }

    /**
     * Ask the server for everything by this artist that the library does not have.
     *
     * The action that turns an un-owned artist from a cul-de-sac into a screen that does
     * the thing the product exists for. REQUIREMENTS.md "Scope" lists requesting missing
     * albums as one of v1 four jobs, and catalogue search invites this tap by labelling the
     * artist "Not in your library yet"; until now the route it invited you down offered
     * nothing but Back.
     *
     * It is a batch of album requests and not an "artist request", because the server has
     * no such endpoint - REQUIREMENTS.md "Placing a request" lists album, track and batch
     * and nothing else. This is also the most natural home for `monitor_artist`: subscribing
     * to an artist future releases is exactly what you want at the moment you ask for their
     * back catalogue.
     */
    fun onPullArtist() {
        val albums: List<Album> = state.value.pullableAlbums
        if (albums.isEmpty()) return
        requestSheet.value = RequestSheetState.forArtist(
            artistName = state.value.artist?.name ?: knownName,
            albums = albums,
        )
    }

    fun onMonitorArtistChange(monitorArtist: Boolean) {
        requestSheet.value = requestSheet.value?.copy(monitorArtist = monitorArtist)
    }

    fun onDismissRequestSheet() {
        requestSheet.value = null
    }

    /**
     * Place the request, carrying the sheet's `monitor_artist` flag.
     *
     * The album is handed to the use case as `known`, which saves it a lookup
     * and — more importantly — lets it send the title, artist and year as hints
     * on the request body. The server uses those to disambiguate a release
     * group whose MusicBrainz entry is thin.
     */
    fun onConfirmRequest() {
        val sheet: RequestSheetState = requestSheet.value ?: return
        if (busy.value) return
        requestSheet.value = null
        busy.value = true
        viewModelScope.launch {
            try {
                notice.value = if (sheet.isArtistWide) placeBatch(sheet) else placeOne(sheet)
            } finally {
                busy.value = false
            }
        }
    }

    /** One album. The title, artist and year hints ride along; see [onPull]. */
    private suspend fun placeOne(sheet: RequestSheetState): AlbumNotice {
        val album: Album = sheet.albums.single()
        val result: Outcome<RequestReceipt> = requestAlbum(
            releaseGroupMbid = album.releaseGroupMbid,
            monitorArtist = sheet.monitorArtist,
            known = album,
        )
        return when (result) {
            is Outcome.Success -> AlbumNotice.forRequest(result.value.status)
            is Outcome.Failure -> AlbumNotice.Problem(problemMessage(result.error))
        }
    }

    /**
     * Every un-owned release group at once.
     *
     * `requestAll` is the use case own batch path: it drops anything already owned or
     * already in flight, and REQUIREMENTS.md "Placing a request" is emphatic that the
     * 500-item cap is a decode-time 422 the caller must chunk for itself rather than
     * something the response dead `overflow` field will report. A discography is never 500
     * releases, so no chunking happens here - but the reason that is safe is written down
     * rather than assumed.
     *
     * The notice is deliberately the plain accepted one. A batch answers `requested` and
     * `skipped` counts rather than a per-album status, so there is no single approval state
     * to report and claiming one would be inventing it.
     */
    private suspend fun placeBatch(sheet: RequestSheetState): AlbumNotice =
        when (val result: Outcome<Unit> = requestAlbum.requestAll(sheet.albums, sheet.monitorArtist)) {
            is Outcome.Success -> AlbumNotice.PullAccepted
            is Outcome.Failure -> AlbumNotice.Problem(problemMessage(result.error))
        }

    fun onDismissNotice() {
        notice.value = null
    }

    /**
     * Fetch the catalogue half, unless this artist's id cannot reach it.
     *
     * The guard is the fix for the 400 the device log was full of. It is here as well
     * as in `DefaultLibraryRepository` on purpose: the repository's copy is what keeps
     * every other caller - search, the widgets, a future following screen - from
     * making the same call, and this one is what keeps *this* screen from logging a
     * warning about a failure it already knows the answer to. Neither is redundant,
     * because a screen that asked and then explained the refusal would still have
     * spent the round trip.
     */
    fun refreshDiscography() {
        if (artistMbid.isNameDerived) {
            // Nothing to fetch, ever. `artistNotInCatalogue` is what the screen draws
            // from; there is no failure here to record and nothing to log.
            lookup.value = DiscographyLookup(settled = true, error = null)
            return
        }
        // The cursor and the counts go with it. This is the first page again, so anything the last
        // walk learned about where it had got to is about a walk that is being abandoned.
        lookup.value = lookup.value.restarting()
        viewModelScope.launch {
            val result: Outcome<ArtistDiscographyPage> =
                library.refreshArtistDiscographyPage(artistMbid, offset = FIRST_PAGE)
            // Keep the reason, not just the fact. NeedlerError's own KDoc says
            // "the distinctions matter to the UI, which is why this is a sealed
            // hierarchy rather than a message" - collapsing it to a boolean here
            // threw that away, and left a universal catalogue failure with no
            // symptom anywhere in the app beyond one sentence that could mean a
            // 404, a 500, a timeout or a parse error.
            val error: NeedlerError? = (result as? Outcome.Failure)?.error
            if (error != null) {
                // diagnostic is exactly this: "a short, non-localised description for the
                // diagnostics log", and its KDoc forbids showing it raw to the user. One
                // line here is the difference between a five-minute diagnosis and taking
                // the server apart, because this is currently the only screen in the app
                // where a v1-lane failure is visible at all.
                Log.w(TAG, "artist discography failed for " + artistMbid.value + ": " + error.diagnostic)
            }
            val page: ArtistDiscographyPage? = (result as? Outcome.Success)?.value
            lookup.value = DiscographyLookup(
                settled = true,
                error = error,
                nextOffset = page?.nextOffset,
                fetched = page?.returned ?: 0,
                total = page?.sourceTotal,
            )
        }
    }

    /**
     * Ask the catalogue for the next page of this artist's discography.
     *
     * The control the screen never had. `v1.artistReleases` was called with the endpoint's own
     * `limit = 50, offset = 0` and its `has_more` and `next_offset` were discarded, so a prolific
     * artist's discography stopped at fifty release groups with nothing on screen saying so - and the
     * sentence drawn underneath it, `CATALOGUE_COMPLETE`, told the reader that was the lot.
     * REQUIREMENTS.md "Library browse" asks this screen for "the artist's full discography".
     *
     * ## A tap, not a scroll
     *
     * Infinite scroll was the alternative, and it is what the artist page this was measured against
     * does. Rejected on what a page costs here: every one is resolved upstream against MusicBrainz,
     * which is the whole reason `warming` exists as a response field, so pages would fire from the
     * flick of a list whose rows are otherwise free. A tap also gives `source_total_count` the one
     * honest place it can be shown - [ArtistUiState.discographyMoreRow]'s label - so a reader can see
     * how much of a discography is still unfetched instead of discovering it by scrolling. It is the
     * same control `:feature:search` pages its bucket endpoint with, which is the second reason: one
     * paging gesture in the app, not two.
     *
     * [ArtistDiscographyPage.nextOffset] being null is the only stop condition, and it is the server's
     * own. The page type cannot represent "there is more and no way to ask for it" - that contradiction
     * is resolved in `DefaultLibraryRepository`, in favour of stopping - so there is no way for this to
     * loop on one offset.
     *
     * A failure leaves the cursor alone, so the row stays and tapping it asks for the same page again.
     * It is deliberately not the discography-wide error: that one says "the list you are reading is a
     * cache and may be short", and this one says "the next page did not arrive", and they name
     * different controls.
     */
    fun onShowMoreDiscography() {
        val current: DiscographyLookup = lookup.value
        val next: Int = current.nextOffset ?: return
        if (current.loadingMore) return
        lookup.value = current.copy(loadingMore = true, moreFailed = false)
        viewModelScope.launch {
            val result: Outcome<ArtistDiscographyPage> =
                library.refreshArtistDiscographyPage(artistMbid, offset = next)
            // A refresh started while this page was in flight has already put the lookup back to its
            // first page, and `restarting` clears this flag - so finding it cleared means this answer
            // belongs to a walk that was abandoned, and adding its count to the new one would report
            // a discography part-fetched twice.
            val latest: DiscographyLookup = lookup.value
            if (!latest.loadingMore) return@launch
            lookup.value = when (result) {
                is Outcome.Failure -> {
                    Log.w(
                        TAG,
                        "artist discography page at " + next + " failed for " + artistMbid.value +
                            ": " + result.error.diagnostic,
                    )
                    latest.copy(loadingMore = false, moreFailed = true)
                }
                is Outcome.Success -> latest.copy(
                    loadingMore = false,
                    moreFailed = false,
                    nextOffset = result.value.nextOffset,
                    fetched = latest.fetched + result.value.returned,
                    // Kept when a later page does not repeat it. The field is documented as null
                    // while the artist is warming upstream, and a total that vanished mid-walk would
                    // take the figure off the row the reader is looking at.
                    total = result.value.sourceTotal ?: latest.total,
                )
            }
        }
    }

    // ---- a way out of a name-derived artist ---------------------------------

    /**
     * Ask the catalogue who else goes by this artist's name.
     *
     * The recourse a name-derived artist had none of. `artistNotInCatalogue` told the user, truthfully,
     * that their server matched this artist by name rather than to MusicBrainz, and then offered
     * nothing to do about it - and the artist it says that about is, on the reporting device, the one
     * with the most records in the library. An accurate dead end is still a dead end.
     *
     * The way out exists because the *name* is still a perfectly good catalogue query even when the id
     * is useless: `GET /api/v1/search` resolves "dido" against MusicBrainz and answers with artists
     * carrying real v4 MBIDs, each of which opens an artist screen that can fetch a full discography.
     * So this screen is a dead end only for as long as nothing asks that question.
     *
     * ## Why the per-bucket endpoint and not rule 2's combined search
     *
     * [SearchRepository.searchCatalogueBucket] asks for the artists bucket alone. The combined search
     * of REQUIREMENTS.md rule 2 would answer this too and would also fetch a screenful of albums for
     * a question that is entirely about artists, doubling the upstream MusicBrainz work for rows that
     * are then thrown away. Rule 5 names this endpoint for paging, and one page of one bucket is
     * exactly what is wanted here.
     *
     * ## Rejected: re-keying the artist automatically
     *
     * The tempting version is to run this search invisibly, take the best name match and treat the
     * mirror's artist as that MBID - the discography would simply appear and nobody would have to
     * tap anything. Rejected, because it asserts an identity the server explicitly declined to
     * assert. MusicBrainz holds several artists called "Wonder" and the device found three further
     * Didos; silently binding the user's library to the wrong one would put a stranger's discography
     * under their own records, with a Pull button on every row of it. The candidates are shown and
     * the user picks, which is the same reason `UnifiedSearchUseCase.mergeArtists` keeps distinct
     * MBIDs apart rather than guessing that two artists with one name are one artist.
     *
     * Two filters on what comes back, both of which would otherwise offer a tap that leads nowhere:
     * a candidate whose own id is name-derived cannot fetch a discography either, and a candidate
     * whose name does not answer the query - the catalogue ranks on aliases and credits, which is how
     * a search for "wonder" returned "Jr. Wonder" first - is not the artist the user is looking at.
     * [UnifiedSearchUseCase.artistMatches] is the same predicate the search lane ranks with, borrowed
     * rather than written again.
     */
    fun onFindInCatalogue() {
        val name: String = (state.value.artist?.name ?: knownName)?.trim().orEmpty()
        if (name.isEmpty() || namesakes.value.searching) return
        namesakes.value = Namesakes(searching = true)
        viewModelScope.launch {
            val result: Outcome<CatalogueSearchPage> = search.searchCatalogueBucket(
                bucket = SearchBucket.ARTISTS,
                query = name,
                limit = NAMESAKE_LIMIT,
                offset = 0,
            )
            namesakes.value = when (result) {
                is Outcome.Failure -> {
                    Log.w(TAG, "catalogue namesakes failed for " + name + ": " + result.error.diagnostic)
                    Namesakes(failed = true)
                }
                is Outcome.Success -> Namesakes(
                    found = result.value.artists.filter { candidate ->
                        candidate.mbid != artistMbid &&
                            candidate.mbid.isCatalogueIdentifier &&
                            UnifiedSearchUseCase.artistMatches(name, candidate.name)
                    },
                    searched = true,
                )
            }
        }
    }

    /**
     * One add, whatever it was an add of, behind the screen's own busy gate.
     *
     * [tracksOf] is a suspending lambda rather than a list because one caller has its tracks in
     * state and the other has to read them from the mirror, and the read belongs inside the gate -
     * two adds in flight at once would interleave their rows in the crate in an order neither tap
     * asked for.
     *
     * An empty list leaves the notice alone rather than reporting an add of nothing.
     */
    private fun addToCrate(playNext: Boolean, tracksOf: suspend () -> List<Track>) {
        val controller: PlaybackController = playback.orElse(null) ?: return
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val tracks: List<Track> = tracksOf()
                if (tracks.isNotEmpty()) {
                    notice.value = controller.addTracksToCrate(tracks, playNext)
                }
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * The catalogue lookup's own state: finished or not, and what it answered.
     *
     * [settled] exists because "the catalogue returned nothing" and "the catalogue has not answered
     * yet" look identical from the album lists alone, and the screen owes the user different
     * sentences for them. Without it there is no honest way to draw the second: an artist whose owned
     * albums are on screen and whose discography is still in flight would be told it is empty, and
     * half a second later told otherwise.
     */
    private data class DiscographyLookup(
        val settled: Boolean = false,
        val error: NeedlerError? = null,
        /**
         * The offset to ask for next, straight from [ArtistDiscographyPage.nextOffset], or null when
         * the discography is complete or has not been asked for at all.
         *
         * The cursor itself rather than a `hasMore` flag beside it, so the screen's offer of another
         * page and this ViewModel's ability to ask for one cannot disagree.
         */
        val nextOffset: Int? = null,
        val loadingMore: Boolean = false,
        val moreFailed: Boolean = false,
        /** Release groups returned so far, summed over the pages fetched. */
        val fetched: Int = 0,
        /** `source_total_count`, or null while the server has not said. */
        val total: Int? = null,
    ) {
        /**
         * The same lookup, about to fetch its first page again.
         *
         * [error] is deliberately kept until the new answer lands: the sentence it draws is about the
         * last attempt, and clearing it here would blank the notice for the length of a round trip,
         * which reads as the problem having fixed itself. Everything about *where the walk had got to*
         * is dropped, because the walk is starting again - and clearing [loadingMore] is also what
         * tells a page still in flight that its answer is no longer wanted.
         */
        fun restarting(): DiscographyLookup = DiscographyLookup(settled = false, error = error)
    }

    private data class Content(
        val artist: Artist?,
        val owned: List<Album>,
        val catalogue: List<Album>,
        val playableTracks: List<Track>,
    )

    private data class Interaction(
        val busy: Boolean,
        val notice: AlbumNotice?,
        val requestSheet: RequestSheetState?,
        val crateTrackCount: Int,
        val crateDurationMs: Long,
        val namesakes: Namesakes,
    )

    /**
     * The catalogue namesake look-up's own state.
     *
     * [searched] is the same distinction [DiscographyLookup.settled] draws, for the same reason: an
     * empty [found] means "nobody by that name in the catalogue either" only once the question has
     * been asked, and before that it means nothing at all. [failed] is separate from an empty answer
     * because one is worth another tap and the other is not.
     */
    private data class Namesakes(
        val found: List<Artist> = emptyList(),
        val searching: Boolean = false,
        val searched: Boolean = false,
        val failed: Boolean = false,
    )

    companion object {
        /** The navigation argument this ViewModel reads the artist id from. */
        const val ARTIST_ID_ARG: String = "artistId"

        /**
         * Optional route argument carrying the artist name.
         *
         * `:app` has to add it - `artist/{artistId}?artistName=&artistSubtitle=` - and until it does,
         * the screen still works for every artist the mirror knows and an artist reached from
         * catalogue search is nameless again. That is the one wiring change this work needs outside
         * its own module.
         */
        const val ARTIST_NAME_ARG: String = "artistName"

        /** Optional route argument carrying the subtitle the caller showed, e.g. search own. */
        const val ARTIST_SUBTITLE_ARG: String = "artistSubtitle"

        /**
         * How many catalogue namesakes to offer.
         *
         * Five, which is the same judgement `:feature:search`'s `ARTIST_PREVIEW` makes with four: the
         * list answers "which of these did you mean", not "browse MusicBrainz". The device's worst
         * case was four Didos, and a sixth row would be a worse match than the five above it.
         */
        private const val NAMESAKE_LIMIT: Int = 5

        /**
         * The offset a discography starts at.
         *
         * Named so that the calls in [refreshDiscography] and [onShowMoreDiscography] cannot read as
         * the same call with a different number in it; one restarts the walk and one continues it.
         */
        private const val FIRST_PAGE: Int = 0

        private const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L

        private const val TAG: String = "ArtistViewModel"
    }
}

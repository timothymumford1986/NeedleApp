@file:OptIn(ExperimentalCoroutinesApi::class)

package app.needler.feature.library.artist

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.FavouriteTarget
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.Track
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.repository.FavouriteRepository
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.domain.usecase.RequestAlbumUseCase
import app.needler.feature.library.album.AlbumNotice
import app.needler.feature.library.common.RequestSheetState
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
    private val discographyFailure = MutableStateFlow<NeedlerError?>(null)

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
     * The three flows that change because the user did something.
     *
     * Grouped rather than combined individually because `combine` has typed
     * overloads up to five flows and this ViewModel now has more than that; a
     * `vararg` combine would type every one of them as `Any?`.
     */
    private val interaction: Flow<Interaction> = combine(
        busy,
        notice,
        requestSheet,
    ) { isBusy, currentNotice, sheet ->
        Interaction(busy = isBusy, notice = currentNotice, requestSheet = sheet)
    }

    val state: StateFlow<ArtistUiState> = combine(
        content,
        sessions.observeConnectivity(),
        discographyFailure,
        interaction,
    ) { current, connectivity, failure, acted ->
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
            offline = !connectivity.isOnline,
            busy = acted.busy,
            notice = acted.notice,
            requestSheet = acted.requestSheet,
            playableTracks = current.playableTracks,
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
            discographyFailure.value = null
            return
        }
        viewModelScope.launch {
            val result: Outcome<Unit> = library.refreshArtistDiscography(artistMbid)
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
            discographyFailure.value = error
        }
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

        private const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L

        private const val TAG: String = "ArtistViewModel"
    }
}

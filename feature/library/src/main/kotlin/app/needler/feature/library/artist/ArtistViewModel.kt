package app.needler.feature.library.artist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.domain.usecase.RequestAlbumUseCase
import app.needler.feature.library.album.AlbumNotice
import app.needler.feature.library.common.problemMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
 */
@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    pulls: PullRepository,
    sessions: SessionRepository,
) : ViewModel() {

    /** The artist this screen is showing; `:app`'s route must use [ARTIST_ID_ARG]. */
    private val artistMbid: ArtistMbid =
        ArtistMbid(requireNotNull(savedStateHandle.get<String>(ARTIST_ID_ARG)) {
            "ArtistViewModel needs an '" + ARTIST_ID_ARG + "' navigation argument"
        })

    private val requestAlbum = RequestAlbumUseCase(library, pulls, sessions)

    private val busy = MutableStateFlow(false)
    private val notice = MutableStateFlow<AlbumNotice?>(null)
    private val discographyUnavailable = MutableStateFlow(false)

    private val content: Flow<Content> = combine(
        library.observeArtist(artistMbid),
        library.observeOwnedAlbumsByArtist(artistMbid),
        library.observeArtistDiscography(artistMbid),
    ) { artist, owned, discography ->
        // Merge on release-group MBID: an album the mirror already has wins,
        // and the catalogue's copy of it is discarded rather than drawn twice.
        val ownedKeys: Set<String> = owned.map { it.releaseGroupMbid.value }.toSet()
        Content(
            artist = artist,
            owned = owned,
            catalogue = discography.filter { it.releaseGroupMbid.value !in ownedKeys },
        )
    }

    val state: StateFlow<ArtistUiState> = combine(
        content,
        sessions.observeConnectivity(),
        busy,
        notice,
        discographyUnavailable,
    ) { current, connectivity, isBusy, currentNotice, unavailable ->
        ArtistUiState(
            loading = false,
            artist = current.artist,
            ownedAlbums = current.owned,
            catalogueAlbums = current.catalogue,
            discographyUnavailable = unavailable && current.catalogue.isEmpty(),
            offline = !connectivity.isOnline,
            busy = isBusy,
            notice = currentNotice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = ArtistUiState(),
    )

    init {
        refreshDiscography()
    }

    /**
     * Pull one un-owned album from the discography.
     *
     * The album is handed to the use case as `known`, which saves it a lookup
     * and — more importantly — lets it send the title, artist and year as hints
     * on the request body. The server uses those to disambiguate a release
     * group whose MusicBrainz entry is thin.
     */
    fun onPull(album: Album) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val result: Outcome<RequestReceipt> =
                    requestAlbum(album.releaseGroupMbid, known = album)
                notice.value = when (result) {
                    is Outcome.Success -> AlbumNotice.forRequest(result.value.status)
                    is Outcome.Failure -> AlbumNotice.Problem(problemMessage(result.error))
                }
            } finally {
                busy.value = false
            }
        }
    }

    fun onDismissNotice() {
        notice.value = null
    }

    fun refreshDiscography() {
        viewModelScope.launch {
            val result: Outcome<Unit> = library.refreshArtistDiscography(artistMbid)
            discographyUnavailable.value = result is Outcome.Failure
        }
    }

    private data class Content(
        val artist: Artist?,
        val owned: List<Album>,
        val catalogue: List<Album>,
    )

    companion object {
        /** The navigation argument this ViewModel reads the artist id from. */
        const val ARTIST_ID_ARG: String = "artistId"

        private const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L
    }
}


@file:OptIn(ExperimentalCoroutinesApi::class)

package app.needler.feature.library.album

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RemovedDownload
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.TrackRequest
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.domain.usecase.PinAlbumForOfflineUseCase
import app.needler.core.domain.usecase.RequestAlbumUseCase
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the album screen.
 *
 * Reads come from the mirror through [LibraryRepository]; writes go out through
 * [PullRepository] and [PinRepository], and both of the domain's use cases are
 * reused rather than re-implemented:
 *
 *  * [RequestAlbumUseCase] already knows that an owned album short-circuits to
 *    "already present", that a stale session cannot reach the catalogue lane,
 *    and how to build the request body from an album.
 *  * [PinAlbumForOfflineUseCase] already knows that library download can be
 *    disabled by an administrator, that an un-owned album cannot be pinned, and
 *    whether the download will wait for Wi-Fi or leave the device short of
 *    space.
 *
 * Both are plain classes with repository constructors rather than injectable
 * singletons, so they are built here from the repositories Hilt provides. That
 * keeps the rules in `:core:domain` where the widgets and Android Auto can
 * reach them too.
 */
@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    private val pulls: PullRepository,
    private val pins: PinRepository,
    private val sessions: SessionRepository,
    private val playback: Optional<PlaybackController>,
) : ViewModel() {

    /**
     * The album this screen is showing.
     *
     * The argument name is [ALBUM_ID_ARG], which `:app`'s navigation graph has
     * to agree with. It is a constant here rather than a string literal so that
     * the route builder and the ViewModel cannot drift apart silently.
     */
    private val releaseGroupMbid: ReleaseGroupMbid =
        ReleaseGroupMbid(requireNotNull(savedStateHandle.get<String>(ALBUM_ID_ARG)) {
            "AlbumViewModel needs a '" + ALBUM_ID_ARG + "' navigation argument"
        })

    private val requestAlbum = RequestAlbumUseCase(library, pulls, sessions)
    private val pinAlbum = PinAlbumForOfflineUseCase(library, pins, sessions)

    private val busy = MutableStateFlow(false)
    private val notice = MutableStateFlow<AlbumNotice?>(null)

    private val nowPlayingKey: Flow<TrackKey?> =
        playback.orElse(null)
            ?.observeState()
            ?.map { playbackState -> playbackState.currentItem?.track?.key }
            ?: flowOf(null)

    private val content: Flow<Content> = combine(
        library.observeAlbum(releaseGroupMbid),
        library.observeAlbumTracks(releaseGroupMbid),
        nowPlayingKey,
    ) { album, tracks, playingKey ->
        Content(
            album = album,
            tracks = tracks.toRows(owned = album?.isOwned == true),
            nowPlayingTrackKey = playingKey,
        )
    }

    private val environment: Flow<Environment> = combine(
        pins.observePin(releaseGroupMbid),
        sessions.observeCapabilities(),
        sessions.observeConnectivity(),
    ) { pin, capabilities, connectivity ->
        Environment(
            download = pin?.download,
            // Unknown capabilities are treated as "allowed" rather than
            // "forbidden": before the first negotiation the app has no reason
            // to hide an action the server may well permit, and the real 403
            // would be handled honestly anyway.
            downloadAllowed = capabilities?.libraryDownloadAllowed ?: true,
            offline = !connectivity.isOnline,
        )
    }

    val state: StateFlow<AlbumUiState> = combine(
        content,
        environment,
        busy,
        notice,
    ) { current, env, isBusy, currentNotice ->
        AlbumUiState(
            loading = false,
            album = current.album,
            tracks = current.tracks,
            nowPlayingTrackKey = current.nowPlayingTrackKey,
            download = env.download,
            downloadAllowed = env.downloadAllowed,
            offline = env.offline,
            busy = isBusy,
            notice = currentNotice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = AlbumUiState(),
    )

    init {
        // The mirror is the read path, so the screen is already drawn by the
        // time this returns. Refreshing is how a track list that was only ever
        // a search result gets its real contents.
        viewModelScope.launch { library.refreshAlbum(releaseGroupMbid) }
    }

    // ---- playback -----------------------------------------------------------

    fun onPlay() {
        val controller: PlaybackController = playback.orElse(null) ?: return
        val startIndex: Int = state.value.firstPlayableIndex
        viewModelScope.launch { controller.playAlbum(releaseGroupMbid, startIndex = startIndex) }
    }

    fun onShuffle() {
        val controller: PlaybackController = playback.orElse(null) ?: return
        viewModelScope.launch { controller.playAlbum(releaseGroupMbid, shuffle = true) }
    }

    /**
     * Play from one track.
     *
     * The index handed to the controller is the track's position in this list,
     * not its printed track number: a multi-disc album restarts numbering at
     * every disc, and starting playback at "track 1" of disc 2 would rewind the
     * user to the beginning of the record.
     */
    fun onPlayTrack(row: AlbumTrack) {
        if (!row.available) return
        val controller: PlaybackController = playback.orElse(null) ?: return
        val index: Int = state.value.tracks.indexOfFirst { it.key == row.key }.coerceAtLeast(0)
        viewModelScope.launch { controller.playAlbum(releaseGroupMbid, startIndex = index) }
    }

    // ---- pulls --------------------------------------------------------------

    fun onPull() {
        runExclusively {
            val album: Album? = state.value.album
            when (val result: Outcome<RequestReceipt> = requestAlbum(releaseGroupMbid, known = album)) {
                is Outcome.Success -> AlbumNotice.forRequest(result.value.status)
                is Outcome.Failure -> AlbumNotice.Problem(problemMessage(result.error))
            }
        }
    }

    fun onCancelPull() {
        runExclusively {
            when (val result: Outcome<Unit> = pulls.cancelRequest(releaseGroupMbid)) {
                is Outcome.Success -> null
                is Outcome.Failure -> AlbumNotice.Problem(problemMessage(result.error))
            }
        }
    }

    fun onRetryPull() {
        runExclusively {
            when (val result: Outcome<Unit> = pulls.retryRequest(releaseGroupMbid)) {
                is Outcome.Success -> AlbumNotice.PullAccepted
                is Outcome.Failure -> AlbumNotice.Problem(problemMessage(result.error))
            }
        }
    }

    /**
     * Ask the server again for one track a part-delivered pull never brought.
     *
     * A single-track request is keyed on the **recording** MBID, not the
     * release group — REQUIREMENTS.md, "Placing a request" — and a mirror row
     * does not always carry one. With no recording MBID there is nothing to ask
     * for at track granularity, so the whole album's request is retried
     * instead, which is the only other thing the server understands.
     */
    fun onRetryTrack(row: AlbumTrack) {
        runExclusively {
            val recording = row.track.recordingMbid
                ?: return@runExclusively when (
                    val fallback: Outcome<Unit> = pulls.retryRequest(releaseGroupMbid)
                ) {
                    is Outcome.Success -> AlbumNotice.PullAccepted
                    is Outcome.Failure -> AlbumNotice.Problem(problemMessage(fallback.error))
                }
            val request = TrackRequest(
                recordingMbid = recording,
                trackTitle = row.track.title,
                artistName = row.track.artistName,
            )
            when (val result: Outcome<RequestReceipt> = pulls.requestTrack(request)) {
                is Outcome.Success -> AlbumNotice.forRequest(result.value.status)
                is Outcome.Failure -> AlbumNotice.Problem(problemMessage(result.error))
            }
        }
    }

    // ---- this device --------------------------------------------------------

    fun onDownloadToDevice() {
        runExclusively {
            when (val result = pinAlbum(releaseGroupMbid)) {
                is Outcome.Success -> when {
                    result.value.waitingForUnmeteredNetwork -> AlbumNotice.DownloadWaitingForWifi
                    result.value.willLeaveDeviceLowOnSpace -> AlbumNotice.DownloadWillFillDevice
                    else -> AlbumNotice.DownloadStarted
                }

                is Outcome.Failure -> AlbumNotice.Problem(problemMessage(result.error))
            }
        }
    }

    /**
     * Remove the download.
     *
     * REQUIREMENTS.md is emphatic that this deletes the bytes there and then
     * and reports how many were freed, rather than demoting the album into the
     * listening tier — which is why the notice carries the figure the
     * repository returned rather than a bare "done".
     */
    fun onRemoveFromDevice() {
        runExclusively {
            when (val result: Outcome<RemovedDownload> = pins.unpinAlbum(releaseGroupMbid)) {
                is Outcome.Success -> AlbumNotice.RemovedFromDevice(result.value.freedBytes)
                is Outcome.Failure -> AlbumNotice.Problem(problemMessage(result.error))
            }
        }
    }

    fun onDismissNotice() {
        notice.value = null
    }

    // ---- internals ----------------------------------------------------------

    /**
     * Run one action at a time, disabling the buttons while it is in flight.
     *
     * Every one of these actions is a server write, and every one of them is
     * behind a button the user can hit twice. Two pulls for the same album is
     * harmless; two unpins is a race over the same files.
     */
    private fun runExclusively(block: suspend () -> AlbumNotice?) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                notice.value = block()
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * Turn mirror rows into list rows.
     *
     * A track is playable only when the album is owned **and** the server has a
     * file behind that row. Both halves matter and they fail differently:
     *
     *  * an un-owned album's track list is catalogue metadata, so none of it
     *    plays — screen 05 draws every row greyed for exactly this reason;
     *  * an owned album can still have holes in it, which is the part-delivered
     *    pull REQUIREMENTS.md calls a normal state.
     */
    private fun List<Track>.toRows(owned: Boolean): List<AlbumTrack> = mapIndexed { index, track ->
        AlbumTrack(
            position = if (track.key.trackNumber > 0) track.key.trackNumber else index + 1,
            track = track,
            available = owned && track.hasPlayableFile,
        )
    }

    private data class Content(
        val album: Album?,
        val tracks: List<AlbumTrack>,
        val nowPlayingTrackKey: TrackKey?,
    )

    private data class Environment(
        val download: OfflineDownloadState?,
        val downloadAllowed: Boolean,
        val offline: Boolean,
    )

    companion object {
        /** The navigation argument this ViewModel reads the album id from. */
        const val ALBUM_ID_ARG: String = "albumId"

        private const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L
    }
}


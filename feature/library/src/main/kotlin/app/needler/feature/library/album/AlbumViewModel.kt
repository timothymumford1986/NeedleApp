@file:OptIn(ExperimentalCoroutinesApi::class)

package app.needler.feature.library.album

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.FavouriteTarget
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RemovedDownload
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.StoppedDownload
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.StreamOverrideScope
import app.needler.core.domain.model.StreamRung
import app.needler.core.domain.model.StreamRungs
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.TrackRequest
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.playback.PlaybackState
import app.needler.core.domain.repository.FavouriteRepository
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.repository.PlaybackSettingsRepository
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.domain.usecase.PinAlbumForOfflineUseCase
import app.needler.core.domain.usecase.RequestAlbumUseCase
import app.needler.core.domain.usecase.ResolvePlayableSourceUseCase
import app.needler.feature.library.common.LibraryFormat
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
import kotlinx.coroutines.flow.flowOf
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
    private val favourites: FavouriteRepository,
    private val sessions: SessionRepository,
    private val playbackSettings: PlaybackSettingsRepository,
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
    private val requestSheet = MutableStateFlow<RequestSheetState?>(null)

    /**
     * What playback is doing, as far as this one record is concerned.
     *
     * Both of the controller's event flows are needed and neither is sufficient:
     * [PlaybackController.observeState] says whether sound is coming out and which track it is,
     * and [PlaybackController.observeQueue] says what the crate is made of - which is the only
     * flow that can distinguish "this album is loaded" from "one of its tracks happens to be in
     * somebody else's crate". See [transportFor].
     *
     * [PlaybackController.observeProgress] is deliberately **not** collected. It ticks several
     * times a second and nothing on this screen moves with it; subscribing would recompose the
     * header and the whole track list on every tick, which is the exact cost the controller
     * documents that flow being separate to avoid.
     *
     * One flow rather than two so that the transport and the row highlight are computed from a
     * single pair of emissions. Read from two combines they could disagree for a frame, and the
     * disagreement would be a button that says Pause over a list with nothing marked as playing.
     */
    private val playbackLens: Flow<PlaybackLens> =
        playback.orElse(null)?.let { controller ->
            combine(controller.observeState(), controller.observeQueue()) { playbackState, queue ->
                PlaybackLens(
                    nowPlayingTrackKey = playbackState.currentItem?.track?.key,
                    transport = transportFor(playbackState, queue),
                    crateTrackCount = queue.items.size,
                    crateDurationMs = queue.totalDurationMs,
                )
            }
        } ?: flowOf(PlaybackLens())

    private val content: Flow<Content> = combine(
        library.observeAlbum(releaseGroupMbid),
        library.observeAlbumTracks(releaseGroupMbid),
        playbackLens,
    ) { album, tracks, lens ->
        Content(
            album = album,
            tracks = tracks.toRows(owned = album?.isOwned == true),
            nowPlayingTrackKey = lens.nowPlayingTrackKey,
            transport = lens.transport,
            crateTrackCount = lens.crateTrackCount,
            crateDurationMs = lens.crateDurationMs,
        )
    }

    private val environment: Flow<Environment> = combine(
        pins.observePin(releaseGroupMbid),
        sessions.observeCapabilities(),
        sessions.observeConnectivity(),
        playbackSettings.observePlaybackPreferences(),
        playbackSettings.observeStreamOverride(
            scope = StreamOverrideScope.ALBUM,
            id = releaseGroupMbid.value,
        ),
    ) { pin, capabilities, connectivity, preferences, override ->
        Environment(
            download = pin?.download,
            // Unknown capabilities are treated as "allowed" rather than
            // "forbidden": before the first negotiation the app has no reason
            // to hide an action the server may well permit, and the real 403
            // would be handled honestly anyway.
            downloadAllowed = capabilities?.libraryDownloadAllowed ?: true,
            offline = !connectivity.isOnline,
            streamRungs = preferences.streamRungs,
            qualityOverride = override,
            // Held rather than resolved here: the rule needs the album's own quality, which arrives on
            // the other flow. Resolving it in the state combine keeps both halves of one decision in
            // one place - see [resolveServerFormat].
            capabilities = capabilities,
            connectivity = connectivity,
        )
    }

    val state: StateFlow<AlbumUiState> = combine(
        content,
        environment,
        busy,
        notice,
        requestSheet,
    ) { current, env, isBusy, currentNotice, sheet ->
        AlbumUiState(
            loading = false,
            album = current.album,
            tracks = current.tracks,
            nowPlayingTrackKey = current.nowPlayingTrackKey,
            transport = current.transport,
            download = env.download,
            downloadAllowed = env.downloadAllowed,
            offline = env.offline,
            busy = isBusy,
            notice = currentNotice,
            requestSheet = sheet,
            serverFormat = resolveServerFormat(current.album, env),
            qualityOverride = env.qualityOverride,
            crateTrackCount = current.crateTrackCount,
            crateDurationMs = current.crateDurationMs,
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

    /**
     * The primary control: pause, resume, or start this album from the top.
     *
     * Which of the three it is comes off [AlbumUiState.transport], so the effect and the label the
     * user read are decided by the same value. Before this, the control called [
     * PlaybackController.playAlbum] in every state, so opening the album you were listening to and
     * pressing the button that looked like a transport control replaced the crate with an identical
     * one and seeked to zero: `position=32975` before the tap, `position=0` after, with the buffer
     * thrown away and re-fetched. It cost the user their place and it cost them the bytes.
     *
     * **Not [PlaybackController.playPause].** That toggles whatever the session is doing at the
     * moment the command lands, which is not necessarily what the button said when it was drawn - a
     * track ending between the render and the tap would make the button labelled Pause start
     * playback. [PlaybackController.pause] and [PlaybackController.play] are idempotent and do what
     * the label promised even when the session has moved on. The player's own transport can use the
     * toggle because its button is redrawn from the same state it toggles; this one is read next to
     * an album header and can be looked at for a while before it is pressed.
     *
     * The transport is read once, before the suspend, for the same reason: the decision the user
     * made is the one on screen.
     */
    fun onPlayPause() {
        val controller: PlaybackController = playback.orElse(null) ?: return
        val snapshot: AlbumUiState = state.value
        viewModelScope.launch {
            when (snapshot.transport) {
                AlbumTransport.PAUSE -> controller.pause()
                AlbumTransport.RESUME -> controller.play()
                AlbumTransport.START ->
                    controller.playAlbum(
                        releaseGroupMbid,
                        startIndex = snapshot.firstPlayableIndex,
                    )
            }
        }
    }

    /**
     * Shuffle, or re-shuffle.
     *
     * One call either way, deliberately: there is no non-destructive re-shuffle to offer. Shuffling
     * a record you are inside means hearing it again in another order, and the controller's only
     * album-shuffle command replaces the crate. What changes when this album is already loaded is
     * the label and the spoken description - [AlbumTransport.shuffleLabel] and
     * [AlbumTransport.shuffleDescription] - so the restart is something the user chose rather than
     * something that happened to them. The reasoning, and the two alternatives rejected, are on
     * [AlbumTransport].
     *
     * Still `playAlbum(shuffle = true)` rather than [PlaybackController.setShuffleEnabled]: album
     * shuffle shuffles this record's own tracks and leaves the global mode alone.
     */
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

    /**
     * Put this whole record in the crate, at the end or next, without disturbing what is playing.
     *
     * This is the capability the album screen did not have: its three controls all replaced the
     * crate, so a user listening to one record could not line up a second one without waiting for
     * the first to end. `PlaybackController.enqueue` has always offered it.
     *
     * Unplayable rows are dropped rather than queued. REQUIREMENTS.md "Partial content is a normal
     * state": a part-delivered pull leaves tracks that exist nowhere, so a crate holding them would
     * stall on gaps the user cannot see the reason for - the same filter `onPlay` applies by
     * starting at [AlbumUiState.firstPlayableIndex].
     */
    fun onAddToCrate(playNext: Boolean) {
        val controller: PlaybackController = playback.orElse(null) ?: return
        val tracks: List<Track> = state.value.tracks.filter { it.available }.map { it.track }
        if (tracks.isEmpty()) return
        runExclusively { controller.addTracksToCrate(tracks, playNext) }
    }

    /**
     * Add one track to the crate.
     *
     * One row rather than the record, because that is what the long press was on. The row's own
     * `available` is re-checked here for the reason every other playback intent re-checks it: a
     * ViewModel that trusts its screen to have filtered correctly is one refactor from queueing a
     * track with no file behind it.
     */
    fun onAddTrackToCrate(row: AlbumTrack, playNext: Boolean) {
        if (!row.available) return
        val controller: PlaybackController = playback.orElse(null) ?: return
        runExclusively { controller.addTracksToCrate(listOf(row.track), playNext) }
    }

    // ---- pulls --------------------------------------------------------------

    /**
     * Open the pull sheet.
     *
     * The request itself is [onConfirmRequest]. Two steps rather than one because
     * REQUIREMENTS.md "Placing a request" puts the `monitor_artist` toggle "on
     * the request sheet", and one tap straight to the server leaves nowhere to
     * put it — which is exactly why no caller in this app has ever passed that
     * flag. The requirements' "Requesting is one tap on any un-owned album" is
     * still honoured: this is that tap, and the sheet's own confirm is the
     * second.
     *
     * A sheet with nothing to request is not opened, so there is no state in
     * which the confirm button acts on a null album.
     */
    fun onPull() {
        val album: Album = state.value.album ?: return
        requestSheet.value = RequestSheetState.forAlbum(album)
    }

    /** The sheet's toggle. Hoisted here so it survives a rotation mid-decision. */
    fun onMonitorArtistChange(monitorArtist: Boolean) {
        requestSheet.value = requestSheet.value?.copy(monitorArtist = monitorArtist)
    }

    fun onDismissRequestSheet() {
        requestSheet.value = null
    }

    /**
     * Place the request the sheet was opened for, carrying its `monitor_artist` flag.
     *
     * The sheet is closed before the call rather than after it. The pull is a 202
     * — the server has accepted the request, not completed it — so there is
     * nothing to wait on the sheet for, and leaving it up over the notice that
     * says "Pulling" would hide the one thing the user is now waiting to read.
     *
     * The album is taken from the sheet rather than re-read, so a sync landing
     * between the tap and the confirm cannot make this request a different
     * record from the one whose title the sheet is showing.
     */
    fun onConfirmRequest() {
        val sheet: RequestSheetState = requestSheet.value ?: return
        val album: Album = sheet.albums.singleOrNull() ?: return
        requestSheet.value = null
        runExclusively {
            val result: Outcome<RequestReceipt> = requestAlbum(
                releaseGroupMbid = releaseGroupMbid,
                monitorArtist = sheet.monitorArtist,
                known = album,
            )
            when (result) {
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
                    else -> AlbumNotice.DownloadStarted(
                        LibraryFormat.albumLabel(state.value.album?.title),
                    )
                }

                is Outcome.Failure -> AlbumNotice.Problem(problemMessage(result.error))
            }
        }
    }

    /**
     * Stop the download and keep what has arrived.
     *
     * **Not [onRemoveFromDevice] under a kinder label.** The album screen used
     * to offer only "Device. Remove … from this device" while a download ran,
     * so the only way to end one was to delete everything it had fetched —
     * observed on a device at "Pulling to device, 31 percent, playing now".
     * REQUIREMENTS.md "The download in flight is a badge, not a banner" makes
     * them two actions with two outcomes: "stopping leaves what has landed as a
     * part-downloaded pin, which plays, while removing deletes the bytes and
     * reports what it freed."
     *
     * Inside [runExclusively] like every other write here, and for a sharper
     * reason than most: Stop and Remove are drawn in the same slot of the
     * action row, and the row redraws the moment the pin row changes. Two taps
     * racing could otherwise send a stop and a removal for the same album.
     */
    fun onStopDownload() {
        runExclusively {
            when (
                val result: Outcome<StoppedDownload> = pins.stopPinnedDownload(releaseGroupMbid)
            ) {
                is Outcome.Success -> AlbumNotice.DownloadStopped(result.value.tracksOnDevice)
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
     *
     * [onStopDownload] is the other half of the pair, and the domain decides
     * which of the two is offered: `AlbumState.Pinned.offeredActions` holds
     * `CANCEL` while the download is in flight and `REMOVE_FROM_DEVICE` once it
     * rests, never both.
     */
    fun onRemoveFromDevice() {
        runExclusively {
            when (val result: Outcome<RemovedDownload> = pins.unpinAlbum(releaseGroupMbid)) {
                is Outcome.Success -> AlbumNotice.RemovedFromDevice(result.value.freedBytes)
                is Outcome.Failure -> AlbumNotice.Problem(problemMessage(result.error))
            }
        }
    }

    // ---- favourites ---------------------------------------------------------

    /**
     * Star or unstar this album.
     *
     * REQUIREMENTS.md "Playlists": "`setRating` is a deliberate no-op on this
     * server … Binary favourites via `star`/`unstar` do persist and are the
     * supported mechanism." This is the only mechanism there is, which is also
     * why the library's "Starred" sort had nothing to sort by until now.
     *
     * Deliberately **not** inside [runExclusively]. That gate exists because
     * every other action here is a server write behind a button a thumb can hit
     * twice, and two unpins race over the same files. A star is different on
     * both counts: `FavouriteRepository.setFavourite` writes the mirror first and
     * journals the server call, so the flow this screen reads has already
     * changed by the time the call goes out, and starring twice lands on the same
     * value either way. Greying the star out for a network round trip would make
     * the most trivial control on the screen the slowest.
     */
    fun onToggleFavourite() {
        val album: Album = state.value.album ?: return
        setFavourite(
            target = FavouriteTarget.OfAlbum(album.releaseGroupMbid),
            starred = !album.isFavourite,
        )
    }

    /**
     * Star or unstar one track.
     *
     * Keyed on [TrackKey] and never on the file id — REQUIREMENTS.md "Track
     * identity is not stable": DroppedNeedle replaces files in place on a quality
     * upgrade, so a favourite keyed on `file_id` would come unstuck from its
     * track the first time the server found a better copy. [FavouriteTarget.OfTrack]
     * takes the stable key for exactly that reason.
     */
    fun onToggleTrackFavourite(row: AlbumTrack) {
        setFavourite(
            target = FavouriteTarget.OfTrack(row.key),
            starred = !row.track.isFavourite,
        )
    }

    fun onDismissNotice() {
        notice.value = null
    }

    // ---- stream quality -----------------------------------------------------

    /**
     * Pins a stream rung to this record - what tapping the `Server:` tag writes.
     *
     * **Absolute, not per-network.** One value, applied on Wi-Fi and on mobile data alike: the mode
     * defaults answer "what do I usually want on this connection", and an override answers "this record
     * is different". A record worth hearing lossless is worth it on the train too, and two per-network
     * overrides per album would be four numbers to reason about for one record, three of which nothing
     * on screen shows.
     *
     * It is kept when the album is downloaded rather than cleared - a local copy wins while it exists,
     * so the override is dormant, and freeing disk space must not silently revoke a preference nobody
     * withdrew. See [app.needler.core.domain.model.StreamOverride], where that decision is recorded as
     * reversible.
     *
     * No notice on success: the tag itself changes under the finger, which is the whole feedback the
     * action needs.
     */
    fun onOverrideQuality(rung: StreamRung) {
        viewModelScope.launch {
            playbackSettings.setStreamOverride(
                scope = StreamOverrideScope.ALBUM,
                id = releaseGroupMbid.value,
                rung = rung,
            )
        }
    }

    /** Drops this record's override, returning it to the rung for whichever connection is in use. */
    fun onClearQualityOverride() {
        viewModelScope.launch {
            playbackSettings.clearStreamOverride(
                scope = StreamOverrideScope.ALBUM,
                id = releaseGroupMbid.value,
            )
        }
    }

    // ---- internals ----------------------------------------------------------

    /**
     * Apply one star, and say so only if it failed.
     *
     * Nothing is reported on success: the star itself has already moved, which is
     * the whole feedback the action needs. A queued-offline star is a success —
     * the write queue replays it — and a notice announcing that would appear on
     * every tap made on a train.
     */
    private fun setFavourite(target: FavouriteTarget, starred: Boolean) {
        viewModelScope.launch {
            val result: Outcome<Unit> = favourites.setFavourite(target, starred)
            if (result is Outcome.Failure) {
                notice.value = AlbumNotice.Problem(problemMessage(result.error))
            }
        }
    }

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

    /**
     * What the server would send for this album right now: the `Server:` half of the tag pair.
     *
     * Through [ResolvePlayableSourceUseCase.resolveStreamFormat] and nothing else, because the tag has
     * to be the same answer playback will give. Reimplementing "is the rung below the source" here -
     * which is the obvious three-line version - is how the tag comes to say `MP3 192` for a record
     * that in fact streams untouched, and there is no way to notice from either side.
     *
     * Null until the album is known: before that there is no source to cap the rung against, and a tag
     * showing the raw rung would be a guess presented as a fact.
     */
    private fun resolveServerFormat(album: Album?, environment: Environment): StreamFormat? {
        if (album == null) return null
        return ResolvePlayableSourceUseCase.resolveStreamFormat(
            rungs = environment.streamRungs,
            connectivity = environment.connectivity,
            capabilities = environment.capabilities,
            source = album.quality,
            // No track override: this is the record's own tag. A per-track override still applies when
            // that track plays - the resolver checks it first - and the player's tag shows it.
            albumOverride = environment.qualityOverride,
        )
    }

    /**
     * Whether the crate the session is holding **is this album**, and if so what it is doing.
     *
     * Three conditions, and the third is the one that is easy to leave out:
     *
     *  1. something is loaded at all - no current item means nothing to pause or resume;
     *  2. the current item belongs to this release group;
     *  3. **every** row of the crate belongs to this release group.
     *
     * Condition 3 is what makes this "the loaded queue is this album" rather than "a track of this
     * album is playing". Without it a crate built from a playlist, a genre, the favourites list or
     * a search result would hand Pause to whichever album happened to own the current track, and
     * Play - the only way to actually play that record - would be unreachable from its own screen
     * for as long as the crate was running. One album in a twenty-album crate would be
     * indistinguishable from that album loaded on its own.
     *
     * The crate is **not** required to contain all of this album. REQUIREMENTS.md "Partial content
     * is a normal state": a part-delivered pull leaves tracks that exist nowhere, `playAlbum`
     * queues what there is, and a subset test would mean the part-delivered albums - the ones most
     * likely to be replayed while the rest is still arriving - were the only ones still losing
     * their position.
     *
     * `isPlaying` alone decides between pause and resume. `isBuffering` is not consulted, because
     * the controller documents a track re-buffering mid-stream as still playing, and the player's
     * own transport row branches on the same field - two surfaces disagreeing about what the same
     * session is doing would be worse than either answer. The one state this gets briefly wrong is
     * the moment between a play command and the first audio, where the button may read Resume; the
     * cost of that is a [PlaybackController.play] call on a session that was starting anyway, which
     * loses nothing.
     */
    private fun transportFor(playbackState: PlaybackState, queue: PlayQueue): AlbumTransport {
        val current: QueueItem = playbackState.currentItem ?: return AlbumTransport.START
        if (current.track.key.releaseGroupMbid != releaseGroupMbid) return AlbumTransport.START
        val items: List<QueueItem> = queue.items
        if (items.isEmpty()) return AlbumTransport.START
        if (items.any { it.track.key.releaseGroupMbid != releaseGroupMbid }) {
            return AlbumTransport.START
        }
        return if (playbackState.isPlaying) AlbumTransport.PAUSE else AlbumTransport.RESUME
    }

    private data class Content(
        val album: Album?,
        val tracks: List<AlbumTrack>,
        val nowPlayingTrackKey: TrackKey?,
        val transport: AlbumTransport,
        val crateTrackCount: Int,
        val crateDurationMs: Long,
    )

    /** The two playback flows folded into the facts this screen reads off them. */
    private data class PlaybackLens(
        val nowPlayingTrackKey: TrackKey? = null,
        val transport: AlbumTransport = AlbumTransport.START,
        val crateTrackCount: Int = 0,
        val crateDurationMs: Long = 0L,
    )

    private data class Environment(
        val download: OfflineDownloadState?,
        val downloadAllowed: Boolean,
        val offline: Boolean,
        val streamRungs: StreamRungs,
        val qualityOverride: StreamRung?,
        val capabilities: ServerCapabilities?,
        val connectivity: ConnectivityState,
    )

    companion object {
        /** The navigation argument this ViewModel reads the album id from. */
        const val ALBUM_ID_ARG: String = "albumId"

        private const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L
    }
}


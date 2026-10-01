// kotlinx.datetime.Instant is a typealias for kotlin.time.Instant from
// kotlinx-datetime 0.7.0 onwards, and the underlying type has carried an
// opt-in marker through several Kotlin releases. Opting in here costs a
// warning if it turns out not to be needed, and avoids a build break if it is.
// The same note sits at the top of LibraryFormat.kt.
@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package app.needler.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.FavouriteTarget
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.StreamOverrideScope
import app.needler.core.domain.model.StreamRung
import app.needler.core.domain.model.Track
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.core.domain.playback.PlaybackState
import app.needler.core.domain.repository.FavouriteRepository
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.repository.PlaybackSettingsRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.domain.usecase.ResolvePlayableSourceUseCase
import app.needler.feature.player.ui.SleepTimerChoice
import app.needler.feature.player.ui.SleepTimerOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The transport, shared by Now Playing, the mini player and the tablet sidebar.
 *
 * All three draw the same thing at three sizes, so there is one view model behind them rather than
 * three that would drift. It holds no playback of its own: every transport command is one call on
 * [PlaybackController], which is the session's only client-facing surface, and every playback failure
 * comes back on [PlayerUiState.error] rather than as a return value - because the session is shared, and
 * a track that will not play has to be visible to the lock screen and Wear too, not only to whichever
 * client pressed play.
 *
 * ## Two flows, kept apart
 *
 * [state] changes when a person changed something. [progress] ticks several times a second. They are
 * separate here because they are separate on the controller, and for the same reason: a screen that
 * merged them would relayout artwork, titles and the transport on every tick, for an entire album.
 * The screens read [progress] through a lambda so the read is deferred into the scrubber itself.
 *
 * The crate's *size* does reach [state], because the sidebar prints "4 up next" beside the crate
 * heading. That costs nothing: it is an `Int` inside a data class, so a queue edit that does not
 * change the count produces an equal state and `StateFlow` drops it.
 *
 * ## Six dependencies, not one
 *
 * The transport comes from the controller; three things beside it do not.
 *
 * **Favourites.** `FavouriteRepository` was fully implemented - mirror-first writes, offline
 * `pending_sync`, the lot - and the player had no heart anywhere, so the one surface a listener is
 * looking at while they decide they like a song could not record it. REQUIREMENTS.md is explicit that
 * binary favourites through `star`/`unstar` are the supported mechanism and that star *ratings* must
 * never be offered.
 *
 * **The artist's identity.** The artist line in the player was dead text while the same artist on album
 * detail was a link. `Track` carries an artist *name* and no MBID, so the destination is resolved through
 * the track's album - `LibraryRepository.observeAlbum(...).artistMbid` - which is the same field
 * `AlbumScreen` links from, and therefore the same page.
 *
 * **The quality tag pair.** The player drew one format badge, taken from the track's metadata, which
 * answered neither of the two questions a listener actually has: what am I hearing, and what will I get
 * if I press play here again on mobile data. Those are different facts whenever a rung transcodes or a
 * download exists, so the pair needs the streaming decision itself - the rungs, the connection, the
 * server's transcoding capability and any per-item override - plus the on-device copy. That is three
 * more repositories, and it is why they are here rather than a string on `PlaybackState`: the
 * *session* has no idea what the user's Wi-Fi rung is, and should not.
 *
 * All of them hang off the current track through `flatMapLatest`, so they re-resolve when the track
 * changes and cost nothing while nothing is playing.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val favourites: FavouriteRepository,
    private val library: LibraryRepository,
    private val pins: PinRepository,
    private val playbackSettings: PlaybackSettingsRepository,
    private val sessions: SessionRepository,
) : ViewModel() {

    /**
     * A star the server permanently refused.
     *
     * Held here rather than on the controller's state because it is not a playback failure and must not be
     * cleared by one. Cleared by the next star that works.
     */
    private val favouriteError: MutableStateFlow<NeedlerError?> = MutableStateFlow(null)

    private val playback: Flow<PlaybackState> = controller.observeState()

    /** The playing track, and only when it actually changes - both lookups below key off it. */
    private val currentTrack: Flow<Track?> = playback
        .map { it.currentItem?.track }
        .distinctUntilChanged()

    private val isFavourite: Flow<Boolean> = currentTrack.flatMapLatest { track ->
        if (track == null) {
            flowOf(false)
        } else {
            favourites.observeIsFavourite(FavouriteTarget.OfTrack(track.key))
        }
    }

    /**
     * The artist behind the playing track, resolved through its album.
     *
     * Through the album because that is where the MBID is: `Track` has `artistName` and no identity, and
     * inventing a name-based lookup would give two screens two different ideas of who "Khruangbin" is.
     * Emits null until the mirror answers, and stays null for an album the mirror has no artist MBID for.
     */
    private val artistMbid: Flow<ArtistMbid?> = currentTrack
        .flatMapLatest { track ->
            if (track == null) {
                flowOf(null)
            } else {
                library.observeAlbum(track.releaseGroupMbid).map { album -> album?.artistMbid }
            }
        }
        .distinctUntilChanged()

    /**
     * What the server would send for the playing track right now, and what is already on this device.
     *
     * One flow rather than two because both halves are about the same track and the tags are drawn as a
     * pair: emitting them separately would let the screen show `Pulled: FLAC` for one frame beside a
     * `Server:` value resolved for the track before it.
     *
     * Note what decides "pulled": `pinned`, complete, and not stale. A merely cached copy is evictable,
     * so calling it pulled would promise offline availability the app cannot keep, and stale bytes are
     * about to be discarded by the resolver on the next play - REQUIREMENTS.md "Invalidating upgraded
     * files" - so a tag naming them would outlive the copy it describes.
     */
    private val quality: Flow<TrackQuality> = currentTrack.flatMapLatest { track ->
        if (track == null) {
            flowOf(TrackQuality.None)
        } else {
            combine(serverFormatFor(track), pins.observeCachedAudio(track.key)) { format, cached ->
                val downloaded: Boolean = cached != null &&
                    cached.pinned &&
                    cached.isComplete &&
                    !cached.isStaleFor(track.fetch)
                TrackQuality(
                    serverFormat = format,
                    // A download is always original bytes, so the copy's quality is the track's own.
                    pulled = if (downloaded) track.quality else null,
                )
            }
        }
    }.distinctUntilChanged()

    /**
     * The streaming decision for one track, re-resolved whenever anything it depends on moves.
     *
     * The same five inputs `ResolvePlayableSourceUseCase` uses, and the same pure function, so the tag
     * and the fetch cannot disagree. Leaving the connection out and showing the Wi-Fi rung would be the
     * obvious simplification and the wrong one: the whole value of the Server tag is that it changes
     * when the user leaves the house.
     */
    private fun serverFormatFor(track: Track): Flow<StreamFormat> = combine(
        playbackSettings.observePlaybackPreferences(),
        sessions.observeConnectivity(),
        sessions.observeCapabilities(),
        playbackSettings.observeStreamOverride(
            scope = StreamOverrideScope.TRACK,
            id = track.key.canonicalString,
        ),
        playbackSettings.observeStreamOverride(
            scope = StreamOverrideScope.ALBUM,
            id = track.releaseGroupMbid.value,
        ),
    ) { preferences, connectivity, capabilities, trackOverride, albumOverride ->
        ResolvePlayableSourceUseCase.resolveStreamFormat(
            rungs = preferences.streamRungs,
            connectivity = connectivity,
            capabilities = capabilities,
            source = track.quality,
            trackOverride = trackOverride,
            albumOverride = albumOverride,
        )
    }

    /**
     * The three per-track lookups, folded before the transport's own combine.
     *
     * Nested rather than widened because `combine` is typed only to five flows, and an array of `Any?`
     * at the top of a state pipeline is how the wrong element gets read as the wrong type.
     */
    private val extras: Flow<TrackExtras> = combine(
        isFavourite,
        favouriteError,
        artistMbid,
        quality,
    ) { favourite, failure, artist, trackQuality ->
        TrackExtras(
            isFavourite = favourite,
            favouriteError = failure,
            artistMbid = artist,
            quality = trackQuality,
        )
    }

    val state: StateFlow<PlayerUiState> = combine(
        playback,
        controller.observeQueue().map { it.upNext.size }.distinctUntilChanged(),
        extras,
    ) { playbackState, upNext, trackExtras ->
        PlayerUiState.from(
            state = playbackState,
            upNextCount = upNext,
            isFavourite = trackExtras.isFavourite,
            favouriteError = trackExtras.favouriteError,
            artistMbid = trackExtras.artistMbid,
            serverFormat = trackExtras.quality.serverFormat,
            pulledQuality = trackExtras.quality.pulled,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = PlayerUiState.Idle,
    )

    val progress: StateFlow<PlaybackProgress> = controller.observeProgress().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = PlaybackProgress.Zero,
    )

    fun playPause() {
        viewModelScope.launch { controller.playPause() }
    }

    fun skipToNext() {
        viewModelScope.launch { controller.skipToNext() }
    }

    /**
     * Skips backwards.
     *
     * The "past a few seconds in, restart the track instead" rule lives in the implementation on
     * purpose, and must not be second-guessed here: two surfaces that each had their own threshold
     * would disagree about what the same button does.
     */
    fun skipToPrevious() {
        viewModelScope.launch { controller.skipToPrevious() }
    }

    /**
     * Seeks to a fraction of the current track.
     *
     * The scrubber works in fractions because that is what it draws and what TalkBack's
     * `setProgress` action hands it; the controller works in milliseconds. A track of unknown length
     * cannot be seeked in fractions at all, so the command is dropped rather than sent as a guess.
     */
    fun seekToFraction(fraction: Float) {
        val duration: Long = state.value.durationMs ?: return
        if (duration <= 0L) return
        val position: Long = (duration * fraction.coerceIn(0f, 1f)).toLong()
        viewModelScope.launch { controller.seekTo(position) }
    }

    fun toggleShuffle() {
        val next: Boolean = !state.value.shuffleEnabled
        viewModelScope.launch { controller.setShuffleEnabled(next) }
    }

    /** Off, all, one, off - the order [app.needler.core.domain.playback.RepeatMode.next] defines. */
    fun cycleRepeat() {
        val next = state.value.repeatMode.next
        viewModelScope.launch { controller.setRepeatMode(next) }
    }

    /**
     * Stars or unstars the playing track.
     *
     * The value sent is the opposite of what is on screen rather than the opposite of what the repository
     * last said, so two quick taps cannot both send the same value. Nothing is applied optimistically:
     * `FavouriteRepository` writes the mirror before it touches the network and the flow above re-emits
     * from that write, so the heart fills under the finger without this view model holding a second copy
     * of the answer.
     *
     * A failure here is a *permanent* rejection - offline and retryable failures are journalled in the
     * write queue and come back as success - and it is worth showing, because the mirror now says starred
     * and the server disagrees.
     */
    fun toggleFavourite() {
        val track: Track = state.value.item?.track ?: return
        val starred: Boolean = !state.value.isFavourite
        viewModelScope.launch {
            val outcome: Outcome<Unit> =
                favourites.setFavourite(FavouriteTarget.OfTrack(track.key), starred)
            favouriteError.value = (outcome as? Outcome.Failure)?.error
        }
    }

    /**
     * Pins a stream rung to the playing track - the `Server:` tag's ladder.
     *
     * Scoped to the **track** rather than to its album, because this is the track the person is
     * listening to and the one they just judged. An album-wide choice belongs on the album screen,
     * where the record is named.
     *
     * The override is absolute - it applies on Wi-Fi and on mobile data alike - and it survives being
     * downloaded: a local copy wins while it exists, so the override is dormant rather than gone, and
     * freeing disk space must not silently revoke a preference nobody withdrew. See
     * [app.needler.core.domain.model.StreamOverride].
     */
    fun overridePlayingTrackQuality(rung: StreamRung) {
        val track: Track = state.value.item?.track ?: return
        viewModelScope.launch {
            playbackSettings.setStreamOverride(
                scope = StreamOverrideScope.TRACK,
                id = track.key.canonicalString,
                rung = rung,
            )
        }
    }

    /** Drops the track's own rung, returning it to its album's override or to the mode default. */
    fun clearPlayingTrackQualityOverride() {
        val track: Track = state.value.item?.track ?: return
        viewModelScope.launch {
            playbackSettings.clearStreamOverride(
                scope = StreamOverrideScope.TRACK,
                id = track.key.canonicalString,
            )
        }
    }

    /**
     * Arms or disarms the sleep timer - REQUIREMENTS.md "Player features": "End of track or a duration".
     *
     * The clock is read here and not in the composable that was tapped, because this is where a command
     * is issued: [SleepTimer.At] holds the moment to stop, and the moment is "now, plus what was chosen",
     * measured once, at the tap. The conversion itself is [SleepTimerOptions.timerFor], which is pure and
     * tested directly.
     *
     * It goes through the controller rather than straight to `PlaybackSettingsRepository` because the
     * controller is the surface every client of the session already holds, and because that is the only
     * route the widgets and Wear will have when they want the same control.
     */
    fun setSleepTimer(choice: SleepTimerChoice) {
        val now: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val timer: SleepTimer = SleepTimerOptions.timerFor(choice, now)
        viewModelScope.launch { controller.setSleepTimer(timer) }
    }

    /**
     * What the quality tag pair needs, for one track.
     *
     * A value rather than a `Pair` so that neither half can be read as the other: they are both "a
     * quality", and the only thing keeping them apart is which question they answer.
     */
    private data class TrackQuality(
        val serverFormat: StreamFormat?,
        val pulled: AudioQuality?,
    ) {
        companion object {
            /** Nothing playing: no tag of either kind. */
            val None: TrackQuality = TrackQuality(serverFormat = null, pulled = null)
        }
    }

    /** The per-track lookups that do not come from the session, folded into one emission. */
    private data class TrackExtras(
        val isFavourite: Boolean,
        val favouriteError: NeedlerError?,
        val artistMbid: ArtistMbid?,
        val quality: TrackQuality,
    )

    private companion object {
        /**
         * How long the flows stay collected after the last subscriber goes.
         *
         * Long enough to survive a rotation or a trip through the crate screen without re-subscribing
         * to the session, short enough that a backgrounded app stops ticking.
         */
        const val STOP_TIMEOUT_MS: Long = 5_000L
    }
}

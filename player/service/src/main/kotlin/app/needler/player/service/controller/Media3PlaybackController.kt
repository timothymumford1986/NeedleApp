package app.needler.player.service.controller

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.PlaybackSpeed
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.QueueItemSource
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.model.Track
import app.needler.core.domain.playback.OutputRouter
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.core.domain.playback.PlaybackState
import app.needler.core.domain.playback.RepeatMode
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PlaybackSettingsRepository
import app.needler.player.service.media.TrackCatalogue
import app.needler.player.service.output.OutputRouteMonitor
import app.needler.player.service.session.NeedlerPlaybackService
import app.needler.player.service.session.awaitResult
import app.needler.player.service.source.NeedlerPlaybackException
import app.needler.player.service.source.PlaybackErrorMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * `PlaybackController` over Media3's `MediaController`, and the only place in the app where the two meet.
 *
 * `:feature:player`, the widgets and Wear talk to the domain interface; nothing outside `:player:service`
 * imports a Media3 type. That boundary is the reason `PlaybackController` is declared in `:core:domain` at
 * all: without it one feature module reaches for `MediaController` and the layering rule becomes decoration.
 *
 * ## The three flows stay three flows
 *
 * [observeProgress] ticks several times a second; [observeState] changes only when a person changed
 * something; [observeQueue] changes when the crate is edited. Folding them together would recompose the whole
 * player screen - artwork, titles, the crate, the transport - on every position tick, on a screen that is
 * often foreground for a whole album. They are kept apart deliberately and must not be merged for tidiness.
 *
 * ## Commands do not report failures
 *
 * Every command returns `Unit` and suspends until the session is connected. A failure appears on
 * [PlaybackState.error], because the session is shared: a track that will not play has to be visible to the
 * lock screen, Auto and Wear, not only to whichever client pressed play.
 *
 * ## Why it is also the OutputRouter
 *
 * REQUIREMENTS.md "Output" puts route discovery in this module - "a repository over Room and HTTP that
 * opened a route-discovery callback would be keeping the radio awake from the wrong place entirely" - and
 * the picker needs it in domain types, so one object implements both interfaces rather than two singletons
 * both claiming to speak for the session. The actual platform work is [OutputRouteMonitor]'s; what is here
 * is the boundary, plus folding the live route into [PlaybackState.output] so every surface naming the
 * output names the same thing.
 */
@OptIn(UnstableApi::class)
@Singleton
public class Media3PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: PlaybackSettingsRepository,
    private val outputRoutes: OutputRouteMonitor,
) : PlaybackController, OutputRouter {

    private val catalogue = TrackCatalogue(libraryRepository)
    private val queueBuilder = QueueBuilder()
    private val connectLock = Mutex()
    private var controller: MediaController? = null

    // ------------------------------------------------------------------- the flows

    /**
     * The transport, the modes, the output and the armed sleep timer.
     *
     * Three sources, not one, because only the first of them is the session: the output target and the
     * sleep timer are things the player *shows* that Media3 has never heard of. Both are narrowed before
     * they are combined - the timer through a `map` and `distinctUntilChanged`, the output through
     * [namedOutput] - so an unrelated settings change, a crossfade length or an EQ band, cannot re-emit a
     * playback state that has not changed.
     */
    override fun observeState(): Flow<PlaybackState> = combine(
        sessionEvents(STATE_EVENTS),
        namedOutput(),
        armedSleepTimer(),
    ) { session, output, timer -> readState(session, output, timer) }
        .flowOn(Dispatchers.Main.immediate)
        .distinctUntilChanged()

    /**
     * Position, polled.
     *
     * `distinctUntilChanged` is not tidiness here, it is what lets a paused player settle. The poll keeps
     * running - a seek while paused does move the position, and stopping would miss it - but a paused
     * player reads the same numbers every time, and every emission of them is a recomposition of the
     * scrubber and a fresh `ProgressBarRangeInfo` on the accessibility tree. A stream of those is a
     * surface that never reaches idle: TalkBack re-announces, and automated tests that wait for
     * quiescence never get it. With the operator, a paused player emits nothing at all.
     *
     * A polling coroutine costs a delayed main-thread wake-up and no invalidation, which is why the poll
     * itself is left alone.
     */
    override fun observeProgress(): Flow<PlaybackProgress> = flow {
        val session: MediaController = connect()
        while (true) {
            emit(readProgress(session))
            // Backed off while paused. A scrubber that is not moving does not need four readings a second,
            // and this flow is collected by the widgets and Wear as well as by the player screen - so the
            // rate is a battery figure, not a rendering one. Still emitting rather than stopping, because a
            // seek while paused does move the position.
            delay(if (session.isPlaying) PROGRESS_TICK_MS else IDLE_TICK_MS)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Main.immediate)

    override fun observeQueue(): Flow<PlayQueue> = sessionEvents(QUEUE_EVENTS)
        .map { session -> readQueue(session) }
        .flowOn(Dispatchers.Main.immediate)
        .distinctUntilChanged()

    override suspend fun currentState(): PlaybackState = withContext(Dispatchers.Main.immediate) {
        readState(
            session = connect(),
            output = namedOutput().first(),
            sleepTimer = armedSleepTimer().first(),
        )
    }

    // ------------------------------------------------------------------- the output

    /**
     * The picker's rows, from [OutputRouteMonitor].
     *
     * Nothing is connected to the session to answer this, deliberately: opening the "Play on" sheet must
     * not be what starts the player service. The device list is a platform read and has no business
     * binding a `MediaController`.
     */
    override fun observeOutputTargets(): Flow<List<OutputTarget>> = outputRoutes.observeTargets()

    override fun observeActiveOutput(): Flow<OutputTarget?> = outputRoutes.observeActive()

    // ---------------------------------------------------------------- the transport

    override suspend fun play(): Unit = command { session ->
        if (session.mediaItemCount == 0) {
            // Nothing loaded: restore the crate rather than doing nothing, which is what a Bluetooth play
            // button after a restart expects.
            val restored: PlayQueue = settingsRepository.restorePersistedQueue()
            if (restored.items.isNotEmpty()) {
                loadQueue(session, restored.items, restored.currentIndex ?: 0)
            }
        }
        session.prepare()
        session.play()
    }

    override suspend fun pause(): Unit = command { session -> session.pause() }

    override suspend fun playPause(): Unit = command { session ->
        if (session.isPlaying) session.pause() else { session.prepare(); session.play() }
    }

    override suspend fun seekTo(positionMs: Long): Unit = command { session ->
        session.seekTo(positionMs.coerceAtLeast(0L))
    }

    override suspend fun skipToNext(): Unit = command { session -> session.seekToNextMediaItem() }

    /**
     * Back, with the restart-the-track threshold every other player has.
     *
     * The threshold lives here rather than in each caller: two surfaces implementing it themselves is two
     * surfaces disagreeing about what the same button does.
     */
    override suspend fun skipToPrevious(): Unit = command { session ->
        if (session.currentPosition > RESTART_THRESHOLD_MS || !session.hasPreviousMediaItem()) {
            session.seekTo(0L)
        } else {
            session.seekToPreviousMediaItem()
        }
    }

    override suspend fun skipToQueueItem(itemId: String): Unit = command { session ->
        val index: Int = indexOfRow(session, itemId)
        if (index >= 0) session.seekTo(index, 0L)
    }

    // -------------------------------------------------------------------- the crate

    override suspend fun playAlbum(mbid: ReleaseGroupMbid, startIndex: Int, shuffle: Boolean) {
        val tracks: List<Track> = libraryRepository.observeAlbumTracks(mbid).first()
        if (tracks.isEmpty()) return
        // Shuffle here rather than by turning on the session's shuffle mode: the album screen's Shuffle button
        // shuffles this album, and leaving global shuffle on afterwards would silently change how everything
        // the user queues next behaves.
        val ordered: List<Track> = if (shuffle) tracks.shuffled() else tracks
        val start: Int = if (shuffle) 0 else startIndex.coerceIn(0, ordered.lastIndex)
        command { session ->
            loadQueue(session, queueBuilder.rowsFor(ordered, QueueItemSource.COLLECTION), start)
            session.prepare()
            session.play()
        }
    }

    override suspend fun playTracks(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val start: Int = startIndex.coerceIn(0, tracks.lastIndex)
        command { session ->
            loadQueue(session, queueBuilder.rowsFor(tracks), start)
            session.prepare()
            session.play()
        }
    }

    /**
     * Add to the end, or straight after the Playing row.
     *
     * Row ids come from [QueueBuilder] with its default [QueueItemSource.USER], which is the honest
     * source for this command and the one difference from [playAlbum]: a record queued deliberately
     * is a user add, a record the user pressed Play on is a collection. The ids count **up** from 1
     * and `SessionRowIds` counts down from -1 for anything arriving from Auto or the browse tree, so
     * an add from here can never collide with one from there - see that class for the whole scheme.
     *
     * Nothing is written to the persisted crate here. `PlaybackCoordinator` persists on the
     * session's `onTimelineChanged`, which `addMediaItems` raises, so these rows survive a restart by
     * the same route as every other edit and there is exactly one writer of the persisted copy -
     * the rule `PlaybackSettingsRepository` states.
     *
     * ## The empty crate
     *
     * `addMediaItems` on a session with nothing in it leaves the player `IDLE`: the rows are in the
     * crate, every surface can see them, and nothing can play them until something calls `prepare`.
     * That is a crate the user is looking at and cannot start, so an add that filled an empty
     * session prepares it. It deliberately does **not** call `play`: this command never starts
     * sound, per [PlaybackController.enqueue], and a caller that wants the add to be heard uses
     * `playTracks`.
     */
    override suspend fun enqueue(tracks: List<Track>, playNext: Boolean) {
        if (tracks.isEmpty()) return
        val rows: List<QueueItem> = queueBuilder.rowsFor(tracks)
        catalogue.remember(tracks)
        command { session ->
            val items: List<MediaItem> = rows.map { catalogue.mediaItemFor(it.track, it.id) }
            val wasEmpty: Boolean = session.mediaItemCount == 0
            if (playNext) {
                val insertAt: Int = (session.currentMediaItemIndex + 1)
                    .coerceIn(0, session.mediaItemCount)
                session.addMediaItems(insertAt, items)
            } else {
                session.addMediaItems(items)
            }
            if (wasEmpty) session.prepare()
        }
    }

    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int): Unit = command { session ->
        val count: Int = session.mediaItemCount
        if (fromIndex in 0 until count && toIndex in 0 until count && fromIndex != toIndex) {
            // The session moves the item and follows the playing row itself, which is what PlayQueue's own
            // withItemMoved does: dragging a row past the Playing row must not change what is playing.
            session.moveMediaItem(fromIndex, toIndex)
        }
    }

    override suspend fun removeQueueItem(itemId: String): Unit = command { session ->
        val index: Int = indexOfRow(session, itemId)
        if (index >= 0) session.removeMediaItem(index)
    }

    override suspend fun clearQueue(): Unit = command { session ->
        session.stop()
        session.clearMediaItems()
    }

    // ---------------------------------------------------------------------- modes

    override suspend fun setShuffleEnabled(enabled: Boolean): Unit = command { session ->
        session.shuffleModeEnabled = enabled
    }

    override suspend fun setRepeatMode(mode: RepeatMode): Unit = command { session ->
        session.repeatMode = PlaybackStateMapper.toPlayerRepeatMode(mode)
    }

    override suspend fun setPlaybackSpeed(speed: PlaybackSpeed) {
        // Recorded as well as applied: the repository publishes the speed the coordinator re-applies on every
        // preferences emission, so a session rebuilt after a process death does not silently drop back to 1x.
        settingsRepository.setPlaybackSpeed(speed)
        command { session -> session.setPlaybackSpeed(speed.value) }
    }

    /**
     * Arms the sleep timer, and sends nothing to the session.
     *
     * There is nothing to send: Media3 has no sleep timer. `PlaybackCoordinator` collects
     * `observePlaybackPreferences` inside the service, evaluates `SleepTimerDecision` on its own position
     * tick and calls `stop()` on the player when the moment comes - which is the only arrangement that
     * also fires with no UI alive, and the only one that cannot fire while playback is paused. Posting a
     * delayed callback from here would do neither.
     *
     * Not connecting to the session is deliberate too: arming a timer must not be what starts the service.
     */
    override suspend fun setSleepTimer(timer: SleepTimer) {
        settingsRepository.setSleepTimer(timer)
    }

    override suspend fun stop(): Unit = command { session -> session.stop() }

    /**
     * Releases the connection to the session.
     *
     * The singleton holds a connected `MediaController` for the life of the process, which is deliberate: it is
     * what makes "play from tap, cached, under 150 ms" reachable, since a cold bind to the service costs more
     * than the budget on its own. A bound controller does keep `MediaSessionService` alive, but not in the
     * foreground and holding no wake lock while paused, so the cost is a bound service and nothing else.
     *
     * Provided for a host that does want to hand the connection back - a test, or a process that keeps the app
     * class alive far longer than any UI.
     */
    public suspend fun release() {
        withContext(Dispatchers.Main.immediate) {
            controller?.release()
            controller = null
        }
    }

    // ------------------------------------------------------------------- plumbing

    /**
     * Connects to the session, once, and waits rather than dropping a command issued during startup.
     *
     * Must be called on the main thread: a `MediaController` may only be touched from its application looper,
     * and every path here goes through [command] or a flow with `flowOn(Main)`.
     */
    private suspend fun connect(): MediaController {
        controller?.takeIf { it.isConnected }?.let { return it }
        return connectLock.withLock {
            controller?.takeIf { it.isConnected }?.let { return@withLock it }
            val token = SessionToken(
                context,
                ComponentName(context, NeedlerPlaybackService::class.java),
            )
            val session: MediaController = MediaController.Builder(context, token)
                .buildAsync()
                .awaitResult()
            controller = session
            session
        }
    }

    private suspend fun command(block: suspend (MediaController) -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            block(connect())
        }
    }

    /**
     * A flow that emits the connected controller whenever one of [events] fires, plus once on collection.
     *
     * The listener is attached per collector rather than once for the singleton, so a widget update that
     * collects for two hundred milliseconds leaves nothing behind.
     */
    private fun sessionEvents(events: IntArray): Flow<MediaController> = callbackFlow {
        val session: MediaController = connect()
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, playerEvents: Player.Events) {
                if (events.any { playerEvents.contains(it) }) trySend(session)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                trySend(session)
            }
        }
        session.addListener(listener)
        trySend(session)
        awaitClose { session.removeListener(listener) }
    }

    /** The armed timer alone, so a crossfade or EQ change on the same flow does not re-emit a state. */
    private fun armedSleepTimer(): Flow<SleepTimer> = settingsRepository.observePlaybackPreferences()
        .map { preferences -> preferences.sleepTimer }
        .distinctUntilChanged()

    /**
     * What to call the output: where sound is actually going, and the remembered choice only when the
     * platform will not say.
     *
     * REQUIREMENTS.md "Output": "The current output is always named in the player - 'Living room speaker',
     * 'This tablet' - so a user never wonders where sound is going." The remembered selection alone cannot
     * honour that, because it is where the user last pointed sound rather than where it is - a speaker
     * that drops out mid-track leaves the two disagreeing, and the player has to print the true one.
     *
     * `onStart` with a null is what keeps this safe to put inside a `combine` that feeds the lock screen,
     * the widgets and Wear: without it, a first route reading that never arrived would stall the whole
     * state flow and no surface would see playback at all. With it, the first emission is exactly what
     * this flow produced before the route monitor existed - the remembered selection - and the live route
     * replaces it when one is known.
     */
    private fun namedOutput(): Flow<OutputTarget?> = combine(
        outputRoutes.observeActive().onStart { emit(null) },
        settingsRepository.observeSelectedOutput(),
    ) { active, remembered -> active ?: remembered }
        .distinctUntilChanged()

    private suspend fun readState(
        session: MediaController,
        output: OutputTarget?,
        sleepTimer: SleepTimer,
    ): PlaybackState {
        val item: MediaItem? = session.currentMediaItem
        val track: Track? = catalogue.resolveOne(item?.mediaId)
        val row: QueueItem? = if (track != null && item != null) {
            QueueItem(id = item.mediaId, track = track)
        } else {
            null
        }
        return PlaybackStateMapper.toState(
            SessionSnapshot(
                currentItem = row,
                status = PlayerStatus.fromPlayerState(session.playbackState),
                playWhenReady = session.playWhenReady,
                durationMs = session.duration.takeIf { it > 0L },
                shuffleEnabled = session.shuffleModeEnabled,
                repeatMode = PlaybackStateMapper.toDomainRepeatMode(session.repeatMode),
                speed = speedOf(session),
                sleepTimer = sleepTimer,
                output = output,
                error = errorOf(session.playerError),
            ),
        )
    }

    // The buffered position is deliberately not read. `MediaController.bufferedPosition` creeps while a
    // paused stream pre-buffers, which is a value that genuinely changes while nothing a listener can see
    // does - so shipping it defeated `distinctUntilChanged` on observeProgress() and woke the player
    // screen, the widgets and Wear once a second to redraw an identical scrubber. Nothing draws a buffered
    // bar; PlaybackProgress says what adding one back would need first.
    private fun readProgress(session: MediaController): PlaybackProgress =
        PlaybackStateMapper.toProgress(positionMs = session.currentPosition)

    private suspend fun readQueue(session: MediaController): PlayQueue {
        val count: Int = session.mediaItemCount
        val ids: List<String> = (0 until count).map { session.getMediaItemAt(it).mediaId }
        catalogue.resolve(ids)
        val rows: List<QueueItem> = ids.mapNotNull { id ->
            catalogue.cached(id)?.let { track -> QueueItem(id = id, track = track) }
        }
        return PlayQueue(
            items = rows,
            currentIndex = session.currentMediaItemIndex.takeIf { it in rows.indices },
        )
    }

    private fun loadQueue(session: MediaController, rows: List<QueueItem>, startIndex: Int) {
        catalogue.remember(rows.map { it.track })
        val items: List<MediaItem> = rows.map { catalogue.mediaItemFor(it.track, it.id) }
        session.setMediaItems(items, startIndex.coerceIn(0, maxOf(items.lastIndex, 0)), 0L)
    }

    private fun indexOfRow(session: MediaController, itemId: String): Int {
        for (index in 0 until session.mediaItemCount) {
            if (session.getMediaItemAt(index).mediaId == itemId) return index
        }
        return -1
    }

    private fun speedOf(session: MediaController): PlaybackSpeed {
        val raw: Float = session.playbackParameters.speed
        return if (raw in PlaybackSpeed.RANGE) PlaybackSpeed(raw) else PlaybackSpeed.Normal
    }

    /**
     * The domain error behind a `PlaybackException`.
     *
     * The data source throws [NeedlerPlaybackException] with the real reason attached, and Media3 wraps it;
     * unwrapping is what turns "ERROR_CODE_IO_BAD_HTTP_STATUS" back into "the server's stream slots are gone".
     * Anything that did not come from Needler's own data source falls through to the transport table.
     */
    private fun errorOf(failure: PlaybackException?): NeedlerError? {
        if (failure == null) return null
        NeedlerPlaybackException.errorIn(failure)?.let { return it }
        val cause: Throwable = failure.cause ?: return NeedlerError.Unexpected(failure.errorCodeName, failure)
        if (cause is CancellationException) return null
        return PlaybackErrorMapper.fromTransport(cause)
    }

    private companion object {
        /** Several times a second, which is what the scrubber and the elapsed label want. */
        const val PROGRESS_TICK_MS: Long = 250L

        /** While paused nothing is moving on its own, so the tick backs off. */
        const val IDLE_TICK_MS: Long = 1_000L

        /** Past this into a track, "previous" restarts the track instead of leaving it. */
        const val RESTART_THRESHOLD_MS: Long = 3_000L

        /** `Player.Event`s that change something a person changed. */
        val STATE_EVENTS: IntArray = intArrayOf(
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_IS_PLAYING_CHANGED,
            Player.EVENT_REPEAT_MODE_CHANGED,
            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
            Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
            Player.EVENT_PLAYER_ERROR,
            Player.EVENT_MEDIA_METADATA_CHANGED,
        )

        /** `Player.Event`s that change the crate and nothing else. */
        val QUEUE_EVENTS: IntArray = intArrayOf(
            Player.EVENT_TIMELINE_CHANGED,
            Player.EVENT_MEDIA_ITEM_TRANSITION,
        )
    }
}

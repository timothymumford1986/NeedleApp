package app.needler.player.service.session

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.needler.core.domain.model.CrossfadeSettings
import app.needler.core.domain.model.EqSettings
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.PlaybackPreferences
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.ScrobbleEvent
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.model.Track
import app.needler.core.domain.repository.PlaybackSettingsRepository
import app.needler.player.service.audio.EqualiserAudioProcessor
import app.needler.player.service.audio.VolumeRamper
import app.needler.player.service.feature.FadePlan
import app.needler.player.service.feature.FadePlanner
import app.needler.player.service.feature.FadeReason
import app.needler.player.service.feature.ScrobbleTracker
import app.needler.player.service.feature.SleepAction
import app.needler.player.service.feature.SleepTimerDecision
import app.needler.player.service.feature.TransitionCause
import app.needler.player.service.media.TrackCatalogue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * Everything the session does that is not the transport itself: fades, the equaliser, scrobbling, the sleep
 * timer, the speed control, and keeping the persisted crate in step.
 *
 * It exists so [NeedlerPlaybackService] stays a service - bind, build a session, tear it down - and so this
 * behaviour has one owner rather than being spread across four listener callbacks.
 *
 * ## One writer for the crate
 *
 * `PlaybackSettingsRepository.savePersistedQueue` is called from here and from nowhere else in the app. The
 * live queue is the session's; the persisted copy is a restore point. Two writers would let the crate change
 * under the user while they are looking at it.
 *
 * ## Fades are volume ramps, and the crossfade does not overlap
 *
 * REQUIREMENTS.md's implementation note for crossfade says two `ExoPlayer` instances with volume ramps, and
 * that is what a true overlapping crossfade needs: one decode pipeline cannot render two tracks at once. This
 * coordinator drives **one** player, so what it implements is a boundary fade - the outgoing track ramps down
 * over the chosen length, the incoming one ramps up - rather than an overlap. The decision logic
 * ([FadePlanner]) and the ramp maths are identical either way, and the second player behind a forwarding
 * `Player` is the remaining work. It is named here rather than left to be discovered on a device.
 */
@OptIn(UnstableApi::class)
public class PlaybackCoordinator(
    private val player: ExoPlayer,
    private val settingsRepository: PlaybackSettingsRepository,
    private val catalogue: TrackCatalogue,
    private val equaliser: EqualiserAudioProcessor,
    private val scope: CoroutineScope,
    private val fadePlanner: FadePlanner = FadePlanner(),
    private val scrobbleTracker: ScrobbleTracker = ScrobbleTracker(),
    /** Repo convention: the clock is a lambda so a test can pin it. */
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val volume = VolumeRamper(scope, applyVolume = { value -> player.volume = value })

    private var preferences: PlaybackPreferences = PlaybackPreferences()
    private var crossfade: CrossfadeSettings = CrossfadeSettings()
    private var positionJob: Job? = null
    private var sawPreferences: Boolean = false

    /** The track the session is on, and its length, as of the last transition. */
    private var currentTrack: Track? = null
    private var currentDurationMs: Long? = null

    /**
     * Set by the session callback immediately before it issues a skip, and cleared once the transition lands.
     *
     * Media3 reports `MEDIA_ITEM_TRANSITION_REASON_SEEK` for a skip-to-next as well as for a scrubber drag, so
     * the player cannot tell the two apart on its own - and the difference is a one-second fade against no
     * fade at all.
     */
    public var nextTransitionIsManual: Boolean = false

    private val listener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val cause: TransitionCause = when {
                nextTransitionIsManual -> TransitionCause.MANUAL_SKIP
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> TransitionCause.AUTO_ADVANCE
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> TransitionCause.AUTO_ADVANCE
                else -> TransitionCause.SEEK
            }
            nextTransitionIsManual = false
            handleTransition(mediaItem, cause)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                scope.launch {
                    endCurrentPlay(playedToEnd = true)
                    evaluateSleepTimer(endOfTrackReached = true)
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) return
            val plan: FadePlan = fadePlanner.forResume(crossfade)
            volume.ramp(target = 1f, durationMs = plan.durationMs, curve = plan.curve)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            scope.launch { persistQueue() }
        }

        override fun onPlayerError(error: PlaybackException) {
            // The reason is already on the session for every surface to read. All that is left here is to stop
            // counting a play that did not happen.
            scrobbleTracker.clear()
        }
    }

    /** Starts observing settings and driving the player. Called once, from the service's `onCreate`. */
    public fun start() {
        player.addListener(listener)
        scope.launch {
            settingsRepository.observePlaybackPreferences().collect { latest -> applyPreferences(latest) }
        }
        scope.launch {
            settingsRepository.observeEqSettings().collect { settings -> applyEq(settings) }
        }
        scope.launch {
            settingsRepository.observeCrossfadeSettings().collect { settings -> crossfade = settings }
        }
        positionJob = scope.launch { tick() }
    }

    /** Detaches from the player. The player itself is released by the service. */
    public fun stop() {
        positionJob?.cancel()
        positionJob = null
        volume.cancel()
        player.removeListener(listener)
        scrobbleTracker.clear()
        currentTrack = null
        currentDurationMs = null
        sawPreferences = false
    }

    // -------------------------------------------------------------------- settings

    private fun applyPreferences(latest: PlaybackPreferences) {
        preferences = latest
        crossfade = latest.crossfade

        // A timed sleep timer is persisted, so one set last night is still in the settings this morning - and
        // already in the past. Acting on it would stop the track the user has just pressed play on, which reads
        // as the app refusing to play. The first reading after the service starts therefore disarms an elapsed
        // timer instead of firing it.
        if (!sawPreferences) {
            sawPreferences = true
            if (SleepTimerDecision.hasElapsed(latest.sleepTimer, Instant.fromEpochMilliseconds(nowMillis()))) {
                preferences = latest.copy(sleepTimer = SleepTimer.Off)
                scope.launch { settingsRepository.setSleepTimer(SleepTimer.Off) }
            }
        }
        val speed: Float = latest.speed.value
        if (player.getPlaybackParameters().speed != speed) {
            player.setPlaybackParameters(PlaybackParameters(speed))
        }
    }

    private fun applyEq(settings: EqSettings) {
        equaliser.setSettings(settings)
    }

    // -------------------------------------------------------------- track lifecycle

    private fun handleTransition(mediaItem: MediaItem?, cause: TransitionCause) {
        val from: Track? = currentTrack
        scope.launch {
            endCurrentPlay(playedToEnd = cause == TransitionCause.AUTO_ADVANCE)

            val to: Track? = catalogue.resolveOne(mediaItem?.mediaId)
            currentTrack = to
            currentDurationMs = null

            val plan: FadePlan = fadePlanner.forTransition(
                settings = crossfade,
                gaplessEnabled = preferences.gaplessEnabled,
                cause = cause,
                from = from,
                to = to,
            )
            if (plan.isNoOp) {
                volume.jumpTo(1f)
            } else {
                // On a single player the outgoing track is already gone by the time this callback arrives, so
                // what is left is to bring the incoming one up. The matching ramp down is the tail scheduled
                // by the position tick for a crossfade, and belongs to the second player for a true overlap.
                volume.jumpTo(0f)
                if (plan.gapMs > 0L) delay(plan.gapMs)
                volume.ramp(target = 1f, durationMs = plan.durationMs, curve = plan.curve)
            }

            if (to != null) {
                submit(
                    scrobbleTracker.onPlayStarted(
                        key = to.key,
                        handle = to.fetch,
                        at = Instant.fromEpochMilliseconds(nowMillis()),
                        enabled = preferences.scrobblingEnabled,
                    ),
                )
            }
            persistQueue()
        }
    }

    /**
     * Ends the play in progress.
     *
     * [playedToEnd] distinguishes a track that ran out from one the user skipped away from. The end-of-track
     * submission exists because a position tick can easily miss the halfway mark on a short track, and losing
     * a scrobble for a track played in full is the more annoying of the two failure modes.
     */
    private suspend fun endCurrentPlay(playedToEnd: Boolean) {
        val event: ScrobbleEvent? = if (playedToEnd) {
            scrobbleTracker.onPlayEnded(currentDurationMs, enabled = preferences.scrobblingEnabled)
        } else {
            scrobbleTracker.clear()
            null
        }
        submit(event)
    }

    // -------------------------------------------------------- the once-a-second work

    /**
     * The scrobble threshold, the sleep timer and the crossfade tail, on a slow tick.
     *
     * One second, not the several-times-a-second rate the scrubber wants. All three are decisions about whole
     * seconds, and the flow the player screen binds to is a separate one for exactly this reason: position
     * ticks must not invalidate anything but the scrubber.
     */
    private suspend fun tick() {
        while (scope.isActive) {
            delay(TICK_MS)
            if (!player.isPlaying) continue
            val duration: Long? = player.duration.takeIf { it > 0L }
            currentDurationMs = duration
            submit(
                scrobbleTracker.onPosition(
                    positionMs = player.currentPosition,
                    durationMs = duration,
                    enabled = preferences.scrobblingEnabled,
                ),
            )
            evaluateSleepTimer(endOfTrackReached = false)
            maybeStartCrossfadeTail(duration)
        }
    }

    /**
     * Starts the outgoing half of a crossfade when the track is close enough to its end.
     *
     * Driven from the position tick rather than from a timer armed at track start, because such a timer drifts
     * the moment the user changes the speed, seeks, or the stream stalls - and a crossfade that fires late cuts
     * the track off, which is worse than not fading at all.
     */
    private fun maybeStartCrossfadeTail(durationMs: Long?) {
        if (durationMs == null || !crossfade.isEnabled) return
        if (!player.hasNextMediaItem()) return
        val nextIndex: Int = player.getNextMediaItemIndex()
        if (nextIndex < 0 || nextIndex >= player.getMediaItemCount()) return
        val nextTrack: Track? = catalogue.cached(player.getMediaItemAt(nextIndex).mediaId)
        val plan: FadePlan = fadePlanner.forTransition(
            settings = crossfade,
            gaplessEnabled = preferences.gaplessEnabled,
            cause = TransitionCause.AUTO_ADVANCE,
            from = currentTrack,
            to = nextTrack,
        )
        if (plan.reason != FadeReason.CROSSFADE) return
        val lead: Long = fadePlanner.crossfadeStartOffsetMs(plan, durationMs)
        if (lead <= 0L) return
        val remaining: Long = durationMs - player.currentPosition
        if (remaining in 1..lead && !volume.isRamping && volume.currentVolume > 0f) {
            volume.ramp(target = 0f, durationMs = remaining, curve = plan.curve)
        }
    }

    // ----------------------------------------------------------------- sleep timer

    private fun evaluateSleepTimer(endOfTrackReached: Boolean) {
        val timer: SleepTimer = preferences.sleepTimer
        when (SleepTimerDecision.evaluate(timer, Instant.fromEpochMilliseconds(nowMillis()), endOfTrackReached)) {
            SleepAction.NONE, SleepAction.STOP_AT_END_OF_TRACK -> Unit
            SleepAction.STOP -> {
                val plan: FadePlan = fadePlanner.forPause(crossfade)
                volume.ramp(target = 0f, durationMs = plan.durationMs, curve = plan.curve) {
                    // Stop, not pause: the sleep timer releases audio focus and leaves the crate intact, so the
                    // queue is still there in the morning.
                    player.stop()
                    volume.jumpTo(1f)
                }
                scope.launch { settingsRepository.setSleepTimer(SleepTimer.Off) }
            }
        }
    }

    // ----------------------------------------------------------------------- fades

    /** Ramps down and pauses, honouring the fade-on-pause setting. Called by the session callback. */
    public fun pauseWithFade() {
        val plan: FadePlan = fadePlanner.forPause(crossfade)
        if (plan.isNoOp) {
            player.pause()
            return
        }
        volume.ramp(target = 0f, durationMs = plan.durationMs, curve = plan.curve) {
            player.pause()
            // The volume is restored while paused, so a resume from any other surface - the lock screen, a
            // Bluetooth button, Auto - is never silent because a resume ramp did not run.
            volume.jumpTo(1f)
        }
    }

    // ------------------------------------------------------------------- the crate

    private suspend fun persistQueue() {
        val count: Int = player.getMediaItemCount()
        val rows: MutableList<QueueItem> = ArrayList(count)
        for (index in 0 until count) {
            val item: MediaItem = player.getMediaItemAt(index)
            val track: Track = catalogue.resolveOne(item.mediaId) ?: continue
            rows.add(QueueItem(id = item.mediaId, track = track))
        }
        val current: Int = player.getCurrentMediaItemIndex()
        settingsRepository.savePersistedQueue(
            PlayQueue(items = rows, currentIndex = current.takeIf { it in rows.indices }),
        )
    }

    private suspend fun submit(event: ScrobbleEvent?) {
        if (event == null) return
        // The repository journals this into the offline write queue when there is no network, and re-resolves
        // the Subsonic track id from the mirror when the queue drains - never replaying the id captured here,
        // which a quality upgrade in the meantime would have moved on.
        settingsRepository.submitScrobble(event)
    }

    public companion object {
        internal const val TICK_MS: Long = 1_000L
    }
}

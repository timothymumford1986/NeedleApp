package app.needler.core.domain.playback

import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.PlaybackSpeed
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.model.Track
import app.needler.core.domain.repository.PlaybackSettingsRepository
import kotlinx.coroutines.flow.Flow

/**
 * The boundary between everything that shows or drives playback and the one Media3 session.
 *
 * Playback runs through a single `MediaLibraryService`, so the lock screen, the widgets, Android
 * Auto, Bluetooth controls and Wear all drive the same session; the app's UI is one more client of
 * that session, not the owner of the player. This interface is what "one more client" means in code.
 *
 * ## Why it lives in `:core:domain`
 *
 * Without it, `:feature:player` reaches for `MediaController` and the session's own types, and once
 * one feature module depends on Media3 the layering rule is decoration. So the contract is declared
 * here, in domain types, and `:player:service` implements it over the Media3 session while `:app`
 * provides the binding. Three consequences, all deliberate:
 *
 *  * `:feature:player` has **no Media3 dependency**. It renders state and calls methods.
 *  * The widgets and any other surface read playback through this interface and the repositories,
 *    never through `:core:data`.
 *  * Testing a player screen with no session running, or swapping the player implementation, needs
 *    no fake Media3.
 *
 * ## Two flows, deliberately
 *
 * [observeProgress] ticks several times a second while [observeState] changes only when something a
 * human did changes. Folding position into one combined state object would recompose the whole
 * player screen - artwork, titles, queue, transport - on every tick, on a screen that is often
 * foreground for an entire album. The split is the difference between one small text node redrawing
 * and the lot, so the two must not be merged back together for tidiness.
 *
 * [observeQueue] is a third flow for the same reason in reverse: the crate is a long list that
 * changes rarely and is rendered on its own screen, so a queue edit must not invalidate the
 * transport and a transport change must not re-diff a 300-row list.
 *
 * ## Commands do not report their own failures
 *
 * Every command returns `Unit`. A failure surfaces on [PlaybackState.error] instead, because the
 * session is shared: a track that will not play must be visible to the lock screen, Auto and Wear,
 * not only to whichever client happened to press play. Commands suspend because connecting to the
 * session is asynchronous - a command issued during startup waits for the connection rather than
 * being dropped.
 *
 * ## Who owns the crate
 *
 * **This controller is the authority on the live queue** - it is the session's own queue, and the
 * session is what is actually playing. [PlaybackSettingsRepository] persists the crate so it
 * survives a restart, and is written by the player service alone. Every mutation therefore goes
 * through the controller; nothing else writes the persisted copy, or the two disagree about what is
 * playing and the user watches the crate change under them. See [PlaybackSettingsRepository] for the
 * other half of this note.
 */
public interface PlaybackController {

    /**
     * Everything about playback that a person changed: what is playing, whether it is playing, and
     * the modes. Emits on change only, so a player screen bound to it does not recompose on a tick.
     */
    public fun observeState(): Flow<PlaybackState>

    /**
     * The playback position, which moves several times a second.
     *
     * Kept apart from [observeState] so that only the scrubber and the elapsed-time label depend on
     * it. The emission rate is the implementation's business; a client that wants fewer updates
     * samples this flow rather than asking the session to tick more slowly, since the same session
     * feeds the widgets and Wear.
     *
     * **Implementations must not emit a value equal to the last one.** A paused player's position does
     * not move, and this flow is collected by the widgets and Wear as well as by the player screen, so
     * an unchanged re-emission wakes three surfaces to redraw the same pixels. See [PlaybackProgress]
     * for the field that was removed because it defeated exactly that.
     */
    public fun observeProgress(): Flow<PlaybackProgress>

    /**
     * The live crate: the Playing row and Up next, as the session holds it right now.
     *
     * This is the flow the crate screen binds to. The persisted copy in
     * [PlaybackSettingsRepository.observePersistedQueue] is a restore point, not a second source of
     * truth.
     */
    public fun observeQueue(): Flow<PlayQueue>

    /** A snapshot for callers that cannot collect - a widget update, a one-shot Auto browse. */
    public suspend fun currentState(): PlaybackState

    public suspend fun play()

    public suspend fun pause()

    /** The transport's primary button: play when paused, pause when playing. */
    public suspend fun playPause()

    /** Seeks within the current item. Positions outside the track are clamped by the session. */
    public suspend fun seekTo(positionMs: Long)

    public suspend fun skipToNext()

    /**
     * Skips backwards.
     *
     * Past a short threshold into a track this restarts the track instead of leaving it, which is
     * what every other player does and what users press it expecting. The threshold belongs to the
     * implementation; callers must not implement it themselves, or two surfaces disagree about what
     * the same button does.
     */
    public suspend fun skipToPrevious()

    /** Jumps to one row of the crate, by [QueueItem.id] rather than by index. */
    public suspend fun skipToQueueItem(itemId: String)

    /**
     * Replaces the crate with an album and starts playing.
     *
     * [startIndex] is the track tapped; [shuffle] is the Shuffle button on album detail, which
     * shuffles the album's own tracks rather than turning on the global shuffle mode for everything
     * that follows.
     */
    public suspend fun playAlbum(
        mbid: ReleaseGroupMbid,
        startIndex: Int = 0,
        shuffle: Boolean = false,
    )

    /**
     * Replaces the crate with [tracks] and starts playing at [startIndex]: a playlist, a search
     * result, a genre or the favourites list.
     */
    public suspend fun playTracks(tracks: List<Track>, startIndex: Int = 0)

    /**
     * Adds tracks to the crate without disturbing what is playing.
     *
     * [playNext] is the difference between the product's two actions: "Play next" inserts directly
     * after the Playing row, "Add to crate" appends.
     *
     * ## Adding to an empty crate is not the same as adding to a playing one
     *
     * **This never starts sound.** Adding to a crate that is playing leaves the current track
     * exactly where it is, which is the whole promise of the two actions; adding to an empty crate
     * therefore leaves a loaded crate and silence. Implementations make those rows *playable* - the
     * session is prepared, so the transport on any surface starts them - and nothing more.
     *
     * A caller that wants the add to be audible when nothing is loaded calls [playTracks] instead,
     * and has to choose between them before the command: `currentState().hasCurrentItem` is the
     * test, and the library features share one helper that makes the choice and reports which of
     * the two happened, because "added to the crate" and "started playing" are different sentences
     * to the person who tapped. Deciding it here instead was rejected: a caller that cannot tell
     * which happened cannot say so, and an `enqueue` that sometimes starts playback would break the
     * guarantee the lock screen, Auto and Wear read this interface for.
     *
     * The added rows persist like every other crate edit - REQUIREMENTS.md "Queue": the crate
     * "lives on the device and persists across restarts" - because the session's own timeline
     * change is what writes it, not the command that caused it.
     */
    public suspend fun enqueue(tracks: List<Track>, playNext: Boolean = false)

    /**
     * Drag-to-reorder. Indices are positions in [PlayQueue.items].
     *
     * Implementations apply [PlayQueue.withItemMoved] rather than recomputing the current index from
     * the indices, so dragging a row past the Playing row cannot change what is playing.
     */
    public suspend fun moveQueueItem(fromIndex: Int, toIndex: Int)

    /** Removes one row of the crate, by [QueueItem.id]. */
    public suspend fun removeQueueItem(itemId: String)

    /** The Clear action on the crate screen. Stops playback, because nothing is left to play. */
    public suspend fun clearQueue()

    public suspend fun setShuffleEnabled(enabled: Boolean)

    public suspend fun setRepeatMode(mode: RepeatMode)

    /** 0.5x to 2x; [PlaybackSpeed] refuses anything else, because Media3 accepts 0 and stalls. */
    public suspend fun setPlaybackSpeed(speed: PlaybackSpeed)

    /**
     * Arms or disarms the sleep timer - REQUIREMENTS.md "Player features": "End of track or a
     * duration".
     *
     * It belongs on the controller rather than only on [PlaybackSettingsRepository] because this
     * interface is the one surface every client already has: Now Playing, the tablet sidebar, the
     * widgets and Wear all hold a `PlaybackController` and none of them holds a repository. Declaring
     * the setter only on the repository is what left the timer unreachable - the decision, the flow
     * and the coordinator that acts on it were all in place with nothing able to turn it on.
     *
     * **Nothing is sent to the session.** Implementations write the armed timer to
     * [PlaybackSettingsRepository.setSleepTimer] and stop there: the player service evaluates the timer
     * on its own position tick and calls [stop] itself when it fires, which is the only way a timer can
     * also fire while the app's UI is not running. A `postDelayed` from here would keep running through
     * a pause, survive a crate clear, and be invisible to a test.
     *
     * The armed value comes back on [PlaybackState.sleepTimer], so a surface that sets it and a surface
     * that only shows it read the same field.
     */
    public suspend fun setSleepTimer(timer: SleepTimer)

    /**
     * Stops playback and releases the audio focus, leaving the crate intact.
     *
     * Distinct from [pause]: stop is what the sleep timer and the notification's dismiss do, and the
     * user can still see what was queued.
     */
    public suspend fun stop()
}

/**
 * Playback as a person changed it: the current item, the transport, and the modes.
 *
 * Deliberately carries no position. See [PlaybackController.observeProgress] for why.
 */
public data class PlaybackState(
    /** The crate row that is playing or paused, or null when nothing is loaded. */
    val currentItem: QueueItem? = null,
    val isPlaying: Boolean = false,
    /**
     * Buffering, which is not the opposite of [isPlaying]: a track re-buffering mid-stream is still
     * "playing" as far as the transport button is concerned, and flipping the button to a play icon
     * on every stall is how a player looks broken on a slow connection.
     */
    val isBuffering: Boolean = false,
    /**
     * Length of the current item in milliseconds, or null while it is unknown.
     *
     * It lives here rather than on [PlaybackProgress] so there is one holder of it: a scrubber reads
     * the length from the state it is already bound to and the position from the ticking flow.
     */
    val durationMs: Long? = null,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val speed: PlaybackSpeed = PlaybackSpeed.Normal,
    /**
     * The armed sleep timer, or [SleepTimer.Off].
     *
     * Here rather than only on [PlaybackSettingsRepository] so that every surface showing the transport
     * can show what is armed without taking a second dependency - the widgets and Wear hold this
     * interface and nothing else.
     *
     * **It carries no countdown, deliberately.** [SleepTimer.At] is an instant, and the reader
     * subtracts the clock from it when it draws; a "minutes remaining" field here would make this flow -
     * the one that emits only when a person changed something - emit once a second for as long as a
     * timer is armed, which is the exact cost [PlaybackController.observeProgress] is a separate flow to
     * avoid. One label ticking locally is cheaper than the whole player screen ticking.
     */
    val sleepTimer: SleepTimer = SleepTimer.Off,
    /**
     * Where sound is going, as the session is actually routing it.
     *
     * The picker itself - probing Cast receivers, listing Bluetooth sinks, persisting the choice -
     * belongs to [PlaybackSettingsRepository]. This field is what the session did with that choice,
     * so the player can always name the output even when a target dropped out underneath it.
     */
    val output: OutputTarget? = null,
    /**
     * The last playback failure, or null.
     *
     * Commands report failures here rather than returning them, because every surface sharing the
     * session needs to see the same failure. Cleared when a later command succeeds.
     */
    val error: NeedlerError? = null,
) {
    /** True when there is something to show a transport for. */
    public val hasCurrentItem: Boolean get() = currentItem != null

    public companion object {
        /** Nothing loaded: what every surface renders before the session connects. */
        public val Idle: PlaybackState = PlaybackState()
    }
}

/**
 * The ticking half of playback state, emitted several times a second while playing.
 *
 * Nothing here changes what the player screen *looks* like beyond the scrubber and two time labels,
 * which is the entire reason it is a separate flow from [PlaybackState].
 *
 * ## Why there is no buffered position
 *
 * There was one - `bufferedPositionMs`, documented as "how far the buffer reaches, for the lighter bar
 * drawn behind the scrubber". No surface ever drew that bar, REQUIREMENTS.md does not ask for one on any
 * of the three scrubbers it specifies (screens 07, 09 and 14), and the field could not have been drawn
 * honestly if one had: the rule it carried was that a track playing from the on-device store reports its
 * whole duration as buffered, because "a creeping buffer bar drawn over a local file is a lie", and the
 * only place that knows which of the two is happening is the data source inside the player service. The
 * controller reads the session, which does not say, so it passed the local-file flag as a constant
 * `false` - meaning a fully-downloaded album reported a creeping buffer, which is the lie the rule
 * existed to prevent.
 *
 * It also cost something every second. [PlaybackController.observeProgress] is `distinctUntilChanged`,
 * so a paused player stops re-emitting and every collector of it - the player screen, the widgets and
 * Wear - stops being woken. A paused stream still pre-buffers, so a buffered position *genuinely*
 * changes while nothing a listener can see does, and the operator cannot suppress what really differs.
 * Removing the field is what makes the paused case actually quiet.
 *
 * If a buffered bar is wanted later it needs two things this field never had: a `buffered` parameter on
 * `NeedlerScrubBar` in `:core:design`, and a session extra carrying the data source's
 * `isPlayingFromLocalFile` so the bar is true for the on-device case. Adding the field back without both
 * reinstates a value that is wrong in the normal case and emits once a second to say so.
 */
public data class PlaybackProgress(
    val positionMs: Long = 0L,
) {
    /**
     * Fraction of [durationMs] elapsed, 0f..1f, or null when the length is unknown.
     *
     * Takes the duration as a parameter rather than holding one, so that the value the scrubber
     * draws and the value the label prints cannot come from two different measurements of the same
     * track.
     */
    public fun fractionOf(durationMs: Long?): Float? {
        if (durationMs == null || durationMs <= 0L) return null
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    public companion object {
        public val Zero: PlaybackProgress = PlaybackProgress()
    }
}

/** Repeat, as the transport's third button cycles it. */
public enum class RepeatMode {
    /** Play the crate through and stop. */
    OFF,

    /** Repeat the current track. */
    ONE,

    /** Repeat the whole crate. */
    ALL,
    ;

    /** The next mode the button cycles to: off, all, one, off. */
    public val next: RepeatMode
        get() = when (this) {
            OFF -> ALL
            ALL -> ONE
            ONE -> OFF
        }
}

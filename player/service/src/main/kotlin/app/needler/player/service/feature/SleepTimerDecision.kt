package app.needler.player.service.feature

import app.needler.core.domain.model.SleepTimer
import kotlinx.datetime.Instant
import kotlin.time.Duration

/** What the sleep timer wants the player to do right now. */
public enum class SleepAction {

    /** Nothing. The timer is off, or its moment has not come. */
    NONE,

    /**
     * Stop, do not pause.
     *
     * REQUIREMENTS.md distinguishes the two on `PlaybackController`: stop releases audio focus and
     * leaves the crate intact, which is what someone falling asleep wants - the phone quiet and the
     * queue still there in the morning.
     */
    STOP,

    /** Arm a stop for the end of the current track, rather than cutting it off mid-song. */
    STOP_AT_END_OF_TRACK,
}

/**
 * The sleep timer as a pure function of the timer setting, the clock and where the track is.
 *
 * A timer implemented as a posted callback is a timer that fires in the wrong place: it keeps running
 * while playback is paused, it survives a crate clear, and it is invisible to a test. Evaluating it on
 * each position tick instead means there is one answer and it is reproducible.
 */
public object SleepTimerDecision {

    /**
     * @param endOfTrackReached true when the player has just finished an item, which is the only moment
     *   [SleepTimer.EndOfTrack] can act on.
     */
    public fun evaluate(
        timer: SleepTimer,
        now: Instant,
        endOfTrackReached: Boolean,
    ): SleepAction = when (timer) {
        SleepTimer.Off -> SleepAction.NONE

        SleepTimer.EndOfTrack ->
            if (endOfTrackReached) SleepAction.STOP else SleepAction.STOP_AT_END_OF_TRACK

        is SleepTimer.At -> if (now >= timer.instant) SleepAction.STOP else SleepAction.NONE
    }

    /**
     * How long is left, or null when nothing is counting down.
     *
     * Null for [SleepTimer.EndOfTrack] as well as for [SleepTimer.Off]: "until this track ends" is not a
     * duration until the track's length is known, and a UI that showed a number there would be guessing.
     */
    public fun remaining(timer: SleepTimer, now: Instant): Duration? = when (timer) {
        SleepTimer.Off, SleepTimer.EndOfTrack -> null
        is SleepTimer.At -> (timer.instant - now).takeIf { it.isPositive() }
    }

    /** True when the timer has already elapsed and should never have been kept. */
    public fun hasElapsed(timer: SleepTimer, now: Instant): Boolean = when (timer) {
        SleepTimer.Off, SleepTimer.EndOfTrack -> false
        is SleepTimer.At -> now >= timer.instant
    }
}

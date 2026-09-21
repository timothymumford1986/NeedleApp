package app.needler.player.service.feature

import app.needler.core.domain.model.ScrobbleEvent
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import kotlinx.datetime.Instant

/**
 * Decides when the two `scrobble` calls happen, and makes sure each happens exactly once per play.
 *
 * REQUIREMENTS.md: `submission=false` on track start, `submission=true` past the halfway point. Simple
 * to state and easy to get wrong in four ways, all of which this class exists to prevent:
 *
 *  1. **Double submission.** A position flow ticking several times a second crosses the halfway mark on
 *     every tick after it, not once. The submission is latched.
 *  2. **A repeat is a new play.** With repeat-one on, the same track playing again must scrobble again,
 *     so the latch is keyed on a play token that [onPlayStarted] advances rather than on the track.
 *  3. **Seeking past halfway is not listening.** A user who drags the scrubber to 90% has not heard the
 *     track. The halfway rule is applied against the furthest point actually *played*, which a seek
 *     forward does not advance.
 *  4. **The timestamp is the start of the play, not the moment of submission.** Scrobbles accrued
 *     offline are queued and submitted on reconnect with their original timestamps, or a commute's worth
 *     of listening all lands in the same minute on arrival.
 *
 * ## What this class does not do
 *
 * It does not submit. `PlaybackSettingsRepository.submitScrobble` does that, and it is also what queues
 * an offline scrobble into the write queue and **re-resolves the Subsonic track id at flush time** - the
 * id that goes on the wire is looked up from the mirror when the queue drains, never replayed from the
 * handle captured here, because a quality upgrade in between would otherwise scrobble the wrong file.
 * The handle still travels on the event as the fallback for a track that has since left the mirror.
 */
public class ScrobbleTracker(
    /** Fraction of the track that counts as listened. Half, per REQUIREMENTS.md. */
    private val submissionFraction: Float = DEFAULT_SUBMISSION_FRACTION,
    /**
     * Tracks shorter than this never produce a submission.
     *
     * A 4-second locked groove or a silent index track would otherwise scrobble on the two seconds it
     * takes to skip past it, and a record with twenty such tracks would fill a listening history with
     * things nobody heard.
     */
    private val minimumTrackDurationMs: Long = DEFAULT_MINIMUM_TRACK_DURATION_MS,
) {

    private var currentKey: TrackKey? = null
    private var currentHandle: TrackFetchHandle? = null
    private var startedAt: Instant? = null
    private var furthestPlayedMs: Long = 0L
    private var lastPositionMs: Long = 0L
    private var submitted: Boolean = false
    private var startReported: Boolean = false

    /**
     * A new play began.
     *
     * Returns the `submission=false` event, or null when scrobbling is off. Calling it again for the same
     * track is a new play: that is what repeat-one is.
     */
    public fun onPlayStarted(
        key: TrackKey,
        handle: TrackFetchHandle,
        at: Instant,
        enabled: Boolean = true,
    ): ScrobbleEvent? {
        currentKey = key
        currentHandle = handle
        startedAt = at
        furthestPlayedMs = 0L
        lastPositionMs = 0L
        submitted = false
        startReported = enabled
        if (!enabled) return null
        return ScrobbleEvent(
            trackKey = key,
            fetchHandle = handle,
            playedAt = at,
            submission = false,
        )
    }

    /**
     * A position tick.
     *
     * Returns the `submission=true` event the first time the furthest point actually played passes the
     * halfway mark, and null every other time.
     *
     * @param positionMs where the player is now.
     * @param durationMs the track's length, or null while it is still unknown - a stream whose duration
     *   has not arrived yet cannot have a halfway point, so nothing is submitted until it does.
     */
    public fun onPosition(
        positionMs: Long,
        durationMs: Long?,
        enabled: Boolean = true,
    ): ScrobbleEvent? {
        val key: TrackKey = currentKey ?: return null
        val handle: TrackFetchHandle = currentHandle ?: return null
        val at: Instant = startedAt ?: return null

        // A forward jump larger than one tick is a seek, and a seek is not listening. Only continuous
        // progress advances the furthest-played mark.
        val advanced: Boolean = positionMs > lastPositionMs &&
            positionMs - lastPositionMs <= CONTINUOUS_TICK_TOLERANCE_MS
        if (advanced) furthestPlayedMs = maxOf(furthestPlayedMs, positionMs)
        lastPositionMs = positionMs

        if (!enabled || submitted) return null
        if (durationMs == null || durationMs < minimumTrackDurationMs) return null
        val threshold: Long = (durationMs * submissionFraction).toLong()
        if (furthestPlayedMs < threshold) return null

        submitted = true
        return ScrobbleEvent(
            trackKey = key,
            fetchHandle = handle,
            playedAt = at,
            submission = true,
        )
    }

    /**
     * The track ran to its end.
     *
     * Returns the submission if the halfway mark had not been observed. A short final buffer, a stream
     * that ended a little early, or a tick that simply did not land near the middle would otherwise lose
     * a scrobble for a track that was played in full.
     */
    public fun onPlayEnded(durationMs: Long?, enabled: Boolean = true): ScrobbleEvent? {
        val key: TrackKey = currentKey ?: return null
        val handle: TrackFetchHandle = currentHandle ?: return null
        val at: Instant = startedAt ?: return null
        val finished: Boolean = durationMs == null ||
            furthestPlayedMs >= (durationMs * COMPLETION_FRACTION).toLong()
        if (!enabled || submitted || !finished) {
            clear()
            return null
        }
        if (durationMs != null && durationMs < minimumTrackDurationMs) {
            clear()
            return null
        }
        submitted = true
        val event = ScrobbleEvent(
            trackKey = key,
            fetchHandle = handle,
            playedAt = at,
            submission = true,
        )
        clear()
        return event
    }

    /** Abandons the current play without submitting. A stop, a crate clear, a failure. */
    public fun clear() {
        currentKey = null
        currentHandle = null
        startedAt = null
        furthestPlayedMs = 0L
        lastPositionMs = 0L
        submitted = false
        startReported = false
    }

    /** For assertions and diagnostics: whether the halfway submission has gone out for this play. */
    public val hasSubmitted: Boolean get() = submitted

    /** For assertions and diagnostics: whether a start was reported for this play. */
    public val hasReportedStart: Boolean get() = startReported

    public companion object {
        public const val DEFAULT_SUBMISSION_FRACTION: Float = 0.5f
        public const val DEFAULT_MINIMUM_TRACK_DURATION_MS: Long = 30_000L

        /**
         * The largest forward jump still counted as continuous play.
         *
         * The position flow ticks several times a second, so a second and a half covers a slow tick and a
         * re-buffer while staying far below any seek a person would make.
         */
        public const val CONTINUOUS_TICK_TOLERANCE_MS: Long = 1_500L

        /** How much of a track must have been played for its end to count as having finished it. */
        public const val COMPLETION_FRACTION: Float = 0.5f
    }
}

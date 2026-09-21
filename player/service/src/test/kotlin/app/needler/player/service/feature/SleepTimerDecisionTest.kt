package app.needler.player.service.feature

import app.needler.core.domain.model.SleepTimer
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The sleep timer, as a function rather than a posted callback.
 *
 * A callback keeps running while playback is paused, survives a crate clear, and is invisible to a test.
 * Evaluating it on each tick means there is one answer and it is reproducible.
 */
class SleepTimerDecisionTest {

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    @Test
    fun `off does nothing`() {
        assertEquals(
            SleepAction.NONE,
            SleepTimerDecision.evaluate(SleepTimer.Off, now, endOfTrackReached = true),
        )
    }

    @Test
    fun `end of track waits for the track to end`() {
        assertEquals(
            SleepAction.STOP_AT_END_OF_TRACK,
            SleepTimerDecision.evaluate(SleepTimer.EndOfTrack, now, endOfTrackReached = false),
        )
        assertEquals(
            SleepAction.STOP,
            SleepTimerDecision.evaluate(SleepTimer.EndOfTrack, now, endOfTrackReached = true),
        )
    }

    @Test
    fun `a timed stop fires at its moment and not before`() {
        val at = SleepTimer.At(now + 30.minutes)

        assertEquals(SleepAction.NONE, SleepTimerDecision.evaluate(at, now, endOfTrackReached = false))
        assertEquals(
            SleepAction.STOP,
            SleepTimerDecision.evaluate(at, now + 30.minutes, endOfTrackReached = false),
        )
        assertEquals(
            SleepAction.STOP,
            SleepTimerDecision.evaluate(at, now + 31.minutes, endOfTrackReached = false),
        )
    }

    @Test
    fun `remaining counts down and is null when nothing is counting`() {
        assertEquals(30.minutes, SleepTimerDecision.remaining(SleepTimer.At(now + 30.minutes), now))
        assertNull(SleepTimerDecision.remaining(SleepTimer.At(now), now))
        assertNull(SleepTimerDecision.remaining(SleepTimer.Off, now))
        // "Until this track ends" is not a duration until the track's length is known, and a UI showing a
        // number there would be guessing.
        assertNull(SleepTimerDecision.remaining(SleepTimer.EndOfTrack, now))
    }

    @Test
    fun `an elapsed timer is recognised as elapsed`() {
        assertTrue(SleepTimerDecision.hasElapsed(SleepTimer.At(now - 1.minutes), now))
        assertFalse(SleepTimerDecision.hasElapsed(SleepTimer.At(now + 1.minutes), now))
        assertFalse(SleepTimerDecision.hasElapsed(SleepTimer.EndOfTrack, now))
    }
}

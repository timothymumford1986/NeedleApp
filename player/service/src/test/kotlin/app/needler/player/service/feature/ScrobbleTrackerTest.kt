package app.needler.player.service.feature

import app.needler.core.domain.model.ScrobbleEvent
import app.needler.player.service.Fixtures
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `submission=false` on start, `submission=true` past halfway - and each exactly once per play.
 *
 * Four ways to get that wrong, all of them tested here: submitting on every tick after the midpoint, failing to
 * scrobble a repeat, counting a drag of the scrubber as listening, and stamping the submission with the time it
 * was sent rather than the time the track started.
 */
class ScrobbleTrackerTest {

    private val startedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val track = Fixtures.track(durationMs = 240_000L)

    private fun tracker() = ScrobbleTracker()

    @Test
    fun `a play reports a start with submission false`() {
        val event: ScrobbleEvent? = tracker().onPlayStarted(track.key, track.fetch, startedAt)

        assertNotNull(event)
        assertFalse(event!!.submission)
        assertEquals(track.key, event.trackKey)
        assertEquals(startedAt, event.playedAt)
    }

    @Test
    fun `the submission goes out once, past halfway`() {
        val tracker = tracker()
        tracker.onPlayStarted(track.key, track.fetch, startedAt)

        // Continuous play up to and past the midpoint, one second at a time.
        var event: ScrobbleEvent? = null
        var position = 0L
        while (position <= 130_000L) {
            val emitted = tracker.onPosition(position, track.durationMs)
            if (emitted != null) {
                assertNull("submitted twice", event)
                event = emitted
            }
            position += 1_000L
        }

        assertNotNull(event)
        assertTrue(event!!.submission)
        // The timestamp is the start of the play, not the moment of submission: a commute's worth of offline
        // listening must not all land in the same minute on reconnect.
        assertEquals(startedAt, event.playedAt)
    }

    /** Dragging the scrubber to 90% is not listening to 90% of the track. */
    @Test
    fun `seeking past the midpoint does not submit`() {
        val tracker = tracker()
        tracker.onPlayStarted(track.key, track.fetch, startedAt)

        tracker.onPosition(1_000L, track.durationMs)
        val afterSeek = tracker.onPosition(220_000L, track.durationMs)

        assertNull(afterSeek)
        assertFalse(tracker.hasSubmitted)
    }

    @Test
    fun `a repeat of the same track scrobbles again`() {
        val tracker = tracker()
        tracker.onPlayStarted(track.key, track.fetch, startedAt)
        playThrough(tracker)
        assertTrue(tracker.hasSubmitted)

        val secondStart = tracker.onPlayStarted(track.key, track.fetch, startedAt)

        assertNotNull(secondStart)
        assertFalse(tracker.hasSubmitted)
        assertNotNull(playThrough(tracker))
    }

    @Test
    fun `a track played to its end submits even if no tick landed near the middle`() {
        val tracker = tracker()
        tracker.onPlayStarted(track.key, track.fetch, startedAt)
        // One long tick straight past the midpoint would look like a seek, so play continuously to the end.
        var position = 0L
        while (position <= 239_000L) {
            tracker.onPosition(position, track.durationMs, enabled = false)
            position += 1_000L
        }

        val event = tracker.onPlayEnded(track.durationMs)

        assertNotNull(event)
        assertTrue(event!!.submission)
    }

    @Test
    fun `a track abandoned early submits nothing`() {
        val tracker = tracker()
        tracker.onPlayStarted(track.key, track.fetch, startedAt)
        tracker.onPosition(5_000L, track.durationMs)

        assertNull(tracker.onPlayEnded(track.durationMs))
    }

    /** A four-second locked groove scrobbled on the two seconds it takes to skip past it is noise. */
    @Test
    fun `a very short track never submits`() {
        val shortTrack = Fixtures.track(number = 2, durationMs = 4_000L)
        val tracker = tracker()
        tracker.onPlayStarted(shortTrack.key, shortTrack.fetch, startedAt)
        tracker.onPosition(1_000L, shortTrack.durationMs)
        tracker.onPosition(2_000L, shortTrack.durationMs)
        tracker.onPosition(3_000L, shortTrack.durationMs)

        assertFalse(tracker.hasSubmitted)
        assertNull(tracker.onPlayEnded(shortTrack.durationMs))
    }

    @Test
    fun `an unknown duration submits nothing until one arrives`() {
        val tracker = tracker()
        tracker.onPlayStarted(track.key, track.fetch, startedAt)

        var position = 0L
        while (position <= 130_000L) {
            assertNull(tracker.onPosition(position, durationMs = null))
            position += 1_000L
        }
    }

    @Test
    fun `scrobbling off reports nothing at all`() {
        val tracker = tracker()

        assertNull(tracker.onPlayStarted(track.key, track.fetch, startedAt, enabled = false))
        assertFalse(tracker.hasReportedStart)
        assertNull(playThrough(tracker, enabled = false))
    }

    @Test
    fun `clearing abandons the play`() {
        val tracker = tracker()
        tracker.onPlayStarted(track.key, track.fetch, startedAt)
        tracker.clear()

        assertNull(tracker.onPosition(200_000L, track.durationMs))
        assertNull(tracker.onPlayEnded(track.durationMs))
    }

    private fun playThrough(tracker: ScrobbleTracker, enabled: Boolean = true): ScrobbleEvent? {
        var event: ScrobbleEvent? = null
        var position = 0L
        while (position <= 130_000L) {
            tracker.onPosition(position, track.durationMs, enabled = enabled)?.let { event = it }
            position += 1_000L
        }
        return event
    }
}

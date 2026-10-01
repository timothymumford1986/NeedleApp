// kotlinx.datetime.Instant is a typealias for kotlin.time.Instant; the note at the top of
// LibraryFormat.kt explains why the opt-in is declared rather than risked.
@file:OptIn(ExperimentalTime::class)

package app.needler.feature.player.ui

import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.CastAvailability
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.SleepTimer
import app.needler.feature.player.fake.PlayerFixtures
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The strings the player prints.
 *
 * Worth testing directly rather than through a rendered screen: the timecodes are the part of this
 * design the pack is fussiest about, an unknown duration has to read as unknown rather than as
 * `0:00`, and every Cast failure has to produce a different sentence - that being the whole point of
 * probing reachability before offering the target.
 */
class PlayerFormatTest {

    @Test
    fun `a timecode has no hours until there are hours`() {
        assertEquals("0:00", PlayerFormat.timecode(0L))
        assertEquals("0:07", PlayerFormat.timecode(7_400L))
        assertEquals("1:16", PlayerFormat.timecode(76_000L))
        assertEquals("21:04", PlayerFormat.timecode(1_264_000L))
        assertEquals("1:02:03", PlayerFormat.timecode(3_723_000L))
    }

    @Test
    fun `an unknown length is not zero`() {
        assertEquals(PlayerFormat.UNKNOWN_TIME, PlayerFormat.timecode(null))
        assertEquals(PlayerFormat.UNKNOWN_TIME, PlayerFormat.timecode(-1L))
        assertEquals(PlayerFormat.UNKNOWN_TIME, PlayerFormat.remaining(0L, null))
        assertEquals(PlayerFormat.UNKNOWN_TIME, PlayerFormat.remaining(0L, 0L))
    }

    @Test
    fun `remaining carries a leading minus and never goes below zero`() {
        assertEquals("-2:04", PlayerFormat.remaining(76_000L, 200_000L))
        // A position can overshoot the reported duration as a track changes over.
        assertEquals("-0:00", PlayerFormat.remaining(210_000L, 200_000L))
    }

    @Test
    fun `a duration is spoken rather than punctuated`() {
        assertEquals("3 minutes 20 seconds", PlayerFormat.spokenDuration(200_000L))
        assertEquals("1 minute 1 second", PlayerFormat.spokenDuration(61_000L))
        assertEquals("0 seconds", PlayerFormat.spokenDuration(0L))
        assertEquals("1 hour 2 minutes 3 seconds", PlayerFormat.spokenDuration(3_723_000L))
        assertEquals("length unknown", PlayerFormat.spokenDuration(null))
    }

    @Test
    fun `the crate summary is the pack's own line`() {
        // Screen 08: "6 tracks - 21 min".
        assertEquals("6 tracks · 21 min", PlayerFormat.crateSummary(6, 1_260_000L))
        assertEquals("1 track · 4 min", PlayerFormat.crateSummary(1, 200_000L))
        assertEquals("0 tracks", PlayerFormat.crateSummary(0, 0L))
        assertEquals("2 tracks · 1 hr 1 min", PlayerFormat.crateSummary(2, 3_660_000L))
    }

    @Test
    fun `a crate with something in it never reads as zero minutes`() {
        assertEquals("1 track · 1 min", PlayerFormat.crateSummary(1, 4_000L))
    }

    /**
     * The crate's two summaries disagreed with each other on a device.
     *
     * Visible: "11 tracks - 51 min". Spoken: "11 tracks, 50 minutes 22 seconds". 50:22 is not 51 min,
     * and both were describing the same crate: the visible line rounds up to the minute while the spoken
     * one went through `spokenDuration`, which truncates to the second because the sleep timer needs it
     * to. 3,022,000 ms is the case that separates them, which is why it is the case asserted first.
     */
    @Test
    fun `the spoken crate summary agrees with the printed one`() {
        assertEquals("11 tracks · 51 min", PlayerFormat.crateSummary(11, 3_022_000L))
        assertEquals("11 tracks, 51 minutes", PlayerFormat.spokenCrateSummary(11, 3_022_000L))

        // And across the shapes the printed line has, so the two cannot drift apart again by one of them
        // gaining an hour form, a singular, or a floor that the other does not have.
        for (millis in longArrayOf(0L, 1L, 4_000L, 60_000L, 200_000L, 1_260_000L, 3_022_000L, 3_660_000L, 7_200_000L)) {
            val printed: String = PlayerFormat.crateSummary(11, millis)
            val spoken: String = PlayerFormat.spokenCrateSummary(11, millis)
            assertEquals(
                "printed and spoken must report the same minutes at " + millis + " ms: " +
                    printed + " / " + spoken,
                PlayerFormat.crateMinutes(millis).takeIf { millis > 0L },
                minutesIn(spoken),
            )
            assertEquals(
                "and the printed side must report that same figure at " + millis + " ms: " + printed,
                PlayerFormat.crateMinutes(millis).takeIf { millis > 0L },
                minutesIn(printed),
            )
        }
    }

    @Test
    fun `the spoken crate summary says minutes in full, and an hour as an hour`() {
        assertEquals("0 tracks", PlayerFormat.spokenCrateSummary(0, 0L))
        assertEquals("1 track, 1 minute", PlayerFormat.spokenCrateSummary(1, 4_000L))
        assertEquals("6 tracks, 21 minutes", PlayerFormat.spokenCrateSummary(6, 1_260_000L))
        assertEquals("2 tracks, 1 hour 1 minute", PlayerFormat.spokenCrateSummary(2, 3_660_000L))
        assertEquals("2 tracks, 2 hours", PlayerFormat.spokenCrateSummary(2, 7_200_000L))
    }

    /** `spokenDuration` is untouched, because the sleep timer needs its truncating behaviour. */
    @Test
    fun `the sleep timer still counts down in seconds`() {
        assertEquals("59 seconds", PlayerFormat.spokenDuration(59_000L))
        assertEquals("3 minutes 20 seconds", PlayerFormat.spokenDuration(200_000L))
    }

    @Test
    fun `a track's place in the record reads the same printed and spoken`() {
        val item = PlayerFixtures.item("q1", PlayerFixtures.hamptons)
        assertEquals("Track 2", PlayerFormat.trackPosition(item))
        assertEquals("Disc 1, track 2", PlayerFormat.trackPosition(item, includeDisc = true))
        assertEquals("", PlayerFormat.trackPosition(null))
    }

    /**
     * Pulls the minute figure back out of either summary, so the two can be compared without this test
     * restating the formatting it is checking.
     */
    private fun minutesIn(summary: String): Long? {
        val hours: Long = Regex("(\\d+) (?:hr|hours?)").find(summary)?.groupValues?.get(1)?.toLong() ?: 0L
        val minutes: Long =
            Regex("(\\d+) (?:min|minutes?)\\b").find(summary)?.groupValues?.get(1)?.toLong() ?: 0L
        val total: Long = hours * 60L + minutes
        return total.takeIf { it > 0L }
    }

    @Test
    fun `a lossless badge carries no bitrate and a lossy one does`() {
        assertEquals("FLAC", PlayerFormat.formatBadge(AudioQuality(AudioFormat.FLAC, 1_411)))
        assertEquals("MP3 320", PlayerFormat.formatBadge(AudioQuality(AudioFormat.MP3, 320)))
        assertEquals("MP3", PlayerFormat.formatBadge(AudioQuality(AudioFormat.MP3, null)))
        assertNull(PlayerFormat.formatBadge(AudioQuality.Unknown))
        assertNull(PlayerFormat.formatBadge(null))
    }

    @Test
    fun `the output is always named, even before the session says anything`() {
        assertEquals("This device", PlayerFormat.outputName(null))
        assertEquals("Living room speaker", PlayerFormat.outputName(PlayerFixtures.livingRoomSpeaker))
    }

    @Test
    fun `the picker's second line says what kind of thing a target is`() {
        assertEquals("Speaker", PlayerFormat.outputDetail(PlayerFixtures.thisPhone))
        assertEquals(
            "Bluetooth · connected",
            PlayerFormat.outputDetail(PlayerFixtures.livingRoomSpeaker),
        )
        assertEquals("Bluetooth · nearby", PlayerFormat.outputDetail(PlayerFixtures.pixelBuds))
        assertEquals("Cast", PlayerFormat.outputDetail(PlayerFixtures.kitchen))
    }

    @Test
    fun `every reason Cast cannot work gets its own sentence`() {
        val reasons: List<String?> = listOf(
            CastAvailability.SERVER_NOT_REACHABLE,
            CastAvailability.SELF_SIGNED_CERTIFICATE,
            CastAvailability.INSECURE_HTTP,
            CastAvailability.UNKNOWN,
        ).map { availability ->
            PlayerFormat.unavailableReason(PlayerFixtures.unreachableCast(availability))
        }

        reasons.forEach { reason -> assertNotNull(reason) }
        assertEquals(
            "each unavailability must read differently",
            reasons.size,
            reasons.toSet().size,
        )
    }

    @Test
    fun `a reachable Cast target and a Bluetooth one have nothing to explain`() {
        assertNull(PlayerFormat.unavailableReason(PlayerFixtures.kitchen))
        assertNull(PlayerFormat.unavailableReason(PlayerFixtures.livingRoomSpeaker))
    }

    @Test
    fun `an unreachable Cast target cannot be selected`() {
        val target: OutputTarget =
            PlayerFixtures.unreachableCast(CastAvailability.SELF_SIGNED_CERTIFICATE)
        assertEquals(false, target.isSelectable)
    }

    @Test
    fun `the subtitle drops the album when there is not one`() {
        val item = PlayerFixtures.item("q1", PlayerFixtures.sienna.copy(albumTitle = null))
        assertEquals("The Marias", PlayerFormat.artistAndAlbum(item))
        assertEquals("The Marias · Submarine", PlayerFormat.artistAndAlbum(PlayerFixtures.playingItem))
        assertEquals("", PlayerFormat.artistAndAlbum(null))
    }

    // ---- the sleep timer ----------------------------------------------------

    @Test
    fun `an unarmed sleep timer names itself`() {
        assertEquals("Sleep timer", PlayerFormat.sleepTimerLabel(SleepTimer.Off, NOW))
        assertEquals("Sleep timer off", PlayerFormat.spokenSleepTimer(SleepTimer.Off, NOW))
    }

    @Test
    fun `end of track is not a countdown`() {
        assertEquals("End of track", PlayerFormat.sleepTimerLabel(SleepTimer.EndOfTrack, NOW))
        assertEquals(
            "Sleep timer, stopping at the end of this track",
            PlayerFormat.spokenSleepTimer(SleepTimer.EndOfTrack, NOW),
        )
    }

    @Test
    fun `a timed stop prints what is left, not what was chosen`() {
        assertEquals("30 min", PlayerFormat.sleepTimerLabel(SleepTimer.At(NOW + 30.minutes), NOW))
        assertEquals("24 min", PlayerFormat.sleepTimerLabel(SleepTimer.At(NOW + 24.minutes), NOW))
        assertEquals("1 hr", PlayerFormat.sleepTimerLabel(SleepTimer.At(NOW + 60.minutes), NOW))
        assertEquals(
            "1 hr 20 min",
            PlayerFormat.sleepTimerLabel(SleepTimer.At(NOW + 80.minutes), NOW),
        )
    }

    @Test
    fun `the last minute does not read as zero`() {
        // "0 min" on a timer that has not fired reads as a broken timer.
        assertEquals(
            "Less than a minute",
            PlayerFormat.sleepTimerLabel(SleepTimer.At(NOW + 40.seconds), NOW),
        )
    }

    @Test
    fun `an elapsed timer reads as unarmed, because it is about to be`() {
        val gone = SleepTimer.At(NOW - 1.minutes)
        assertEquals("Sleep timer", PlayerFormat.sleepTimerLabel(gone, NOW))
        assertEquals("Sleep timer off", PlayerFormat.spokenSleepTimer(gone, NOW))
        assertNull(SleepTimerOptions.selectedFor(gone, NOW))
    }

    @Test
    fun `a countdown is spoken in words rather than as a number`() {
        assertEquals(
            "Sleep timer, 24 minutes left",
            PlayerFormat.spokenSleepTimer(SleepTimer.At(NOW + 24.minutes), NOW),
        )
    }

    // ---- the sleep timer's choices ------------------------------------------

    @Test
    fun `a chosen duration becomes an instant that far ahead`() {
        assertEquals(
            SleepTimer.At(NOW + 45.minutes),
            SleepTimerOptions.timerFor(SleepTimerChoice.MINUTES_45, NOW),
        )
        assertEquals(SleepTimer.Off, SleepTimerOptions.timerFor(SleepTimerChoice.OFF, NOW))
        assertEquals(
            SleepTimer.EndOfTrack,
            SleepTimerOptions.timerFor(SleepTimerChoice.END_OF_TRACK, NOW),
        )
    }

    @Test
    fun `the lit pill is recovered from what is left`() {
        // The domain stores the moment to stop and not the pill that was pressed, so the pill is inferred:
        // the shortest offered duration that still covers the remaining time.
        assertEquals(
            SleepTimerChoice.MINUTES_30,
            SleepTimerOptions.selectedFor(SleepTimer.At(NOW + 30.minutes), NOW),
        )
        assertEquals(
            SleepTimerChoice.MINUTES_30,
            SleepTimerOptions.selectedFor(SleepTimer.At(NOW + 25.minutes), NOW),
        )
        assertEquals(
            SleepTimerChoice.MINUTES_15,
            SleepTimerOptions.selectedFor(SleepTimer.At(NOW + 10.minutes), NOW),
        )
        // Longer than anything offered - a timer armed from another surface - lights the longest.
        assertEquals(
            SleepTimerChoice.MINUTES_60,
            SleepTimerOptions.selectedFor(SleepTimer.At(NOW + 180.minutes), NOW),
        )
    }

    @Test
    fun `off and end of track select themselves exactly`() {
        assertEquals(SleepTimerChoice.OFF, SleepTimerOptions.selectedFor(SleepTimer.Off, NOW))
        assertEquals(
            SleepTimerChoice.END_OF_TRACK,
            SleepTimerOptions.selectedFor(SleepTimer.EndOfTrack, NOW),
        )
    }

    @Test
    fun `every choice has a printed label and a spoken one`() {
        for (choice in SleepTimerOptions.offered) {
            assertNotNull(SleepTimerOptions.label(choice))
            assertNotNull(SleepTimerOptions.spokenLabel(choice))
            // TalkBack must not be handed the printed form: "15 min" is read "fifteen min".
            assertEquals(
                "a spoken label should differ from the printed one for " + choice,
                false,
                SleepTimerOptions.label(choice) == SleepTimerOptions.spokenLabel(choice),
            )
        }
    }

    // ---- favourites ---------------------------------------------------------

    @Test
    fun `a refused favourite says which one of the two things went wrong`() {
        assertEquals(
            "Your account is not allowed to change favourites.",
            PlayerFormat.favouriteErrorMessage(NeedlerError.PermissionDenied()),
        )
        assertEquals(
            "That track is no longer on the server.",
            PlayerFormat.favouriteErrorMessage(NeedlerError.NotFound("track")),
        )
        // The fallback is about the favourite, not about playback: "That track would not play" would be
        // the wrong sentence entirely for a star.
        assertEquals(
            "That favourite did not reach the server.",
            PlayerFormat.favouriteErrorMessage(NeedlerError.RateLimited()),
        )
    }

    private companion object {
        /** A fixed clock, so every expectation above is arithmetic rather than a race. */
        val NOW: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    }
}

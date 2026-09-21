package app.needler.feature.player.ui

import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.CastAvailability
import app.needler.core.domain.model.OutputTarget
import app.needler.feature.player.fake.PlayerFixtures
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
}

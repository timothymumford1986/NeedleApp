package app.needler.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a bitrate is allowed to be stated on a badge.
 *
 * The device read `MP3 319` and `MP3 280` off two album rows. Both figures are the server's *average*
 * over a variable-bitrate file, so the last digit is a property of that particular encode's frame mix
 * and of the track's length rather than of the music - and a user reading 319 means 320.
 *
 * The two halves of the decision are both here, and the second is the one worth guarding:
 *
 *  * 319 reads as 320, because rounding to the nearest 10 kbps is below the precision an average has;
 *  * **280 stays 280**, because snapping to the ladder's rungs would be the app lying about the
 *    quality of the user's own library to tidy up a row.
 *
 * A later pass that decides banding is neater will change the first test and the second will stop it.
 */
class AudioQualityBadgeTest {

    @Test
    fun `a 319 average reads as the 320 it is`() {
        assertEquals(320, badgeFor(319))
        assertEquals(320, badgeFor(321))
        assertEquals(320, badgeFor(320))
    }

    /**
     * The whole point of rounding rather than banding.
     *
     * 280 is audibly and measurably less than 320. Rounding to the nearest 10 cannot move it there;
     * rounding to the nearest rung could, and that is why it is not what this does.
     */
    @Test
    fun `a 280 average is not rounded up to a rung it is not on`() {
        assertEquals(280, badgeFor(280))
        assertEquals(280, badgeFor(283))
        assertEquals(280, badgeFor(276))
    }

    @Test
    fun `the lower rungs snap to the figure the encoder wrote`() {
        assertEquals(256, badgeFor(258))
        assertEquals(192, badgeFor(192))
        assertEquals(128, badgeFor(128))
    }

    /**
     * A rung that is not a multiple of ten keeps its own figure, and that is the point.
     *
     * An earlier draft rounded everything to ten, which bought `319` reading as `320` and sold `192`
     * as `190` and `256` as `260` - figures no encoder has ever written. The rungs are checked first
     * now, so the exact values survive and only genuine averages are rounded. `280` is still `280`,
     * because it reaches no rung: 256 is 9.4% away and 320 is 12.5%, both outside the 2% window.
     */
    @Test
    fun `an exact rung is reported as itself, and a figure between rungs keeps its own`() {
        assertEquals(192, badgeFor(192))
        assertEquals(256, badgeFor(256))
        assertEquals(160, badgeFor(160))
        assertEquals(280, badgeFor(280))
    }

    /**
     * The window is 2%, wide enough to recognise a target and narrow enough not to invent one.
     *
     * A variable-bitrate encode aiming at 192 and averaging 190 is reported as the 192 it was aiming
     * at. A file averaging 310 is not close enough to 320 to claim it, so it keeps a rounded figure
     * of its own rather than being promoted into a quality it does not have.
     */
    @Test
    fun `a near miss snaps to its target and a far one does not`() {
        assertEquals(192, badgeFor(190))
        assertEquals(320, badgeFor(319))
        assertEquals(310, badgeFor(310))
    }

    @Test
    fun `nothing reported is nothing stated`() {
        assertNull(AudioQuality(format = AudioFormat.MP3, bitrateKbps = null).badgeBitrateKbps)
        assertNull(AudioQuality(format = AudioFormat.MP3, bitrateKbps = 0).badgeBitrateKbps)
        assertNull(AudioQuality(format = AudioFormat.MP3, bitrateKbps = -1).badgeBitrateKbps)
    }

    /**
     * A figure that would round to zero is floored instead of being drawn as `MP3 0`.
     *
     * Such a file is a server bug or a truncated header rather than an encode, and the lowest figure
     * the rounding can express is a prompt to go and look at it. Zero is a claim about the audio.
     */
    @Test
    fun `an absurdly low bitrate is floored rather than rounded to zero`() {
        assertEquals(AudioQuality.BADGE_ROUNDING, badgeFor(1))
        assertEquals(AudioQuality.BADGE_ROUNDING, badgeFor(4))
    }

    /** A lossless figure is still rounded; it is the badge that leaves it off, not this. */
    @Test
    fun `losslessness is not this property's business`() {
        assertEquals(890, AudioQuality(AudioFormat.FLAC, 891).badgeBitrateKbps)
    }

    private fun badgeFor(bitrateKbps: Int): Int? =
        AudioQuality(format = AudioFormat.MP3, bitrateKbps = bitrateKbps).badgeBitrateKbps
}

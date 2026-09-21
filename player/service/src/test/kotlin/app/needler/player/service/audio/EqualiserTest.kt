package app.needler.player.service.audio

import app.needler.core.domain.model.EqBand
import app.needler.core.domain.model.EqSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 10-band equaliser, checked against its own frequency response rather than by ear on a phone.
 *
 * REQUIREMENTS.md requires the bands to match DroppedNeedle's web player **exactly**: 31, 62, 125, 250, 500 Hz
 * and 1, 2, 4, 8, 16 kHz, plus or minus 12 dB, with a preamp. That is a claim a test can check, and this is
 * the test that checks it.
 */
class EqualiserTest {

    private val sampleRate = 44_100

    @Test
    fun `the band set is the one the web player uses`() {
        assertEquals(10, EqSettings.BAND_COUNT)
        assertEquals(
            listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000),
            EqBand.entries.map { it.centreFrequencyHz },
        )
        assertEquals(-12f..12f, EqSettings.GAIN_RANGE_DB)
    }

    /** A bell set to +6 dB must measure +6 dB at its own centre frequency. */
    @Test
    fun `a peaking filter hits its gain at the centre frequency`() {
        for (band in EqBand.entries) {
            val centre = band.centreFrequencyHz.toDouble()
            if (centre >= sampleRate * BiquadCoefficients.MAX_FREQUENCY_FRACTION) continue
            val design = BiquadCoefficients.peaking(centre, gainDb = 6.0, sampleRateHz = sampleRate)

            assertEquals(
                "band " + band.centreFrequencyHz,
                6.0,
                design.magnitudeDbAt(centre, sampleRate),
                0.05,
            )
        }
    }

    /** And must leave a decade away alone, or the sliders stop meaning what they say. */
    @Test
    fun `a peaking filter is local to its band`() {
        val design = BiquadCoefficients.peaking(1_000.0, gainDb = 12.0, sampleRateHz = sampleRate)

        assertTrue(abs(design.magnitudeDbAt(100.0, sampleRate)) < 1.0)
        assertTrue(abs(design.magnitudeDbAt(10_000.0, sampleRate)) < 1.0)
    }

    @Test
    fun `a flat band designs to a passthrough`() {
        assertTrue(
            BiquadCoefficients.peaking(1_000.0, gainDb = 0.0, sampleRateHz = sampleRate) ===
                BiquadCoefficients.Passthrough,
        )
    }

    /**
     * 16 kHz is above Nyquist on a 32 kHz file, and near it the bell design warps into an unpredictable lump.
     * Leaving the band out is the honest answer.
     */
    @Test
    fun `a band at or above Nyquist is left out`() {
        assertTrue(
            BiquadCoefficients.peaking(16_000.0, gainDb = 9.0, sampleRateHz = 32_000) ===
                BiquadCoefficients.Passthrough,
        )
    }

    // -------------------------------------------------------------------- the kernel

    @Test
    fun `a disabled equaliser is inactive and changes nothing`() {
        val kernel = EqualiserKernel()
        kernel.configure(sampleRate, channelCount = 2)
        kernel.setSettings(EqSettings.Default)

        assertFalse(kernel.isActive)

        val samples = shortArrayOf(100, -100, 2_000, -2_000)
        val original = samples.copyOf()
        kernel.process(samples, frameCount = 2)

        assertTrue(samples.contentEquals(original))
    }

    @Test
    fun `an enabled but flat equaliser is still inactive`() {
        val kernel = EqualiserKernel()
        kernel.configure(sampleRate, channelCount = 2)
        kernel.setSettings(EqSettings(isEnabled = true))

        assertFalse(kernel.isActive)
    }

    @Test
    fun `the preamp scales the signal`() {
        val kernel = EqualiserKernel()
        kernel.configure(sampleRate, channelCount = 1)
        kernel.setSettings(EqSettings(isEnabled = true, preampDb = -6f))

        assertTrue(kernel.isActive)

        val samples = shortArrayOf(1_000, -1_000)
        kernel.process(samples, frameCount = 2)

        // -6 dB is a factor of about 0.501.
        assertEquals(501.0, samples[0].toDouble(), 2.0)
        assertEquals(-501.0, samples[1].toDouble(), 2.0)
    }

    /**
     * Ten bands at +12 dB plus a preamp can add better than 20 dB to a mix mastered near full scale. A wrapped
     * sample flips from full positive to full negative in one step, which is a click on every peak; clipping
     * is merely loud.
     */
    @Test
    fun `overdriven samples saturate rather than wrap`() {
        val kernel = EqualiserKernel()
        kernel.configure(sampleRate, channelCount = 1)
        kernel.setSettings(EqSettings(isEnabled = true, preampDb = 12f))

        val samples = shortArrayOf(30_000, -30_000)
        kernel.process(samples, frameCount = 2)

        assertEquals(Short.MAX_VALUE, samples[0])
        assertEquals(Short.MIN_VALUE, samples[1])
    }

    /** A 1 kHz tone through a +12 dB 1 kHz band must come out louder, and by roughly the right amount. */
    @Test
    fun `boosting a band boosts a tone in that band`() {
        val gains = EqSettings.FlatGains.toMutableList()
        gains[EqBand.KHZ_1.ordinal] = 12f
        val kernel = EqualiserKernel()
        kernel.configure(sampleRate, channelCount = 1)
        kernel.setSettings(EqSettings(isEnabled = true, bandGainsDb = gains, preampDb = 0f))

        assertTrue(kernel.isActive)

        val frames = sampleRate / 2
        val tone = ShortArray(frames) { index ->
            (4_000.0 * sin(2.0 * PI * 1_000.0 * index / sampleRate)).toInt().toShort()
        }
        kernel.process(tone, frameCount = frames)

        // Measured over the second half, so the filter's start-up transient is not in the figure.
        val peak = tone.drop(frames / 2).maxOf { abs(it.toInt()) }
        assertTrue("peak was " + peak, peak > 14_000 && peak < 17_500)
    }

    @Test
    fun `a flush clears the filter history`() {
        val gains = EqSettings.FlatGains.toMutableList()
        gains[EqBand.HZ_31.ordinal] = 12f
        val kernel = EqualiserKernel()
        kernel.configure(sampleRate, channelCount = 1)
        kernel.setSettings(EqSettings(isEnabled = true, bandGainsDb = gains))

        val loud = ShortArray(1_000) { 20_000.toShort() }
        kernel.process(loud, frameCount = 1_000)
        kernel.flush()

        // After a flush the first sample of silence must still be silence: a leftover tail is the thump a
        // listener hears a few milliseconds into the next track.
        val silence = ShortArray(8)
        kernel.process(silence, frameCount = 8)
        assertTrue(silence.all { it.toInt() == 0 })
    }
}

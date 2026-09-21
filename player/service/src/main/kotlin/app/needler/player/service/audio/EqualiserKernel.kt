package app.needler.player.service.audio

import app.needler.core.domain.model.EqBand
import app.needler.core.domain.model.EqSettings
import kotlin.math.pow

/**
 * The 10-band equaliser and preamp, as arithmetic over 16-bit PCM.
 *
 * REQUIREMENTS.md rules out the platform `Equalizer` effect: it is a fixed number of bands at
 * frequencies the device chooses, it has no preamp, and it cannot be composed with the crossfade ramps
 * that have to run in the same chain. The bands are therefore designed here - 31, 62, 125, 250, 500 Hz
 * and 1, 2, 4, 8, 16 kHz at plus or minus 12 dB, exactly matching DroppedNeedle's web player - and this
 * class is the part with no Android in it, so all of it is tested on the JVM.
 *
 * ## Why the preamp is not optional
 *
 * Ten bells at +12 dB can add better than 20 dB to a mix that was already mastered near full scale, and
 * 16-bit samples have nowhere to put it: the result wraps, and wrapped samples are not loud, they are a
 * buzz. The preamp is the user's headroom control, and [process] hard-limits at full scale as a last
 * resort so a bad setting is quiet-and-distorted rather than destroyed.
 */
public class EqualiserKernel {

    private var sampleRateHz: Int = 0
    private var channelCount: Int = 0
    private var settings: EqSettings = EqSettings.Default

    /**
     * Per-band coefficients, recomputed only when the settings or the format change.
     *
     * Volatile and replaced wholesale rather than edited in place: the audio thread reads this while the
     * settings flow writes it from the main thread, and a half-updated array would be a filter designed for
     * two different gain settings at once - which is an audible burst, not a rounding error.
     */
    @Volatile
    private var coefficients: Array<BiquadCoefficients> = Array(EqSettings.BAND_COUNT) {
        BiquadCoefficients.Passthrough
    }

    /** `[band][channel]` filter history. Every entry is reset on a flush. */
    private var states: Array<Array<BiquadState>> = emptyArray()

    @Volatile
    private var preampGain: Double = 1.0

    /** True when this kernel would change the audio at all. False means the processor can be bypassed. */
    @Volatile
    public var isActive: Boolean = false
        private set

    /**
     * Sets the PCM format. Clears all history, because coefficients designed for one sample rate say
     * nothing about the state left over from another.
     */
    public fun configure(sampleRateHz: Int, channelCount: Int) {
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount
        states = Array(EqSettings.BAND_COUNT) { Array(channelCount.coerceAtLeast(1)) { BiquadState() } }
        redesign()
    }

    /**
     * Applies new settings.
     *
     * Filter history is deliberately kept: a gain change mid-track is a knob the user is turning, and
     * zeroing the history on every drag step is a click per step. The coefficients change under the same
     * state, which is what every mixing desk does.
     */
    public fun setSettings(settings: EqSettings) {
        this.settings = settings
        redesign()
    }

    /** Clears filter history. Called on seek and on a track transition. */
    public fun flush() {
        states.forEach { perChannel -> perChannel.forEach { it.reset() } }
    }

    /**
     * Filters [frameCount] frames of interleaved 16-bit samples in place.
     *
     * In place because this runs on the audio thread for every buffer of every track: allocating a
     * second array per buffer is a garbage collection every few seconds for no reason at all.
     */
    public fun process(samples: ShortArray, frameCount: Int, offset: Int = 0) {
        if (!isActive) return
        val channels: Int = channelCount.coerceAtLeast(1)
        val total: Int = frameCount * channels
        var index: Int = offset
        var frame = 0
        while (frame < frameCount) {
            var channel = 0
            while (channel < channels) {
                if (index >= offset + total) return
                var value: Double = samples[index] * preampGain
                var band = 0
                while (band < EqSettings.BAND_COUNT) {
                    val design: BiquadCoefficients = coefficients[band]
                    if (design !== BiquadCoefficients.Passthrough) {
                        value = states[band][channel].process(value, design)
                    }
                    band++
                }
                samples[index] = clampToPcm16(value)
                index++
                channel++
            }
            frame++
        }
    }

    private fun redesign() {
        preampGain = if (settings.isEnabled) dbToLinear(settings.preampDb.toDouble()) else 1.0
        coefficients = Array(EqSettings.BAND_COUNT) { ordinal ->
            if (!settings.isEnabled || sampleRateHz <= 0) {
                BiquadCoefficients.Passthrough
            } else {
                BiquadCoefficients.peaking(
                    centreFrequencyHz = EqBand.entries[ordinal].centreFrequencyHz.toDouble(),
                    gainDb = settings.bandGainsDb[ordinal].toDouble(),
                    sampleRateHz = sampleRateHz,
                )
            }
        }
        val anyBand: Boolean = coefficients.any { it !== BiquadCoefficients.Passthrough }
        val anyPreamp: Boolean = settings.isEnabled &&
            kotlin.math.abs(settings.preampDb) >= BiquadCoefficients.GAIN_EPSILON_DB
        isActive = sampleRateHz > 0 && channelCount > 0 && (anyBand || anyPreamp)
    }

    public companion object {

        /** Full scale for signed 16-bit PCM. */
        public const val PCM16_MAX: Int = Short.MAX_VALUE.toInt()

        /** Full scale the other way. */
        public const val PCM16_MIN: Int = Short.MIN_VALUE.toInt()

        public fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

        /**
         * Saturates rather than wraps.
         *
         * A wrapped sample flips from full positive to full negative in one step, which is a click on
         * every peak and sounds like a broken file. Clipping is merely loud.
         */
        public fun clampToPcm16(value: Double): Short = when {
            value >= PCM16_MAX.toDouble() -> Short.MAX_VALUE
            value <= PCM16_MIN.toDouble() -> Short.MIN_VALUE
            else -> kotlin.math.round(value).toInt().toShort()
        }
    }
}

package app.needler.player.service.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Normalised second-order IIR coefficients, `a0` already divided out.
 *
 * Plain doubles and no audio framework anywhere near them, so the filter design can be checked against
 * its own frequency response in a unit test rather than by ear on a phone.
 */
public data class BiquadCoefficients(
    public val b0: Double,
    public val b1: Double,
    public val b2: Double,
    public val a1: Double,
    public val a2: Double,
) {

    /**
     * Magnitude response in dB at [frequencyHz], for a filter running at [sampleRateHz].
     *
     * This is what makes the 10-band design testable: a band set to +6 dB must measure +6 dB at its
     * centre frequency and roughly nothing an octave and a half away. Evaluating `H(z)` on the unit
     * circle at `z = exp(j*omega)`.
     */
    public fun magnitudeDbAt(frequencyHz: Double, sampleRateHz: Int): Double {
        val omega: Double = 2.0 * PI * frequencyHz / sampleRateHz
        val cos1: Double = cos(omega)
        val sin1: Double = sin(omega)
        val cos2: Double = cos(2.0 * omega)
        val sin2: Double = sin(2.0 * omega)
        val numeratorReal: Double = b0 + b1 * cos1 + b2 * cos2
        val numeratorImaginary: Double = -(b1 * sin1 + b2 * sin2)
        val denominatorReal: Double = 1.0 + a1 * cos1 + a2 * cos2
        val denominatorImaginary: Double = -(a1 * sin1 + a2 * sin2)
        val numerator: Double = sqrt(numeratorReal * numeratorReal + numeratorImaginary * numeratorImaginary)
        val denominator: Double = sqrt(
            denominatorReal * denominatorReal + denominatorImaginary * denominatorImaginary,
        )
        if (denominator == 0.0) return Double.POSITIVE_INFINITY
        return 20.0 * log10(numerator / denominator)
    }

    public companion object {

        /** The identity filter: output equals input, used for a band that is flat or out of range. */
        public val Passthrough: BiquadCoefficients =
            BiquadCoefficients(b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0)

        /**
         * A peaking (bell) filter, from the Audio EQ Cookbook.
         *
         * Peaking rather than shelving for every band including the outer two. A 31 Hz low shelf and a
         * 16 kHz high shelf would each tilt everything beyond them, so two neighbouring bands set in
         * opposite directions would fight over the same octave and the control would stop matching what
         * the user hears. Ten bells at fixed centres is what DroppedNeedle's web player does, and
         * REQUIREMENTS.md requires the bands to match it exactly.
         *
         * Returns [Passthrough] for a zero gain - cheap, and exactly flat rather than nearly flat - and
         * for a centre frequency at or above Nyquist, which 16 kHz is on a 32 kHz file.
         */
        public fun peaking(
            centreFrequencyHz: Double,
            gainDb: Double,
            sampleRateHz: Int,
            q: Double = DEFAULT_Q,
        ): BiquadCoefficients {
            if (abs(gainDb) < GAIN_EPSILON_DB) return Passthrough
            if (sampleRateHz <= 0) return Passthrough
            // Above 45% of the sample rate the bell is no longer a bell: the design warps towards
            // Nyquist and a boost turns into an unpredictable lump. Leaving the band out is honest.
            if (centreFrequencyHz >= sampleRateHz * MAX_FREQUENCY_FRACTION) return Passthrough

            val a: Double = 10.0.pow(gainDb / 40.0)
            val omega: Double = 2.0 * PI * centreFrequencyHz / sampleRateHz
            val alpha: Double = sin(omega) / (2.0 * q)
            val cosOmega: Double = cos(omega)

            val b0: Double = 1.0 + alpha * a
            val b1: Double = -2.0 * cosOmega
            val b2: Double = 1.0 - alpha * a
            val a0: Double = 1.0 + alpha / a
            val a1: Double = -2.0 * cosOmega
            val a2: Double = 1.0 - alpha / a

            return BiquadCoefficients(
                b0 = b0 / a0,
                b1 = b1 / a0,
                b2 = b2 / a0,
                a1 = a1 / a0,
                a2 = a2 / a0,
            )
        }

        /**
         * Q for a one-octave bell: `sqrt(2) / (2 - 1)`.
         *
         * The ten bands are one octave apart (31, 62, 125 ... 16000), so a one-octave bandwidth is the
         * value that makes adjacent bands meet at their half-gain points instead of overlapping into a
         * single broad tilt.
         */
        public const val DEFAULT_Q: Double = 1.4142135623730951

        /** Below this the band is treated as flat. A tenth of a dB is inaudible and saves a filter. */
        public const val GAIN_EPSILON_DB: Double = 0.05

        /** Bands at or above this fraction of the sample rate are skipped. */
        public const val MAX_FREQUENCY_FRACTION: Double = 0.45
    }
}

/**
 * One biquad's running state for one channel, transposed direct form II.
 *
 * Transposed direct form II because it needs two state values instead of four and is the numerically
 * better-behaved of the cheap forms at the low end, which matters for the 31 Hz band where the poles sit
 * very close to the unit circle at 48 kHz.
 */
public class BiquadState {
    private var s1: Double = 0.0
    private var s2: Double = 0.0

    public fun process(sample: Double, coefficients: BiquadCoefficients): Double {
        val out: Double = coefficients.b0 * sample + s1
        s1 = coefficients.b1 * sample - coefficients.a1 * out + s2
        s2 = coefficients.b2 * sample - coefficients.a2 * out
        return out
    }

    /**
     * Clears the history.
     *
     * Called on every seek and every track transition. Not clearing it is audible: the tail of the
     * previous track's low end arrives as a thump a few milliseconds into the next one.
     */
    public fun reset() {
        s1 = 0.0
        s2 = 0.0
    }
}

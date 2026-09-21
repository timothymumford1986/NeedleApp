package app.needler.player.service.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The shape a volume ramp follows.
 *
 * Which curve is used is audible and the two are not interchangeable.
 */
public enum class FadeCurve {

    /**
     * Straight line in amplitude. Right for a fade to or from silence - a pause, a stop, the start of a
     * track - where there is nothing on the other side to add up with.
     */
    LINEAR,

    /**
     * Constant-power `sin`/`cos` pair. Right for a crossfade, where two ramps are summed.
     *
     * Two linear ramps crossing at 0.5 sum to half power at the midpoint, which is an audible dip in
     * the middle of every transition. The equal-power pair keeps `gainOut^2 + gainIn^2` at one, so the
     * loudness holds steady across the join.
     */
    EQUAL_POWER,
    ;

    /**
     * Gain at [progress] through a fade **in**, where 0 is silent and 1 is full.
     *
     * Progress outside 0..1 is clamped, so a ramp that is asked about a frame beyond its end answers
     * with its endpoint rather than an extrapolation.
     */
    public fun gainIn(progress: Float): Float {
        val t: Float = progress.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> t
            EQUAL_POWER -> sin(t * PI.toFloat() / 2f)
        }
    }

    /** Gain at [progress] through a fade **out**: the complement of [gainIn] under the same curve. */
    public fun gainOut(progress: Float): Float {
        val t: Float = progress.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> 1f - t
            EQUAL_POWER -> cos(t * PI.toFloat() / 2f)
        }
    }
}

/**
 * One scheduled volume ramp, measured in frames rather than milliseconds.
 *
 * Frames because the ramp is applied inside the audio processor, where the only clock that exists is the
 * number of samples that have gone past. A ramp expressed in wall-clock time drifts against the audio
 * as soon as the buffer size changes or the user moves the speed control, and a crossfade that drifts
 * either cuts a track off or leaves a gap.
 */
public data class GainRamp(
    public val startFrame: Long,
    public val durationFrames: Long,
    public val fromGain: Float,
    public val toGain: Float,
    public val curve: FadeCurve = FadeCurve.LINEAR,
) {

    public val endFrame: Long get() = startFrame + durationFrames

    /** True when this ramp fades towards silence. */
    public val isFadeOut: Boolean get() = toGain < fromGain

    /**
     * Gain at an absolute frame position.
     *
     * Before the ramp it is [fromGain] and after it is [toGain]: holding the endpoint is what lets a
     * single ramp object describe "faded out and staying out" without the caller tracking a separate
     * flag.
     */
    public fun gainAt(frame: Long): Float {
        if (durationFrames <= 0L) return toGain
        if (frame <= startFrame) return fromGain
        if (frame >= endFrame) return toGain
        val progress: Float = (frame - startFrame).toFloat() / durationFrames.toFloat()
        val shaped: Float = if (isFadeOut) curve.gainOut(progress) else curve.gainIn(progress)
        val low: Float = minOf(fromGain, toGain)
        val high: Float = maxOf(fromGain, toGain)
        // The curve runs 0..1; map it onto the endpoints so a ramp between two partial gains works.
        return (low + shaped * (high - low)).coerceIn(low, high)
    }

    /** True once [frame] is at or past the end of the ramp. */
    public fun isComplete(frame: Long): Boolean = durationFrames <= 0L || frame >= endFrame

    public companion object {

        /** A ramp of no length: the gain is simply [gain] from here on. */
        public fun hold(gain: Float, atFrame: Long = 0L): GainRamp =
            GainRamp(startFrame = atFrame, durationFrames = 0L, fromGain = gain, toGain = gain)

        /** Converts a fade length in milliseconds to frames at [sampleRateHz]. */
        public fun framesFor(durationMs: Long, sampleRateHz: Int): Long =
            if (sampleRateHz <= 0 || durationMs <= 0L) 0L else durationMs * sampleRateHz / 1_000L
    }
}

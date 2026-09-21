package app.needler.player.service.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs one volume ramp at a time, and guarantees the endpoint is reached.
 *
 * ## Why volume and not an audio processor
 *
 * The equaliser has to be an `AudioProcessor`: it filters samples, and the platform effect cannot express
 * the fixed band set REQUIREMENTS.md requires. A fade does not filter anything - it multiplies by a scalar -
 * and `Player.setVolume` already does that, inside the sink, at the right point in the chain, without a
 * buffer copy per buffer for every user who never turns a fade on. An extra processor in the chain to
 * multiply by a number would cost every listener something to give a few of them a fade.
 *
 * ## The endpoint is not optional
 *
 * Every path through [ramp] ends with the volume exactly at its target, including cancellation. A ramp that
 * is interrupted halfway and leaves the volume at 0.36 is a player that is silent for the rest of the
 * session with nothing on screen to explain it - the single worst bug a fade can have, because the transport
 * still says it is playing. So the target is applied in a `finally`, and a new ramp cancels the old one
 * before it starts rather than racing it.
 */
public class VolumeRamper(
    private val scope: CoroutineScope,
    /** Applies a volume to the player. Called on [scope]'s dispatcher, which must be the player's thread. */
    private val applyVolume: (Float) -> Unit,
    /** How often the volume is updated. 20 ms is smooth and is 50 writes for a one-second fade. */
    private val stepMs: Long = DEFAULT_STEP_MS,
) {

    private var job: Job? = null

    /** The last volume this ramper applied, so a new ramp can start where the old one stopped. */
    public var currentVolume: Float = 1f
        private set

    /**
     * Ramps from [currentVolume] to [target] over [durationMs], cancelling any ramp already running.
     *
     * A zero or negative duration jumps straight to the target, which is what "fade off" means.
     */
    public fun ramp(
        target: Float,
        durationMs: Long,
        curve: FadeCurve = FadeCurve.LINEAR,
        onComplete: (() -> Unit)? = null,
    ) {
        job?.cancel()
        val from: Float = currentVolume
        val to: Float = target.coerceIn(0f, 1f)
        if (durationMs <= 0L || from == to) {
            job = null
            setVolume(to)
            onComplete?.invoke()
            return
        }
        val steps: Int = maxOf(1, (durationMs / stepMs).toInt())
        val ramp = GainRamp(
            startFrame = 0L,
            durationFrames = steps.toLong(),
            fromGain = from,
            toGain = to,
            curve = curve,
        )
        job = scope.launch {
            try {
                var step = 1
                while (step <= steps && isActive) {
                    delay(stepMs)
                    setVolume(ramp.gainAt(step.toLong()))
                    step++
                }
            } finally {
                // Reached, cancelled or failed: the volume ends where it was asked to end. Leaving it
                // part-way is a player that is quietly silent with a playing transport.
                setVolume(to)
                onComplete?.invoke()
            }
        }
    }

    /** Stops any ramp and holds the volume where it is. */
    public fun cancel() {
        job?.cancel()
        job = null
    }

    /** Sets the volume with no ramp at all, and forgets any ramp in flight. */
    public fun jumpTo(volume: Float) {
        cancel()
        setVolume(volume.coerceIn(0f, 1f))
    }

    /** True while a ramp is running. */
    public val isRamping: Boolean get() = job?.isActive == true

    private fun setVolume(value: Float) {
        currentVolume = value
        applyVolume(value)
    }

    public companion object {
        public const val DEFAULT_STEP_MS: Long = 20L
    }
}

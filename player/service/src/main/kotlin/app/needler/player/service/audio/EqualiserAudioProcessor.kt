package app.needler.player.service.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.needler.core.domain.model.EqSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 10-band equaliser, in the sink's processor chain.
 *
 * All the arithmetic is in [EqualiserKernel], which has no Android in it and is tested on the JVM. This class
 * is only the plumbing: unpack 16-bit little-endian PCM out of Media3's `ByteBuffer`, hand it to the kernel,
 * pack it back.
 *
 * ## Why not the platform effect
 *
 * `android.media.audiofx.Equalizer` is what the obvious implementation would use, and REQUIREMENTS.md rules it
 * out. It offers however many bands the device's effect engine happens to implement, at frequencies the device
 * chooses, so the 31 Hz to 16 kHz set that has to match DroppedNeedle's web player exactly cannot be expressed.
 * It has no preamp, which ten bands at plus 12 dB make mandatory rather than nice. And it attaches to an audio
 * session rather than sitting in a chain, so it cannot be composed with the crossfade ramps that have to run
 * over the same samples.
 *
 * ## 16-bit only
 *
 * [onConfigure] refuses anything but `ENCODING_PCM_16BIT`, which is what Media3's decoders output by default.
 * Refusing is `UnhandledAudioFormatException`, and Media3 responds by leaving the processor inactive - the
 * audio plays, unequalised, rather than failing. Silently passing through a float or 24-bit buffer while
 * pretending to filter it would be worse: the user would move the sliders and hear nothing change.
 */
@OptIn(UnstableApi::class)
public class EqualiserAudioProcessor : BaseAudioProcessor() {

    private val kernel = EqualiserKernel()
    private var settings: EqSettings = EqSettings.Default
    private var frameSizeBytes: Int = 0
    private var scratch: ShortArray = ShortArray(0)

    /**
     * Applies new settings, from `PlaybackSettingsRepository.observeEqSettings`.
     *
     * Safe to call while playing: the coefficients change under the existing filter history, which is what a
     * mixing desk does and is why dragging a slider does not click.
     */
    public fun setSettings(settings: EqSettings) {
        this.settings = settings
        kernel.setSettings(settings)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        frameSizeBytes = inputAudioFormat.bytesPerFrame
        kernel.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        kernel.setSettings(settings)
        return inputAudioFormat
    }

    /**
     * Always active once a format has been accepted, and deliberately so.
     *
     * The tempting optimisation is to report inactive while the settings are flat, so Media3 leaves the
     * processor out of the chain and nobody who never opens screen 19 pays for it. That optimisation is a bug:
     * Media3 builds the active pipeline in `configure`, which runs per input format, so a processor that was
     * inactive when the track started stays out of the chain for the whole track. Turning the equaliser on
     * would then do nothing until the next song - the worst kind of settings screen, one that appears to be
     * ignored.
     *
     * So the processor stays in the chain and [queueInput] copies straight through when the kernel has nothing
     * to do. That is one `memcpy` of a few kilobytes per buffer, some twenty times a second: far below the
     * cost of getting the behaviour wrong.
     */
    override fun isActive(): Boolean = frameSizeBytes > 0

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining: Int = inputBuffer.remaining()
        if (remaining == 0) return
        val output: ByteBuffer = replaceOutputBuffer(remaining)

        if (!kernel.isActive) {
            // Flat and disabled: pass the bytes through untouched rather than running ten passthrough
            // biquads over them. See isActive for why the processor is still in the chain at all.
            output.put(inputBuffer)
            output.flip()
            return
        }

        val sampleCount: Int = remaining / BYTES_PER_SAMPLE
        if (scratch.size < sampleCount) scratch = ShortArray(sampleCount)

        val input: ByteBuffer = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until sampleCount) {
            scratch[index] = input.short
        }
        inputBuffer.position(inputBuffer.position() + sampleCount * BYTES_PER_SAMPLE)

        val frames: Int = if (frameSizeBytes > 0) remaining / frameSizeBytes else 0
        kernel.process(scratch, frames)

        output.order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until sampleCount) {
            output.putShort(scratch[index])
        }
        output.flip()
    }

    /**
     * Clears the filter history.
     *
     * Called on every seek and every track transition. Not clearing it is audible: the tail of the previous
     * track's low end arrives as a thump a few milliseconds into the next one.
     */
    override fun onFlush() {
        kernel.flush()
    }

    override fun onReset() {
        kernel.flush()
        scratch = ShortArray(0)
        frameSizeBytes = 0
    }

    private companion object {
        const val BYTES_PER_SAMPLE: Int = 2
    }
}

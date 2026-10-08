package app.needler.player.service.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import app.needler.core.domain.model.EqSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Media3 adapter around [EqualiserKernel], as opposed to the kernel itself.
 *
 * ## Why this file exists
 *
 * `EqualiserTest` covers the arithmetic and says so: the kernel "has no Android in it and is tested
 * on the JVM". That left the plumbing - unpack little-endian PCM out of Media3's `ByteBuffer`, hand
 * it to the kernel, pack it back - with no test at all, and the plumbing is where the framework
 * contract lives.
 *
 * That split is a pattern in this module, not an accident of one file. `BrowseTreeTest` covers
 * `BrowseTree` and not `BrowseItems`; `OutputTargetMapperTest` covers the mapper and not
 * `AudioOutputs`. Nineteen test files, and none of them named a class that touches a framework type
 * until this one. `WorkManagerSchedulerTest` records what that costs: the pure half of the download
 * scheduler was covered, the half that called `WorkManager` was not, and it crashed the app on
 * every pull for want of one assertion that a request could be built.
 *
 * `BaseAudioProcessor` needs no `Context`, so this runs on the JVM like everything beside it.
 */
class EqualiserAudioProcessorTest {

    private val stereo44k = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    /**
     * The refusal the class's own KDoc promises: anything but 16-bit PCM is rejected outright.
     *
     * Media3 answers an `UnhandledAudioFormatException` by leaving the processor inactive, so the
     * audio plays unequalised rather than failing. Passing a float buffer through while pretending
     * to filter it would move the sliders and change nothing.
     */
    @Test
    fun `a format that is not 16-bit PCM is refused rather than passed through`() {
        val processor = EqualiserAudioProcessor()

        assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) {
            processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT))
        }
    }

    @Test
    fun `16-bit PCM is accepted and returned unchanged, because this processor does not resample`() {
        val processor = EqualiserAudioProcessor()

        assertEquals(stereo44k, processor.configure(stereo44k))
    }

    /**
     * Active once configured, and inactive before and after - which is what keeps it in the chain.
     *
     * The class's KDoc explains why it may not report inactive while the settings are flat: Media3
     * builds the active pipeline in `configure`, so a processor left out when the track started
     * stays out for the whole track, and turning the equaliser on would do nothing until the next
     * song. These assertions are what stop that optimisation being reintroduced.
     */
    @Test
    fun `it stays active with flat settings, so turning the equaliser on takes effect this track`() {
        val processor = EqualiserAudioProcessor()
        assertFalse("inactive before a format is accepted", processor.isActive)

        processor.configure(stereo44k)
        processor.flush()

        assertTrue("flat settings must not drop it out of the chain", processor.isActive)
        assertEquals(EqSettings.Default.bandGainsDb, EqSettings.Default.bandGainsDb)
    }

    @Test
    fun `reset puts it back to inactive`() {
        val processor = EqualiserAudioProcessor()
        processor.configure(stereo44k)
        processor.flush()
        assertTrue(processor.isActive)

        processor.reset()

        assertFalse(processor.isActive)
    }

    /**
     * Flat and disabled: the bytes come out exactly as they went in.
     *
     * This is the passthrough branch, which is the one most listening happens on - anyone who never
     * opens the equaliser screen is on this path for every buffer of every track.
     */
    @Test
    fun `a disabled equaliser returns the samples byte for byte`() {
        val processor = EqualiserAudioProcessor()
        processor.configure(stereo44k)
        processor.flush()
        processor.setSettings(EqSettings.Default)
        val samples = shortArrayOf(0, 1, -1, 4096, -4096, Short.MAX_VALUE, Short.MIN_VALUE, 17)

        val output: ByteBuffer = processor.run(samples)

        assertArrayEquals(samples, output.toShorts())
    }

    /**
     * Enabled and boosted: the samples change, and exactly as many come back as went in.
     *
     * The length is the half that is about this class rather than the kernel. A buffer that came
     * back short would drop audio - and `sampleCount` is `remaining / 2`, so the arithmetic is this
     * file's to get right.
     */
    @Test
    fun `an enabled equaliser alters the samples and returns the same count`() {
        val processor = EqualiserAudioProcessor()
        processor.configure(stereo44k)
        processor.flush()
        processor.setSettings(
            EqSettings(isEnabled = true, bandGainsDb = List(EqSettings.BAND_COUNT) { 12f }),
        )
        val samples = ShortArray(512) { (it * 37 % 8000 - 4000).toShort() }

        val output: ByteBuffer = processor.run(samples)
        val result: ShortArray = output.toShorts()

        assertEquals("every sample in must be a sample out", samples.size, result.size)
        assertFalse("a +12 dB boost that changed nothing is a dead chain", result.contentEquals(samples))
    }

    /** Little-endian in, little-endian out. A byte order flip is silent and sounds like noise. */
    @Test
    fun `the output is little-endian, as Media3 hands it over`() {
        val processor = EqualiserAudioProcessor()
        processor.configure(stereo44k)
        processor.flush()
        processor.setSettings(EqSettings.Default)

        val output: ByteBuffer = processor.run(shortArrayOf(0x0102, 0x0304))

        assertEquals(0x02.toByte(), output.get(0))
        assertEquals(0x01.toByte(), output.get(1))
    }

    /** Queueing nothing produces nothing, rather than a stale buffer from the last call. */
    @Test
    fun `an empty input buffer yields no output`() {
        val processor = EqualiserAudioProcessor()
        processor.configure(stereo44k)
        processor.flush()

        processor.queueInput(ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN))

        assertEquals(0, processor.output.remaining())
    }

    /** Hands [samples] to the processor as Media3 would, and returns what came back. */
    private fun EqualiserAudioProcessor.run(samples: ShortArray): ByteBuffer {
        val input: ByteBuffer = ByteBuffer
            .allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(input::putShort)
        input.flip()

        queueInput(input)
        assertFalse("the processor must consume what it is given", input.hasRemaining())
        return output
    }

    private fun ByteBuffer.toShorts(): ShortArray {
        val view = duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(view.remaining() / 2) { view.short }
    }
}

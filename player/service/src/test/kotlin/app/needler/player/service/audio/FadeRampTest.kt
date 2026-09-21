package app.needler.player.service.audio

import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ramp maths, and the one guarantee the ramper makes: the endpoint is always reached. */
@OptIn(ExperimentalCoroutinesApi::class)
class FadeRampTest {

    @Test
    fun `a ramp holds its endpoints outside its window`() {
        val ramp = GainRamp(startFrame = 100L, durationFrames = 100L, fromGain = 1f, toGain = 0f)

        assertEquals(1f, ramp.gainAt(0L), 0f)
        assertEquals(1f, ramp.gainAt(100L), 0f)
        assertEquals(0f, ramp.gainAt(200L), 0f)
        assertEquals(0f, ramp.gainAt(5_000L), 0f)
        assertTrue(ramp.isComplete(200L))
        assertFalse(ramp.isComplete(150L))
    }

    @Test
    fun `a linear fade out is halfway down at halfway through`() {
        val ramp = GainRamp(0L, 100L, fromGain = 1f, toGain = 0f, curve = FadeCurve.LINEAR)

        assertEquals(0.5f, ramp.gainAt(50L), 0.001f)
    }

    /**
     * Two linear ramps crossing at 0.5 sum to half power, which is an audible dip in the middle of every
     * transition. The equal-power pair keeps the loudness steady across the join.
     */
    @Test
    fun `equal-power ramps sum to constant power`() {
        val out = GainRamp(0L, 100L, fromGain = 1f, toGain = 0f, curve = FadeCurve.EQUAL_POWER)
        val into = GainRamp(0L, 100L, fromGain = 0f, toGain = 1f, curve = FadeCurve.EQUAL_POWER)

        for (frame in 0..100 step 10) {
            val a = out.gainAt(frame.toLong())
            val b = into.gainAt(frame.toLong())
            assertEquals("frame " + frame, 1.0, (a * a + b * b).toDouble(), 0.001)
        }
    }

    @Test
    fun `a linear crossfade dips in the middle, which is why it is not the default for one`() {
        val out = FadeCurve.LINEAR.gainOut(0.5f)
        val into = FadeCurve.LINEAR.gainIn(0.5f)

        assertTrue(abs(out * out + into * into - 1f) > 0.4f)
    }

    @Test
    fun `a zero-length ramp is simply the target`() {
        assertEquals(0.25f, GainRamp.hold(0.25f).gainAt(999L), 0f)
    }

    @Test
    fun `frames are computed from the sample rate`() {
        assertEquals(44_100L, GainRamp.framesFor(1_000L, 44_100))
        assertEquals(0L, GainRamp.framesFor(0L, 44_100))
        assertEquals(0L, GainRamp.framesFor(1_000L, 0))
    }

    // ----------------------------------------------------------------- the ramper

    @Test
    fun `a ramp moves the volume from where it was to the target`() = runTest {
        val applied = mutableListOf<Float>()
        val ramper = VolumeRamper(TestScope(testScheduler), applyVolume = { applied.add(it) }, stepMs = 10L)

        ramper.ramp(target = 0f, durationMs = 100L)
        advanceUntilIdle()

        assertTrue(applied.isNotEmpty())
        assertEquals(0f, applied.last(), 0f)
        assertEquals(0f, ramper.currentVolume, 0f)
        // Monotonic on the way down, or the fade audibly wobbles.
        assertTrue(applied.zipWithNext().all { (a, b) -> b <= a + 0.0001f })
    }

    /**
     * The worst bug a fade can have: interrupted halfway, the volume stays at 0.36, and the player is silent
     * for the rest of the session with a transport that still says it is playing.
     */
    @Test
    fun `a cancelled ramp still lands on its target`() = runTest {
        val applied = mutableListOf<Float>()
        val ramper = VolumeRamper(TestScope(testScheduler), applyVolume = { applied.add(it) }, stepMs = 10L)

        ramper.ramp(target = 0f, durationMs = 1_000L)
        advanceTimeBy(200L)
        ramper.cancel()
        advanceUntilIdle()

        assertEquals(0f, ramper.currentVolume, 0f)
        assertEquals(0f, applied.last(), 0f)
    }

    @Test
    fun `a new ramp replaces the one in flight rather than racing it`() = runTest {
        val applied = mutableListOf<Float>()
        val ramper = VolumeRamper(TestScope(testScheduler), applyVolume = { applied.add(it) }, stepMs = 10L)

        ramper.ramp(target = 0f, durationMs = 1_000L)
        advanceTimeBy(200L)
        ramper.ramp(target = 1f, durationMs = 100L)
        advanceUntilIdle()

        assertEquals(1f, ramper.currentVolume, 0f)
        assertFalse(ramper.isRamping)
    }

    @Test
    fun `a zero-length fade jumps and reports completion`() = runTest {
        var completed = false
        val ramper = VolumeRamper(TestScope(testScheduler), applyVolume = { }, stepMs = 10L)

        ramper.ramp(target = 0.5f, durationMs = 0L) { completed = true }

        assertTrue(completed)
        assertEquals(0.5f, ramper.currentVolume, 0f)
    }
}

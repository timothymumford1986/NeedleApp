package app.needler.player.service.feature

import app.needler.core.domain.model.CrossfadeDuration
import app.needler.core.domain.model.CrossfadeSettings
import app.needler.player.service.Fixtures
import app.needler.player.service.audio.FadeCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screen 20's four rows, and the way they interact.
 *
 * The interaction is the part worth testing: crossfade is suppressed inside an album so gapless records survive,
 * but pressing next still fades, and turning gapless off has to do something audible.
 */
class FadePlannerTest {

    private val planner = FadePlanner()

    private val sixSeconds = CrossfadeSettings(duration = CrossfadeDuration.SIX_SECONDS)
    private val off = CrossfadeSettings(duration = CrossfadeDuration.OFF)

    @Test
    fun `the offered lengths are off, four, six and twelve seconds`() {
        assertEquals(
            listOf(0L, 4L, 6L, 12L),
            FadePlanner.OFFERED_DURATIONS.map { it.duration.inWholeSeconds },
        )
    }

    @Test
    fun `an automatic advance between albums crossfades at the chosen length`() {
        val plan = planner.forTransition(
            settings = sixSeconds,
            gaplessEnabled = true,
            cause = TransitionCause.AUTO_ADVANCE,
            from = Fixtures.track(album = Fixtures.ALBUM_A, number = 9),
            to = Fixtures.track(album = Fixtures.ALBUM_B, number = 1),
        )

        assertEquals(FadeReason.CROSSFADE, plan.reason)
        assertEquals(6_000L, plan.durationMs)
        assertEquals(FadeCurve.EQUAL_POWER, plan.curve)
    }

    /**
     * A live album, a DJ mix or anything segued has no silence at its joins. A six-second crossfade across a
     * join that was already seamless removes six seconds of the record and replaces them with a smear.
     */
    @Test
    fun `crossfade is suppressed between two tracks of the same album`() {
        val plan = planner.forTransition(
            settings = sixSeconds,
            gaplessEnabled = true,
            cause = TransitionCause.AUTO_ADVANCE,
            from = Fixtures.track(album = Fixtures.ALBUM_A, number = 3),
            to = Fixtures.track(album = Fixtures.ALBUM_A, number = 4),
        )

        assertTrue(plan.isNoOp)
        assertEquals(FadeReason.NONE, plan.reason)
    }

    @Test
    fun `the suppression can be turned off`() {
        val plan = planner.forTransition(
            settings = sixSeconds.copy(suppressWithinAlbum = false),
            gaplessEnabled = true,
            cause = TransitionCause.AUTO_ADVANCE,
            from = Fixtures.track(album = Fixtures.ALBUM_A, number = 3),
            to = Fixtures.track(album = Fixtures.ALBUM_A, number = 4),
        )

        assertEquals(FadeReason.CROSSFADE, plan.reason)
    }

    /**
     * Pressing next is a different event from a record playing itself: the abrupt cut it replaces is the
     * response to a button, not part of how the album runs. So the one-second fade applies inside an album too,
     * and with crossfade off entirely.
     */
    @Test
    fun `a manual skip fades for one second, even inside an album and with crossfade off`() {
        val insideAlbum = planner.forTransition(
            settings = sixSeconds,
            gaplessEnabled = true,
            cause = TransitionCause.MANUAL_SKIP,
            from = Fixtures.track(album = Fixtures.ALBUM_A, number = 3),
            to = Fixtures.track(album = Fixtures.ALBUM_A, number = 4),
        )
        val crossfadeOff = planner.forTransition(
            settings = off,
            gaplessEnabled = true,
            cause = TransitionCause.MANUAL_SKIP,
            from = Fixtures.track(album = Fixtures.ALBUM_A, number = 3),
            to = Fixtures.track(album = Fixtures.ALBUM_B, number = 1),
        )

        assertEquals(FadeReason.MANUAL_SKIP, insideAlbum.reason)
        assertEquals(1_000L, insideAlbum.durationMs)
        assertEquals(FadeReason.MANUAL_SKIP, crossfadeOff.reason)
        assertEquals(1_000L, crossfadeOff.durationMs)
    }

    @Test
    fun `fade on skip can be turned off`() {
        val plan = planner.forTransition(
            settings = sixSeconds.copy(fadeOnSkip = false),
            gaplessEnabled = true,
            cause = TransitionCause.MANUAL_SKIP,
            from = Fixtures.track(number = 3),
            to = Fixtures.track(album = Fixtures.ALBUM_B, number = 1),
        )

        assertTrue(plan.isNoOp)
    }

    /** A seek is navigation. Fading it makes the player feel slow to respond. */
    @Test
    fun `a seek is never faded`() {
        val plan = planner.forTransition(
            settings = sixSeconds,
            gaplessEnabled = true,
            cause = TransitionCause.SEEK,
            from = Fixtures.track(number = 3),
            to = Fixtures.track(album = Fixtures.ALBUM_B, number = 1),
        )

        assertTrue(plan.isNoOp)
    }

    @Test
    fun `nothing is faded into the end of the crate`() {
        val plan = planner.forTransition(
            settings = sixSeconds,
            gaplessEnabled = true,
            cause = TransitionCause.AUTO_ADVANCE,
            from = Fixtures.track(number = 9),
            to = null,
        )

        assertTrue(plan.isNoOp)
    }

    /**
     * Media3 concatenates gaplessly and offers no way to stop it, so the toggle has to be implemented rather
     * than passed through. A control that does nothing when switched off is worse than one that does something
     * small.
     */
    @Test
    fun `gapless off inserts a short break where there would otherwise be none`() {
        val plan = planner.forTransition(
            settings = off,
            gaplessEnabled = false,
            cause = TransitionCause.AUTO_ADVANCE,
            from = Fixtures.track(number = 3),
            to = Fixtures.track(number = 4),
        )

        assertEquals(FadeReason.GAPLESS_OFF, plan.reason)
        assertTrue(plan.durationMs > 0L)
        assertTrue(plan.gapMs > 0L)
    }

    @Test
    fun `gapless on with no crossfade leaves the join completely alone`() {
        val plan = planner.forTransition(
            settings = off,
            gaplessEnabled = true,
            cause = TransitionCause.AUTO_ADVANCE,
            from = Fixtures.track(number = 3),
            to = Fixtures.track(number = 4),
        )

        assertTrue(plan.isNoOp)
    }

    @Test
    fun `pause and resume ramp, and can be turned off`() {
        assertEquals(FadeReason.PAUSE, planner.forPause(sixSeconds).reason)
        assertEquals(FadeReason.RESUME, planner.forResume(sixSeconds).reason)
        assertTrue(planner.forPause(sixSeconds.copy(fadeOnPause = false)).isNoOp)
        assertTrue(planner.forResume(sixSeconds.copy(fadeOnPause = false)).isNoOp)
    }

    /** A 12-second crossfade into a 20-second interlude would start before the track's own first chorus. */
    @Test
    fun `the crossfade lead is clamped to a third of the track`() {
        val twelve = planner.forTransition(
            settings = CrossfadeSettings(duration = CrossfadeDuration.TWELVE_SECONDS),
            gaplessEnabled = true,
            cause = TransitionCause.AUTO_ADVANCE,
            from = Fixtures.track(number = 1),
            to = Fixtures.track(album = Fixtures.ALBUM_B, number = 1),
        )

        assertEquals(12_000L, planner.crossfadeStartOffsetMs(twelve, trackDurationMs = 240_000L))
        assertEquals(6_666L, planner.crossfadeStartOffsetMs(twelve, trackDurationMs = 20_000L))
        assertEquals(0L, planner.crossfadeStartOffsetMs(twelve, trackDurationMs = null))
        assertEquals(0L, planner.crossfadeStartOffsetMs(FadePlan.None, trackDurationMs = 240_000L))
    }
}

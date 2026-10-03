package app.needler.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.ui.unit.IntOffset
import app.needler.core.design.motion.NeedlerMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The navigation transitions, asserted on the only thing a test can hold.
 *
 * A screenshot test renders one frame and cannot photograph a transition, and no
 * JVM test can watch a `NavHost` animate. What it *can* check is that the spec
 * the host is handed is the spec that was intended - which is why
 * [NeedlerNavTransitions] is five named instances and six pure functions instead
 * of `fadeIn(tween(200))` written inline in a lambda, where the reduced-motion
 * branch would have had nothing to assert against at all.
 *
 * The reduced-motion cases are the ones that matter most. REQUIREMENTS.md
 * "Motion" requires that "every one of these is suppressed under
 * `prefers-reduced-motion`", mapping on Android to `ANIMATOR_DURATION_SCALE`
 * being zero, and a transition added to fix one complaint must not quietly
 * reintroduce motion for a listener who has turned it off. There is no visible
 * symptom when that regresses, so there is a test for it.
 */
class NeedlerNavTransitionsTest {

    // ---- reduced motion ---------------------------------------------------

    @Test
    fun `reduced motion gives the player layer no motion in either direction`() {
        assertEquals(
            "the player would still slide up with animations turned off",
            EnterTransition.None,
            NeedlerNavTransitions.enter(toPlayerLayer = true, reducedMotion = true),
        )
        assertEquals(
            ExitTransition.None,
            NeedlerNavTransitions.exit(toPlayerLayer = true, reducedMotion = true),
        )
        assertEquals(
            EnterTransition.None,
            NeedlerNavTransitions.popEnter(fromPlayerLayer = true, reducedMotion = true),
        )
        assertEquals(
            "the player would still slide back down with animations turned off",
            ExitTransition.None,
            NeedlerNavTransitions.popExit(fromPlayerLayer = true, reducedMotion = true),
        )
    }

    @Test
    fun `reduced motion gives a change of place no motion`() {
        assertEquals(
            EnterTransition.None,
            NeedlerNavTransitions.enter(toPlayerLayer = false, reducedMotion = true),
        )
        assertEquals(
            ExitTransition.None,
            NeedlerNavTransitions.exit(toPlayerLayer = false, reducedMotion = true),
        )
        assertEquals(
            EnterTransition.None,
            NeedlerNavTransitions.popEnter(fromPlayerLayer = false, reducedMotion = true),
        )
        assertEquals(
            ExitTransition.None,
            NeedlerNavTransitions.popExit(fromPlayerLayer = false, reducedMotion = true),
        )
    }

    @Test
    fun `reduced motion gives a change of content no motion`() {
        assertEquals(
            "the inner graph would still cross-fade with animations turned off",
            EnterTransition.None,
            NeedlerNavTransitions.contentEnter(reducedMotion = true),
        )
        assertEquals(
            ExitTransition.None,
            NeedlerNavTransitions.contentExit(reducedMotion = true),
        )
    }

    // ---- the player layer -------------------------------------------------

    @Test
    fun `the player layer rises and falls`() {
        assertEquals(
            NeedlerNavTransitions.SheetRise,
            NeedlerNavTransitions.enter(toPlayerLayer = true, reducedMotion = false),
        )
        assertEquals(
            NeedlerNavTransitions.SheetFall,
            NeedlerNavTransitions.popExit(fromPlayerLayer = true, reducedMotion = false),
        )
    }

    @Test
    fun `what is underneath the player layer holds still`() {
        assertEquals(
            "Home faded while the player rose over it, so the strip the player " +
                "had not covered yet showed a dimmed library",
            NeedlerNavTransitions.HoldUnderSheet,
            NeedlerNavTransitions.exit(toPlayerLayer = true, reducedMotion = false),
        )
        // The destination being popped back to is the transition's target, so
        // AnimatedContent never disposes it and None means "sit still, fully
        // opaque, underneath". This is the assertion that would fail if someone
        // "fixed" it to a fade and brought the bottom bar's ghost back.
        assertEquals(
            EnterTransition.None,
            NeedlerNavTransitions.popEnter(fromPlayerLayer = true, reducedMotion = false),
        )
    }

    @Test
    fun `holding under the sheet lasts exactly as long as the sheet moves`() {
        // Not a tautology: the hold is what keeps the outgoing destination
        // composed, so a hold shorter than the slide would dispose Home partway
        // up and finish the rise over bare canvas.
        //
        // The specs are what is compared and not the transitions. `Slide` holds
        // its offset function by reference and `EnterTransition` compares by it,
        // so two slides built from identical numbers are never equal - which is
        // exactly why SheetSpec is hoisted. Pinning it pins the duration and the
        // easing of both the rise and the fall at once.
        assertEquals(
            NeedlerNavTransitions.SheetSpec,
            tween<IntOffset>(
                NeedlerNavTransitions.SheetDurationMillis,
                easing = NeedlerMotion.Standard,
            ),
        )
        assertEquals(
            "a hold shorter than the slide disposes Home mid-rise",
            NeedlerNavTransitions.HoldUnderSheet,
            fadeOut(
                animationSpec = tween(
                    NeedlerNavTransitions.SheetDurationMillis,
                    easing = NeedlerMotion.Linear,
                ),
                targetAlpha = 1f,
            ),
        )
    }

    // ---- a change of content ----------------------------------------------

    @Test
    fun `the inner graph cross-fades, in both directions and both ways round`() {
        assertEquals(
            NeedlerNavTransitions.ContentFadeIn,
            NeedlerNavTransitions.contentEnter(reducedMotion = false),
        )
        assertEquals(
            NeedlerNavTransitions.ContentFadeOut,
            NeedlerNavTransitions.contentExit(reducedMotion = false),
        )
        // Both directions are built from one spec, so pinning it pins the
        // duration and the easing of the cross-fade.
        assertEquals(
            NeedlerNavTransitions.ContentFadeSpec,
            tween<Float>(
                NeedlerNavTransitions.ContentFadeDurationMillis,
                easing = NeedlerMotion.Standard,
            ),
        )
    }

    @Test
    fun `an outer destination that is not the player layer cross-fades too`() {
        // Connect and Home. They replace one another rather than layering, and a
        // sign-out that slid the library down off the screen would read as the
        // player closing.
        assertEquals(
            NeedlerNavTransitions.ContentFadeIn,
            NeedlerNavTransitions.enter(toPlayerLayer = false, reducedMotion = false),
        )
        assertEquals(
            NeedlerNavTransitions.ContentFadeOut,
            NeedlerNavTransitions.exit(toPlayerLayer = false, reducedMotion = false),
        )
        assertEquals(
            NeedlerNavTransitions.ContentFadeIn,
            NeedlerNavTransitions.popEnter(fromPlayerLayer = false, reducedMotion = false),
        )
        assertEquals(
            NeedlerNavTransitions.ContentFadeOut,
            NeedlerNavTransitions.popExit(fromPlayerLayer = false, reducedMotion = false),
        )
    }

    // ---- the join between the graph and the motion ------------------------

    @Test
    fun `the player layer is exactly Now Playing and the crate`() {
        // Both are built from the host's own route constants, so this pins the
        // values rather than the join. What it is really guarding is scope: add
        // a third chrome-free destination and it has to be added here too, and
        // adding a destination that keeps the bottom bar must not be.
        assertEquals(setOf("nowplaying", "crate"), PLAYER_LAYER_ROUTES)
    }

    @Test
    fun `no player route is also a tab`() {
        // A player route that NeedlerDestination matched would light a nav item
        // on a screen the pack draws no nav bar on, and owningTab would start
        // returning it.
        PLAYER_LAYER_ROUTES.forEach { route ->
            assertNull(route, NeedlerDestination.fromRoute(route))
        }
    }
}

package app.needler.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.ui.unit.IntOffset
import app.needler.core.design.motion.NeedlerMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // ---- a Back the finger is still holding -------------------------------

    /**
     * The defect: the inner graph left this pair at navigation-compose's defaults.
     *
     * The comment that did so called them "the platform's own seeked preview of the screen behind".
     * They are `scaleOut(targetScale = 0.7f)` plus a fade, over a fade-in - so seeking the pair drew
     * both screens at partial alpha for the whole drag while the outgoing one collapsed to
     * seven-tenths in the centre of the window. Reported from the device as looking exactly as bad
     * as that describes.
     *
     * What this asserts is that the pair is no longer a fade, because a fade is the half that makes
     * two screens visible through one another.
     */
    @Test
    fun `a dragged Back does not cross-fade, which is what showed both screens at once`() {
        assertNotEquals(
            "the screen being dragged off fades, so the one behind shows through it",
            NeedlerNavTransitions.ContentFadeOut,
            NeedlerNavTransitions.predictivePopExit(swipeEdge = 0, reducedMotion = false),
        )
        assertEquals(
            "the destination must sit still and opaque under the screen being peeled away",
            EnterTransition.None,
            NeedlerNavTransitions.predictivePopEnter(reducedMotion = false),
        )
    }

    /**
     * The surface travels the way the finger is travelling, so the two edges cannot agree.
     *
     * A drag from the left edge moves rightwards; a screen that slid left under it would read as a
     * fight with the gesture.
     *
     * Asserted on `predictiveSlideDirection` rather than on the two transitions, and that is the
     * whole point of the function existing. `Slide` holds its offset lambda by reference and a
     * transition compares by it, so `assertNotEquals` between two separately built slides is true
     * whatever the numbers - it would pass with the edge ignored entirely. This reads the decision
     * instead of the object built from it.
     */
    @Test
    fun `the two swipe edges push the screen opposite ways`() {
        assertEquals(
            "a drag from the left edge travels rightwards, so the screen must too",
            1,
            NeedlerNavTransitions.predictiveSlideDirection(swipeEdge = 0),
        )
        assertEquals(
            -1,
            NeedlerNavTransitions.predictiveSlideDirection(swipeEdge = 1),
        )
    }

    @Test
    fun `reduced motion gives a dragged Back no motion either`() {
        assertEquals(
            "the screen would still shrink and slide with animations turned off",
            ExitTransition.None,
            NeedlerNavTransitions.predictivePopExit(swipeEdge = 0, reducedMotion = true),
        )
        assertEquals(
            ExitTransition.None,
            NeedlerNavTransitions.predictivePopExit(swipeEdge = 1, reducedMotion = true),
        )
    }

    /**
     * The shrink stays near the platform's figure rather than the library's.
     *
     * 0.7 is the number that made the old animation read as a collapse. These are the two constants
     * the shape rests on, pinned so a later tweak is a decision rather than a drift.
     */
    @Test
    fun `the screen shrinks slightly and slides a little, not a lot`() {
        assertEquals(0.9f, NeedlerNavTransitions.PredictiveScale, 0.001f)
        assertEquals(0.08f, NeedlerNavTransitions.PredictiveSlideFraction, 0.001f)
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

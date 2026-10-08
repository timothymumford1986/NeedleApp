package app.needler.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset
import app.needler.core.design.motion.NeedlerMotion

/**
 * How one destination replaces another.
 *
 * ## What was wrong
 *
 * Nothing in `:app` had ever named a transition, so every destination used
 * navigation-compose's host defaults. In 2.10.1 those are
 * `fadeIn(tween(700))` / `fadeOut(tween(700))` - `DefaultNavTransitions` in
 * `androidx.navigation.compose`, confirmed by disassembling the shipped AAR
 * rather than from documentation - and `popEnterTransition` and
 * `popExitTransition` default to the same two.
 *
 * A 700ms cross-fade is already longer than Material's longest full-screen
 * transition, but the duration was the smaller half of the problem. The outer
 * graph's Home destination **is** the whole of
 * [NeedlerNavigationScaffold] - bottom bar, mini-player and update banner are
 * composed outside the inner `NavHost` but inside the outer one - so Back from
 * Now Playing cross-faded the app's own furniture in from zero alpha while the
 * full player ghosted out through it. Back from album detail, one graph down,
 * ran the identical cross-fade over the content pane alone and left the bar
 * perfectly still. One gesture, two results, decided by which `NavHost` a
 * destination happened to land in.
 *
 * ## What it does now
 *
 * Two relationships, deliberately different, and each one consistent with
 * itself:
 *
 *  - **A layer over the app.** Now Playing and the crate are siblings of Home,
 *    not destinations within it, because the pack draws no bar and no rail on
 *    screens 07 and 08. They now rise from the bottom edge and fall back to it,
 *    and whatever is underneath holds still - so the bar and the mini-player are
 *    *revealed* rather than faded in. That is also what makes the chevron-down
 *    close and a system Back look the same as each other: both are a pop on the
 *    same controller, and both now get [SheetFall].
 *  - **A change of content.** Everything in the inner graph - the four tabs,
 *    album, artist, playlists, genres, the Settings sub-screens - cross-fades
 *    over [ContentFadeDurationMillis]. REQUIREMENTS.md "Tablet layout" keeps the
 *    bar and the rail visible on album and artist detail, so opening one "is a
 *    change of content, not a change of context", and a cross-fade is the motion
 *    that says exactly that. The chrome is outside this `NavHost`, so it does
 *    not move.
 *
 * ## Reduced motion
 *
 * REQUIREMENTS.md "Motion" requires every animation to be suppressed when the
 * system animator duration scale is zero, which
 * `app.needler.core.design.motion.LocalReducedMotion` reports. Every function
 * here takes that flag and returns [EnterTransition.None] or
 * [ExitTransition.None] for it, which is an instant cut: `AnimatedContent`
 * draws the target and disposes the outgoing destination on the same frame.
 *
 * ## Why a named object rather than literals in the host's lambdas
 *
 * A screenshot test cannot photograph a transition, so the only thing a test
 * can hold is the spec itself. Each transition is therefore a single named
 * instance and each choice a pure function of two booleans, so
 * `NeedlerNavTransitionsTest` asserts on identity rather than on a lambda it has
 * no way to reach. The alternative - inlining `fadeIn(tween(200))` in the host
 * - left the reduced-motion branch untestable, which is the one branch
 * REQUIREMENTS.md actually requires.
 *
 * The object lives in `:app` beside the graph rather than in
 * `:core:design/motion` with [NeedlerMotion], because the distinction it encodes
 * - a player layer versus a content pane - is a fact about this navigation graph
 * and not about the design system. It borrows the easing vocabulary from
 * [NeedlerMotion] so there is still only one set of curves in the app.
 */
object NeedlerNavTransitions {

    /**
     * How long the player layer takes to rise or fall.
     *
     * Material's "expand a full-screen container" is 500ms and its standard
     * screen transition 300ms; 400ms is the middle of that for a surface that
     * crosses the whole viewport. Not the 700ms the library defaults to, which
     * is what made the old cross-fade read as a double exposure.
     */
    const val SheetDurationMillis: Int = 400

    /**
     * How long one content pane takes to cross-fade into another.
     *
     * Short on purpose: the chrome around it does not move, so there is no
     * context change to cover and a long fade only delays the content.
     */
    const val ContentFadeDurationMillis: Int = 200

    /**
     * Just past the bottom edge of the window: the whole height, so the surface
     * starts and ends completely off screen rather than half of it.
     *
     * One value shared by the rise and the fall, so the two cannot drift apart
     * into a sheet that comes up further than it goes down.
     */
    val FromBelowWindow: (Int) -> Int = { fullHeight -> fullHeight }

    /**
     * The curve the player layer travels on, in both directions.
     *
     * Hoisted because it is the only part of [SheetRise] and [SheetFall] a test
     * can read. `Slide` holds its offset function by reference and
     * `EnterTransition` compares by it, so two separately built slides are never
     * equal however identical their numbers; the spec is therefore where the
     * duration and the easing are pinned, and [HoldUnderSheet] taking its
     * duration from the same constant is what keeps the hold from being shorter
     * than the slide.
     */
    val SheetSpec: FiniteAnimationSpec<IntOffset> =
        tween(SheetDurationMillis, easing = NeedlerMotion.Standard)

    /** Now Playing and the crate arriving: up from the bottom edge. */
    val SheetRise: EnterTransition = slideInVertically(
        animationSpec = SheetSpec,
        initialOffsetY = FromBelowWindow,
    )

    /** Now Playing and the crate leaving: back down to the bottom edge. */
    val SheetFall: ExitTransition = slideOutVertically(
        animationSpec = SheetSpec,
        targetOffsetY = FromBelowWindow,
    )

    /**
     * Home staying exactly where it is while the player layer rises over it.
     *
     * `targetAlpha = 1f` is the whole point: the alpha animates from 1 to 1, so
     * nothing about Home changes, but `AnimatedContent` still has a running exit
     * transition for it and therefore keeps it composed and drawn for
     * [SheetDurationMillis]. [ExitTransition.None] was tried first and is wrong
     * - with no exit animation the outgoing destination is disposed on the next
     * frame, and the strip of window the rising sheet has not covered yet shows
     * bare canvas instead of the library the listener came from.
     *
     * `ExitTransition.KeepUntilTransitionsFinished`, which is exactly this, is
     * internal to `androidx.compose.animation`.
     *
     * The duration must never be shorter than [SheetDurationMillis] or the same
     * bare strip reappears at the end of the slide; `NeedlerNavTransitionsTest`
     * holds that.
     */
    val HoldUnderSheet: ExitTransition = fadeOut(
        animationSpec = tween(SheetDurationMillis, easing = NeedlerMotion.Linear),
        targetAlpha = 1f,
    )

    /** The curve one content pane cross-fades on. */
    val ContentFadeSpec: FiniteAnimationSpec<Float> =
        tween(ContentFadeDurationMillis, easing = NeedlerMotion.Standard)

    /** One content pane arriving inside unmoving chrome. */
    val ContentFadeIn: EnterTransition = fadeIn(animationSpec = ContentFadeSpec)

    /** One content pane leaving inside unmoving chrome. */
    val ContentFadeOut: ExitTransition = fadeOut(animationSpec = ContentFadeSpec)

    /**
     * The destination being navigated to.
     *
     * @param toPlayerLayer whether the arriving destination is one of
     *   [PLAYER_LAYER_ROUTES]. A boolean rather than the route itself so this
     *   object knows about motion and the graph keeps its routes.
     */
    fun enter(toPlayerLayer: Boolean, reducedMotion: Boolean): EnterTransition = when {
        reducedMotion -> EnterTransition.None
        toPlayerLayer -> SheetRise
        else -> ContentFadeIn
    }

    /**
     * The destination being navigated away from.
     *
     * Held still, not faded, when a player layer is arriving over it: it is
     * still the app, and the layer is what moves.
     */
    fun exit(toPlayerLayer: Boolean, reducedMotion: Boolean): ExitTransition = when {
        reducedMotion -> ExitTransition.None
        toPlayerLayer -> HoldUnderSheet
        else -> ContentFadeOut
    }

    /**
     * The destination being returned to by a pop.
     *
     * [EnterTransition.None] when the player layer is what is leaving, which is
     * not an omission: the destination being popped to is the transition's
     * target, so it is never disposed, and navigation-compose gives it a z-index
     * one below the outgoing destination. It therefore sits still and fully
     * opaque underneath while [SheetFall] slides the player off it.
     *
     * @param fromPlayerLayer whether the destination being popped *off* is one
     *   of [PLAYER_LAYER_ROUTES].
     */
    fun popEnter(fromPlayerLayer: Boolean, reducedMotion: Boolean): EnterTransition = when {
        reducedMotion || fromPlayerLayer -> EnterTransition.None
        else -> ContentFadeIn
    }

    /** The destination being popped off. */
    fun popExit(fromPlayerLayer: Boolean, reducedMotion: Boolean): ExitTransition = when {
        reducedMotion -> ExitTransition.None
        fromPlayerLayer -> SheetFall
        else -> ContentFadeOut
    }

    /** Any move within the inner graph, arriving. */
    fun contentEnter(reducedMotion: Boolean): EnterTransition =
        if (reducedMotion) EnterTransition.None else ContentFadeIn

    /** Any move within the inner graph, leaving. */
    fun contentExit(reducedMotion: Boolean): ExitTransition =
        if (reducedMotion) ExitTransition.None else ContentFadeOut

    // -------------------------------------------------- a Back the finger is still holding

    /**
     * How far the outgoing screen shrinks at the end of a full drag. 0.9.
     *
     * The platform's own figure. navigation-compose defaults to `scaleOut(0.7f)`, which is a far
     * bigger collapse than anything Android draws, and it scales **about the centre** so the screen
     * implodes in place rather than being pushed aside.
     */
    const val PredictiveScale: Float = 0.9f

    /**
     * How far it slides, as a fraction of the window's width. 0.08.
     *
     * Small on purpose: the gesture is a peel, not a page turn. Together with [PredictiveScale] this
     * is the shape Android draws for a system Back - the surface eases away from under the finger
     * and the destination is revealed behind it, rather than through it.
     */
    const val PredictiveSlideFraction: Float = 0.08f

    /**
     * The screen being dragged off, while the finger is still down.
     *
     * ## What this replaced, and why the old answer was wrong
     *
     * The inner graph left this pair at navigation-compose's defaults, on the recorded belief that
     * "a gestural Back in here gets the platform's own seeked `scaleOut(0.7f)` preview of the screen
     * behind". The library's default is not the platform's animation. It is
     * `scaleOut(targetScale = 0.7f)` plus a fade, against a fade-in underneath, and seeking that
     * pair draws both screens at partial alpha for the whole of the drag: a double exposure that
     * follows the thumb, with the outgoing screen collapsing to seven-tenths in the middle of the
     * window.
     *
     * Android's predictive Back does none of that. The outgoing surface stays **opaque**, shrinks
     * only slightly, and travels in the direction the finger is travelling. The destination sits
     * still and opaque behind it. That is what this builds.
     *
     * ## Why the swipe edge matters here and not on the player layer
     *
     * The outer graph ignores it deliberately - Now Playing moves on the vertical axis, so which
     * side the finger came from says nothing about where the sheet should go. A content pane is the
     * opposite case: the whole illusion is that the screen is being pushed aside, and a screen that
     * slid left while the finger pulled right would read as a fight with the gesture.
     *
     * A drag from the left edge travels rightwards, so the surface goes with it, and the reverse
     * from the right edge.
     *
     * @param swipeEdge `BackEventCompat.EDGE_LEFT` (0) or `EDGE_RIGHT` (1).
     */
    fun predictivePopExit(swipeEdge: Int, reducedMotion: Boolean): ExitTransition {
        if (reducedMotion) return ExitTransition.None
        val direction: Int = predictiveSlideDirection(swipeEdge)
        return scaleOut(targetScale = PredictiveScale) +
            slideOutHorizontally { width ->
                (width * PredictiveSlideFraction * direction).toInt()
            }
    }

    /**
     * Which way the screen travels: `+1` rightwards, `-1` leftwards.
     *
     * Hoisted for the same reason [SheetSpec] is, and the reason is worth repeating because it is a
     * trap. `Slide` holds its offset function by reference and a transition compares by it, so two
     * separately built slides are **never** equal however identical their numbers - which means an
     * `assertNotEquals` between the two edges passes whether or not the edge is used at all. That
     * is a test asserting nothing. This function is the part of the decision a test can actually
     * read, so the direction is pinned here and the transition above is built from it.
     *
     * A drag from the left edge travels rightwards and the surface goes with it.
     */
    fun predictiveSlideDirection(swipeEdge: Int): Int = if (swipeEdge == EdgeLeft) 1 else -1

    /**
     * The screen being returned to, while the finger is still down: nothing at all.
     *
     * It is the transition's target, so navigation-compose never disposes it and gives it a z-index
     * below the outgoing screen. It therefore sits still and fully opaque, which is exactly what has
     * to be behind a surface being peeled away. Fading it up - the library's default - is what makes
     * the two screens visible through one another for the length of the drag.
     */
    fun predictivePopEnter(reducedMotion: Boolean): EnterTransition = EnterTransition.None

    /** `BackEventCompat.EDGE_LEFT`, inlined so this file needs no activity dependency. */
    const val EdgeLeft: Int = 0
}

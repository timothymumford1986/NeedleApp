package app.needler.core.design.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Needler's motion, transcribed from the `@keyframes` blocks that every screen in the pack shares.
 *
 * There are three animations in the whole design, and they are all part of one launch sequence plus
 * one loop:
 *
 *  1. **Spin-up** - the record turns two full revolutions over 2.1s while the tonearm drops onto it
 *     (`needler-spinup`, `needler-arm`), the whole splash fading in, holding, then shrinking away
 *     upward (`needler-splash`).
 *  2. **Rise** - the sign-in content rises 18dp into place over 0.5s, staggered at 1.70s, 1.85s,
 *     2.00s and 2.15s so it lands as the splash leaves (`needler-rise`).
 *  3. **Record spin** - a 3.6s linear loop while playback is running (`needler-spin`).
 *
 * Every helper here reads [LocalReducedMotion] and collapses to the animation's end state when it
 * is set, which is what the pack's `@media (prefers-reduced-motion: reduce)` rules do.
 */
object NeedlerMotion {

    /** Total length of the splash, from `animation: needler-splash 2.1s`. */
    const val SplashDurationMillis: Int = 2100

    /** Revolutions the record turns during spin-up: `needler-spinup` goes 0deg to 720deg. */
    const val SpinUpDegrees: Float = 720f

    /** Length of one content rise, from `animation: needler-rise 0.5s ease-out both`. */
    const val RiseDurationMillis: Int = 500

    /** How far content rises from, from `transform: translateY(18px)`. */
    val RiseOffset: Dp = 18.dp

    /** The `boot-d0`..`boot-d3` animation delays, in order. */
    val RiseDelaysMillis: List<Int> = listOf(1700, 1850, 2000, 2150)

    /** One revolution of the playing record, from `animation: needler-spin 3.6s linear infinite`. */
    const val RecordSpinDurationMillis: Int = 3600

    /** `cubic-bezier(.4,0,.2,1)` - the splash container's curve. Material's standard easing. */
    val Standard: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** `cubic-bezier(.3,0,.7,1)` - the record's spin-up curve. */
    val SpinUp: Easing = CubicBezierEasing(0.3f, 0f, 0.7f, 1f)

    /** CSS `ease-out`, used by the content rise. */
    val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** CSS `ease-in-out`, used by the tonearm drop. */
    val EaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Linear, used by the playing record. */
    val Linear: Easing = LinearEasing

    // ---- Splash keyframe boundaries, as fractions of [SplashDurationMillis] ------------------

    /** `needler-splash` reaches full opacity and scale at 12%. */
    const val SplashFadeInFraction: Float = 0.12f

    /** `needler-splash` starts leaving at 72%. */
    const val SplashHoldEndFraction: Float = 0.72f

    /** `needler-splash` enters at `scale(0.6)`. */
    const val SplashEnterScale: Float = 0.6f

    /** `needler-splash` exits at `scale(0.25)`. */
    const val SplashExitScale: Float = 0.25f

    /**
     * `needler-splash` exits with `translateY(-900px)`.
     *
     * Expressed as a multiple of the splash's own height rather than a fixed pixel count: 900px on
     * the pack's 844px-tall phone frame is 1.066 of the viewport, and that ratio - "just past the
     * top edge" - is what the animation is actually saying.
     */
    const val SplashExitTranslationFactor: Float = -1.066f

    /** `needler-arm` holds the tonearm lifted until 32%. */
    const val ArmHoldFraction: Float = 0.32f

    /** `needler-arm` has the tonearm down by 58%. */
    const val ArmDownFraction: Float = 0.58f

    /** `needler-arm` starts at `rotate(-38deg)`. */
    const val ArmLiftedDegrees: Float = -38f

    /** Delay for the nth element of the staggered rise, extrapolating past the fourth. */
    fun riseDelayMillis(stagger: Int): Int = when {
        stagger <= 0 -> RiseDelaysMillis.first()
        stagger < RiseDelaysMillis.size -> RiseDelaysMillis[stagger]
        else -> RiseDelaysMillis.last() + (stagger - RiseDelaysMillis.lastIndex) * 150
    }
}

/**
 * A tween that honours reduced motion.
 *
 * Under reduced motion the duration and delay collapse to zero, so the animation still *runs* - any
 * `onFinished` still fires, any state still settles - it simply arrives instantly.
 */
@Composable
fun <T> needlerTween(
    durationMillis: Int,
    delayMillis: Int = 0,
    easing: Easing = NeedlerMotion.Standard,
): FiniteAnimationSpec<T> {
    val reduced = LocalReducedMotion.current
    return if (reduced) {
        tween(durationMillis = 0, delayMillis = 0, easing = LinearEasing)
    } else {
        tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = easing)
    }
}

/**
 * Rotation in degrees of the playing record, looping every
 * [NeedlerMotion.RecordSpinDurationMillis].
 *
 * @param playing whether playback is running. The record is still when it is not.
 * @return a state holding degrees in `[0, 360)`. Pinned at `0f` under reduced motion, so no
 *   animation is started at all.
 */
@Composable
fun rememberRecordRotation(playing: Boolean = true): State<Float> {
    val reduced = LocalReducedMotion.current
    if (reduced || !playing) {
        return remember { mutableFloatStateOf(0f) }
    }
    val transition = rememberInfiniteTransition(label = "needler-record")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = NeedlerMotion.RecordSpinDurationMillis,
                easing = NeedlerMotion.Linear,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "needler-record-rotation",
    )
}

/**
 * Progress of one element's entry in the staggered launch rise, from `0f` (18dp low, transparent) to
 * `1f` (in place, opaque).
 *
 * @param stagger which of the staggered delays to use; 0 is the wordmark, 1 the headline, 2 the
 *   fields, 3 the button, and anything beyond continues the 150ms cadence.
 * @param play set `false` to hold the element at its start state, e.g. while a screen is off-stage.
 * @return `1f` immediately under reduced motion, matching `.boot-rise { animation: none }`.
 */
@Composable
fun rememberRiseProgress(stagger: Int = 0, play: Boolean = true): State<Float> {
    val reduced = LocalReducedMotion.current
    if (reduced) {
        return remember { mutableFloatStateOf(1f) }
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(stagger, play) {
        if (!play) return@LaunchedEffect
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = NeedlerMotion.RiseDurationMillis,
                delayMillis = NeedlerMotion.riseDelayMillis(stagger),
                easing = NeedlerMotion.EaseOut,
            ),
        )
    }
    return progress.asState()
}

/**
 * Applies the launch rise - 18dp up, fading in - to this element.
 *
 * Attach it to the wordmark, headline, field group and primary button of a launch screen with
 * `stagger` 0 to 3, exactly as the pack's `boot-d0`..`boot-d3` classes do.
 *
 * Under reduced motion this is a no-op layer: full opacity, no offset, no animation started.
 */
@Composable
fun Modifier.needlerRise(stagger: Int = 0, play: Boolean = true): Modifier {
    val progress = rememberRiseProgress(stagger, play)
    val offsetPx = with(LocalDensity.current) { NeedlerMotion.RiseOffset.toPx() }
    return graphicsLayer {
        val value = progress.value
        alpha = value
        translationY = (1f - value) * offsetPx
    }
}

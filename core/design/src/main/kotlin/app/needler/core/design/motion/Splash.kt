package app.needler.core.design.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.needler.core.design.theme.NeedlerTheme

/**
 * Needler's launch splash: the record spins up through two revolutions while the tonearm drops onto
 * it, then the whole mark shrinks away upward.
 *
 * This is a port of the pack's inline SVG plus its three `@keyframes` blocks, drawn as Compose
 * vector graphics. No SVG or drawable ships with it.
 *
 * Three rules from REQUIREMENTS.md are built in:
 *
 *  - **It overlays already-loaded content.** This composable paints an opaque [NeedlerColors.canvas]
 *    layer over whatever is beneath it and never delays that content's composition. Put it last in
 *    a `Box` whose earlier children are the real screen, so cold start is never waiting on it.
 *  - **It is skippable by a tap.** Any tap calls [onFinished] immediately.
 *  - **It is suppressed under reduced motion.** When [LocalReducedMotion] is set the splash is not
 *    drawn at all - matching `@media (prefers-reduced-motion: reduce) { .splash { display: none } }`
 *    - and [onFinished] fires on the first composition.
 *
 * @param onFinished called exactly once, when the animation completes, when the user taps to skip,
 *   or immediately under reduced motion. The caller is responsible for removing the splash in
 *   response; this composable does not hide itself.
 * @param markSize how large to draw the record. 240dp on the phone Connect screen, 420dp on the
 *   tablet one.
 */
@Composable
fun NeedlerSplash(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    markSize: Dp = 240.dp,
) {
    val reducedMotion = LocalReducedMotion.current
    if (reducedMotion) {
        LaunchedEffect(Unit) { onFinished() }
        return
    }

    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        clock.animateTo(
            targetValue = 1f,
            // One linear 0..1 clock drives the whole sequence; each keyframe segment applies its
            // own curve below, which is how CSS interpolates between keyframes.
            animationSpec = tween(
                durationMillis = NeedlerMotion.SplashDurationMillis,
                easing = NeedlerMotion.Linear,
            ),
        )
        onFinished()
    }
    val progress: State<Float> = clock.asState()
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NeedlerTheme.colors.canvas)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = "Skip the opening animation",
                onClick = onFinished,
            )
            .graphicsLayer {
                val t = progress.value
                alpha = splashAlpha(t)
                val s = splashScale(t)
                scaleX = s
                scaleY = s
                translationY = splashTranslationFraction(t) * size.height
            },
        contentAlignment = Alignment.Center,
    ) {
        NeedlerRecordMark(
            progress = progress,
            modifier = Modifier
                .size(markSize)
                // Decorative: the pack marks the splash aria-hidden. The skip action above carries
                // the only semantics a screen reader needs here.
                .clearAndSetSemantics {},
        )
    }
}

/**
 * The record-and-tonearm mark, drawn at whatever size it is given.
 *
 * Geometry is the pack's `viewBox="0 0 200 200"` scaled to the composable's width: a 7-unit ring at
 * r=86, three 1-unit grooves at r=66/52/38, an 18-unit label with a 2.5-unit spindle hole, and a
 * tonearm pivoting at (176, 24) with a 5-unit stylus head at (116, 94).
 *
 * @param progress the 0..1 splash clock. The record's rotation and the arm's angle are both derived
 *   from it, so the drawing stays in step with [NeedlerSplash] without a second animation.
 */
@Composable
fun NeedlerRecordMark(
    progress: State<Float>,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    Canvas(modifier = modifier) {
        val unit = size.minDimension / 200f
        val centre = Offset(100f * unit, 100f * unit)
        val t = progress.value

        rotate(degrees = NeedlerMotion.SpinUp.transform(t) * NeedlerMotion.SpinUpDegrees, pivot = centre) {
            drawRecord(colors.textPrimary, colors.recordRing, colors.canvas, centre, unit)
        }

        val pivot = Offset(176f * unit, 24f * unit)
        rotate(degrees = armAngle(t), pivot = pivot) {
            drawTonearm(colors.accent, unit)
        }
    }
}

/** The disc: outer ring, three grooves, label, spindle hole. */
private fun DrawScope.drawRecord(
    ringColor: Color,
    grooveColor: Color,
    holeColor: Color,
    centre: Offset,
    unit: Float,
) {
    drawCircle(
        color = ringColor,
        radius = 86f * unit,
        center = centre,
        style = Stroke(width = 7f * unit),
    )
    for (radius in intArrayOf(66, 52, 38)) {
        drawCircle(
            color = grooveColor,
            radius = radius * unit,
            center = centre,
            style = Stroke(width = 1f * unit),
        )
    }
    drawCircle(color = ringColor, radius = 18f * unit, center = centre)
    drawCircle(
        color = holeColor,
        radius = 2.5f * unit,
        center = Offset(100f * unit, 84f * unit),
    )
}

/** The tonearm: pivot boss, arm, stylus head. */
private fun DrawScope.drawTonearm(color: Color, unit: Float) {
    drawCircle(color = color, radius = 9f * unit, center = Offset(176f * unit, 24f * unit))
    drawLine(
        color = color,
        start = Offset(176f * unit, 24f * unit),
        end = Offset(118f * unit, 92f * unit),
        strokeWidth = 5f * unit,
        cap = StrokeCap.Round,
    )
    drawCircle(color = color, radius = 5f * unit, center = Offset(116f * unit, 94f * unit))
}

/** `needler-splash`: 0 to 1 by 12%, held to 72%, back to 0 at the end. */
internal fun splashAlpha(t: Float): Float = when {
    t < NeedlerMotion.SplashFadeInFraction ->
        NeedlerMotion.Standard.transform(t / NeedlerMotion.SplashFadeInFraction)
    t < NeedlerMotion.SplashHoldEndFraction -> 1f
    else -> 1f - NeedlerMotion.Standard.transform(exitFraction(t))
}

/** `needler-splash`: `scale(0.6)` to `scale(1)` by 12%, held, then out to `scale(0.25)`. */
internal fun splashScale(t: Float): Float = when {
    t < NeedlerMotion.SplashFadeInFraction -> lerp(
        start = NeedlerMotion.SplashEnterScale,
        stop = 1f,
        fraction = NeedlerMotion.Standard.transform(t / NeedlerMotion.SplashFadeInFraction),
    )
    t < NeedlerMotion.SplashHoldEndFraction -> 1f
    else -> lerp(
        start = 1f,
        stop = NeedlerMotion.SplashExitScale,
        fraction = NeedlerMotion.Standard.transform(exitFraction(t)),
    )
}

/** `needler-splash`: the exit slides the mark up past the top edge. */
internal fun splashTranslationFraction(t: Float): Float = when {
    t < NeedlerMotion.SplashHoldEndFraction -> 0f
    else -> lerp(
        start = 0f,
        stop = NeedlerMotion.SplashExitTranslationFactor,
        fraction = NeedlerMotion.Standard.transform(exitFraction(t)),
    )
}

/** `needler-arm`: lifted until 32%, down by 58%, then held. */
internal fun armAngle(t: Float): Float = when {
    t < NeedlerMotion.ArmHoldFraction -> NeedlerMotion.ArmLiftedDegrees
    t < NeedlerMotion.ArmDownFraction -> lerp(
        start = NeedlerMotion.ArmLiftedDegrees,
        stop = 0f,
        fraction = NeedlerMotion.EaseInOut.transform(
            (t - NeedlerMotion.ArmHoldFraction) /
                (NeedlerMotion.ArmDownFraction - NeedlerMotion.ArmHoldFraction),
        ),
    )
    else -> 0f
}

private fun exitFraction(t: Float): Float =
    ((t - NeedlerMotion.SplashHoldEndFraction) / (1f - NeedlerMotion.SplashHoldEndFraction))
        .coerceIn(0f, 1f)

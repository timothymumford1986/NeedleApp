package app.needler.core.design.motion

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * The vinyl disc that turns behind the artwork while something is playing (screens 07, 09, 11, 16).
 *
 * The pack builds it in CSS as a `repeating-radial-gradient` of 3px grooves overlaid with a
 * `conic-gradient` sheen, spinning on `animation: needler-spin 3.6s linear infinite`. Compose has no
 * repeating radial gradient, so the grooves are drawn here as concentric 3dp strokes alternating
 * between [NeedlerColors.recordGrooveLight] and [NeedlerColors.recordGrooveDark] - the same banding,
 * expressed as geometry rather than as a gradient. The sheen is a sweep gradient, which starts at
 * three o'clock where the CSS conic gradient starts at twelve; the disc is rotating, so the offset
 * is not observable.
 *
 * It is decorative and carries no semantics. Put the artwork in a sibling layer on top of it.
 *
 * @param playing whether playback is running. The disc holds still when it is not, and - via
 *   [rememberRecordRotation] - starts no animation at all under reduced motion.
 * @param labelColor the centre label. The pack tints it from the album artwork; pass
 *   [NeedlerColors.textPrimary] when there is no artwork to sample.
 */
@Composable
fun NeedlerSpinningRecord(
    playing: Boolean,
    modifier: Modifier = Modifier,
    labelColor: Color = NeedlerTheme.colors.textPrimary,
) {
    val colors = NeedlerTheme.colors
    val rotation = rememberRecordRotation(playing)

    Canvas(modifier = modifier.clearAndSetSemantics {}) {
        // DrawScope is a Density, so dp converts without a LocalDensity lookup.
        val grooveWidthPx = 3.dp.toPx()
        val labelRadiusPx = 23.dp.toPx()
        val labelRingPx = 7.dp.toPx()
        val centre = Offset(size.width / 2f, size.height / 2f)
        val outer = size.minDimension / 2f

        rotate(degrees = rotation.value, pivot = centre) {
            drawGrooves(
                light = colors.recordGrooveLight,
                dark = colors.recordGrooveDark,
                centre = centre,
                outerRadius = outer,
                bandWidth = grooveWidthPx,
            )
            // The sheen: two bright lobes roughly opposite each other, as in the conic gradient.
            drawCircle(
                brush = Brush.sweepGradient(
                    0.00f to Color.Transparent,
                    0.05f to colors.recordSheenBright,
                    0.11f to Color.Transparent,
                    0.50f to Color.Transparent,
                    0.55f to colors.recordSheenDim,
                    0.61f to Color.Transparent,
                    1.00f to Color.Transparent,
                    center = centre,
                ),
                radius = outer,
                center = centre,
            )
        }

        drawCircle(
            color = colors.recordEdge,
            radius = outer - 0.5f,
            center = centre,
            style = Stroke(width = 1f),
        )
        // The label, ringed by the raised surface exactly as the 7dp border in the HTML does.
        drawCircle(color = colors.surfaceRaised, radius = labelRadiusPx, center = centre)
        drawCircle(color = labelColor, radius = labelRadiusPx - labelRingPx, center = centre)
    }
}

/** Concentric 3dp bands, alternating light and dark, standing in for the repeating gradient. */
private fun DrawScope.drawGrooves(
    light: Color,
    dark: Color,
    centre: Offset,
    outerRadius: Float,
    bandWidth: Float,
) {
    // Fill first so the innermost band never leaves a hole at the centre.
    drawCircle(color = dark, radius = outerRadius, center = centre)
    var radius = outerRadius - bandWidth / 2f
    var useLight = true
    while (radius > 0f) {
        drawCircle(
            color = if (useLight) light else dark,
            radius = radius,
            center = centre,
            style = Stroke(width = bandWidth),
        )
        radius -= bandWidth
        useLight = !useLight
    }
}

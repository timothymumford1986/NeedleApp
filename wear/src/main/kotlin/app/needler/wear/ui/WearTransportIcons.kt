package app.needler.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The transport glyphs, drawn from the design pack's own path data.
 *
 * `:core:design` draws its icons the same way, and its reasoning carries over word for word: "Every
 * one keeps the pack's own path data on a 24x24 viewport with a 1.8-unit round-capped stroke, so an
 * icon here is the same shape the design shows and not a lookalike from an icon set."
 *
 * There are two extra reasons on a watch. First, `:core:design` is not on this module's classpath, so
 * its icons cannot be imported even though they are the same shapes - the path strings below are
 * copied from `design/html/07-NowPlaying.html`, the same source `:core:design` transcribed. Second,
 * neither `androidx.compose.material:material-icons-core` nor Wear's icon set is a dependency of this
 * module, and adding one to get four glyphs Needler already owns would be the wrong trade.
 *
 * These are decorative: each clears its own semantics, and the button around it carries the content
 * description. An icon that announces itself *and* sits in a described button is read out twice.
 */

/** The pack's viewport for every icon. */
private const val VIEWPORT: Float = 24f

/** Stroke width the pack uses almost everywhere, in viewport units. */
private const val STROKE: Float = 1.8f

/** `M7 5v14l11-7z` - the solid play triangle, as `:core:design` also has it. */
private const val PATH_PLAY: String = "M7 5v14l11-7z"

/** `M18 5v14L9 12z` - the solid triangle of the Previous button, pointing left. */
private const val PATH_PREVIOUS_TRIANGLE: String = "M18 5v14L9 12z"

/** `M6 5v14` - the bar Previous sits against. */
private const val PATH_PREVIOUS_BAR: String = "M6 5v14"

/** `M6 5v14l9-7z` - the solid triangle of the Next button, pointing right. */
private const val PATH_NEXT_TRIANGLE: String = "M6 5v14l9-7z"

/** `M18 5v14` - the bar Next runs into. */
private const val PATH_NEXT_BAR: String = "M18 5v14"

/**
 * Scales the drawing surface so the pack's 24x24 coordinates land inside whatever size the caller
 * gave. Pivoted at the origin rather than the centre, because the path data is expressed from the
 * top-left of the viewport.
 */
private fun DrawScope.scaleToViewport(block: DrawScope.() -> Unit) {
    val factor: Float = size.minDimension / VIEWPORT
    scale(scaleX = factor, scaleY = factor, pivot = Offset.Zero) { block() }
}

private fun strokeStyle(): Stroke =
    Stroke(width = STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** The solid play triangle. */
@Composable
fun PlayGlyph(tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val triangle: Path = remember { PathParser().parsePathString(PATH_PLAY).toPath() }
    Canvas(modifier = Modifier.size(size).then(modifier).clearAndSetSemantics {}) {
        scaleToViewport { drawPath(path = triangle, color = tint) }
    }
}

/**
 * The two pause bars.
 *
 * The pack draws these as `<rect>` elements rather than a path - `x=6/14, y=5, 4x14, rx=1` - so they
 * are composed here instead of parsed, exactly as `:core:design` does for its own composite glyphs.
 */
@Composable
fun PauseGlyph(tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier = Modifier.size(size).then(modifier).clearAndSetSemantics {}) {
        scaleToViewport {
            for (x in floatArrayOf(6f, 14f)) {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(x, 5f),
                    size = Size(4f, 14f),
                    cornerRadius = CornerRadius(1f, 1f),
                )
            }
        }
    }
}

/** Triangle plus bar, pointing left. */
@Composable
fun PreviousGlyph(tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val triangle: Path = remember { PathParser().parsePathString(PATH_PREVIOUS_TRIANGLE).toPath() }
    val bar: Path = remember { PathParser().parsePathString(PATH_PREVIOUS_BAR).toPath() }
    Canvas(modifier = Modifier.size(size).then(modifier).clearAndSetSemantics {}) {
        scaleToViewport {
            drawPath(path = triangle, color = tint)
            drawPath(path = bar, color = tint, style = strokeStyle())
        }
    }
}

/** Triangle plus bar, pointing right. */
@Composable
fun NextGlyph(tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val triangle: Path = remember { PathParser().parsePathString(PATH_NEXT_TRIANGLE).toPath() }
    val bar: Path = remember { PathParser().parsePathString(PATH_NEXT_BAR).toPath() }
    Canvas(modifier = Modifier.size(size).then(modifier).clearAndSetSemantics {}) {
        scaleToViewport {
            drawPath(path = triangle, color = tint)
            drawPath(path = bar, color = tint, style = strokeStyle())
        }
    }
}

/**
 * A record seen face-on: the placeholder where artwork would be, and the whole of the idle screen.
 *
 * The pack builds its record from a repeating radial gradient of `#171e13` and `#101610` three pixels
 * apart. A gradient that fine is invisible at watch sizes and expensive to redraw, so this is the
 * same two colours as discrete rings - the mark, at the size it will actually be seen.
 *
 * Deliberately still: `:core:design` has a `SpinningRecord`, and it is not copied here. An animation
 * that runs for as long as music plays is a battery decision on a phone and a different one on a
 * watch, where the screen is either off or being looked at for four seconds.
 */
@Composable
fun RecordGlyph(modifier: Modifier = Modifier, size: Dp = 64.dp, label: Color = NeedlerWearColours.surfaceRaised) {
    Canvas(modifier = Modifier.size(size).then(modifier).clearAndSetSemantics {}) {
        val radius: Float = this.size.minDimension / 2f
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(color = NeedlerWearColours.recordGrooveDark, radius = radius, center = centre)
        // Grooves from the rim inwards, stopping short of the label.
        var groove: Float = radius * 0.94f
        val step: Float = radius * 0.11f
        while (groove > radius * 0.34f) {
            drawCircle(
                color = NeedlerWearColours.recordGrooveLight,
                radius = groove,
                center = centre,
                style = Stroke(width = radius * 0.035f),
            )
            groove -= step
        }
        drawCircle(color = label, radius = radius * 0.28f, center = centre)
        drawCircle(color = NeedlerWearColours.canvas, radius = radius * 0.06f, center = centre)
    }
}

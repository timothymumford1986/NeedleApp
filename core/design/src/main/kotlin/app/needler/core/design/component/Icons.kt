package app.needler.core.design.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
 * The handful of icons the shared components draw for themselves, ported from the inline SVG in
 * `design/html`.
 *
 * Every one keeps the pack's own path data on a 24x24 viewport with a 1.8-unit round-capped stroke,
 * so an icon here is the same shape the design shows and not a lookalike from an icon set. Screens
 * that need icons the design system does not own (nav glyphs, shuffle, the tonearm) can draw them
 * the same way with [NeedlerStrokeIcon].
 *
 * These are decorative by default: they carry no semantics of their own, because in the pack an icon
 * always sits inside a control or beside a label that names it. Where an icon is the *only* content
 * of a control, the control - not the icon - carries the content description.
 */
private const val VIEWPORT = 24f

/** Stroke width every icon in the pack uses, in viewport units. */
private const val STROKE = 1.8f

/**
 * Draws an SVG path string from the pack on a 24x24 viewport.
 *
 * @param pathData an SVG `d` attribute, e.g. `"M5 12l5 5 9-10"`.
 * @param strokeWidth stroke width in viewport units; the pack uses 1.8 almost everywhere, 2.4 for
 *   the small check inside the on-device badge and 2.0 for the grid play triangle.
 * @param filled fill the path instead of stroking it, for the solid transport triangles.
 */
@Composable
fun NeedlerStrokeIcon(
    pathData: String,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    strokeWidth: Float = STROKE,
    filled: Boolean = false,
) {
    val path = remember(pathData) { PathParser().parsePathString(pathData).toPath() }
    Canvas(modifier = Modifier.size(size).then(modifier).clearAndSetSemantics {}) {
        scaleToViewport {
            if (filled) {
                drawPath(path = path, color = tint)
            } else {
                drawPath(
                    path = path,
                    color = tint,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}

/** `M5 12l5 5 9-10` - the check used by In library, Ready, and the crossfade toggles. */
const val PathCheck: String = "M5 12l5 5 9-10"

/** `M12 4v11M7 10l5 5 5-5M4 19h16` - the download arrow used by Pull and Pulling. */
const val PathPull: String = "M12 4v11M7 10l5 5 5-5M4 19h16"

/** `M7 5v14l11-7z` - the solid play triangle. */
const val PathPlay: String = "M7 5v14l11-7z"

/** `M7 4.5v15l12-7.5z` - the outlined play triangle on an album grid cell. */
const val PathPlayOutline: String = "M7 4.5v15l12-7.5z"

/** `M9 5l7 7-7 7` - the row chevron. */
const val PathChevronRight: String = "M9 5l7 7-7 7"

/** `M5 9l7 7 7-7` - the disclosure chevron on a sort or output control. */
const val PathChevronDown: String = "M5 9l7 7 7-7"

/** `M15 5l-7 7 7 7` - the back arrow. */
const val PathChevronLeft: String = "M15 5l-7 7 7 7"

/** `M6 6l12 12M18 6L6 18` - the clear button inside a search field. */
const val PathClose: String = "M6 6l12 12M18 6L6 18"

/** The Searching clock: a circle with hands. */
const val PathClock: String = "M12 8v4l3 2"

/** Check, Pull, chevrons: the plain stroke icons, wrapped for convenience. */
@Composable
fun NeedlerCheckIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) =
    NeedlerStrokeIcon(PathCheck, tint, modifier, size)

@Composable
fun NeedlerPullIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) =
    NeedlerStrokeIcon(PathPull, tint, modifier, size)

@Composable
fun NeedlerPlayIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) =
    NeedlerStrokeIcon(PathPlay, tint, modifier, size, filled = true)

@Composable
fun NeedlerChevronRightIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) =
    NeedlerStrokeIcon(PathChevronRight, tint, modifier, size)

@Composable
fun NeedlerChevronDownIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) =
    NeedlerStrokeIcon(PathChevronDown, tint, modifier, size)

/**
 * The "on device" glyph: a phone outline with a check inside it.
 *
 * The pack draws it as a rounded `<rect>` plus two paths, so it is composed here rather than parsed
 * from a single path string.
 */
@Composable
fun NeedlerOnDeviceIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val check = remember { PathParser().parsePathString("M9.5 13.5l1.8 1.8 3.4-3.6").toPath() }
    val notch = remember { PathParser().parsePathString("M10 6h4").toPath() }
    Canvas(modifier = Modifier.size(size).then(modifier).clearAndSetSemantics {}) {
        scaleToViewport {
            val stroke = Stroke(width = STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawRoundRect(
                color = tint,
                topLeft = Offset(7f, 2.5f),
                size = Size(10f, 19f),
                cornerRadius = CornerRadius(2f, 2f),
                style = stroke,
            )
            drawPath(path = notch, color = tint, style = stroke)
            drawPath(path = check, color = tint, style = stroke)
        }
    }
}

/**
 * The "searching" glyph: a clock.
 *
 * A circle plus hands, as the pack draws it.
 */
@Composable
fun NeedlerClockIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val hands = remember { PathParser().parsePathString(PathClock).toPath() }
    Canvas(modifier = Modifier.size(size).then(modifier).clearAndSetSemantics {}) {
        scaleToViewport {
            val stroke = Stroke(width = STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawCircle(color = tint, radius = 8f, center = Offset(12f, 12f), style = stroke)
            drawPath(path = hands, color = tint, style = stroke)
        }
    }
}

/**
 * The "this track is playing" glyph: a record seen face-on, two concentric circles.
 *
 * Replaces the track number on the playing row of an album track list (screens 04, 08, 11).
 */
@Composable
fun NeedlerNowPlayingIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = Modifier.size(size).then(modifier).clearAndSetSemantics {}) {
        scaleToViewport {
            val stroke = Stroke(width = STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawCircle(color = tint, radius = 9f, center = Offset(12f, 12f), style = stroke)
            drawCircle(color = tint, radius = 2.5f, center = Offset(12f, 12f), style = stroke)
        }
    }
}

/**
 * The search glyph: a circle with a handle.
 *
 * A `<circle>` plus a path in the pack, so it is composed rather than parsed.
 */
@Composable
fun NeedlerSearchIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val handle = remember { PathParser().parsePathString("M20 20l-3.5-3.5").toPath() }
    Canvas(modifier = Modifier.size(size).then(modifier).clearAndSetSemantics {}) {
        scaleToViewport {
            val stroke = Stroke(width = STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawCircle(color = tint, radius = 7f, center = Offset(11f, 11f), style = stroke)
            drawPath(path = handle, color = tint, style = stroke)
        }
    }
}

/** The three-dot overflow glyph. */
@Composable
fun NeedlerMoreIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = Modifier.size(size).then(modifier).clearAndSetSemantics {}) {
        scaleToViewport {
            for (x in intArrayOf(6, 12, 18)) {
                drawCircle(color = tint, radius = 1.4f, center = Offset(x.toFloat(), 12f))
            }
        }
    }
}

/**
 * The drag handle on a crate row: three 10x2 bars at half opacity.
 *
 * Drawn at the pack's literal size rather than on the 24x24 viewport, because that is how the HTML
 * builds it.
 */
@Composable
fun NeedlerDragHandleIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(
        modifier = Modifier.size(width = 14.dp, height = 12.dp).then(modifier)
            .clearAndSetSemantics {},
    ) {
        val barWidth = 10.dp.toPx()
        val barHeight = 2.dp.toPx()
        val gap = 3.dp.toPx()
        val left = (size.width - barWidth) / 2f
        var top = (size.height - (barHeight * 3 + gap * 2)) / 2f
        repeat(3) {
            drawRect(
                color = tint.copy(alpha = tint.alpha * 0.5f),
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
            )
            top += barHeight + gap
        }
    }
}

/** Scales the drawing so the pack's 24-unit viewport fills this canvas. */
private inline fun DrawScope.scaleToViewport(crossinline block: DrawScope.() -> Unit) {
    val factor = size.minDimension / VIEWPORT
    scale(scale = factor, pivot = Offset.Zero) { block() }
}

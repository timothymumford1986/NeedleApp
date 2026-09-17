package app.needler.core.design.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * The linear progress bar: a 4dp track with a 2dp radius.
 *
 * Ported from the pull rows on Pulls (06), where it is green because it reports acquisition, and
 * from the scrubber on Now Playing (07, 09, 14), where it is the accent because it reports playback.
 * Both are the same bar; only the colour and the thumb differ.
 *
 * REQUIREMENTS.md's "2 px for progress bars" is the corner radius; the pack draws the bar itself
 * 4px thick.
 *
 * @param progress 0f to 1f. Coerced, so an out-of-range server percentage cannot draw outside the
 *   track.
 * @param contentDescription what this bar is reporting, e.g. "Pull progress". The value itself is
 *   announced from the range info, so do not put a percentage in here.
 */
@Composable
fun NeedlerLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = NeedlerTheme.colors.positive,
    trackColor: Color = NeedlerTheme.colors.progressTrack,
    thickness: Dp = NeedlerTheme.sizes.progressTrackThickness,
    contentDescription: String? = null,
) {
    val clamped = progress.coerceIn(0f, 1f)
    val shape = NeedlerTheme.shapes.progress
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .clip(shape)
            .background(trackColor)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
                if (contentDescription != null) this.contentDescription = contentDescription
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(thickness)
                .clip(shape)
                .background(color),
        )
    }
}

/**
 * The playback scrubber: the same 4dp track with a round thumb sitting on it.
 *
 * Ported from Now Playing (07, 16dp thumb), the tablet sidebar (09, 14dp thumb) and the lock screen
 * (14, no thumb). The row is padded to at least [NeedlerSizes.minTouchTarget] tall so the thumb is
 * draggable without being a 4dp target.
 *
 * @param onSeek when non-null the bar becomes interactive, and exposes a `setProgress` action so
 *   TalkBack can seek without dragging.
 */
@Composable
fun NeedlerScrubBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = NeedlerTheme.colors.accent,
    trackColor: Color = NeedlerTheme.colors.progressTrack,
    thumbSize: Dp = NeedlerTheme.sizes.scrubberThumb,
    contentDescription: String = "Playback position",
    onSeek: ((Float) -> Unit)? = null,
) {
    val clamped = progress.coerceIn(0f, 1f)
    val shape = NeedlerTheme.shapes.progress
    val thickness = NeedlerTheme.sizes.progressTrackThickness

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(maxOf(NeedlerTheme.sizes.minTouchTarget, thumbSize))
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
                if (onSeek != null) {
                    // REQUIREMENTS.md: anything reachable by dragging must also be reachable by an
                    // accessible action. TalkBack seeks through this rather than by swiping a 4dp
                    // track.
                    setProgress { target ->
                        onSeek(target.coerceIn(0f, 1f))
                        true
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidth = maxWidth
        val trackWidthPx = with(LocalDensity.current) { trackWidth.toPx() }
        val gestures = if (onSeek == null) {
            Modifier
        } else {
            Modifier
                .pointerInput(trackWidthPx, onSeek) {
                    detectTapGestures { offset ->
                        onSeek((offset.x / trackWidthPx).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(trackWidthPx, onSeek) {
                    detectHorizontalDragGestures { change, _ ->
                        onSeek((change.position.x / trackWidthPx).coerceIn(0f, 1f))
                    }
                }
        }
        Box(modifier = Modifier.matchParentSize().then(gestures))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(thickness)
                .clip(shape)
                .background(trackColor),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(thickness)
                .clip(shape)
                .background(color),
        )
        Box(
            modifier = Modifier
                .offset(x = trackWidth * clamped - thumbSize / 2)
                .size(thumbSize)
                .clip(NeedlerTheme.shapes.circle)
                .background(color),
        )
    }
}

/**
 * The progress ring drawn over album artwork while a pull runs (06).
 *
 * A 34dp ring with a 4dp round-capped stroke, starting at twelve o'clock: the pack rotates its SVG
 * `-90deg` and uses `stroke-dasharray`, which is the same thing said in CSS.
 *
 * Put it over [app.needler.core.design.theme.NeedlerColors.artworkScrimStrong] on top of the
 * artwork, as screen 06 does.
 */
@Composable
fun NeedlerProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = NeedlerTheme.sizes.progressRing,
    strokeWidth: Dp = NeedlerTheme.sizes.progressRingStroke,
    color: Color = NeedlerTheme.colors.positive,
    trackColor: Color = NeedlerTheme.colors.progressTrackOnArtwork,
    contentDescription: String? = null,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
                if (contentDescription != null) this.contentDescription = contentDescription
            },
    ) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2f
        val arcSize = Size(
            width = this.size.width - stroke.width,
            height = this.size.height - stroke.width,
        )
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke.width),
        )
        if (clamped > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * clamped,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = stroke,
            )
        }
    }
}

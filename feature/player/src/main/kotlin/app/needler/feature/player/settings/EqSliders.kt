package app.needler.feature.player.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.EqBand
import app.needler.core.domain.model.EqSettings
import kotlin.math.roundToInt

/**
 * One band of the equaliser: the gain readout, a vertical slider, and the frequency.
 *
 * The pack draws a 4 dp track 220 dp tall with a 24 dp thumb, the accent filling from the centre -
 * 0 dB - out to wherever the thumb is, so a cut reads as a bar hanging below the middle and a boost
 * as one standing above it. That is worth keeping: it is the difference between ten sliders and a
 * curve you can see.
 *
 * ## Accessibility
 *
 * A 4 dp track cannot be dragged by anyone relying on a screen reader, and ten of them across a
 * phone cannot each be 48 dp wide. So the whole column is the gesture target, and the band is
 * exposed as a range with a `setProgress` action: TalkBack sets 31 Hz to a value instead of swiping
 * at a hairline. The spoken form is the band and its gain - "31 hertz, plus 2 decibels" - because
 * "thirty-one" and "plus two" on their own are not an instrument.
 */
@Composable
fun EqBandSlider(
    band: EqBand,
    gainDb: Float,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackHeight: Dp = 220.dp,
) {
    val colors = NeedlerTheme.colors
    val range = EqSettings.GAIN_RANGE_DB
    val clamped: Float = gainDb.coerceIn(range)
    // 0f at +12 dB (the top of the track), 1f at -12 dB. Screen coordinates run downwards and gain
    // runs upwards, which is the one place this is easy to get backwards.
    val fromTop: Float = (range.endInclusive - clamped) / (range.endInclusive - range.start)

    val thumb: Dp = 24.dp
    val trackHeightPx: Float = with(LocalDensity.current) { trackHeight.toPx() }

    fun gainAt(y: Float): Float {
        val fraction: Float = (y / trackHeightPx).coerceIn(0f, 1f)
        return range.endInclusive - fraction * (range.endInclusive - range.start)
    }

    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = spokenBand(band)
                stateDescription = spokenGain(clamped)
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = clamped,
                    range = range,
                    // 24 dB in 1 dB steps: what the readout above the slider shows.
                    steps = 24,
                )
                if (enabled) {
                    setProgress { target ->
                        onGainChange(target.coerceIn(range))
                        true
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = gainLabel(clamped),
            style = NeedlerTheme.typography.micro,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
        )

        Box(
            modifier = Modifier
                .width(thumb)
                .height(trackHeight)
                .then(
                    if (!enabled) {
                        Modifier
                    } else {
                        Modifier
                            .pointerInput(trackHeightPx) {
                                detectTapGestures { offset -> onGainChange(gainAt(offset.y)) }
                            }
                            .pointerInput(trackHeightPx) {
                                detectVerticalDragGestures { change, _ ->
                                    change.consume()
                                    onGainChange(gainAt(change.position.y))
                                }
                            }
                    },
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(NeedlerTheme.shapes.progress)
                    .background(colors.progressTrack),
            )
            // The bar between 0 dB and the thumb. Drawn from whichever of the two is higher.
            val centreFraction = 0.5f
            val top: Float = minOf(fromTop, centreFraction)
            val bottom: Float = maxOf(fromTop, centreFraction)
            Box(
                modifier = Modifier
                    .offset(y = trackHeight * top)
                    .width(4.dp)
                    .height(trackHeight * (bottom - top))
                    .background(if (enabled) colors.accent else colors.surfaceRaised),
            )
            Box(
                modifier = Modifier
                    .offset(y = trackHeight * fromTop - thumb / 2)
                    .size(thumb)
                    .clip(NeedlerTheme.shapes.circle)
                    .background(if (enabled) colors.accent else colors.surfaceRaised),
            )
        }

        Text(
            text = bandLabel(band),
            style = NeedlerTheme.typography.caption,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The preamp: a label, the value, and a slider under it.
 *
 * Screen 19 draws the preamp as a row that only reports - a label and "-2 dB" - with no way to move
 * it, which leaves `EqSettings.preampDb` settable by nothing. The value is exactly the one a listener
 * reaches for when a boosted preset starts clipping, so the row is drawn as the pack has it and a
 * slider is added beneath. That is the one control on this screen the design does not draw.
 */
@Composable
fun PreampSlider(
    preampDb: Float,
    onPreampChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = NeedlerTheme.colors
    val range = EqSettings.GAIN_RANGE_DB
    val clamped: Float = preampDb.coerceIn(range)
    val fraction: Float = (clamped - range.start) / (range.endInclusive - range.start)
    val thumb: Dp = 20.dp

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Preamp",
                style = NeedlerTheme.typography.body,
                color = if (enabled) colors.textPrimary else colors.textMuted,
            )
            Text(
                text = gainLabel(clamped) + " dB",
                style = NeedlerTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(NeedlerTheme.sizes.minTouchTarget)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Preamp"
                    stateDescription = spokenGain(clamped)
                    progressBarRangeInfo = ProgressBarRangeInfo(clamped, range, steps = 24)
                    if (enabled) {
                        setProgress { target ->
                            onPreampChange(target.coerceIn(range))
                            true
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val widthPx: Float = with(LocalDensity.current) { maxWidth.toPx() }
            val trackWidth = maxWidth

            fun valueAt(x: Float): Float {
                val f: Float = (x / widthPx).coerceIn(0f, 1f)
                return range.start + f * (range.endInclusive - range.start)
            }

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(
                        if (!enabled) {
                            Modifier
                        } else {
                            Modifier
                                .pointerInput(widthPx) {
                                    detectTapGestures { offset -> onPreampChange(valueAt(offset.x)) }
                                }
                                .pointerInput(widthPx) {
                                    detectHorizontalDragGestures { change, _ ->
                                        change.consume()
                                        onPreampChange(valueAt(change.position.x))
                                    }
                                }
                        },
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NeedlerTheme.sizes.progressTrackThickness)
                    .clip(NeedlerTheme.shapes.progress)
                    .background(colors.progressTrack),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(NeedlerTheme.sizes.progressTrackThickness)
                    .clip(NeedlerTheme.shapes.progress)
                    .background(if (enabled) colors.accent else colors.surfaceRaised),
            )
            Box(
                modifier = Modifier
                    .offset(x = trackWidth * fraction - thumb / 2)
                    .size(thumb)
                    .clip(NeedlerTheme.shapes.circle)
                    .background(if (enabled) colors.accent else colors.surfaceRaised),
            )
        }
    }
}

/** `+2`, `0`, `-1` - the readout above each band, rounded to whole decibels as the pack prints it. */
fun gainLabel(gainDb: Float): String {
    val whole: Int = gainDb.roundToInt()
    return when {
        whole > 0 -> "+" + whole
        else -> whole.toString()
    }
}

/** `31`, `1k`, `16k` - the frequency under each band. */
fun bandLabel(band: EqBand): String {
    val hz: Int = band.centreFrequencyHz
    return if (hz >= 1_000) (hz / 1_000).toString() + "k" else hz.toString()
}

/** `31 hertz`, `16 kilohertz`: how a band should be read out, rather than as "31 k". */
internal fun spokenBand(band: EqBand): String {
    val hz: Int = band.centreFrequencyHz
    return if (hz >= 1_000) (hz / 1_000).toString() + " kilohertz" else hz.toString() + " hertz"
}

/** `plus 2 decibels`, `0 decibels`, `minus 1 decibel`. */
internal fun spokenGain(gainDb: Float): String {
    val whole: Int = gainDb.roundToInt()
    val unit: String = if (whole == 1 || whole == -1) "decibel" else "decibels"
    return when {
        whole > 0 -> "plus " + whole + " " + unit
        whole < 0 -> "minus " + (-whole) + " " + unit
        else -> "0 " + unit
    }
}

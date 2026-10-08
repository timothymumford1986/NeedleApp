package app.needler.feature.player.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerCheckIcon
import app.needler.core.design.component.NeedlerHairline
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.CrossfadeDuration
import app.needler.core.domain.model.CrossfadeSettings
import kotlin.math.roundToInt

/**
 * Crossfade, screen 20.
 *
 * Off, 4 s, 6 s or 12 s on a four-stop slider; a preview of what the chosen length actually does;
 * and the three sub-toggles - skip the fade inside an album, fade on skip, fade on pause.
 *
 * ## Why the sub-toggles go grey when crossfade is off
 *
 * All three describe a fade. With the length at Off there is no fade to skip, shorten or apply, so
 * leaving them live would offer three settings that change nothing. They keep their values - turning
 * crossfade back on restores exactly the arrangement that was there before - and simply stop
 * responding, which is the difference between a disabled control and a forgotten one.
 */
@Composable
fun CrossfadeScreen(
    settings: CrossfadeSettings,
    onDurationChange: (CrossfadeDuration) -> Unit,
    onSuppressWithinAlbumChange: (Boolean) -> Unit,
    onFadeOnSkipChange: (Boolean) -> Unit,
    onFadeOnPauseChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The two track names in the preview. The pack prints the crate's next two. */
    previewFrom: String = "This track",
    previewTo: String = "The next one",
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography

    Column(modifier = modifier.fillMaxSize()) {
        PlayerSettingsHeader(title = "Crossfade", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "Fade between tracks",
                        style = typography.bodyStrong,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = durationLabel(settings.duration),
                        style = typography.numeralDisplay,
                        color = colors.accent,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }

                CrossfadeSlider(
                    duration = settings.duration,
                    onDurationChange = onDurationChange,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics {},
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // The pack labels three of the four stops; 4 s is named by the readout above.
                    Text(text = "Off", style = typography.timecode, color = colors.textMuted)
                    Text(text = "6 s", style = typography.timecode, color = colors.textMuted)
                    Text(text = "12 s", style = typography.timecode, color = colors.textMuted)
                }
            }

            CrossfadePreview(
                duration = settings.duration,
                fromTrack = previewFrom,
                toTrack = previewTo,
            )

            Column {
                CheckRow(
                    label = "Skip fade inside an album",
                    subtitle = "Keeps gapless albums intact",
                    checked = settings.suppressWithinAlbum,
                    onCheckedChange = onSuppressWithinAlbumChange,
                    enabled = settings.isEnabled,
                )
                CheckRow(
                    label = "Fade on skip",
                    subtitle = "Short 1 s fade when you press next",
                    checked = settings.fadeOnSkip,
                    onCheckedChange = onFadeOnSkipChange,
                    enabled = settings.isEnabled,
                )
                CheckRow(
                    label = "Fade on pause",
                    subtitle = null,
                    checked = settings.fadeOnPause,
                    onCheckedChange = onFadeOnPauseChange,
                    enabled = settings.isEnabled,
                )
            }
        }
    }
}

/**
 * The four-stop slider.
 *
 * It looks continuous and behaves discretely, which is the pack's own design: the thumb sits at
 * nought, a third, two thirds or the end, and a drag snaps to the nearest. The accessible form says
 * so - a range of four steps with a `setProgress` action - so the control can be set without a
 * gesture, which a 4 dp track otherwise rules out.
 */
@Composable
private fun CrossfadeSlider(
    duration: CrossfadeDuration,
    onDurationChange: (CrossfadeDuration) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val stops: List<CrossfadeDuration> = CrossfadeDuration.entries
    val index: Int = stops.indexOf(duration).coerceAtLeast(0)
    val fraction: Float = index.toFloat() / (stops.size - 1).toFloat()
    val thumb = 28.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(NeedlerTheme.sizes.minTouchTarget)
            .semantics(mergeDescendants = true) {
                contentDescription = "Crossfade length"
                stateDescription = spokenDuration(duration)
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = index.toFloat(),
                    range = 0f..(stops.size - 1).toFloat(),
                    steps = stops.size - 2,
                )
                setProgress { target ->
                    val stop: Int = target.roundToInt().coerceIn(0, stops.lastIndex)
                    onDurationChange(stops[stop])
                    true
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidth = maxWidth
        val widthPx: Float = with(LocalDensity.current) { trackWidth.toPx() }

        fun stopAt(x: Float): CrossfadeDuration {
            val f: Float = (x / widthPx).coerceIn(0f, 1f)
            val stop: Int = (f * (stops.size - 1)).roundToInt().coerceIn(0, stops.lastIndex)
            return stops[stop]
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(widthPx) {
                    detectTapGestures { offset -> onDurationChange(stopAt(offset.x)) }
                }
                .pointerInput(widthPx) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        onDurationChange(stopAt(change.position.x))
                    }
                },
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
                .background(colors.accent),
        )
        Box(
            modifier = Modifier
                // Inside the gutter at both ends. It was `trackWidth * fraction - thumb / 2`, which
                // centres the thumb on its stop and therefore hangs half a thumb - 14 dp - outside
                // the screen's 24 dp margin at Off and again at 12 s. The travel is the track less
                // the thumb's own width instead, so the control's ink stays within the column every
                // other row on the screen is aligned to, and the thumb still covers its stop.
                .offset(x = (trackWidth - thumb) * fraction)
                .size(thumb)
                .clip(NeedlerTheme.shapes.circle)
                .background(colors.accent),
        )
    }
}

/**
 * The preview card: two volume envelopes crossing, or not crossing.
 *
 * The pack draws the crossfade case as two curves swapping places. The off case it never draws, so
 * it is drawn here as what actually happens - one track stopping where the next starts, square - and
 * the difference between the two pictures is the whole point of the setting.
 *
 * ## Why it takes the duration and not a boolean
 *
 * It took `enabled: Boolean`, and that made a panel labelled PREVIEW draw one unchanging picture for
 * three of the four stops. Measured: `player-crossfade-phone.png` at 4 s and
 * `player-crossfade-twelve-phone.png` at 12 s were pixel-identical across the whole curve region -
 * 0 differing pixels of 72,600 sampled at x 60 to 720, y 590 to 700 - so moving the slider two stops
 * changed the number above the card and nothing inside it. A preview that does not respond teaches a
 * listener that the control it previews does nothing, which is worse than no preview at all.
 *
 * ## What scales, and why it is the onset rather than the crossing point
 *
 * The handover **ends** where the pack puts it, at x=250, and a longer fade starts further back:
 * [HANDOVER_UNITS_PER_SECOND] units of the 320-unit viewBox per second, so 4 s is the pack's own
 * 180-to-250 drawing kept to the unit and 12 s begins at x=40. That is what a crossfade is - one
 * track's last N seconds are the next track's first N - so the picture gets longer at the front,
 * where the sound actually changes, rather than sliding sideways.
 *
 * The rejected alternative was to keep the crossing in the middle and widen it symmetrically. It is
 * the easier arithmetic and the wrong picture: it moves the moment the next track arrives, which the
 * setting does not do, and at 12 s it runs off both ends of the card.
 *
 * It is decorative and hidden from the accessibility tree; the line under it names both tracks, and
 * the slider above already says how long the fade is.
 */
@Composable
private fun CrossfadePreview(
    duration: CrossfadeDuration,
    fromTrack: String,
    toTrack: String,
    modifier: Modifier = Modifier,
) {
    val enabled: Boolean = duration != CrossfadeDuration.OFF
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val shape = NeedlerTheme.shapes.large

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Preview".uppercase(),
            style = typography.sectionHeader,
            color = colors.textSecondary,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clearAndSetSemantics {},
        ) {
            val w: Float = size.width
            val h: Float = size.height
            // The pack's own geometry, on a 320 by 56 viewBox: full level at y=12, silence at y=44,
            // the handover ending at x=250. Where it *begins* is the duration's doing.
            val high: Float = h * (12f / 56f)
            val low: Float = h * (44f / 56f)
            val end: Float = w * (250f / 320f)
            val start: Float = end - w * (handoverUnits(duration) / 320f)
            val cut: Float = w * (215f / 320f)
            val strokeWidth: Float = 3.dp.toPx()

            val fading = Path().apply {
                moveTo(0f, high)
                lineTo(start, high)
                if (enabled) {
                    cubicTo(start + (end - start) * 0.4f, high, end - (end - start) * 0.4f, low, end, low)
                } else {
                    lineTo(cut, high)
                    lineTo(cut, low)
                }
                lineTo(w, low)
            }
            val rising = Path().apply {
                moveTo(0f, low)
                lineTo(start, low)
                if (enabled) {
                    cubicTo(start + (end - start) * 0.4f, low, end - (end - start) * 0.4f, high, end, high)
                } else {
                    lineTo(cut, low)
                    lineTo(cut, high)
                }
                lineTo(w, high)
            }
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            drawPath(path = fading, color = colors.accent, style = stroke)
            drawPath(path = rising, color = colors.positive, style = stroke)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = fromTrack, style = typography.caption, color = colors.accent)
            Text(text = toTrack, style = typography.caption, color = colors.positive)
        }
    }
}

/**
 * A 56 dp row that is on when it carries a check, from screen 20.
 *
 * Not `NeedlerToggleRow`, which draws a switch: the pack's crossfade sub-settings are checks, and a
 * switch beside them would be a third control for the same idea in one app. The whole row is the
 * target and it reports itself as a checkbox, so the state is spoken rather than only coloured.
 */
@Composable
private fun CheckRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = if (subtitle == null) label else label + ". " + subtitle
                }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = typography.body,
                    color = if (enabled) colors.textPrimary else colors.disabled,
                )
                if (subtitle != null) {
                    Text(text = subtitle, style = typography.caption, color = colors.textMuted)
                }
            }
            if (checked) {
                NeedlerCheckIcon(
                    tint = if (enabled) colors.accent else colors.disabled,
                    size = 20.dp,
                )
            } else {
                UncheckedBox(enabled = enabled)
            }
        }
        NeedlerHairline()
    }
}

/**
 * How wide the handover is, in units of the preview's 320-unit viewBox.
 *
 * Internal and not `@Composable` so the one thing that makes the preview move can be asserted as
 * arithmetic, at every stop, without rendering a card and diffing two PNGs - which is how the
 * identical-preview defect got as far as a committed golden in the first place.
 *
 * `Off` is given the pack's own 70 rather than nought. The off case draws a square cut at x=215 and
 * never uses this width for anything, and returning nought would put the fade's start beyond its end.
 */
internal fun handoverUnits(duration: CrossfadeDuration): Float {
    if (duration == CrossfadeDuration.OFF) return PACK_HANDOVER_UNITS
    return duration.duration.inWholeSeconds * HANDOVER_UNITS_PER_SECOND
}

/**
 * The pack's own handover: x=180 to x=250 at 4 s, which is 70 units.
 *
 * Every other stop is this figure per second, so the drawing the design pack signed off is the one
 * that renders at the stop the pack drew.
 */
private const val PACK_HANDOVER_UNITS: Float = 70f

/** 70 units over 4 seconds. Twelve seconds is therefore 210, which begins at x=40 and fits. */
private const val HANDOVER_UNITS_PER_SECOND: Float = PACK_HANDOVER_UNITS / 4f

/**
 * What an off row draws where an on row draws its check.
 *
 * An off row used to draw nothing at all: `player-crossfade-phone.png`'s "Fade on pause" control
 * column contained zero non-background pixels while the two rows above carried accent ticks, so the
 * only off setting on the screen read as a line of prose rather than as a control in its off state.
 * A reader cannot learn that a row is a toggle from a row that has no toggle on it.
 *
 * An empty box rather than a greyed check, which would have said "set, and unavailable". The border
 * is `textSecondary` at 7.0:1 and not the hairline: REQUIREMENTS.md "Accessibility" measures the
 * hairline at **1.20:1** against the canvas and carries that as an open question, and an empty box
 * nobody can see is the defect this is fixing, drawn one shade lighter.
 */
@Composable
private fun UncheckedBox(enabled: Boolean) {
    val colors = NeedlerTheme.colors
    Box(
        modifier = Modifier
            .size(20.dp)
            .border(
                width = 1.5.dp,
                color = if (enabled) colors.textSecondary else colors.disabled,
                shape = UNCHECKED_BOX_SHAPE,
            ),
    )
}

/** The check icon's own corner, so on and off are the same shape at the same size. */
private val UNCHECKED_BOX_SHAPE: RoundedCornerShape = RoundedCornerShape(4.dp)

/** `Off`, `4 s`, `6 s`, `12 s` - the readout beside "Fade between tracks". */
fun durationLabel(duration: CrossfadeDuration): String = when (duration) {
    CrossfadeDuration.OFF -> "Off"
    CrossfadeDuration.FOUR_SECONDS -> "4 s"
    CrossfadeDuration.SIX_SECONDS -> "6 s"
    CrossfadeDuration.TWELVE_SECONDS -> "12 s"
}

/** The same, spoken: "off", "4 seconds". */
internal fun spokenDuration(duration: CrossfadeDuration): String = when (duration) {
    CrossfadeDuration.OFF -> "Off"
    CrossfadeDuration.FOUR_SECONDS -> "4 seconds"
    CrossfadeDuration.SIX_SECONDS -> "6 seconds"
    CrossfadeDuration.TWELVE_SECONDS -> "12 seconds"
}

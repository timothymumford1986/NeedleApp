package app.needler.feature.player.output

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerOutputRow
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.OutputTarget
import app.needler.feature.player.ui.PlayerBluetoothIcon
import app.needler.feature.player.ui.PlayerFormat
import app.needler.feature.player.ui.PlayerSpeakerIcon
import kotlin.math.roundToInt

/**
 * The "Play on" picker, screen 21: a sheet over the dimmed player.
 *
 * ## Why an unusable target is listed rather than hidden
 *
 * REQUIREMENTS.md, on Cast: "Probe reachability before offering a Cast target, and when it cannot
 * work, say why in the output picker rather than failing after the user picks a speaker." Hiding the
 * speaker would satisfy the second half and fail the first - the listener can see the thing on the
 * shelf, and a picker that pretends it is not there is a picker they stop trusting. So it is listed,
 * unselectable, with the reason in place of its usual second line: the VPN their server is behind,
 * the self-signed certificate the receiver will not accept, the plain HTTP it refuses. Every one of
 * those names something about the *setup*, because there is nothing wrong with the speaker.
 *
 * `NeedlerOutputRow` takes that reason directly and folds it into the spoken label, so the
 * explanation is not only visual.
 *
 * @param backdrop what shows through above the sheet. The pack dims Now Playing to 35% behind it;
 *   pass that composable to reproduce it, or leave it out for a plain canvas.
 */
@Composable
fun OutputPickerScreen(
    state: OutputUiState,
    onSelect: (OutputTarget) -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: (@Composable () -> Unit)? = null,
) {
    val colors = NeedlerTheme.colors

    Box(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        if (backdrop != null) {
            Box(modifier = Modifier.fillMaxSize().alpha(0.35f)) { backdrop() }
        }
        OutputSheet(
            state = state,
            onSelect = onSelect,
            onVolumeChange = onVolumeChange,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The sheet itself, without a backdrop.
 *
 * Separate so that a host with a real modal sheet - a `ModalBottomSheet` in `:app`'s navigation -
 * can put this inside it and get the same content, rather than a second copy of it.
 */
@Composable
fun OutputSheet(
    state: OutputUiState,
    onSelect: (OutputTarget) -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(NeedlerTheme.shapes.sheet)
            .background(colors.surface)
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(40.dp)
                .height(4.dp)
                .clip(NeedlerTheme.shapes.progress)
                .background(colors.grabberOnSheet),
        )

        Text(
            text = "Play on".uppercase(),
            style = typography.sheetTitle,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )

        if (state.isEmpty) {
            Text(
                text = "No speakers found. Sound plays on this device.",
                style = typography.meta,
                color = colors.textSecondary,
            )
        }

        Column {
            state.targets.forEach { target ->
                NeedlerOutputRow(
                    name = target.displayName,
                    detail = PlayerFormat.outputDetail(target),
                    selected = state.isSelected(target),
                    onClick = { onSelect(target) },
                    enabled = target.isSelectable,
                    unavailableReason = PlayerFormat.unavailableReason(target),
                    leadingIcon = { tint ->
                        when (target) {
                            is OutputTarget.Bluetooth -> PlayerBluetoothIcon(tint = tint, size = 22.dp)
                            else -> PlayerSpeakerIcon(tint = tint, size = 22.dp)
                        }
                    },
                )
            }
        }

        val status: String? = when {
            state.discovering -> "Looking for speakers nearby"
            state.hasPendingProbe -> "Checking whether a Cast speaker can reach your server"
            else -> null
        }
        if (status != null) {
            Text(
                text = status,
                style = typography.caption,
                color = colors.textMuted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        val error: String? = state.error?.let(PlayerFormat::errorMessage)
        if (error != null) {
            Text(
                text = error,
                style = typography.caption,
                color = colors.destructive,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        val volume: Float? = state.volume
        if (volume != null) {
            VolumeRow(volume = volume, onVolumeChange = onVolumeChange)
        }
    }
}

/**
 * The volume slider at the foot of the sheet.
 *
 * Drawn only when the state carries a level - see [OutputUiState.volume] for why it usually does
 * not. It is a percentage rather than a dB figure, because that is what a volume control is
 * everywhere else on the device, and it is announced as one.
 */
@Composable
private fun VolumeRow(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val clamped: Float = volume.coerceIn(0f, 1f)
    val thumb = 16.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Volume", style = NeedlerTheme.typography.meta, color = colors.textSecondary)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(NeedlerTheme.sizes.minTouchTarget)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Volume, " + (clamped * 100f).roundToInt() + " percent"
                    progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
                    setProgress { target ->
                        onVolumeChange(target.coerceIn(0f, 1f))
                        true
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val trackWidth = maxWidth
            val widthPx: Float = with(LocalDensity.current) { trackWidth.toPx() }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(widthPx) {
                        detectTapGestures { offset ->
                            onVolumeChange((offset.x / widthPx).coerceIn(0f, 1f))
                        }
                    }
                    .pointerInput(widthPx) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            onVolumeChange((change.position.x / widthPx).coerceIn(0f, 1f))
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
                    .fillMaxWidth(clamped)
                    .height(NeedlerTheme.sizes.progressTrackThickness)
                    .clip(NeedlerTheme.shapes.progress)
                    .background(colors.accent),
            )
            Box(
                modifier = Modifier
                    .offset(x = trackWidth * clamped - thumb / 2)
                    .size(thumb)
                    .clip(NeedlerTheme.shapes.circle)
                    .background(colors.accent),
            )
        }
    }
}

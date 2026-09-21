package app.needler.feature.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerScrubBar
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.playback.PlaybackProgress

/**
 * The scrub bar with elapsed on the left and remaining on the right.
 *
 * ## Why [progress] is a lambda
 *
 * `PlaybackController` emits position on its own flow, several times a second, so that a player
 * screen bound to playback *state* does not recompose for an entire album. Passing the value down
 * would throw that away at the last step: the screen reads it, so the screen recomposes. Taking a
 * lambda defers the read into this composable, and the tick invalidates the two labels and the bar -
 * not the artwork, the title, the transport or the crate.
 *
 * ## Accessibility
 *
 * [NeedlerScrubBar] exposes a `setProgress` action whenever `onSeek` is non-null, so TalkBack seeks
 * by setting a value rather than by dragging a 4 dp track, and the row is padded to a 48 dp target.
 * The two labels are hidden from the accessibility tree: the bar already announces its position, and
 * hearing "one colon sixteen, minus two colon oh four" after it helps nobody.
 */
@Composable
fun Scrubber(
    progress: () -> PlaybackProgress,
    durationMs: Long?,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbSize: Dp = NeedlerTheme.sizes.scrubberThumb,
    gap: Dp = 10.dp,
) {
    val colors = NeedlerTheme.colors
    val current: PlaybackProgress = progress()
    val fraction: Float = current.fractionOf(durationMs) ?: 0f

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        NeedlerScrubBar(
            progress = fraction,
            thumbSize = thumbSize,
            color = if (enabled) colors.accent else colors.progressTrack,
            contentDescription = "Playback position",
            onSeek = if (enabled && durationMs != null && durationMs > 0L) onSeek else null,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {},
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = PlayerFormat.timecode(if (durationMs == null) null else current.positionMs),
                style = NeedlerTheme.typography.timecode,
                color = colors.textMuted,
            )
            Text(
                text = PlayerFormat.remaining(current.positionMs, durationMs),
                style = NeedlerTheme.typography.timecode,
                color = colors.textMuted,
            )
        }
    }
}

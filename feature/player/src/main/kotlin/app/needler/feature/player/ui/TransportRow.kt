package app.needler.feature.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.playback.RepeatMode

/**
 * The five transport slots, at the two sizes the pack draws them.
 *
 * Screen 07 puts them at 48 / 56 / 80 / 56 / 48 dp; the tablet sidebar on screen 09 is the same row
 * at 44 / 48 / 68 / 48 / 44. The two smallest of those are under the 48 dp REQUIREMENTS.md asks of
 * transport controls, so they are drawn at the pack's size and given a 48 dp touch target -
 * [NeedlerIconButton] does that itself, taking `visualSize` for the ink and `max(visualSize, 48dp)`
 * for the target.
 */
enum class TransportSize(
    internal val sideButton: Dp,
    internal val sideIcon: Dp,
    internal val skipButton: Dp,
    internal val skipIcon: Dp,
    internal val playButton: Dp,
    internal val playIcon: Dp,
) {
    /** Now Playing on a phone (07). */
    Phone(
        sideButton = 48.dp,
        sideIcon = 22.dp,
        skipButton = 56.dp,
        skipIcon = 32.dp,
        playButton = 80.dp,
        playIcon = 36.dp,
    ),

    /** The tablet sidebar (09). */
    Sidebar(
        sideButton = 44.dp,
        sideIcon = 20.dp,
        skipButton = 48.dp,
        skipIcon = 28.dp,
        playButton = 68.dp,
        playIcon = 30.dp,
    ),
}

/**
 * Shuffle, previous, play/pause, next, repeat.
 *
 * ## The fifth slot
 *
 * The pack puts a Queue button there, on both 07 and 09. On 07 the header already carries one two
 * fingers above it, and on 09 the crate itself is the next thing down the sidebar, so in both places
 * the pack's fifth slot is a second route to something already on screen. It carries Repeat here
 * instead - a control `PlaybackController` cycles three modes of and the pack draws nowhere, which
 * would otherwise be reachable from no surface at all. That is the one deliberate departure from the
 * transport row as drawn, and it costs no route to the crate.
 *
 * @param enabled false when nothing is loaded. The row is still drawn, because a transport that
 *   vanishes when the crate empties makes the screen jump; the buttons simply do nothing and are
 *   announced as unavailable.
 */
@Composable
fun TransportRow(
    isPlaying: Boolean,
    isBuffering: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
    size: TransportSize = TransportSize.Phone,
    enabled: Boolean = true,
) {
    val colors = NeedlerTheme.colors

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerIconButton(
            contentDescription = if (shuffleEnabled) "Shuffle on" else "Shuffle off",
            onClick = onShuffle,
            enabled = enabled,
            visualSize = size.sideButton,
        ) {
            PlayerShuffleIcon(
                tint = if (shuffleEnabled && enabled) colors.accent else colors.textMuted,
                size = size.sideIcon,
            )
        }

        NeedlerIconButton(
            contentDescription = "Previous track",
            onClick = onPrevious,
            enabled = enabled,
            visualSize = size.skipButton,
        ) {
            PlayerPreviousIcon(
                tint = if (enabled) colors.textPrimary else colors.textMuted,
                size = size.skipIcon,
            )
        }

        NeedlerIconButton(
            contentDescription = playPauseDescription(isPlaying, isBuffering, enabled),
            onClick = onPlayPause,
            enabled = enabled,
            visualSize = size.playButton,
            background = if (enabled) colors.accent else colors.surfaceRaised,
        ) {
            val tint = if (enabled) colors.onAccent else colors.textMuted
            if (isPlaying) {
                PlayerPauseIcon(tint = tint, size = size.playIcon)
            } else {
                PlayerPlayIcon(tint = tint, size = size.playIcon)
            }
        }

        NeedlerIconButton(
            contentDescription = "Next track",
            onClick = onNext,
            enabled = enabled,
            visualSize = size.skipButton,
        ) {
            PlayerNextIcon(
                tint = if (enabled) colors.textPrimary else colors.textMuted,
                size = size.skipIcon,
            )
        }

        NeedlerIconButton(
            contentDescription = PlayerFormat.repeatDescription(repeatMode),
            onClick = onRepeat,
            enabled = enabled,
            visualSize = size.sideButton,
        ) {
            PlayerRepeatIcon(
                tint = if (repeatMode != RepeatMode.OFF && enabled) colors.accent else colors.textMuted,
                size = size.sideIcon,
                one = repeatMode == RepeatMode.ONE,
            )
        }
    }
}

/**
 * What the primary button is called right now.
 *
 * Buffering is deliberately an addition to the label rather than a change of icon: a track
 * re-buffering mid-stream is still playing as far as this button is concerned, and flipping it to a
 * play triangle on every stall is how a player looks broken on a slow connection. See
 * [app.needler.core.domain.playback.PlaybackState.isBuffering].
 */
private fun playPauseDescription(
    isPlaying: Boolean,
    isBuffering: Boolean,
    enabled: Boolean,
): String = when {
    !enabled -> "Play, nothing in the crate"
    isPlaying && isBuffering -> "Pause, buffering"
    isPlaying -> "Pause"
    isBuffering -> "Play, buffering"
    else -> "Play"
}

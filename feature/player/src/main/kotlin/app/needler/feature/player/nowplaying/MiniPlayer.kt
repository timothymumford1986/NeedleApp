package app.needler.feature.player.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.theme.NeedlerTheme
import app.needler.feature.player.PlayerUiState
import app.needler.feature.player.ui.PlayerArtwork
import app.needler.feature.player.ui.PlayerNextIcon
import app.needler.feature.player.ui.PlayerPauseIcon
import app.needler.feature.player.ui.PlayerPlayIcon

/**
 * The collapsed player above the bottom navigation on a phone, from screens 06 and 13.
 *
 * A 64 dp raised card with a hairline, 12 dp in from either side and 8 dp above the nav bar: 48 dp
 * of artwork, the title and the artist, and two controls. Tapping anywhere else on the card opens
 * Now Playing, which is the whole point of the bar.
 *
 * ## Accessibility
 *
 * The card is one target and the two buttons are two more, so the row is *not* merged into a single
 * node - merging would swallow Pause and Next into the card's label and leave a listener no way to
 * pause without opening the full screen. The card's own label is the whole player spoken as a
 * sentence ("Sienna, The Marias - Submarine, playing, on Living room speaker"), because that is what
 * a glance at it tells everyone else.
 *
 * ## Nothing playing
 *
 * Drawn, not hidden - see [MiniPlayerRoute] for when the bar is composed at all. The empty card says
 * what to do about it and disables the play button rather than offering a control that would do
 * nothing.
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val shape = NeedlerTheme.shapes.large

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .defaultMinSize(minHeight = NeedlerTheme.sizes.miniPlayerMinHeight)
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .clickable(role = Role.Button, onClick = onExpand)
            .semantics { contentDescription = state.spokenSummary + ". Open now playing" }
            .padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerArtwork(
            artwork = state.item?.track?.artwork,
            albumTitle = state.item?.track?.albumTitle,
            artistName = state.item?.track?.artistName,
            modifier = Modifier.size(NeedlerTheme.sizes.artworkThumb),
            describe = false,
            // The card is already the raised surface, which is what the default placeholder tint
            // is, so a cover that has not arrived would leave an invisible hole.
            placeholderColor = colors.surface,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = state.title,
                style = typography.rowTitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.item?.track?.artistName ?: state.subtitle,
                style = typography.meta,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NeedlerIconButton(
            contentDescription = if (state.isPlaying) "Pause" else "Play",
            onClick = onPlayPause,
            enabled = state.hasTrack,
            visualSize = NeedlerTheme.sizes.playButtonSmall,
            background = if (state.hasTrack) colors.accent else colors.surface,
        ) {
            val tint = if (state.hasTrack) colors.onAccent else colors.textMuted
            if (state.isPlaying) {
                PlayerPauseIcon(tint = tint, size = 22.dp)
            } else {
                PlayerPlayIcon(tint = tint, size = 22.dp)
            }
        }
        NeedlerIconButton(
            contentDescription = "Next track",
            onClick = onNext,
            enabled = state.hasTrack,
            visualSize = NeedlerTheme.sizes.playButtonSmall,
        ) {
            PlayerNextIcon(
                tint = if (state.hasTrack) colors.textPrimary else colors.textMuted,
                size = 22.dp,
            )
        }
    }
}

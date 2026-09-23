package app.needler.wear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import app.needler.wear.playback.WearPlaybackState

/**
 * The watch's one screen.
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Wear OS" puts transport controls at the head of Wear's
 * v1 scope, and that is what this is: artwork, title, artist, and previous / play-pause / next. The
 * crate and on-device playback, the other two items in that scope, are not here - `WearPlaybackProtocol`
 * records what they would each need and why neither is half-built alongside this.
 *
 * ## One screen, four states, no navigation
 *
 * There is no `SwipeDismissableNavHost` and no `ScalingLazyColumn`, because there is nothing to
 * navigate to and no list to scroll. `wear/build.gradle.kts` already carries
 * `wear-compose-navigation` for when the crate lands; an empty nav graph wrapped round a single
 * destination would be scaffolding pretending to be structure.
 *
 * The four states come from [WearPlaybackState], and the two that phone screens never need are the
 * point: a watch has to be able to say that it cannot see the phone, rather than showing a dead
 * transport. See that file for the argument.
 *
 * ## Round screens
 *
 * The layout is inset by a fraction of its own width, more on a round screen than a square one,
 * because the corners of a round display are not there. Everything is centred both ways for the same
 * reason - a circle has one safe area and it is the middle of it. The fraction is read from
 * `Configuration.isScreenRound` through [LocalContext] rather than from a window size class: watches
 * come in two shapes, not several widths, and the shape is the only thing the layout reacts to.
 *
 * ## Why the transport buttons are not Wear components
 *
 * They are a `Box` with a circular clip, a fill and a hand-drawn glyph. Wear's `IconButton` would
 * give a lookalike; the pack draws a specific transport - an 80dp accent-filled circle between two
 * bare 56dp buttons, `#aed5f2` on `#0d120a` - and `:core:design` makes the same call about icons for
 * the same reason. The sizes here are the pack's proportions taken down to a watch, not its
 * millimetres: 44dp either side of a 52dp primary keeps every target at or above Wear's 44dp minimum
 * while still fitting across the narrowest round screen Wear OS ships.
 *
 * ## Stateless
 *
 * Nothing here collects, connects or suspends. It takes a state and three lambdas, which is what
 * lets all four states be previewed, and screenshot-tested later, with no watch and no phone.
 */
@Composable
fun NowPlayingScreen(
    state: WearPlaybackState,
    artwork: ImageBitmap?,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRound: Boolean = LocalContext.current.resources.configuration.isScreenRound
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(NeedlerWearColours.canvas),
        contentAlignment = Alignment.Center,
    ) {
        // Wear's own guidance is a 5.2% margin on a square screen; a round one needs roughly double
        // that at the widest point to keep text off the bezel.
        val inset: Dp = maxWidth * (if (isRound) ROUND_INSET else SQUARE_INSET)
        val artworkSize: Dp = maxWidth * ARTWORK_FRACTION

        when (state) {
            is WearPlaybackState.NowPlaying -> NowPlayingContent(
                state = state,
                artwork = artwork,
                artworkSize = artworkSize,
                inset = inset,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
            )

            WearPlaybackState.Idle -> MessageContent(
                artworkSize = artworkSize,
                inset = inset,
                headline = "Nothing playing",
                detail = "Start something on your phone.",
            )

            WearPlaybackState.PhoneUnreachable -> MessageContent(
                artworkSize = artworkSize,
                inset = inset,
                headline = "Phone not connected",
                detail = "Needler on the watch controls the phone. Bring them back in range.",
            )

            WearPlaybackState.Connecting -> MessageContent(
                artworkSize = artworkSize,
                inset = inset,
                headline = "Connecting",
                detail = null,
                headlineColour = NeedlerWearColours.textMuted,
            )
        }
    }
}

@Composable
private fun NowPlayingContent(
    state: WearPlaybackState.NowPlaying,
    artwork: ImageBitmap?,
    artworkSize: Dp,
    inset: Dp,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = inset / 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Artwork(artwork = artwork, size = artworkSize)

        Spacer(Modifier.height(8.dp))

        // Title and artist are one line each, ellipsised. A watch showing two wrapped lines of a long
        // title has no room left for the transport, and the transport is the reason the screen exists.
        Text(
            text = state.title,
            modifier = Modifier.padding(horizontal = inset / 2),
            color = NeedlerWearColours.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = state.artist,
            modifier = Modifier.padding(horizontal = inset / 2),
            color = NeedlerWearColours.textSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(
                label = "Previous",
                diameter = SECONDARY_BUTTON,
                fill = Color.Transparent,
                onClick = onPrevious,
            ) {
                PreviousGlyph(tint = NeedlerWearColours.textPrimary, size = 22.dp)
            }

            TransportButton(
                label = if (state.isPlaying) "Pause" else "Play",
                diameter = PRIMARY_BUTTON,
                // Buffering dims the fill rather than flipping the glyph. `PlaybackState` documents
                // why: "flipping the button to a play icon on every stall is how a player looks
                // broken on a slow connection", and a watch on the far side of a Bluetooth link sees
                // more stalls than the phone does, not fewer.
                fill = if (state.isBuffering) {
                    NeedlerWearColours.accent.copy(alpha = 0.62f)
                } else {
                    NeedlerWearColours.accent
                },
                onClick = onPlayPause,
            ) {
                if (state.isPlaying) {
                    PauseGlyph(tint = NeedlerWearColours.onAccent, size = 24.dp)
                } else {
                    PlayGlyph(tint = NeedlerWearColours.onAccent, size = 24.dp)
                }
            }

            TransportButton(
                label = "Next",
                diameter = SECONDARY_BUTTON,
                fill = Color.Transparent,
                onClick = onNext,
            ) {
                NextGlyph(tint = NeedlerWearColours.textPrimary, size = 22.dp)
            }
        }
    }
}

/**
 * Idle, unreachable and connecting, which differ only in their words.
 *
 * They share the record mark so that the screen does not lurch between three unrelated layouts as
 * the connection comes and goes - the mark stays put and the words underneath it change.
 */
@Composable
private fun MessageContent(
    artworkSize: Dp,
    inset: Dp,
    headline: String,
    detail: String?,
    headlineColour: Color = NeedlerWearColours.textSecondary,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = inset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RecordGlyph(size = artworkSize)

        Spacer(Modifier.height(10.dp))

        Text(
            text = headline,
            color = headlineColour,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = detail,
                color = NeedlerWearColours.textMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Artwork, or the record mark when there is none.
 *
 * Null is a perfectly ordinary answer here, not a loading state: the phone may have sent no cover,
 * the bytes may still be crossing the link, or the transfer may have failed. All three look the same
 * to the user and all three are fine - see [app.needler.wear.playback.WearPlaybackClient.loadArtwork].
 */
@Composable
private fun Artwork(artwork: ImageBitmap?, size: Dp) {
    if (artwork == null) {
        RecordGlyph(size = size)
        return
    }
    Image(
        bitmap = artwork,
        // Described by the title and artist immediately below it; announcing the cover as well only
        // makes a screen reader say the album name twice.
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(ARTWORK_RADIUS)),
        contentScale = ContentScale.Crop,
    )
}

/**
 * One circular transport target.
 *
 * The glyph inside clears its own semantics, so [label] is the only thing announced, and the `Role`
 * makes it announce as a button rather than as text that happens to be tappable.
 */
@Composable
private fun TransportButton(
    label: String,
    diameter: Dp,
    fill: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Inset as a fraction of screen width on a round display. */
private const val ROUND_INSET: Float = 0.12f

/** Inset as a fraction of screen width on a square one; Wear's documented 5.2% margin. */
private const val SQUARE_INSET: Float = 0.052f

/** Artwork side as a fraction of screen width: big enough to read, small enough to leave the transport room. */
private const val ARTWORK_FRACTION: Float = 0.30f

/** `NeedlerShapes.artworkCompact` - the 12dp radius the pack uses for lock-screen artwork. */
private val ARTWORK_RADIUS: Dp = 12.dp

private val PRIMARY_BUTTON: Dp = 52.dp

private val SECONDARY_BUTTON: Dp = 44.dp

// ---- Previews -------------------------------------------------------------------------------
//
// There is no Wear screen in `design/`, so these are the closest thing this module has to a drawn
// reference. They render on both of Wear's stock round sizes because the layout is proportional and
// the small round device is where it is tightest.

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun NowPlayingPreview() {
    NeedlerWearTheme {
        NowPlayingScreen(
            state = WearPlaybackState.NowPlaying(
                title = "Sienna",
                artist = "The Marías",
                album = "Submarine",
                isPlaying = true,
                isBuffering = false,
                artworkId = null,
            ),
            artwork = null,
            onPlayPause = {},
            onPrevious = {},
            onNext = {},
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
private fun IdlePreview() {
    NeedlerWearTheme {
        NowPlayingScreen(
            state = WearPlaybackState.Idle,
            artwork = null,
            onPlayPause = {},
            onPrevious = {},
            onNext = {},
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
private fun PhoneUnreachablePreview() {
    NeedlerWearTheme {
        NowPlayingScreen(
            state = WearPlaybackState.PhoneUnreachable,
            artwork = null,
            onPlayPause = {},
            onPrevious = {},
            onNext = {},
        )
    }
}

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
import app.needler.wear.playback.WearPlaybackSource
import app.needler.wear.playback.WearPlaybackState

/**
 * The watch's transport screen, for whichever of the two players is in charge.
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Wear OS" puts transport controls at the head of Wear's
 * v1 scope, and that is what this is: artwork, title, artist, and previous / play-pause / next, plus
 * the way through to [CrateScreen] and to [OnWatchScreen].
 *
 * ## One screen, two players
 *
 * On-device playback - the third item in Wear's v1 scope - arrived without this screen gaining a second
 * copy of itself. Both sources produce a [WearPlaybackState], so the transport renders either, and the
 * only thing the screen has to add is *which*: [source] is printed above the title, in both states, for
 * the reason [WearPlaybackSource] records. A user who cannot tell which player they are looking at is a
 * user who presses pause and hears nothing stop.
 *
 * The caption cost two percent of the screen's width off the artwork - see [ARTWORK_FRACTION] - which is
 * the second time that budget has been raided and is still the right trade: a cover an eighth smaller is
 * a cover; a transport that lies about what it controls is not a transport.
 *
 * ## Four states, and one way out
 *
 * The four states come from [WearPlaybackState], and the two that phone screens never need are the
 * point: a watch has to be able to say that it cannot see the phone, rather than showing a dead
 * transport. See that file for the argument.
 *
 * The crate link is drawn in the [WearPlaybackState.NowPlaying] state alone. With nothing loaded there
 * is nothing in the crate either - the phone's crate and its current item empty together - so the link
 * would lead to an empty list, and a control that leads nowhere is the thing every other decision on
 * this screen avoids.
 *
 * The on-watch link is the exception and appears on two of the message states as well, because that is
 * precisely when it is most useful: a watch that cannot see the phone, or whose phone is playing nothing,
 * may still have an album on it. It is absent from [WearPlaybackState.Connecting] alone, where the watch
 * does not yet know what it is looking at.
 *
 * ## The link row's height, and what it cost
 *
 * It is 36dp tall and the full width of the screen, now split into two halves - the crate and the
 * on-watch library. REQUIREMENTS.md "Accessibility" requires 48dp of *transport controls*, which the
 * three buttons have, and Wear asks for 48dp of anything tappable, which this does not meet in height.
 * The alternatives were worse: the vertical budget on Wear's small round screen is 192dp, and artwork
 * plus two lines of text plus a 52dp primary button plus a 48dp row does not fit inside it. Taking the
 * height out of the transport instead would push the play button under 48dp, which is the control this
 * screen exists for and the one the requirement actually names. Artwork gave up six percent of the
 * screen width in total to make this much room.
 *
 * Splitting the row horizontally rather than stacking a second one is what kept that budget: two halves
 * of a 36dp band are two targets, where two bands would be 72dp the layout does not have. A third
 * destination reached by swiping through the crate was the alternative and it buries the feature this
 * work exists for two gestures deep.
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
 * Nothing here collects, connects or suspends. It takes a state, a source and five lambdas, which is
 * what lets all four states be previewed against either source, and screenshot-tested later, with no
 * watch and no phone.
 */
@Composable
fun NowPlayingScreen(
    state: WearPlaybackState,
    artwork: ImageBitmap?,
    source: WearPlaybackSource,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenCrate: () -> Unit,
    onOpenOnWatch: () -> Unit,
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
                source = source,
                artworkSize = artworkSize,
                inset = inset,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onOpenCrate = onOpenCrate,
                onOpenOnWatch = onOpenOnWatch,
            )

            WearPlaybackState.Idle -> MessageContent(
                artworkSize = artworkSize,
                inset = inset,
                headline = "Nothing playing",
                // The way out of an idle screen is now the reason this module exists, so it is offered
                // rather than described: a watch with music on it is not waiting for the phone.
                detail = "Start something on your phone, or play what is on your watch.",
                link = "On watch",
                onLink = onOpenOnWatch,
            )

            WearPlaybackState.PhoneUnreachable -> MessageContent(
                artworkSize = artworkSize,
                inset = inset,
                headline = "Phone not connected",
                // Rewritten now that it is no longer true that the watch only controls the phone. The
                // old line - "Needler on the watch controls the phone" - would have been the most
                // misleading sentence in the app on a watch holding an album it could play.
                detail = "Play what is on your watch, or bring the phone back in range.",
                link = "On watch",
                onLink = onOpenOnWatch,
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
    source: WearPlaybackSource,
    artworkSize: Dp,
    inset: Dp,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenCrate: () -> Unit,
    onOpenOnWatch: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = inset / 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Artwork(artwork = artwork, size = artworkSize)

        Spacer(Modifier.height(4.dp))

        // Which player this transport drives. Above the title rather than below the buttons, because it
        // qualifies what is playing rather than what the buttons do, and because the eye reaches the top
        // of a block of text first. Accent when the watch is playing its own audio: that is the state
        // worth noticing, and the one that keeps working with the phone switched off.
        Text(
            text = source.label,
            color = if (source == WearPlaybackSource.WATCH) {
                NeedlerWearColours.accent
            } else {
                NeedlerWearColours.textMuted
            },
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )

        Spacer(Modifier.height(4.dp))

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

        Spacer(Modifier.height(8.dp))

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

        Row(modifier = Modifier.fillMaxWidth()) {
            NavLink(
                label = "In the crate",
                modifier = Modifier.weight(1f),
                onClick = onOpenCrate,
            )
            NavLink(
                label = "On watch",
                modifier = Modifier.weight(1f),
                onClick = onOpenOnWatch,
            )
        }
    }
}

/**
 * One of the two ways off this screen: [CrateScreen] and [OnWatchScreen].
 *
 * "In the crate" is REQUIREMENTS.md "Vocabulary" for the play queue, and "On watch" is the narrowing of
 * its "On device" to the device in front of the user. Both are labels rather than glyphs for two
 * reasons: this module draws its icons from the design pack's own path data and the pack has neither a
 * queue nor a storage icon, and a lone unlabelled symbol on a watch is a guess.
 *
 * The accent colour is what marks each as a control - `NeedlerWearColours.accent` is the pack's role for
 * "primary buttons, links, transport". Each takes half the width, so the two targets together are the
 * band the crate link used to be on its own; the height is discussed on [NowPlayingScreen] and is the
 * one thing on this screen under Wear's 48dp.
 */
@Composable
private fun NavLink(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(NAV_LINK_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = NeedlerWearColours.accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    link: String? = null,
    onLink: (() -> Unit)? = null,
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

        // Drawn only when there is somewhere to go. A message screen with a dead control on it is the
        // thing every other decision in this file avoids.
        if (link != null && onLink != null) {
            NavLink(
                label = link,
                modifier = Modifier.fillMaxWidth(),
                onClick = onLink,
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

/**
 * Artwork side as a fraction of screen width: big enough to read, small enough to leave the transport
 * room.
 *
 * It was 0.30 before the crate link took four percent, and 0.26 before the source caption took two more.
 * On Wear's small round screen - 192dp square, of which a round display uses rather less - artwork at
 * 0.30 plus two lines of text plus a 52dp button plus a 36dp link row overruns the height, and a Column
 * centred vertically overruns by clipping both ends.
 *
 * The caption is 9sp plus 8dp of spacers, which is close enough to two percent of a small round screen's
 * width that the exchange is one for one. It is the last thing this budget can afford: the next control
 * this screen wants belongs on a destination, not on it.
 */
private const val ARTWORK_FRACTION: Float = 0.24f

/** `NeedlerShapes.artworkCompact` - the 12dp radius the pack uses for lock-screen artwork. */
private val ARTWORK_RADIUS: Dp = 12.dp

private val PRIMARY_BUTTON: Dp = 52.dp

private val SECONDARY_BUTTON: Dp = 44.dp

/** Height of the link row. See [NowPlayingScreen] for why this is not 48dp. */
private val NAV_LINK_HEIGHT: Dp = 36.dp

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
            source = WearPlaybackSource.PHONE,
            onPlayPause = {},
            onPrevious = {},
            onNext = {},
            onOpenCrate = {},
            onOpenOnWatch = {},
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
            source = WearPlaybackSource.PHONE,
            onPlayPause = {},
            onPrevious = {},
            onNext = {},
            onOpenCrate = {},
            onOpenOnWatch = {},
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
            source = WearPlaybackSource.PHONE,
            onPlayPause = {},
            onPrevious = {},
            onNext = {},
            onOpenCrate = {},
            onOpenOnWatch = {},
        )
    }
}

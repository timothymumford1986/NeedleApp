package app.needler.feature.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathPlay

/**
 * The glyphs the player draws that the design system does not own.
 *
 * `:core:design`'s [NeedlerStrokeIcon] documents exactly this case - "screens that need icons the
 * design system does not own (nav glyphs, shuffle, the tonearm) can draw them the same way" - so
 * every path below is the pack's own `d` attribute, copied out of `design/html`, on the same 24x24
 * viewport with the same 1.8-unit round-capped stroke. Two of them (previous and next) are a filled
 * triangle plus a stroked bar in the pack, which is why they are two layered draws rather than one
 * path.
 *
 * They are decorative, like every icon in `:core:design`: the control around them carries the
 * content description.
 */

/** Shuffle, from the transport row on 07 and 09. */
const val PathShuffle: String =
    "M4 6h3l10 12h3M20 18l-2-2M20 18l-2 2M4 18h3l3-3.5M14 9.5L17 6h3M20 6l-2-2M20 6l-2 2"

/** The two bars of Pause, drawn as one filled path so the pack's 4x14 rects keep their proportions. */
const val PathPause: String = "M6 5h4v14H6zM14 5h4v14h-4z"

/** The filled triangle of Previous. The stroked bar beside it is [PathPreviousBar]. */
const val PathPreviousTriangle: String = "M18 5v14L9 12z"

/** Previous' leading bar. */
const val PathPreviousBar: String = "M6 5v14"

/** The filled triangle of Next. The stroked bar beside it is [PathNextBar]. */
const val PathNextTriangle: String = "M6 5v14l9-7z"

/** Next' trailing bar. */
const val PathNextBar: String = "M18 5v14"

/** The "add to queue" list glyph the pack uses for the crate, on 07, 08 and 09. */
const val PathQueue: String = "M4 6h12M4 12h12M4 18h8M19 15v6M16 18h6"

/** The Bluetooth rune, from the output chip on 07 and the picker on 21. */
const val PathBluetooth: String = "M7 7l10 10-5 5V2l5 5L7 17"

/**
 * The speaker the pack draws for "This phone", "This tablet" and for a Cast receiver on 21.
 *
 * The pack draws it as a rounded rect plus two circles; SVG primitives have no `d`, so the same
 * three shapes are written out as path data here.
 */
const val PathSpeaker: String =
    "M8 3h8a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z" +
        "M15.5 14a3.5 3.5 0 1 1-7 0 3.5 3.5 0 1 1 7 0" +
        "M13 7.5a1 1 0 1 1-2 0 1 1 0 1 1 2 0"

/**
 * Repeat.
 *
 * The one glyph here with no counterpart in the pack: the design draws no repeat control at all,
 * although `PlaybackController` cycles three repeat modes. It is drawn in the pack's idiom - two
 * arrowed arcs on the same 24x24 viewport - so it sits beside Shuffle without looking imported.
 */
const val PathRepeat: String =
    "M4 9V7a2 2 0 0 1 2-2h11M16 2l3 3-3 3M20 15v2a2 2 0 0 1-2 2H7M8 22l-3-3 3-3"

/** The dot that marks "repeat this track" rather than "repeat the crate". */
const val PathRepeatOneDot: String = "M13.2 12a1.2 1.2 0 1 1-2.4 0 1.2 1.2 0 1 1 2.4 0"

@Composable
fun PlayerShuffleIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) =
    NeedlerStrokeIcon(PathShuffle, tint, modifier, size)

@Composable
fun PlayerQueueIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) =
    NeedlerStrokeIcon(PathQueue, tint, modifier, size)

@Composable
fun PlayerBluetoothIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) =
    NeedlerStrokeIcon(PathBluetooth, tint, modifier, size)

@Composable
fun PlayerSpeakerIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) =
    NeedlerStrokeIcon(PathSpeaker, tint, modifier, size)

@Composable
fun PlayerPlayIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 36.dp) =
    NeedlerStrokeIcon(PathPlay, tint, modifier, size, filled = true)

@Composable
fun PlayerPauseIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 36.dp) =
    NeedlerStrokeIcon(PathPause, tint, modifier, size, filled = true)

/** Previous: the filled triangle with its bar, layered as the pack draws them. */
@Composable
fun PlayerPreviousIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        NeedlerStrokeIcon(PathPreviousTriangle, tint, size = size, filled = true)
        NeedlerStrokeIcon(PathPreviousBar, tint, size = size)
    }
}

/** Next: the filled triangle with its bar. */
@Composable
fun PlayerNextIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        NeedlerStrokeIcon(PathNextTriangle, tint, size = size, filled = true)
        NeedlerStrokeIcon(PathNextBar, tint, size = size)
    }
}

/**
 * Repeat, with the "one track" dot drawn inside it when [one] is true.
 *
 * Off and All are the same glyph in two colours, which is how every player distinguishes them; the
 * state is also spoken, because colour alone is not an accessible difference.
 */
@Composable
fun PlayerRepeatIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    one: Boolean = false,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        NeedlerStrokeIcon(PathRepeat, tint, size = size)
        if (one) NeedlerStrokeIcon(PathRepeatOneDot, tint, size = size, filled = true)
    }
}

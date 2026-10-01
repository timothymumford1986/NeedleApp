package app.needler.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import app.needler.wear.playback.WearCrateRow
import app.needler.wear.playback.WearCrateState

/**
 * The crate on the watch: what is playing and what is coming next.
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Wear OS" puts the crate in Wear's v1 scope alongside the
 * transport, and "Vocabulary" fixes the word: the play queue is "in the crate" and the word "queue"
 * appears nowhere a user can see it. The structure follows the phone's own crate - screens 08 and 09 -
 * as far as a watch can carry it: a Playing row, then Up next.
 *
 * ## What it deliberately cannot do
 *
 * The phone's crate has drag-to-reorder handles, a remove action and Clear. None of the three is here.
 * `PlaybackController` offers all of them, so this is a product decision rather than a missing
 * binding: reordering by dragging a row on a screen an inch across is not a gesture anyone completes
 * on purpose, and REQUIREMENTS.md "Accessibility" already treats dragging as insufficient even on the
 * phone. Tapping a row to play it is the one crate action a watch can offer honestly, and it is the
 * one this screen has.
 *
 * ## Why `ScalingLazyColumn`
 *
 * Wear's own list, not a `Column` in a scroller. It scales and fades rows towards the top and bottom
 * of a round screen, which is the difference between a list that reads on a circle and one whose first
 * and last rows are clipped by the bezel. It is also lazy, which matters because the published window
 * is up to [app.needler.wear.playback.WearPlaybackProtocol.MAX_CRATE_ROWS] rows.
 *
 * Rotary input is not wired up. Wear's rotary modifier wants a `FocusRequester` and a behaviour object
 * whose shape has moved between Wear Compose releases, and getting it wrong is the sort of thing that
 * looks fine in a preview and does nothing on a crown. Touch scrolling works; the rotary modifier is a
 * one-line addition for whoever first runs this on hardware.
 *
 * ## Stateless
 *
 * Takes a state and one lambda, so all four states preview - and screenshot-test later - with no watch
 * and no phone.
 */
@Composable
fun CrateScreen(
    state: WearCrateState,
    onPlayRow: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRound: Boolean = LocalContext.current.resources.configuration.isScreenRound
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(NeedlerWearColours.canvas),
        contentAlignment = Alignment.Center,
    ) {
        // The same inset the transport screen uses, and for the same reason: the corners of a round
        // display are not there.
        val inset: Dp = maxWidth * (if (isRound) ROUND_INSET else SQUARE_INSET)

        when (state) {
            is WearCrateState.InTheCrate -> CrateList(
                state = state,
                inset = inset,
                onPlayRow = onPlayRow,
            )

            WearCrateState.Empty -> CrateMessage(
                inset = inset,
                headline = "Nothing in the crate",
                detail = "Play something on your phone and it will show up here.",
            )

            WearCrateState.PhoneUnreachable -> CrateMessage(
                inset = inset,
                headline = "Phone not connected",
                detail = "The crate lives on the phone. Bring them back in range.",
            )

            WearCrateState.Connecting -> CrateMessage(
                inset = inset,
                headline = "Connecting",
                detail = null,
                headlineColour = NeedlerWearColours.textMuted,
            )
        }
    }
}

/**
 * The list itself.
 *
 * Captions are emitted inside the row items rather than as items of their own, so the item count and
 * the row count stay the same number. A list where index 3 is sometimes a heading and sometimes a
 * track is how a lazy list ends up playing the wrong song.
 */
@Composable
private fun CrateList(
    state: WearCrateState.InTheCrate,
    inset: Dp,
    onPlayRow: (String) -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = inset / 2, vertical = 12.dp),
    ) {
        item {
            Text(
                text = "In the crate",
                modifier = Modifier.padding(bottom = 4.dp),
                color = NeedlerWearColours.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        items(state.rows.size) { index ->
            val row: WearCrateRow = state.rows[index]
            val isPlaying: Boolean = index == state.playingIndex
            Column(modifier = Modifier.fillMaxWidth()) {
                val caption: String? = when {
                    isPlaying -> "Playing"
                    // Only immediately after the playing row, so the heading appears once.
                    state.playingIndex != null && index == state.playingIndex + 1 -> "Up next"
                    // Nothing is playing at all: the crate was restored and the session is stopped.
                    state.playingIndex == null && index == 0 -> "Up next"
                    else -> null
                }
                if (caption != null) {
                    Text(
                        text = caption,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        color = NeedlerWearColours.textMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
                CrateRow(row = row, isPlaying = isPlaying, onClick = { onPlayRow(row.id) })
            }
        }

        if (state.notShownAfter > 0) {
            item {
                Text(
                    // Plain count rather than a scrollable tail: the rest of the crate is on the
                    // phone, and saying how much of it there is beats pretending this is all of it.
                    text = "+ " + state.notShownAfter + " more on your phone",
                    modifier = Modifier.padding(top = 8.dp),
                    color = NeedlerWearColours.textMuted,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * One tappable row: title, artist, and the accent when it is the one playing.
 *
 * The whole row is the target and it is at least 52dp tall, which clears Wear's minimum with room for
 * a 200% font scale - REQUIREMENTS.md "Accessibility" asks for both. The content description carries
 * the artist as well as the title, because two tracks on the same album differ by title alone and a
 * screen reader reading only "Sienna" three times over is not a usable crate.
 */
@Composable
private fun CrateRow(
    row: WearCrateRow,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val described: String = if (isPlaying) {
        "Playing: " + row.title + ", " + row.artist
    } else {
        "Play " + row.title + ", " + row.artist
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(ROW_RADIUS))
            .background(if (isPlaying) NeedlerWearColours.surfaceRaised else NeedlerWearColours.canvas)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = described },
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = row.title,
            color = if (isPlaying) NeedlerWearColours.accent else NeedlerWearColours.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = row.artist,
            color = NeedlerWearColours.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Empty, unreachable and connecting.
 *
 * No record mark here, unlike the transport screen: that screen is *about* one record, and this one is
 * about a list. A large piece of album art where a list should be reads as a failed load.
 */
@Composable
private fun CrateMessage(
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
        Text(
            text = headline,
            color = headlineColour,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            Box(modifier = Modifier.padding(top = 4.dp)) {
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
}

/** Inset as a fraction of screen width on a round display, as on the transport screen. */
private const val ROUND_INSET: Float = 0.12f

/** Inset as a fraction of screen width on a square one; Wear's documented 5.2% margin. */
private const val SQUARE_INSET: Float = 0.052f

/** `NeedlerShapes.card` taken down to a watch row. */
private val ROW_RADIUS: Dp = 10.dp

// ---- Previews -------------------------------------------------------------------------------

private val PREVIEW_ROWS: List<WearCrateRow> = listOf(
    WearCrateRow(id = "1@a/1/1", title = "Sienna", artist = "The Marias"),
    WearCrateRow(id = "2@a/1/2", title = "Hush", artist = "The Marias"),
    WearCrateRow(id = "3@a/1/3", title = "No One Knows", artist = "The Marias"),
    WearCrateRow(id = "4@b/1/1", title = "Everything Now", artist = "Arcade Fire"),
)

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun CratePreview() {
    NeedlerWearTheme {
        CrateScreen(
            state = WearCrateState.InTheCrate(
                rows = PREVIEW_ROWS,
                playingIndex = 0,
                notShownAfter = 212,
            ),
            onPlayRow = {},
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
private fun CrateEmptyPreview() {
    NeedlerWearTheme {
        CrateScreen(state = WearCrateState.Empty, onPlayRow = {})
    }
}

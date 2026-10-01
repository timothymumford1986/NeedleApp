package app.needler.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.needler.wear.store.WearStoreSpace
import app.needler.wear.sync.WearOfferState
import app.needler.wear.sync.WearOnWatchRow
import app.needler.wear.sync.WearOnWatchState

/**
 * What is on the watch, what could be, and how much room there is.
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Wear OS" puts "playback of on-device audio synced from the
 * phone" in Wear's v1 scope, and the sync policy that implements it - recorded on
 * [app.needler.wear.playback.WearPlaybackProtocol.PATH_SYNC_OFFER] - comes with a requirement attached:
 * the watch's bound must be explicit, and the user must be able to see and change what is on it. This
 * screen is that. [app.needler.wear.sync.WearOnWatchState] records how the four facts it draws are
 * assembled.
 *
 * ## The vocabulary is REQUIREMENTS.md's, not a new one
 *
 * "Vocabulary" fixes the product's words, and two of them do the work here. **On device** is "cached
 * locally, plays without a network", which is what an album on the watch is - so the screen says "On
 * watch", which is the same word narrowed to the device the user is looking at. **Downloaded** is "kept
 * on device because the user asked for it; never evicted automatically", which is exactly what the
 * watch's tier is, and it is why nothing on this screen offers to free space automatically: the only
 * thing that removes an album is the user.
 *
 * Nothing here says "cached", because nothing on the watch is cached while listening. That distinction is
 * the whole sync policy and it would be undone by one loose label.
 *
 * ## Removal takes two taps
 *
 * The remove control arms on the first tap and acts on the second, and disarms when anything else is
 * touched. A watch row is small and a sleeve brushes it; an album is minutes of Bluetooth transfer to
 * get back, and may be unrecoverable if the phone has since dropped the download. A confirmation dialog
 * was the alternative and it is worse on this screen - it would be a second destination, reached by the
 * gesture that also means "go back", to confirm something the user can see the result of immediately.
 *
 * ## Stateless apart from that
 *
 * Takes a state and four lambdas. The armed row is the only thing it remembers, because it is a property
 * of this screen being on screen rather than of anything underneath it.
 */
@Composable
fun OnWatchScreen(
    state: WearOnWatchState,
    onPlayAlbum: (String) -> Unit,
    onAddAlbum: (String) -> Unit,
    onRemoveAlbum: (String) -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRound: Boolean = LocalContext.current.resources.configuration.isScreenRound
    // Reset by the key changing, so an album that finishes transferring or is removed cannot leave a
    // stale armed control pointing at a row that has moved.
    val armed: MutableState<String?> = remember(state.rows.size) { mutableStateOf<String?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(NeedlerWearColours.canvas),
        contentAlignment = Alignment.Center,
    ) {
        // The same inset as the other two screens, and for the same reason: the corners of a round
        // display are not there.
        val inset: Dp = maxWidth * (if (isRound) ROUND_INSET else SQUARE_INSET)

        when {
            !state.scanned -> OnWatchMessage(
                inset = inset,
                headline = "Looking",
                detail = null,
                headlineColour = NeedlerWearColours.textMuted,
            )

            state.rows.isEmpty() && state.offer is WearOfferState.PhoneUnreachable -> OnWatchMessage(
                inset = inset,
                headline = "Phone not connected",
                detail = "Music is copied from the phone. Bring them back in range.",
            )

            state.rows.isEmpty() -> OnWatchMessage(
                inset = inset,
                headline = "Nothing on your watch",
                detail = "Download an album on your phone and it will show up here to add.",
            )

            else -> OnWatchList(
                state = state,
                inset = inset,
                armed = armed,
                onPlayAlbum = onPlayAlbum,
                onAddAlbum = onAddAlbum,
                onRemoveAlbum = onRemoveAlbum,
                onSyncNow = onSyncNow,
            )
        }
    }
}

@Composable
private fun OnWatchList(
    state: WearOnWatchState,
    inset: Dp,
    armed: MutableState<String?>,
    onPlayAlbum: (String) -> Unit,
    onAddAlbum: (String) -> Unit,
    onRemoveAlbum: (String) -> Unit,
    onSyncNow: () -> Unit,
) {
    var armedKey: String? by armed

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = inset / 2, vertical = 12.dp),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "On watch",
                    color = NeedlerWearColours.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    // The bound, in so many words. It is the figure that decides whether an album can be
                    // added, so it is on screen rather than implied by a greyed control.
                    text = spaceLine(state),
                    color = NeedlerWearColours.textSecondary,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        items(state.rows.size) { index ->
            val row: WearOnWatchRow = state.rows[index]
            OnWatchAlbumRow(
                row = row,
                armed = armedKey == row.albumKey,
                onPrimary = {
                    armedKey = null
                    when {
                        row.playable -> onPlayAlbum(row.albumKey)
                        row.addable -> onAddAlbum(row.albumKey)
                        else -> Unit
                    }
                },
                onRemove = {
                    if (armedKey == row.albumKey) {
                        armedKey = null
                        onRemoveAlbum(row.albumKey)
                    } else {
                        armedKey = row.albumKey
                    }
                },
            )
        }

        if (state.notShown > 0) {
            item {
                Text(
                    // The honest form of the offer's cap. The alternative to saying this is a picker that
                    // silently omits a user's older albums.
                    text = "+ " + state.notShown + " more downloaded on your phone",
                    modifier = Modifier.padding(top = 8.dp),
                    color = NeedlerWearColours.textMuted,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = FOOTER_HEIGHT)
                    .clickable(role = Role.Button) {
                        armedKey = null
                        onSyncNow()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // Copying normally waits for the charger - see KEY_WATCH_CHARGING - so this is the
                    // override, and the label says which rather than implying a refresh.
                    text = if (state.transferring) "Copying now" else "Copy now",
                    color = NeedlerWearColours.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * One album row: title, artist, what state it is in, and the two things that can be done to it.
 *
 * The row body is the primary target - play it, or add it - and the trailing control removes. Both clear
 * Wear's 44dp minimum in height; the remove control is [REMOVE_WIDTH] wide, which is narrower than the
 * 48dp REQUIREMENTS.md "Accessibility" asks of *transport controls* and is the same compromise
 * [NowPlayingScreen] records for its links, for the same reason: the alternative on a 192dp screen is a
 * second destination to reach a control that belongs beside the thing it acts on.
 *
 * The content description carries the album, the artist and the state, because a row whose only
 * difference from the one above it is "9 of 12" against "12 of 12" is not distinguishable by title alone.
 */
@Composable
private fun OnWatchAlbumRow(
    row: WearOnWatchRow,
    armed: Boolean,
    onPrimary: () -> Unit,
    onRemove: () -> Unit,
) {
    val status: String = statusLine(row)
    val described: String = buildString {
        append(if (row.playable) "Play " else "Add ")
        append(row.title)
        if (row.artist != null) {
            append(", ")
            append(row.artist)
        }
        append(". ")
        append(status)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .clip(RoundedCornerShape(ROW_RADIUS))
            .background(if (row.onWatch) NeedlerWearColours.surfaceRaised else NeedlerWearColours.canvas),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ROW_HEIGHT)
                // A row that can do neither is still drawn and still readable; it simply is not a
                // control. An album the phone has dropped and the watch still holds is exactly that
                // until the user removes it.
                .clickable(
                    enabled = row.playable || row.addable,
                    role = Role.Button,
                    onClick = onPrimary,
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .semantics { contentDescription = described },
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = row.title,
                color = if (row.onWatch) NeedlerWearColours.textPrimary else NeedlerWearColours.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.artist != null) {
                Text(
                    text = row.artist,
                    color = NeedlerWearColours.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = status,
                color = if (row.fits || row.onWatch) {
                    NeedlerWearColours.textMuted
                } else {
                    // The one place the destructive-family colour is right on this screen: it marks an
                    // album that cannot be added rather than an action that would destroy something.
                    NeedlerWearColours.destructive
                },
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (row.onWatch) {
            Box(
                modifier = Modifier
                    .width(REMOVE_WIDTH)
                    .heightIn(min = ROW_HEIGHT)
                    .clickable(role = Role.Button, onClick = onRemove)
                    .semantics {
                        contentDescription = if (armed) {
                            "Confirm removing " + row.title + " from your watch"
                        } else {
                            "Remove " + row.title + " from your watch, frees " +
                                WearFormat.bytes(row.watchBytes)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (armed) "Sure?" else "Remove",
                    color = if (armed) NeedlerWearColours.destructive else NeedlerWearColours.textSecondary,
                    fontSize = 10.sp,
                    fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The one line under an album that says what state it is in.
 *
 * Six states, and they are not interchangeable - which is the reason this is a function with a comment
 * rather than a string interpolation at the call site:
 *
 *  * **Complete.** Everything the phone has is here, with the bytes it occupies.
 *  * **Transferring.** The user asked for it and tracks are still arriving.
 *  * **Kept.** The user stopped fetching and what arrived is still here. Not a failure.
 *  * **Orphaned.** On the watch, and the phone is no longer offering it, so nothing can fill it in and
 *    nothing can tell whether the server has replaced the bytes. REQUIREMENTS.md "Invalidating upgraded
 *    files" is why that has to be said out loud.
 *  * **Addable.** Not here, and there is room for it.
 *  * **Will not fit.** Not here, and the free-space floor would refuse it. Said rather than shown as a
 *    dead control, which is the rule REQUIREMENTS.md sets for Cast: "say why... rather than failing
 *    after the user picks a speaker."
 */
private fun statusLine(row: WearOnWatchRow): String = when {
    row.onWatch && !row.refreshable ->
        WearFormat.trackShare(row.heldTracks, 0) + " on watch, not on phone"

    row.complete ->
        "On watch, " + WearFormat.bytes(row.watchBytes)

    row.transferring ->
        "Copying, " + WearFormat.trackShare(row.heldTracks, row.offeredTracks)

    row.partialAndStopped ->
        WearFormat.trackShare(row.heldTracks, row.offeredTracks) + " kept, " +
            WearFormat.bytes(row.watchBytes)

    row.wanted ->
        "Waiting to copy"

    row.fits ->
        "Add, " + WearFormat.bytes(row.offeredBytes)

    else ->
        "No room, needs " + WearFormat.bytes(row.offeredBytes)
}

/**
 * The header's second line: what the store holds, and the room the watch has.
 *
 * An unreadable volume says so rather than showing a zero. REQUIREMENTS.md "Storage" treats a failed
 * free-space reading as a reason to suspend the policy rather than guess, and a screen that reported
 * "0 MB free" from a failed probe would have the user deleting music to fix a device that is fine.
 */
private fun spaceLine(state: WearOnWatchState): String {
    val used: String = WearFormat.bytes(state.usedBytes)
    val space: WearStoreSpace = state.space
    if (space.isUnknown) return used + " on watch, free space unknown"
    return used + " on watch, " + WearFormat.bytes(space.headroomBytes) + " free to use"
}

/** Empty, unreachable and looking. The same shape as `CrateScreen`'s, and for the same reason. */
@Composable
private fun OnWatchMessage(
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

/** Inset as a fraction of screen width on a round display, as on the other two screens. */
private const val ROUND_INSET: Float = 0.12f

/** Inset as a fraction of screen width on a square one; Wear's documented 5.2% margin. */
private const val SQUARE_INSET: Float = 0.052f

/** `NeedlerShapes.card` taken down to a watch row, as `CrateScreen` uses. */
private val ROW_RADIUS: Dp = 10.dp

/** Three lines of text with room for a 200% font scale, and above Wear's 44dp minimum. */
private val ROW_HEIGHT: Dp = 60.dp

/** The trailing remove control. Narrow, and the class note records what that cost. */
private val REMOVE_WIDTH: Dp = 54.dp

/** The footer action. */
private val FOOTER_HEIGHT: Dp = 40.dp

// ---- Previews -------------------------------------------------------------------------------

private val PREVIEW_ROWS: List<WearOnWatchRow> = listOf(
    WearOnWatchRow(
        albumKey = "a1b2c3d4-0000-4000-8000-000000000001",
        title = "Submarine",
        artist = "The Marias",
        format = "FLAC",
        heldTracks = 12,
        offeredTracks = 12,
        watchBytes = 412_000_000L,
        offeredBytes = 412_000_000L,
        wanted = true,
        refreshable = true,
        fits = true,
    ),
    WearOnWatchRow(
        albumKey = "a1b2c3d4-0000-4000-8000-000000000002",
        title = "Everything Now",
        artist = "Arcade Fire",
        format = "FLAC",
        heldTracks = 5,
        offeredTracks = 13,
        watchBytes = 148_000_000L,
        offeredBytes = 390_000_000L,
        wanted = true,
        refreshable = true,
        fits = true,
    ),
    WearOnWatchRow(
        albumKey = "a1b2c3d4-0000-4000-8000-000000000003",
        title = "In Rainbows",
        artist = "Radiohead",
        format = null,
        heldTracks = 0,
        offeredTracks = 10,
        watchBytes = 0L,
        offeredBytes = 2_400_000_000L,
        wanted = false,
        refreshable = true,
        fits = false,
    ),
)

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun OnWatchPreview() {
    NeedlerWearTheme {
        OnWatchScreen(
            state = WearOnWatchState(
                scanned = true,
                offer = WearOfferState.Offered(albums = emptyList(), notShown = 7),
                rows = PREVIEW_ROWS,
                usedBytes = 560_000_000L,
                space = WearStoreSpace(usableBytes = 3_200_000_000L, totalBytes = 8_000_000_000L),
                notShown = 7,
            ),
            onPlayAlbum = {},
            onAddAlbum = {},
            onRemoveAlbum = {},
            onSyncNow = {},
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
private fun OnWatchEmptyPreview() {
    NeedlerWearTheme {
        OnWatchScreen(
            state = WearOnWatchState.NotScanned.copy(
                scanned = true,
                offer = WearOfferState.Offered.Nothing,
            ),
            onPlayAlbum = {},
            onAddAlbum = {},
            onRemoveAlbum = {},
            onSyncNow = {},
        )
    }
}

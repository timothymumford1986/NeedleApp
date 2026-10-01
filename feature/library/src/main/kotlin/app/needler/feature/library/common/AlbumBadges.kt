package app.needler.feature.library.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerAlbumBadge
import app.needler.core.design.component.NeedlerOnDeviceIcon
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.PullFailureReason
import app.needler.core.domain.model.PullState

/**
 * The badge one [AlbumState] wears, from REQUIREMENTS.md "Album states".
 *
 * | State | Badge |
 * | --- | --- |
 * | `NotOwned` | none |
 * | `PendingApproval` | Waiting |
 * | `Acquiring` | Pulling, with percentage — or Searching / Needs attention |
 * | `Owned` | In library |
 * | `Pinned` | On device |
 * | `Failed` | no source found |
 *
 * The two derived acquiring states come straight off [PullState], which already
 * knows that `queued` with no search job means the server is still looking and
 * `queued` with a search job but no candidate means a human has to pick a
 * source on the server. Deriving them here again would put the rule in two
 * places.
 *
 * Returns null where the pack draws nothing, which is the un-owned case: an
 * album you do not own wears a **Pull** button instead of a badge.
 */
internal fun albumBadge(state: AlbumState): NeedlerAlbumBadge? = when (state) {
    AlbumState.NotOwned -> null
    is AlbumState.PendingApproval -> NeedlerAlbumBadge.Waiting
    is AlbumState.Acquiring -> when (state.stage) {
        PullState.PENDING_APPROVAL -> NeedlerAlbumBadge.Waiting
        PullState.SEARCHING -> NeedlerAlbumBadge.Searching
        PullState.AWAITING_SOURCE_REVIEW -> NeedlerAlbumBadge.NeedsAttention
        else -> NeedlerAlbumBadge.Pulling(state.progress.percent)
    }
    AlbumState.Owned -> NeedlerAlbumBadge.InLibrary
    is AlbumState.Pinned -> NeedlerAlbumBadge.OnDevice
    is AlbumState.Failed -> NeedlerAlbumBadge.NoSource
}

/**
 * What went wrong, in a sentence, for the failure notice on album detail.
 *
 * The badge vocabulary has exactly one failure label — "no source found" — but
 * [PullFailureReason] distinguishes six, and three of them lead somewhere
 * completely different: a rejected request is an administrator's decision, a
 * held import needs the web UI, and a failed download is worth retrying. A
 * screen that said "no source found" for all of them would send the user
 * hunting for a source that was never the problem.
 *
 * This is the local answer to a gap in `:core:design`, where
 * `NeedlerAlbumBadge` models only the one failure. See the handover notes.
 */
internal fun failureExplanation(reason: PullFailureReason, message: String?): String {
    val fromServer: String? = message?.takeIf { it.isNotBlank() }
    return when (reason) {
        PullFailureReason.NO_SOURCE_FOUND ->
            "Dropped Needle could not find a source for this album. Retrying looks again; " +
                "sources come and go, so it is often worth a second try."
        PullFailureReason.DOWNLOAD_FAILED ->
            fromServer ?: "The download did not finish. Retrying starts it again."
        PullFailureReason.IMPORT_FAILED ->
            fromServer ?: "The files arrived but could not be imported into the library."
        PullFailureReason.REJECTED ->
            fromServer ?: "An administrator rejected this request."
        PullFailureReason.HELD_FOR_REVIEW ->
            "This pull is held for review on the server. Held items need the Dropped Needle " +
                "web interface in this version of Needler."
        PullFailureReason.CANCELLED ->
            "This pull was cancelled. Retrying asks for the album again."
        PullFailureReason.UNKNOWN ->
            fromServer ?: "This pull did not complete. Retrying asks for the album again."
    }
}

/**
 * The format label on an album row: `FLAC`, `MP3 320`, `MP3 256`.
 *
 * REQUIREMENTS.md "Local persistence" says why the mirror denormalises `album.format` and
 * `album.bitrate` onto the album row at all: "screen 13 badges every row FLAC / MP3 320 / MP3 256",
 * and loading an album's tracks to work that out per row would spend the scroll budget on it.
 *
 * ## The colour-alone failure this fixes
 *
 * REQUIREMENTS.md's palette table lists the positive green's uses as "Progress, Ready, on-device check
 * and **the FLAC badge**" - so in the design, green *is* how lossless is announced. On screen 13 the
 * same green also marks a row as on device, which left one hue carrying two unrelated facts and left
 * lossless with no channel but hue. That is a WCAG 1.4.1 failure ("colour is not used as the only
 * visual means of conveying information"), and it is the kind that is invisible to whoever wrote it:
 * the auditor confirmed this library is entirely MP3 320, so the one case where it matters is the case
 * that almost never renders.
 *
 * So the two facts are separated onto two channels each, and neither of them is hue alone:
 *
 *  * **lossless** is the word `FLAC` plus a hairline chip drawn around it. A lossy format is bare
 *    text. Shape and wording, no colour;
 *  * **on device** is the phone-and-check glyph plus the green, as it already was. Glyph and colour.
 *
 * Hue therefore no longer says anything about format. The alternative considered was appending the
 * word "lossless" to the label: rejected because it doubles the width of the widest thing in a row's
 * trailing column, and at 200% text it would push the title out of a phone row entirely.
 *
 * The spoken reading is separate again, and it says both facts in words - see
 * [albumFormatSpokenLabel], which is what the row's own content description appends.
 *
 * @param onDevice whether this album is complete on this device. Callers pass `album.showsOnDeviceCheck`
 *   so that the row and the artwork's green check cannot disagree.
 */
@Composable
internal fun AlbumFormatLabel(
    quality: AudioQuality?,
    onDevice: Boolean,
    modifier: Modifier = Modifier,
) {
    val label: String = LibraryFormat.quality(quality) ?: return
    val colors = NeedlerTheme.colors
    val lossless: Boolean = quality?.isLossless == true
    val tint = if (onDevice) colors.positive else colors.textMuted
    val shape = NeedlerTheme.shapes.formatBadge

    Row(
        modifier = modifier
            // Silent: the row that contains this already reads the format aloud through its own
            // content description - see [albumFormatSpokenLabel] - so a second node saying it would
            // have TalkBack say it twice, and the chip border is decoration rather than information.
            .clearAndSetSemantics {}
            .then(
                if (lossless) {
                    Modifier
                        .clip(shape)
                        .border(NeedlerTheme.sizes.hairlineThickness, tint, shape)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onDevice) NeedlerOnDeviceIcon(tint = colors.positive, size = 14.dp)
        Text(
            text = label,
            style = NeedlerTheme.typography.caption,
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * The format, on device state and lossless-ness in words, for a row's content description.
 *
 * Returns null when there is no format to report, so a caller can drop the clause rather than say
 * "unknown". "Lossless" and "lossy" are spelled out because the chip that carries that distinction on
 * screen is a border, and a border is nothing at all to a screen reader.
 */
internal fun albumFormatSpokenLabel(quality: AudioQuality?, onDevice: Boolean): String? {
    val label: String = LibraryFormat.quality(quality) ?: return null
    val lossless: String = if (quality?.isLossless == true) "lossless" else "lossy"
    return if (onDevice) {
        label + ", " + lossless + ", on device"
    } else {
        label + ", " + lossless
    }
}

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerAlbumBadge
import app.needler.core.design.component.NeedlerAlbumSource
import app.needler.core.design.component.NeedlerOnDeviceIcon
import app.needler.core.design.component.NeedlerPullIcon
import app.needler.core.design.component.NeedlerStateBadge
import app.needler.core.design.component.accessibleLabel
import app.needler.core.design.component.label
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.OfflineDownloadState
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
 * | `Owned` | Server |
 * | `Pinned` | Device, or the download's own state while bytes are arriving |
 * | `Failed` | no source found |
 *
 * The two derived acquiring states come straight off [PullState], which already
 * knows that `queued` with no search job means the server is still looking and
 * `queued` with a search job but no candidate means a human has to pick a
 * source on the server. Deriving them here again would put the rule in two
 * places.
 *
 * A pin whose bytes have not all landed is the same shape of fact and is read
 * off [OfflineDownloadState] here for the same reason. Pinning is instant and
 * the download is not, so a pin in flight badges the journey - Pulling to
 * device, or Waiting for Wi-Fi when the setting is holding it - rather than
 * claiming the record is already here. A pin that has nothing at all on the
 * device drops back to the server's own word: the album is on the server and
 * playable, and a Device badge over zero bytes would be a false promise.
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
    is AlbumState.Pinned -> when {
        state.download == OfflineDownloadState.WaitingForUnmeteredNetwork ->
            NeedlerAlbumBadge.WaitingForWifi
        state.download.isInFlight ->
            NeedlerAlbumBadge.PullingToDevice(
                (state.download as? OfflineDownloadState.Downloading)
                    ?.fraction?.let { (it * 100f).toInt() },
            )
        // Nothing landed: the bytes are not here, so the badge must not claim they are.
        state.download is OfflineDownloadState.Failed ||
            (state.download as? OfflineDownloadState.Partial)?.tracksComplete == 0 ->
            NeedlerAlbumBadge.InLibrary
        else -> NeedlerAlbumBadge.OnDevice
    }
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
    modifier: Modifier = Modifier,
    onDevice: Boolean = false,
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
 * The format, the state word and lossless-ness in words, for a row's content description.
 *
 * Returns null when there is no format to report, so a caller can drop the clause rather than say
 * "unknown". "Lossless" and "lossy" are spelled out because the chip that carries that distinction on
 * screen is a border, and a border is nothing at all to a screen reader.
 */
internal fun albumFormatSpokenLabel(quality: AudioQuality?, onDevice: Boolean): String? {
    val label: String = LibraryFormat.quality(quality) ?: return null
    val lossless: String = if (quality?.isLossless == true) "lossless" else "lossy"
    return if (onDevice) {
        label + ", " + lossless + ", " + NeedlerAlbumSource.Device.label()
    } else {
        label + ", " + lossless
    }
}

/**
 * Where this album is, in the word REQUIREMENTS.md "Vocabulary" fixes for it.
 *
 * ## What this replaces, and why a word was the only fix
 *
 * The library drew three states four ways and never used any of their names. A boxed green
 * `FLAC`, an unboxed green `MP3 320`, a boxed grey `FLAC` and a plain grey `MP3 320` were four
 * distinct treatments on one list, and cross-referencing the grid's own ticks showed that boxed
 * green and unboxed green both meant [NeedlerAlbumSource.Device] - so one of the two axes carried
 * no discoverable meaning at all, and the other carried a state nothing on the screen named. The
 * canonical words existed and appeared only in Search.
 *
 * REQUIREMENTS.md "Accessibility" settles how it has to be fixed rather than leaving it to taste:
 * accent and positive "measure 1.01:1 against each other", which makes them one swatch to a
 * red-green colour-blind reader, so "the hue is never the signal: each state's word is drawn
 * wherever its hue is". Search's album row already obeys that - [albumBadge] into
 * `NeedlerStateBadge`, the word beside the row with its glyph and its hue - and this is that row's
 * treatment, lifted whole so the two surfaces cannot drift.
 *
 * ## Why the un-owned case is drawn here rather than by the badge
 *
 * `NeedlerAlbumBadge` has no member for it: Search gives an un-owned album a **Pull** button
 * instead of a badge, and [albumBadge] returns null to say so. A library list is not a search
 * result - the row's action is Play, and an album with no audio behind it has none - so the row
 * still has to say *why* it offers nothing, and "Not retrieved" is the word for that in
 * [NeedlerAlbumSource.NotRetrieved]. It is drawn in the muted colour with the pull glyph: muted
 * because the record is nowhere, and the glyph because what would change that is a pull.
 *
 * The rejected alternative was to leave the row blank for this state, which is what the grid did -
 * a tick or nothing, so `Server` and `Not retrieved` were the same picture.
 */
@Composable
internal fun AlbumStateLabel(
    state: AlbumState,
    modifier: Modifier = Modifier,
) {
    val badge: NeedlerAlbumBadge? = albumBadge(state)
    if (badge != null) {
        NeedlerStateBadge(badge = badge, modifier = modifier)
        return
    }
    val colors = NeedlerTheme.colors
    val label: String = NeedlerAlbumSource.NotRetrieved.label()
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerPullIcon(tint = colors.textMuted)
        Text(
            text = label,
            style = NeedlerTheme.typography.metaStrong,
            color = colors.textMuted,
            maxLines = 2,
        )
    }
}

/**
 * The same state as a phrase, for a row or tile that builds its own content description.
 *
 * Separate from the drawn label for the reason [NeedlerAlbumBadge.accessibleLabel] exists: a
 * percentage reads as a symbol on screen and as a word aloud. Never null - every album is in one
 * of the states, and a row that said nothing about where its record is would be the defect this
 * function was added to remove.
 */
internal fun albumStateSpokenLabel(state: AlbumState): String =
    albumBadge(state)?.accessibleLabel() ?: NeedlerAlbumSource.NotRetrieved.label()

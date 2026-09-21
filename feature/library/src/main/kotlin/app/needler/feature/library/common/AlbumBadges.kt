package app.needler.feature.library.common

import app.needler.core.design.component.NeedlerAlbumBadge
import app.needler.core.domain.model.AlbumState
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

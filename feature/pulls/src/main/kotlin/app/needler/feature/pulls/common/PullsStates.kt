package app.needler.feature.pulls.common

import app.needler.core.design.component.NeedlerAlbumBadge
import app.needler.core.design.component.NeedlerAlbumSource
import app.needler.core.design.component.accessibleLabel
import app.needler.core.design.component.label
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullState
import app.needler.core.domain.model.RequestHistoryEntry
import app.needler.core.domain.model.RequestOutcome
import app.needler.core.domain.model.WantedGap
import app.needler.core.domain.model.WantedWatch
import app.needler.core.domain.model.WantedWatchState

/**
 * The one word the trailing column of a row draws, for all three lanes.
 *
 * ## Two vocabularies, and which slot carries which
 *
 * REQUIREMENTS.md, "Where a record is", fixes three words — **Not retrieved**, **Server**,
 * **Device** — and says they are "the words every surface draws". A reviewer found zero occurrences
 * of any of them across this module's 39 renders, and about twenty-five phrasings in their place:
 * the chips said *Searching, Waiting, Needs attention, Ready, Failed, Partly delivered, Pulling,
 * Cancelled* while the lines independently said *being acquired, waiting to start, arrived,
 * declined by Ada, requires review, in your library, not found yet, found* and more. Every History
 * row stated its status twice and never in the same words.
 *
 * The collapse rests on a distinction REQUIREMENTS.md already makes and this module had lost.
 * **The three words say where a record is. A pull's lifecycle states say what is happening to it.**
 * They are different axes, so they get different slots rather than different synonyms:
 *
 *  * **While a pull is in flight the chip names the stage** — `Waiting`, `Searching`,
 *    `Needs attention`, `Pulling`. Where the record is, is not in question: it is on neither store
 *    yet, which is what the `Now` heading over those rows already says.
 *  * **Once it has settled the chip names the location**, because the stage is over and the only
 *    fact left is where the record ended up. A finished pull is [NeedlerAlbumSource.Server]; a
 *    failed, cancelled or declined one is [NeedlerAlbumSource.NotRetrieved].
 *  * **The line never repeats the chip.** It carries the figure, the source being asked or the
 *    reason — the half the one word cannot hold.
 *
 * `Ready`, `Failed` and `Cancelled` therefore leave this screen's chips altogether. `Ready` was the
 * worst of them: it is `Server` under another name, drawn in the on-device green, on a screen whose
 * every row is server-side work.
 *
 * ## The one state that is neither word
 *
 * [NeedlerAlbumBadge.PartlyDelivered] stays, and it is the single exception. REQUIREMENTS.md,
 * "Partial content is a normal state", requires a part-delivered album to be drawn as itself rather
 * than as a failure, and it is genuinely between two of the three words — some of the record
 * reached the server and some did not. Collapsing it into `Server` would promise a complete album
 * and into `Not retrieved` would hide one that plays.
 *
 * ## Rejected
 *
 *  * **Chipping every row with the location word**, lifecycle included. Six active states would
 *    then draw one identical `Not retrieved` chip, the percentage would have nowhere to live, and
 *    the user would lose the only glanceable answer to "what is it doing" — which is the question
 *    the Pulls screen exists to answer.
 *  * **Keeping `Ready` and only repainting it accent.** The hue would be right and the word would
 *    still be a fourth name for a state that has three, which is what REQUIREMENTS.md's 2026-10-03
 *    revision cut nine words to three to stop.
 *  * **Adding a `Declined` badge to `:core:design`** for the rejected outcome, which is where the
 *    old code's handover note pointed. It is not this module's to change, and it is no longer
 *    needed: a declined request leaves the record nowhere, so `Not retrieved` is the true word and
 *    the reason belongs in the line beside it, where it already is.
 */
internal sealed interface PullsChip {

    /** What the chip draws. */
    val word: String

    /** What a screen reader hears in its place, which is the same word wherever one word will do. */
    val spoken: String

    /** A state `:core:design` has a badge for, drawn by `NeedlerStateBadge`. */
    data class Badge(val badge: NeedlerAlbumBadge) : PullsChip {
        override val word: String get() = badge.label()
        override val spoken: String get() = badge.accessibleLabel()
    }

    /**
     * [NeedlerAlbumSource.NotRetrieved], which `:core:design` has no badge for.
     *
     * It has none on purpose: `NeedlerAlbumSource`'s own table gives that state no hue because "a
     * record that is nowhere wears the **Pull** button instead, which is the whole signal". That
     * holds on a library row and does not hold here — a Pulls row for a request that failed already
     * carries **Retry**, and leaving its status column empty is exactly the defect this screen was
     * once redrawn to fix. So the word is drawn as a label in the badge's own type and weight, read
     * from [NeedlerAlbumSource.label] rather than written as a literal, which REQUIREMENTS.md
     * "Where a record is" forbids.
     *
     * A `NeedlerAlbumBadge.NotRetrieved` in `:core:design` is the right home for it and is in the
     * handover notes; that module is being worked on by someone else.
     */
    data object NotRetrieved : PullsChip {
        override val word: String get() = NeedlerAlbumSource.NotRetrieved.label()
        override val spoken: String get() = word
    }
}

/**
 * The chip on a queue row.
 *
 * The percentage rides on the `Pulling` chip rather than replacing it. It used to replace it: a row
 * that was actually downloading drew a bare `62%` where every other row drew a word, so the one row
 * in flight was the only one whose state the trailing column did not name — visible in
 * `screenshots/pulls-queue-phone.png`.
 */
internal fun chipFor(pull: Pull): PullsChip = when (pull.state) {
    PullState.PENDING_APPROVAL -> PullsChip.Badge(NeedlerAlbumBadge.Waiting)
    PullState.SEARCHING -> PullsChip.Badge(NeedlerAlbumBadge.Searching)
    PullState.AWAITING_SOURCE_REVIEW -> PullsChip.Badge(NeedlerAlbumBadge.NeedsAttention)

    // One stage for the whole of what REQUIREMENTS.md "Acquisition lifecycle" calls acquiring; the
    // line carries the distinction - "waiting for a download slot", "12 of 19 files", "importing".
    PullState.QUEUED,
    PullState.DOWNLOADING,
    PullState.PROCESSING ->
        PullsChip.Badge(NeedlerAlbumBadge.Pulling(PullsFormat.percent(pull.progress.fraction)))

    // The record is on the server now, which is the whole of what a finished pull means.
    PullState.COMPLETED -> PullsChip.Badge(NeedlerAlbumBadge.InLibrary)

    PullState.PARTIAL -> PullsChip.Badge(NeedlerAlbumBadge.PartlyDelivered)

    // On neither store. Which way it ended is the line's business: "no source found", "stopped".
    PullState.FAILED, PullState.CANCELLED -> PullsChip.NotRetrieved
}

/**
 * The chip on a history row.
 *
 * The settled outcomes read [RequestHistoryEntry.inLibrary] rather than assuming the request is the
 * only way the record could have arrived. That field was drawn as the words "in your library" in
 * the line, next to a chip saying something else about the same album; it is one fact about where
 * the record is, so it belongs in the one slot that reports where records are.
 *
 * [RequestOutcome.OTHER] is a status this client has never heard of, and REQUIREMENTS.md "Placing a
 * request" requires the server's status to be rendered rather than inferred. Nothing is inferred
 * here: the chip reports the location, which is a separate field, and the line prints the server's
 * own token.
 */
internal fun chipFor(entry: RequestHistoryEntry): PullsChip = when (entry.status) {
    RequestOutcome.PENDING,
    RequestOutcome.AWAITING_APPROVAL -> PullsChip.Badge(NeedlerAlbumBadge.Waiting)

    // No percentage: this lane reports that an acquisition is under way, not how far it has got.
    RequestOutcome.IN_PROGRESS -> PullsChip.Badge(NeedlerAlbumBadge.Pulling())

    RequestOutcome.COMPLETED -> PullsChip.Badge(NeedlerAlbumBadge.InLibrary)

    RequestOutcome.FAILED,
    RequestOutcome.REJECTED,
    RequestOutcome.CANCELLED,
    RequestOutcome.OTHER -> locationOf(entry.inLibrary)
}

/**
 * The chip on a wanted row.
 *
 * A watch is a record the server has not found, so all but one of the states are the same location
 * said six ways — "not found yet", "still being looked for", "checked only occasionally now", "no
 * longer being looked for" and the rest were six phrasings of `Not retrieved`. How hard the server
 * is still looking is a different fact and stays in the line, where [PullsFormat.wantedEffort] puts
 * it beside the countdown.
 */
internal fun chipFor(watch: WantedWatch): PullsChip = when {
    watch.state == WantedWatchState.FULFILLED -> PullsChip.Badge(NeedlerAlbumBadge.InLibrary)
    watch.gap == WantedGap.PARTIAL -> PullsChip.Badge(NeedlerAlbumBadge.PartlyDelivered)
    else -> PullsChip.NotRetrieved
}

/**
 * The chip on a retrying row.
 *
 * Always the same word, because a retrying album is by definition one the server has not got. That
 * the next attempt is scheduled is in the line, from `PullsFormat.wantedRetryDetailParts`.
 */
internal val RETRYING_CHIP: PullsChip = PullsChip.NotRetrieved

/** `Server` or `Not retrieved`, from the one fact a lane has about where the record is. */
private fun locationOf(inLibrary: Boolean): PullsChip = if (inLibrary) {
    PullsChip.Badge(NeedlerAlbumBadge.InLibrary)
} else {
    PullsChip.NotRetrieved
}

/**
 * Whether this pull is parked on a person rather than on the server's own work.
 *
 * Two states wait on a human in DroppedNeedle's web interface and nothing this app does moves them
 * along: an approval the server has not granted, and a source nobody has picked. Counting them as
 * "in progress" is what let the header read `12 in progress` over twelve rows all reading
 * `Needs attention` in `screenshots/pulls-all-awaiting-review-phone.png`, with nothing in progress
 * at all.
 *
 * Kept in this module rather than added to `PullState` in `:core:domain`: the distinction is about
 * what a header sentence may claim, not about the lifecycle, and `:core:domain` is shared with four
 * other feature modules that have no such sentence.
 */
internal val PullState.waitsForAPerson: Boolean
    get() = this == PullState.PENDING_APPROVAL || this == PullState.AWAITING_SOURCE_REVIEW

/**
 * Whether the row offers **Stop**.
 *
 * REQUIREMENTS.md, "Queue screen requirements" item 3, allows cancel "only while searching, queued
 * or downloading", and that is [Pull.canCancel] — the rule for a **download task**. It is not the
 * whole rule for a **row**, and the gap was visible: `screenshots/pulls-every-state-phone.png`
 * draws a `Waiting` row with no Stop between a `Searching` row and a `Needs attention` row that
 * both have one, and `screenshots/pulls-history-phone.png` shows what that row is waiting for — an
 * administrator, potentially for ever. A queue with no way out of it is the thing the Cancel pill
 * was added to this screen to fix in the first place.
 *
 * A pending approval has no download task, so there is nothing for item 3 to govern. It is a
 * **request**, `PullsViewModel.onCancel` already routes a pull with no task id to
 * `PullRepository.cancelRequest`, and REQUIREMENTS.md "Placing a request" has that call refusing
 * in-band with a reason when the server will not do it — which `ProblemMessages` renders. So the
 * control works, and the one it replaces was silence.
 *
 * `PROCESSING` is still excluded, and that is the whole of what item 3 rules out here:
 * "Cancellation during `processing` is unsafe because files are being moved, and the server refuses
 * it." Offering a Stop that is certain to come back as a refusal is worse than offering none.
 *
 * Rejected: widening `PullState.isCancellable` in `:core:domain`. It is read by the album screen,
 * the widget and the notification actions, each of which cancels through a different lane, and a
 * change there would silently retune three surfaces this work has not looked at.
 */
internal val Pull.canStop: Boolean
    get() = canCancel || state == PullState.PENDING_APPROVAL

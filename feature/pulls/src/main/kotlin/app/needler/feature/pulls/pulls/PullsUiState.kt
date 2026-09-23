@file:OptIn(ExperimentalTime::class)

package app.needler.feature.pulls.pulls

import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullBucket
import app.needler.feature.pulls.common.PullsFormat
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Everything the Pulls screen renders — screen 06 in `design/html/06-Pulls.html`.
 *
 * The screen is stateless: it is handed one of these plus callbacks, so every
 * state it can be in — loading, empty, each bucket, held items, offline, an
 * action that failed — can be screenshotted and asserted on without a
 * repository, a database or a server.
 *
 * ## One list, not three
 *
 * [pulls] is the whole queue in one sequence, newest first, exactly as
 * `PullRepository.observePulls()` returns it. The buckets are *derived* from it
 * rather than fetched separately, and that ordering is the reason:
 * REQUIREMENTS.md, "Queue screen requirements", asks for tasks "bucketed into
 * Active, Completed and Failed … sorted newest first", and taking three
 * independently sorted flows would make "newest first" true within each section
 * and meaningless across them. Filtering one ordered list keeps a single
 * chronology under the whole screen, which is what lets the design's "Earlier"
 * block mix finished and failed pulls without the order looking arbitrary.
 *
 * ## The buckets and the two headings
 *
 * The domain has three buckets ([PullBucket]) and the design draws two headings,
 * `NOW` and `EARLIER`. Both are honoured: the data is bucketed as the
 * requirements demand and reachable here as [active], [completed] and [failed],
 * while the screen draws the pack's two headings over [active] and [earlier].
 * A third heading is one line away if the design ever grows one. See
 * `PullsScreen` for the rest of that argument.
 *
 * ## Why there is no per-row busy flag
 *
 * [busy] is screen-wide. Cancelling and retrying are server writes against a
 * list that repaints every time the poller lands, and a per-row flag keyed on an
 * MBID would have to survive a row being replaced underneath it — which, for a
 * retry, it cannot: REQUIREMENTS.md notes that `retryDownload` returns a *new*
 * task id, so the row the user tapped is not the row that comes back. One flag
 * that disables the actions for the moment an action is in flight is both
 * simpler and correct.
 */
data class PullsUiState(

    /** True until the mirror has answered once. Not "until the server answers". */
    val loading: Boolean = true,

    /** The whole queue, newest first, with pending approvals folded in. */
    val pulls: List<Pull> = emptyList(),

    /**
     * Items the server is holding or has quarantined.
     *
     * REQUIREMENTS.md, "Queue screen requirements" item 5: surface `held_count`
     * from the activity summary "as a read-only notice. Held and quarantined
     * items need the web UI in v1." So this is a number and a sentence, never a
     * list and never an action — there is no endpoint behind one.
     */
    val heldCount: Int = 0,

    /**
     * What the Pulls tab badge is showing: active pulls plus unseen completions.
     *
     * The badge itself is drawn by `:app`'s navigation scaffold, not here. It is
     * carried on the state anyway because REQUIREMENTS.md calls it "the reliable
     * channel" for pull state, and a screen that could contradict it — saying
     * nothing is in progress while the tab says two — is worth being able to
     * test for.
     */
    val badgeCount: Int = 0,

    /** The server is unreachable. Not an error: the rows below are still true. */
    val offline: Boolean = false,

    /** A cancel or retry is in flight, so the actions are disabled rather than tappable twice. */
    val busy: Boolean = false,

    /** The result of the last action, or an explanation the screen owes the user. */
    val notice: PullsNotice? = null,

    /** When the state was assembled, so "today" and "3d ago" are computed from a fixed instant. */
    val renderedAt: Instant = Instant.fromEpochSeconds(0L),
) {

    /** The `NOW` block: everything the server is still working on. */
    val active: List<Pull> get() = pulls.filter { it.bucket == PullBucket.ACTIVE }

    /** Finished and playable. */
    val completed: List<Pull> get() = pulls.filter { it.bucket == PullBucket.COMPLETED }

    /** Failed, cancelled or part-delivered — the bucket the requirements call Failed. */
    val failed: List<Pull> get() = pulls.filter { it.bucket == PullBucket.FAILED }

    /**
     * The `EARLIER` block: everything the server has finished with, whichever
     * way it finished.
     *
     * Taken from [pulls] rather than as `completed + failed` so the section is
     * one chronology. A pull that failed this morning belongs above one that
     * landed last week, and concatenating the two buckets would bury it.
     */
    val earlier: List<Pull> get() = pulls.filter { it.bucket != PullBucket.ACTIVE }

    val activeCount: Int get() = active.size

    val isEmpty: Boolean get() = pulls.isEmpty()

    /** The empty state is only honest once the mirror has actually answered. */
    val showEmptyState: Boolean get() = !loading && isEmpty

    /**
     * Whether the header's **Clear done** control does anything.
     *
     * Drawn unconditionally in the pack; drawn only when there is something to
     * clear here. A control that is always present and usually inert teaches
     * the user to ignore it, and this one has a real effect worth noticing.
     */
    val canClearDone: Boolean get() = completed.isNotEmpty()

    /** `2 in progress`, the line under the title on screen 06. */
    val headerLine: String get() = PullsFormat.headerLine(activeCount, pulls.size)
}

/**
 * Something the screen has to tell the user after an action.
 *
 * Modelled rather than collapsed into one string for the same reason
 * `AlbumNotice` is in `:feature:library`: these are different outcomes with
 * different next steps, and a cancel that the write queue will replay when the
 * phone finds a network is not the same event as a cancel the server has already
 * carried out.
 */
sealed interface PullsNotice {

    val message: String

    /** True for the ones that are bad news, which the screen tints differently. */
    val isProblem: Boolean get() = false

    /** The server accepted the cancellation. */
    data object Cancelled : PullsNotice {
        override val message: String
            get() = "Cancelled. The server has stopped work on that pull."
    }

    /**
     * The server accepted the retry.
     *
     * Deliberately does not promise the same row will update: REQUIREMENTS.md
     * notes that `retryDownload` returns a **new** task id, so what comes back
     * on the next poll is a new task for the same album rather than the old one
     * resurrected.
     */
    data object RetryPlaced : PullsNotice {
        override val message: String
            get() = "Asked the server to try again. It appears as a fresh pull on the next update."
    }

    /**
     * Finished pulls were cleared from this list.
     *
     * Says "from this list" on purpose. There is no endpoint that deletes a
     * finished task — `PullRepository` offers `markCompletionsSeen` and nothing
     * else — so this hides the rows here and clears them from the tab badge,
     * and the server's own history is untouched.
     */
    data object DoneCleared : PullsNotice {
        override val message: String
            get() = "Cleared. Finished pulls are hidden here; the server keeps its own history."
    }

    /** Something went wrong, in whatever words the domain error justified. */
    data class Problem(override val message: String) : PullsNotice {
        override val isProblem: Boolean get() = true
    }
}

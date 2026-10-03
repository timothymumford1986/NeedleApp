@file:OptIn(ExperimentalTime::class)

package app.needler.feature.pulls.pulls

import app.needler.core.domain.model.RequestHistoryEntry
import app.needler.core.domain.model.RequestHistoryPage
import app.needler.core.domain.model.WantedList
import app.needler.core.domain.model.WantedRetry
import app.needler.core.domain.model.WantedWatch
import app.needler.feature.pulls.common.PullsFormat
import kotlin.time.ExperimentalTime

/**
 * How far a read-through lane has got.
 *
 * The queue needs nothing like this: it is a Room query, so it has rows or it has not. These two
 * lanes are suspend reads against the server with nothing mirrored behind them, so "never asked",
 * "asking", "answered" and "could not ask" are four genuinely different things to draw, and
 * collapsing any pair of them loses something the user needs.
 *
 * [UNASKED] in particular is not the same as [LOADED] with nothing in it. REQUIREMENTS.md
 * "Accessibility" aside, an empty state that appears before the question has been asked is a screen
 * telling the user they have never requested anything when in fact nobody has looked yet — the same
 * mistake `PullsUiState.showEmptyState` exists to avoid on the queue.
 */
enum class LaneStatus {
    /** The lane has not been opened in this ViewModel's lifetime, so nothing has been fetched. */
    UNASKED,

    /** The first fetch is in flight. */
    LOADING,

    /** The server answered. The lane may still be empty, and that is now a fact worth drawing. */
    LOADED,

    /** The fetch failed, or there was no connection to make it over. See the lane's `problem`. */
    UNAVAILABLE,
}

/**
 * The one row at the foot of a paged list: an offer, a statement, or a reason.
 *
 * Shaped after `:feature:search`'s `SearchMoreRow` on purpose. That screen already solved exactly
 * this — one control that is in turn "load the next page", "loading", "that is all" and "the last
 * page failed, here is why" — and two lists in one app that page by different gestures is a thing
 * the user has to learn twice.
 *
 * @param enabled false for the two states that are statements rather than offers.
 * @param isProblem tints the label as news rather than as an action, as `MoreRow` does.
 */
data class LaneMoreRow(
    val label: String,
    val enabled: Boolean,
    val isProblem: Boolean = false,
)

/**
 * `GET /api/v1/requests/history`, accumulated page by page.
 *
 * ## This lane really does page, and really does know where the end is
 *
 * REQUIREMENTS.md warns of `GET /api/v1/downloads` that it "has no `total` and no `total_pages` …
 * An infinitely scrolling list is fine; a 'page 3 of 7' control is not implementable". That warning
 * is about the download queue and **not** about this endpoint, which
 * `DefaultPullRepository.requestHistory` says so of in as many words: it "reports both, so it does
 * exactly one call for exactly the page it was asked for and hands the totals back untouched for the
 * caller to page with". So the foot of this list names the page it is about to fetch and how many
 * there are, and the header states the real total — both of which the queue cannot do.
 *
 * ## Pages are appended, not replaced
 *
 * [entries] is every page fetched so far in order, and [paging] is the facts from the most recent
 * one. A one-page-at-a-time control was the alternative and was rejected: a phone list the user has
 * scrolled through loses its place on every page turn, and `:feature:search` already established
 * append-on-tap as this app's answer. Refreshing starts again from page one, which is the only
 * honest thing to do when the server may have inserted rows above page two.
 */
data class HistoryLaneState(
    val status: LaneStatus = LaneStatus.UNASKED,

    /** Every page fetched so far, newest request first, in the order the server returned them. */
    val entries: List<RequestHistoryEntry> = emptyList(),

    /** The totals and page number from the most recently fetched page. */
    val paging: RequestHistoryPage = RequestHistoryPage.Empty,

    /** A page beyond the first is being fetched. The rows already drawn stay drawn. */
    val appending: Boolean = false,

    /** Why the lane, or its last page, could not be read. Already in words a user can act on. */
    val problem: String? = null,
) {

    /** `46 requests`, the line under the title. The server's own total when it sent one. */
    val headerLine: String get() = PullsFormat.historyHeaderLine(paging.total, entries.size)

    /** True when the server has answered and has nothing on this user's record. */
    val showEmptyState: Boolean get() = status == LaneStatus.LOADED && entries.isEmpty()

    /** Whether asking for another page could return anything. The server's own answer. */
    val hasMore: Boolean get() = entries.isNotEmpty() && paging.hasMore

    /**
     * The foot of the list, or null when the list should simply end.
     *
     * Four states in one row, in priority order: a page being fetched, a page that failed, a page
     * that can be fetched, and the end of the list said out loud. The last of those is deliberately
     * not nothing — a list that just stops leaves the user wondering whether it stopped or broke,
     * and this is the one request list that can say with authority that there is no more.
     */
    val moreRow: LaneMoreRow?
        get() = when {
            entries.isEmpty() -> null

            appending -> LaneMoreRow(
                label = "Loading page " + (paging.page + 1) + MORE_ELLIPSIS,
                enabled = false,
            )

            // Only ever set here by a failed *append*: a failed first fetch has no rows under it, so
            // it is drawn as the lane's own message instead. Enabled, because the next move is to
            // try the same page again.
            problem != null -> LaneMoreRow(label = problem, enabled = true, isProblem = true)

            hasMore -> LaneMoreRow(label = loadMoreLabel(), enabled = true)

            else -> LaneMoreRow(label = endOfListLabel(), enabled = false)
        }

    /**
     * `Load page 3 of 7`, or `Load more requests` when the server sent no page count.
     *
     * Naming the page count is the whole point of this lane being different from the download queue,
     * and the fallback exists because `RequestHistoryPage.hasMore` has a third branch for a server
     * that reports neither total — in that case the list still scrolls, it just cannot say how far.
     */
    private fun loadMoreLabel(): String = if (paging.totalPages > 1) {
        "Load page " + (paging.page + 1) + " of " + paging.totalPages
    } else {
        "Load more requests"
    }

    /** `That is all 46 requests.` — the figure the server reported, or what is on screen. */
    private fun endOfListLabel(): String {
        val counted: Int = if (paging.total > 0) paging.total else entries.size
        return "That is all " + PullsFormat.plural(counted.toLong(), "request") + "."
    }

    private companion object {
        /** A real ellipsis, matching `:feature:search`'s "Looking for more…". */
        const val MORE_ELLIPSIS: String = "…"
    }
}

/**
 * `GET /api/v1/requests/wanted`: the standing watches, and the albums being re-attempted.
 *
 * ## There is no paging here, and that absence is the design
 *
 * REQUIREMENTS.md: this endpoint and `requests/active` "have no paging at all and return the whole
 * list". `WantedList`'s own KDoc adds the consequence — "Modelling it as a page would invite a
 * caller to ask for a second one, which the server has no way of answering" — and this state follows
 * it exactly. There is no page number, no total pages, no `hasMore` and no [LaneMoreRow]: the type
 * cannot express a second page, so no screen built on it can offer one. That is a stronger guarantee
 * than a comment, which is why it is done by omission rather than by a disabled control.
 *
 * ## Read-only, because `/api/v1` offers nothing else
 *
 * `PullRepository.wantedList` records that there is "no endpoint on `/api/v1` to start, stop or
 * re-schedule a watch", so the rows here carry no actions — the same decision, for the same reason,
 * as the held-items notice on the queue. A watch is started by `monitor_artist` on a request, which
 * is the request sheet's **Follow this artist**, and the empty state names that control rather than
 * offering a button here that could not work.
 */
data class WantedLaneState(
    val status: LaneStatus = LaneStatus.UNASKED,

    /** Standing watches, as the server ordered them. */
    val watches: List<WantedWatch> = emptyList(),

    /**
     * Albums the server is actively re-attempting, with an attempt budget.
     *
     * Kept apart from [watches] because the server counts them apart: the response's `count` covers
     * the watches only. Folding them together would make the header's figure wrong by however many
     * of these there are, which is the sort of discrepancy that teaches a user to distrust a screen.
     */
    val retrying: List<WantedRetry> = emptyList(),

    /** Why the lane could not be read, already in words. */
    val problem: String? = null,
) {

    /** `7 watched`, or `7 watched · 2 retrying`. Both figures, because the server's `count` is one. */
    val headerLine: String get() = PullsFormat.wantedHeaderLine(watches.size, retrying.size)

    val isEmpty: Boolean get() = watches.isEmpty() && retrying.isEmpty()

    /** True when the server has answered and is looking for nothing on this user's behalf. */
    val showEmptyState: Boolean get() = status == LaneStatus.LOADED && isEmpty

    companion object {
        /** Everything the one call returned. There is no second call to make. */
        fun loaded(list: WantedList): WantedLaneState = WantedLaneState(
            status = LaneStatus.LOADED,
            watches = list.watches,
            retrying = list.retrying,
        )
    }
}

package app.needler.feature.pulls.pulls

/**
 * The three request lists `/api/v1` offers, as three tabs of one screen.
 *
 * REQUIREMENTS.md, "Endpoints Needler consumes", gives the request half of the API three lists —
 * `GET /api/v1/requests/active`, `GET /api/v1/requests/history` and `GET /api/v1/requests/wanted` —
 * and they answer three different questions about the same album: what is happening to it now, what
 * happened to it before, and what the server is still looking for. One screen for all three is what
 * REQUIREMENTS.md's navigation already implies: there is one Pulls destination in the bottom bar and
 * no second place for a request list to live.
 *
 * ## Why tabs, and whose idiom this is
 *
 * The design pack does **not** draw a tabbed Pulls screen. `design/html/06-Pulls.html` is a title, a
 * count, Clear done and one list; it has no segmented control and no mention of history or wanted.
 * So this borrows the pack's own sub-navigation idiom from screen 02, the library's
 * Albums / Artists / Songs control, drawn with the very component that was ported from it —
 * `app.needler.core.design.component.NeedlerSegmentedTabs`. Nothing new was invented: the same 36dp
 * pill, the same `Role.Tab` semantics, the same place under the screen title.
 *
 * `PullsFormat.historySubtitle` had already assumed this shape before any of it was drawn — "the two
 * lists sit behind two tabs of one screen and a user moving between them should not have to re-learn
 * where to look" — so the wording and the layout agree by construction rather than by luck.
 *
 * Two alternatives were rejected. A second navigation destination would need a route registering in
 * `app/src/main/kotlin/app/needler/ui/navigation`, which this work does not own, and would put two
 * entries in a four-item bottom bar for one subject. A single merged list was rejected because the
 * three lanes cannot be merged honestly: they are keyed differently in time, only one of them pages,
 * and only one of them is mirrored on the device, so a merged list would have to pretend all three
 * refresh and scroll alike.
 *
 * ## The lanes are deliberately asymmetric
 *
 * REQUIREMENTS.md: "`GET /api/v1/requests/active` and `GET /api/v1/requests/wanted` have **no paging
 * at all** and return the whole list. Only `GET /api/v1/requests/history` pages, taking `page`,
 * `page_size`, `status` and `sort`." The UI respects that rather than flattening it — see
 * [HistoryLaneState], which has a page control because the server reports `total_pages`, and
 * [WantedLaneState], which has no paging surface at all because there is no second page to ask for.
 *
 * ## Every number on this screen counts a different population, and must say which
 *
 * A device showed four pull counts within two minutes — `35` on the tab badge, `35 in progress` in
 * the header, `490 items held for review on the server` in the banner, and a notification reading
 * `360 pulls need attention` — and a user had no way to tell that those are four different
 * populations rather than four attempts at one. These lanes add two more, so the rule is written
 * down here, where the lanes are defined, rather than discovered again per surface.
 *
 * **The rule: whatever names the population owns the noun; the figure beneath it only counts.**
 * A lane's tab label is that name, which is why the labels are nouns for *lists* rather than for
 * states, and why none of them is a number's adjective. A figure that is not inside a lane has to
 * name its own population in its own sentence — which the held banner already does, and does well:
 * it says "on the server" and then says who can act on it.
 *
 * Read from source, and in every case the number is a pass-through of exactly one thing:
 *
 *  * **Tab badge** — `PullRepository.observePullBadgeCount`: a Room `COUNT(*)` over the six active
 *    `pull` statuses, plus completed rows not in the `seen_pulls` set. Local, and counts no
 *    failures at all.
 *  * **Pulls lane, "N in progress"** — [PullsUiState.activeCount], the same mirror rows the badge's
 *    active half counts. The two agree *by construction*, which is why the device saw 35 twice;
 *    they are one population read in two places, not two measurements that happened to match.
 *  * **Held banner** — `PullActivitySummary.heldCount`, the server's `held_count`: items held or
 *    quarantined server-side, which `/api/v1` gives this client no way to resolve.
 *  * **History lane, "46 requests"** — `RequestHistoryPage.total`, the server's count of this
 *    user's whole request record, settled and unsettled. The tab says History; the line counts.
 *  * **Wanted lane, "8 watched · 2 retrying"** — the standing watches and the albums being
 *    re-attempted, counted separately because the endpoint's own `count` covers only the first.
 *
 * The one surface that breaks the rule is not on this screen and is not this module's: the
 * `pull-failed` notification interpolates the server's **cumulative** `failed_count` into the
 * sentence "N pulls need attention" — which is both a sixth population and the exact phrase the
 * rows here use for a *seventh*, `PullState.AWAITING_SOURCE_REVIEW`. Two surfaces sharing a phrase
 * while counting different things is the worst case of this, and it is in the handover notes with
 * the file and line.
 *
 * @param label the tab's visible word.
 * @param spokenLabel what a screen reader hears. The visible labels are short because three of them
 *   have to fit across a 390dp phone; the spoken ones say which list is which, because "Wanted" on
 *   its own does not say who wants what.
 */
enum class PullsLane(
    val label: String,
    val spokenLabel: String,
) {
    /**
     * The download queue: the pack's own screen, `NOW` over `EARLIER`.
     *
     * Named for the screen rather than for the endpoint. It is not only
     * `GET /api/v1/requests/active` — it is the mirrored `pull` table, which holds finished and
     * failed tasks too, so "Active" would be a lie about two thirds of its rows.
     */
    QUEUE(label = "Pulls", spokenLabel = "Pulls in progress and recently finished"),

    /** `GET /api/v1/requests/history`: everything ever asked for, and what came of it. */
    HISTORY(label = "History", spokenLabel = "Everything you have asked for"),

    /** `GET /api/v1/requests/wanted`: the standing watches for music not found yet. */
    WANTED(label = "Wanted", spokenLabel = "Music the server is still looking for"),
    ;

    /**
     * Whether this lane is read through to the server with nothing mirrored behind it.
     *
     * The distinction decides what the lane does when there is no connection, and it is a property
     * of the data rather than of the screen: `PullRepository.requestHistory` and
     * `PullRepository.wantedList` are suspend reads against the network because REQUIREMENTS.md
     * "Local persistence" gives the `pull` table no history sibling, while the queue is a Room query
     * and renders offline. So [QUEUE] shows stale truth offline and these two show an explanation.
     */
    val isReadThrough: Boolean get() = this != QUEUE

    companion object {
        /** The tab labels, in tab order, for `NeedlerSegmentedTabs`. */
        val labels: List<String> = entries.map { it.label }
    }
}

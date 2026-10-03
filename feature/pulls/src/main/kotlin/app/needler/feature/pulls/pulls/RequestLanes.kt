@file:OptIn(ExperimentalTime::class)

package app.needler.feature.pulls.pulls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.AsyncAlbumArt
import app.needler.core.design.component.NeedlerAlbumBadge
import app.needler.core.design.component.NeedlerAlbumRow
import app.needler.core.design.component.NeedlerPillButton
import app.needler.core.design.component.NeedlerSectionHeader
import app.needler.core.design.component.NeedlerStateBadge
import app.needler.core.design.component.NeedlerTextButton
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestHistoryEntry
import app.needler.core.domain.model.RequestOutcome
import app.needler.core.domain.model.WantedRetry
import app.needler.core.domain.model.WantedWatch
import app.needler.feature.pulls.common.PullsFormat
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// ---------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------

/**
 * `GET /api/v1/requests/history`: everything this user has asked for, and what came of it.
 *
 * One scrolling list with a page control at its foot. REQUIREMENTS.md's warning that a
 * "'page 3 of 7' control is not implementable" is about `GET /api/v1/downloads`, which reports
 * neither `total` nor `total_pages`; this endpoint reports both — `DefaultPullRepository` says so in
 * its own KDoc — so the foot of the list names the page it is about to fetch and how many there are,
 * and says out loud when there are no more. See [HistoryLaneState.moreRow].
 *
 * ## What a row says, and what it does not
 *
 * Every string comes from `PullsFormat`, which was written for this lane before anything drew it and
 * is not this work's to change. So the title is [PullsFormat.historyTitle] — the *track* on a track
 * request, because naming the album would name something the user did not ask for — the line under
 * it is [PullsFormat.historySubtitle], and TalkBack hears [PullsFormat.spokenHistoryRow].
 *
 * The outcome is therefore already in the subtitle, in the formatter's own words, and the badge
 * carries the state's **name** where `:core:design` has exactly that word for it. That is the same
 * split the queue's rows use: name in the trailing column, explanation in the line. Two outcomes get
 * no badge — see [historyBadge].
 *
 * Ownership is **not** drawn here and there is no ownership component on these rows. The one fact
 * this lane has about where a record lives is `RequestHistoryEntry.inLibrary`, and the formatter
 * already folds it into the line as "in your library", and only where it is news — on a request that
 * failed or was declined and whose album got there by some other route. A badge saying the same
 * thing would be the duplication the queue's rows were redrawn to remove.
 */
@Composable
internal fun HistoryLane(
    state: HistoryLaneState,
    offline: Boolean,
    busy: Boolean,
    now: Instant,
    gutter: Dp,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onRetryRequest: (RequestHistoryEntry) -> Unit,
    onLoadMore: () -> Unit,
    onTryAgain: () -> Unit,
) {
    when {
        state.status == LaneStatus.LOADING && state.entries.isEmpty() ->
            LaneSkeleton(gutter = gutter, label = "Reading your request history")

        state.status == LaneStatus.UNAVAILABLE -> LanePlaceholder(
            gutter = gutter,
            title = if (offline) OFFLINE_TITLE else "Could not read your history",
            body = state.problem.orEmpty(),
            action = "Try again",
            onAction = onTryAgain,
        )

        state.showEmptyState -> LanePlaceholder(
            gutter = gutter,
            title = "Nothing asked for yet",
            body = "This is every album and track you have asked this server for, and what " +
                "became of each one. Find something under Search and tap Pull, and it appears " +
                "here as soon as the server has accepted it.",
            caption = "Requests the server is still working on are on the Pulls tab, which also " +
                "works with no connection.",
            action = null,
            onAction = onTryAgain,
        )

        // UNASKED, which is momentary: selecting the tab starts the fetch. Drawn as the skeleton
        // rather than as an empty state, because nobody has asked the server anything yet and
        // "nothing asked for yet" would be a claim about the user.
        state.entries.isEmpty() ->
            LaneSkeleton(gutter = gutter, label = "Reading your request history")

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = gutter,
                end = gutter,
                bottom = NeedlerTheme.spacing.step12,
            ),
        ) {
            // The index is in the key on purpose. A history entry is identified only by its release
            // group, and the same album legitimately appears twice — asked for, failed, asked for
            // again — so an MBID alone is not unique in this list and a duplicate key is a crash.
            // Pages are only ever appended or replaced wholesale, so the position is stable for as
            // long as the rows are.
            itemsIndexed(
                items = state.entries,
                key = { index, entry -> "history-" + index + "-" + entry.releaseGroupMbid.value },
            ) { _, entry ->
                HistoryRow(
                    entry = entry,
                    now = now,
                    busy = busy,
                    onOpenAlbum = onOpenAlbum,
                    onRetryRequest = onRetryRequest,
                )
            }

            laneMoreRow(key = "history", row = state.moreRow, onClick = onLoadMore)
        }
    }
}

@Composable
private fun HistoryRow(
    entry: RequestHistoryEntry,
    now: Instant,
    busy: Boolean,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onRetryRequest: (RequestHistoryEntry) -> Unit,
) {
    val badge: NeedlerAlbumBadge? = historyBadge(entry)
    NeedlerAlbumRow(
        title = PullsFormat.historyTitle(entry),
        subtitle = PullsFormat.historySubtitle(entry, now),
        onClick = { onOpenAlbum(entry.releaseGroupMbid) },
        contentDescription = PullsFormat.spokenHistoryRow(entry, now),
        artwork = { LaneArtwork(mbid = entry.releaseGroupMbid) },
        trailing = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step3),
            ) {
                if (badge != null) NeedlerStateBadge(badge = badge)
                if (entry.canRetry) {
                    LaneAction(
                        label = "Retry the request for " + PullsFormat.historyPhrase(entry),
                    ) {
                        NeedlerPillButton(
                            text = "Retry",
                            onClick = { onRetryRequest(entry) },
                            emphasised = true,
                            enabled = !busy,
                        )
                    }
                }
            }
        },
    )
}

/**
 * Which of `:core:design`'s badges names this outcome, or null when none of them does.
 *
 * Six of the eight outcomes have an exact word in the existing vocabulary, and using it is what
 * keeps this lane and the queue reading as one screen. [RequestOutcome.PENDING] and
 * [RequestOutcome.AWAITING_APPROVAL] share `Waiting` because the badge carries the state's name and
 * the subtitle carries which wait it is — "waiting to start" against "waiting for an administrator".
 *
 * ## Why two outcomes draw no badge
 *
 * [RequestOutcome.REJECTED] has no word in `NeedlerAlbumBadge`. Nothing there fits: `Failed` calls a
 * decision an error, `Cancelled` says the request was stopped rather than refused, and
 * `NeedsAttention` claims the server is still waiting on something. The honest options were to add a
 * `Declined` badge, which means editing `:core:design` and is not this work's to do, or to let the
 * subtitle carry it — and the subtitle carries it *better* than a badge could, because
 * `PullsFormat.historyOutcome` names the administrator: "declined by Ada", which is the difference
 * between a policy and a mystery. The missing badge is a handover note.
 *
 * [RequestOutcome.OTHER] draws none for a stronger reason. It is a state this client has never heard
 * of, and REQUIREMENTS.md "Placing a request" requires the status the server returned to be rendered
 * rather than inferred; picking the nearest badge for an unknown token is precisely the inference
 * that rule forbids. The subtitle prints the server's own word, tidied, which is what the user will
 * also see in DroppedNeedle's web interface.
 */
private fun historyBadge(entry: RequestHistoryEntry): NeedlerAlbumBadge? = when (entry.status) {
    RequestOutcome.PENDING,
    RequestOutcome.AWAITING_APPROVAL -> NeedlerAlbumBadge.Waiting

    // No percentage: this lane reports that an acquisition is under way, not how far it has got.
    // The figure belongs to the download task, which is the Pulls tab's business.
    RequestOutcome.IN_PROGRESS -> NeedlerAlbumBadge.Pulling()

    RequestOutcome.COMPLETED -> NeedlerAlbumBadge.Ready
    RequestOutcome.FAILED -> NeedlerAlbumBadge.Failed
    RequestOutcome.CANCELLED -> NeedlerAlbumBadge.Cancelled

    RequestOutcome.REJECTED, RequestOutcome.OTHER -> null
}

// ---------------------------------------------------------------------------
// Wanted
// ---------------------------------------------------------------------------

/**
 * `GET /api/v1/requests/wanted`: music the server could not find and keeps looking for.
 *
 * ## No paging, and nothing drawn that could suggest otherwise
 *
 * REQUIREMENTS.md: `requests/active` and `requests/wanted` "have no paging at all and return the
 * whole list". So there is no [laneMoreRow] call in this composable, no page number anywhere, and
 * [WantedLaneState] has no field one could be built from. The list simply ends, because the list
 * really has ended — which is a different fact from the history lane's "load page 3 of 7" and is
 * drawn differently on purpose.
 *
 * ## Read-only, and the empty state says where watches come from
 *
 * `PullRepository.wantedList` records that `/api/v1` "exposes nothing to start, stop or re-schedule a
 * watch", so no row here carries an action — the same decision as the queue's held-items notice, for
 * the same reason: a control that cannot work is worse than none. The one thing the user *can* do is
 * **Follow this artist** on the request sheet, which sets `monitor_artist`, and the empty state names
 * that control rather than offering a dead button here.
 *
 * ## Two sections, because the server counts them separately
 *
 * `WantedList` keeps watches and retrying albums apart, and `wantedHeaderLine` reports both figures
 * for the stated reason that the response's own `count` covers the watches only. The two read
 * differently too: a watch is a standing wish, a retrying album is a download about to be attempted
 * again, and [PullsFormat.wantedRetrySubtitle] is a separate formatter so that the one which is
 * moving does not read like the one which is waiting.
 *
 * No badges. The state wording this screen must use is already decided — `PullsFormat.wantedState`
 * gives "not found yet", "only part of it found", "still being looked for" and so on — and those are
 * sentences, not badge words. A 13sp/600 badge slot is the wrong place for a clause, and at 200% text
 * it would be the first thing to break. So the state sits where the formatter put it: in the line.
 */
@Composable
internal fun WantedLane(
    state: WantedLaneState,
    offline: Boolean,
    now: Instant,
    gutter: Dp,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onTryAgain: () -> Unit,
) {
    when {
        state.status == LaneStatus.LOADING && state.isEmpty ->
            LaneSkeleton(gutter = gutter, label = "Reading the wanted list")

        state.status == LaneStatus.UNAVAILABLE -> LanePlaceholder(
            gutter = gutter,
            title = if (offline) OFFLINE_TITLE else "Could not read the wanted list",
            body = state.problem.orEmpty(),
            action = "Try again",
            onAction = onTryAgain,
        )

        state.showEmptyState -> LanePlaceholder(
            gutter = gutter,
            title = "Nothing on the wanted list",
            body = "When the server cannot find something you have asked for, it keeps looking " +
                "and the album appears here with what the last look turned up. An empty list " +
                "means everything you have asked for was either found or is no longer being " +
                "looked for.",
            caption = "Watches are started by the server, not from here. Turn on Follow this " +
                "artist when you place a request and new releases are watched for too.",
            action = null,
            onAction = onTryAgain,
        )

        state.isEmpty -> LaneSkeleton(gutter = gutter, label = "Reading the wanted list")

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = gutter,
                end = gutter,
                bottom = NeedlerTheme.spacing.step12,
            ),
        ) {
            if (state.watches.isNotEmpty()) {
                item(key = "wanted-heading-watched") {
                    NeedlerSectionHeader(title = HEADING_WATCHED)
                }
                itemsIndexed(
                    items = state.watches,
                    key = { index, watch ->
                        "watch-" + index + "-" + watch.releaseGroupMbid.value
                    },
                ) { _, watch ->
                    WantedWatchRow(watch = watch, now = now, onOpenAlbum = onOpenAlbum)
                }
            }

            if (state.retrying.isNotEmpty()) {
                item(key = "wanted-heading-retrying") {
                    NeedlerSectionHeader(title = HEADING_RETRYING)
                }
                itemsIndexed(
                    items = state.retrying,
                    key = { index, retry ->
                        "retry-" + index + "-" + retry.releaseGroupMbid.value
                    },
                ) { _, retry ->
                    WantedRetryRow(retry = retry, now = now, onOpenAlbum = onOpenAlbum)
                }
            }
        }
    }
}

@Composable
private fun WantedWatchRow(
    watch: WantedWatch,
    now: Instant,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
) {
    NeedlerAlbumRow(
        title = PullsFormat.albumTitle(watch.albumTitle),
        subtitle = PullsFormat.wantedSubtitle(watch, now),
        onClick = { onOpenAlbum(watch.releaseGroupMbid) },
        contentDescription = PullsFormat.spokenWantedRow(watch, now),
        artwork = { LaneArtwork(mbid = watch.releaseGroupMbid) },
    )
}

@Composable
private fun WantedRetryRow(
    retry: WantedRetry,
    now: Instant,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
) {
    NeedlerAlbumRow(
        title = PullsFormat.albumTitle(retry.albumTitle),
        subtitle = PullsFormat.wantedRetrySubtitle(retry, now),
        onClick = { onOpenAlbum(retry.releaseGroupMbid) },
        contentDescription = PullsFormat.spokenWantedRetryRow(retry, now),
        artwork = { LaneArtwork(mbid = retry.releaseGroupMbid) },
    )
}

// ---------------------------------------------------------------------------
// Shared between the two lanes
// ---------------------------------------------------------------------------

/**
 * The foot of a paged list: `Load page 3 of 7`, `Loading page 3…`, `That is all 46 requests.`, or
 * why the last page did not arrive.
 *
 * Only the history lane calls this. It is written as a `LazyListScope` extension rather than as the
 * last item's child for the reason `:feature:search`'s `moreRowItem` gives: it keeps its own key, so
 * it animates as its own row when its label changes rather than redrawing the row above it.
 *
 * A polite live region, so a screen reader hears "Loading page 3" and then the answer without being
 * thrown back to the top of a list it has just walked.
 */
private fun LazyListScope.laneMoreRow(
    key: String,
    row: LaneMoreRow?,
    onClick: () -> Unit,
) {
    if (row == null) return
    item(key = "more-" + key) {
        val colors = NeedlerTheme.colors
        NeedlerTextButton(
            text = row.label,
            onClick = onClick,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            enabled = row.enabled,
            // The palette has one emphasis colour and no error colour, so a problem is drawn in the
            // primary text colour rather than in a red this design system does not have. The same
            // decision `:feature:search`'s `MoreRow` took.
            color = if (row.isProblem) colors.textPrimary else colors.accent,
        )
    }
}

/**
 * The 56dp square on a history or wanted row.
 *
 * [ArtworkRef.Catalogue] for the same reason the queue's rows use it: a request is by definition for
 * something the server did not have, so the Subsonic `getCoverArt` lane has nothing to answer with
 * until it lands. There is still no mapper from a catalogue ref to a URL in any module below `:app`,
 * so every square draws as the placeholder tint — the same handover note `PullsScreen` carries.
 */
@Composable
private fun LaneArtwork(mbid: ReleaseGroupMbid) {
    Box(
        modifier = Modifier.size(NeedlerTheme.sizes.artworkRow),
        contentAlignment = Alignment.Center,
    ) {
        AsyncAlbumArt(
            model = ArtworkRef.Catalogue(mbid),
            // The row already names the album; a second reading would say it twice.
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            shape = NeedlerTheme.shapes.artworkThumb,
        )
    }
}

/**
 * Keeps an interactive control inside a merged row reachable on its own.
 *
 * The same trick `PullsScreen`'s `RowAction` plays, and needed for the same reason:
 * `NeedlerAlbumRow` merges its descendants so TalkBack reads a row as one sentence, and a merge
 * swallows any descendant that is not itself a merging boundary — which a bare button is not.
 */
@Composable
private fun LaneAction(label: String, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = label
        },
    ) {
        content()
    }
}

/**
 * A lane that cannot show rows: no connection, a server that refused, or nothing to show.
 *
 * One component for all three because they occupy the same pane and want the same shape — a heading,
 * an explanation and at most one thing to do. Written here rather than reusing `PullsScreen`'s
 * `PullsEmptyState` because that one carries a third paragraph about the tab badge which is true of
 * the queue only.
 *
 * @param action the label of the one control, or null when the way out of this state is on another
 *   screen. An empty lane offers nothing: the way to fill it is Search, and a button here would only
 *   navigate — the same argument `PullsEmptyState` makes.
 */
@Composable
private fun LanePlaceholder(
    gutter: Dp,
    title: String,
    body: String,
    action: String?,
    onAction: () -> Unit,
    caption: String? = null,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter, vertical = spacing.step20),
        verticalArrangement = Arrangement.spacedBy(spacing.step6),
    ) {
        Text(
            text = title,
            style = typography.displayCompact,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = body,
            style = typography.body,
            color = colors.textSecondary,
            // Polite rather than silent: this pane replaces a list under the user when a refresh
            // fails, and a screen reader that was reading rows should be told why they went.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (caption != null) {
            Text(text = caption, style = typography.caption, color = colors.textMuted)
        }
        if (action != null) {
            NeedlerPillButton(text = action, onClick = onAction)
        }
    }
}

/**
 * The loading state for a lane that has to go to the server.
 *
 * Empty rows rather than a spinner, matching the queue's skeleton — except that the queue is waiting
 * on a Room read of single-digit milliseconds and this is waiting on a network call, so here the
 * blocks are genuinely on screen for a moment. They hold the layout still, which is what stops the
 * list jumping when the page lands.
 */
@Composable
private fun LaneSkeleton(gutter: Dp, label: String) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        repeat(SKELETON_ROWS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NeedlerTheme.sizes.albumRowMinHeight),
                horizontalArrangement = Arrangement.spacedBy(spacing.step7),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(NeedlerTheme.sizes.artworkRow)
                        .clip(NeedlerTheme.shapes.artworkThumb)
                        .background(colors.surface),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.step3),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(SKELETON_TITLE_WIDTH)
                            .height(12.dp)
                            .clip(NeedlerTheme.shapes.progress)
                            .background(colors.surface),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(SKELETON_SUBTITLE_WIDTH)
                            .height(10.dp)
                            .clip(NeedlerTheme.shapes.progress)
                            .background(colors.surface),
                    )
                }
            }
        }
    }
}

/** The proportions `PullsSkeleton` uses, so the two loading states look like one app. */
private const val SKELETON_TITLE_WIDTH: Float = 0.6f

private const val SKELETON_SUBTITLE_WIDTH: Float = 0.35f

/** As many blocks as fill a phone list, so the pane is not half empty while it waits. */
private const val SKELETON_ROWS: Int = 5

private const val OFFLINE_TITLE: String = "No connection"

/** The two overlines on the wanted lane, drawn uppercase by `NeedlerSectionHeader`. */
private const val HEADING_WATCHED: String = "Watched"

private const val HEADING_RETRYING: String = "Retrying"

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
import app.needler.core.design.component.NeedlerAlbumRow
import app.needler.core.design.component.NeedlerButtonSize
import app.needler.core.design.component.NeedlerPillButton
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerSectionHeader
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestHistoryEntry
import app.needler.core.domain.model.WantedRetry
import app.needler.core.domain.model.WantedWatch
import app.needler.feature.pulls.common.PullsFormat
import app.needler.feature.pulls.common.RETRYING_CHIP
import app.needler.feature.pulls.common.chipFor
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
    busy: Boolean,
    now: Instant,
    gutter: Dp,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onRetryRequest: (RequestHistoryEntry) -> Unit,
    onLoadMore: () -> Unit,
    onTryAgain: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    when {
        state.status == LaneStatus.LOADING && state.entries.isEmpty() ->
            LaneSkeleton(gutter = gutter, label = "Reading your request history")

        state.status == LaneStatus.UNAVAILABLE -> LanePlaceholder(
            gutter = gutter,
            title = "Nothing to show",
            body = state.problem.orEmpty(),
            action = TRY_AGAIN,
            onAction = onTryAgain,
        )

        state.showEmptyState -> LanePlaceholder(
            gutter = gutter,
            title = "Nothing asked for yet",
            body = "This is every album and track you have asked this server for, and what " +
                "became of each one. A request appears here as soon as the server has accepted " +
                "it, and stays after the pull itself has gone.",
            caption = "Requests the server is still working on are on the Pulls tab, which also " +
                "works with no connection.",
            action = FIND_MUSIC,
            onAction = onOpenSearch,
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
    NeedlerAlbumRow(
        title = PullsFormat.historyTitle(entry),
        subtitle = PullsFormat.historySubtitle(entry, now),
        onClick = { onOpenAlbum(entry.releaseGroupMbid) },
        contentDescription = PullsFormat.spokenHistoryRow(entry, now),
        artwork = { LaneArtwork(mbid = entry.releaseGroupMbid) },
        trailing = {
            StatusColumn(
                chip = chipFor(entry),
                modifier = Modifier.align(Alignment.Top),
            ) {
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
 ## Why these rows have a chip now
 *
 * They had none, on the argument that the wording this lane must use was already fixed as sentences
 * — "not found yet", "only part of it found", "still being looked for" — and a 13sp/600 slot is the
 * wrong place for a clause. The argument was sound about those strings and the strings were wrong.
 * Five of the six said one thing: the record is on neither store. That is `Not retrieved`, one of
 * the three words REQUIREMENTS.md "Where a record is" fixes, and one word fits a chip. What is left
 * for the line is how hard the server is still looking, which is a different question and a shorter
 * answer — see `PullsFormat.wantedEffort` and `chipFor`.
 */
@Composable
internal fun WantedLane(
    state: WantedLaneState,
    now: Instant,
    gutter: Dp,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onTryAgain: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    when {
        state.status == LaneStatus.LOADING && state.isEmpty ->
            LaneSkeleton(gutter = gutter, label = "Reading the wanted list")

        state.status == LaneStatus.UNAVAILABLE -> LanePlaceholder(
            gutter = gutter,
            title = "Nothing to show",
            body = state.problem.orEmpty(),
            action = TRY_AGAIN,
            onAction = onTryAgain,
        )

        state.showEmptyState -> LanePlaceholder(
            gutter = gutter,
            title = "Nothing on the wanted list",
            body = "When the server cannot find something you have asked for, it keeps looking " +
                "and the album appears here with what the last look turned up. An empty list " +
                "means everything you have asked for was either found or is no longer being " +
                "looked for.",
            // The route to **Follow this artist** is the request sheet, which is reached from
            // Search - `PullRepository.wantedList` records that `/api/v1` "exposes nothing to
            // start, stop or re-schedule a watch", so there is no control to put here. The button
            // therefore goes where the control actually is rather than naming it and stopping.
            caption = "Watches are started when you place a request: turn on Follow this artist " +
                "on the request sheet and new releases are watched for too.",
            action = FIND_MUSIC,
            onAction = onOpenSearch,
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
        title = PullsFormat.albumTitle(watch.albumTitle, watch.artistName),
        subtitle = PullsFormat.wantedSubtitle(watch, now),
        onClick = { onOpenAlbum(watch.releaseGroupMbid) },
        contentDescription = PullsFormat.spokenWantedRow(watch, now),
        artwork = { LaneArtwork(mbid = watch.releaseGroupMbid) },
        trailing = {
            StatusColumn(chip = chipFor(watch), modifier = Modifier.align(Alignment.Top)) {}
        },
    )
}

@Composable
private fun WantedRetryRow(
    retry: WantedRetry,
    now: Instant,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
) {
    NeedlerAlbumRow(
        title = PullsFormat.albumTitle(retry.albumTitle, retry.artistName),
        subtitle = PullsFormat.wantedRetrySubtitle(retry, now),
        onClick = { onOpenAlbum(retry.releaseGroupMbid) },
        contentDescription = PullsFormat.spokenWantedRetryRow(retry, now),
        artwork = { LaneArtwork(mbid = retry.releaseGroupMbid) },
        trailing = {
            StatusColumn(chip = RETRYING_CHIP, modifier = Modifier.align(Alignment.Top)) {}
        },
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
 * ## It is a button now, because it is the only way to the rest of the list
 *
 * It was a bare text link, left-aligned in the whitespace under the last row and in nothing but the
 * accent colour — `screenshots/pulls-history-page-control-phone.png` is one short phrase floating
 * under three rows, and it is the only route to the other sixteen of nineteen requests. A control
 * that important wearing less emphasis than the **Retry** pill on the row above it is the wrong way
 * round, and a text link is also the weakest touch target on the screen.
 *
 * The two states that are **statements** rather than offers stay as text, and that distinction is
 * the point: "Loading page 3…" and "That is all 46 requests." are things to read, and a disabled
 * button would invite a tap at both.
 *
 * A failed page keeps its reason as text and puts the retry in the button beneath it, because the
 * reason is a sentence and a sentence makes a bad button label.
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = NeedlerTheme.spacing.step6)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step4),
        ) {
            if (!row.enabled || row.isProblem) {
                Text(
                    text = row.label,
                    style = NeedlerTheme.typography.caption,
                    color = colors.textMuted,
                )
            }
            if (row.enabled) {
                NeedlerPrimaryButton(
                    // A failed page's label is its reason, which is a sentence; the button beneath
                    // it says what tapping does.
                    text = if (row.isProblem) TRY_AGAIN else row.label,
                    onClick = onClick,
                    size = NeedlerButtonSize.Compact,
                )
            }
        }
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
 * @param action the label of the one control. Never null now: an empty lane used to offer nothing,
 *   on the argument that "the way to fill it is Search, and a button here would only navigate" —
 *   which is what an empty state's button is for. `PullsEmptyState` carries the rest of that
 *   reversal, and `screenshots/playlists-empty-phone.png` is the template all three now follow.
 */
@Composable
private fun LanePlaceholder(
    gutter: Dp,
    title: String,
    body: String,
    action: String,
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
        NeedlerPrimaryButton(text = action, onClick = onAction)
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

/** The one label for the one act, wherever a failed read offers it. */
internal const val TRY_AGAIN: String = "Try again"

/** The two overlines on the wanted lane, drawn uppercase by `NeedlerSectionHeader`. */
private const val HEADING_WATCHED: String = "Watched"

private const val HEADING_RETRYING: String = "Retrying"

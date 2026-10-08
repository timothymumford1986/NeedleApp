@file:OptIn(ExperimentalTime::class)

package app.needler.feature.pulls.pulls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.AsyncAlbumArt
import app.needler.core.design.component.NeedlerAlbumBadge
import app.needler.core.design.component.NeedlerAlbumRow
import app.needler.core.design.component.NeedlerButtonSize
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerLinearProgress
import app.needler.core.design.component.NeedlerPillButton
import app.needler.core.design.component.NeedlerRowLayout
import app.needler.core.design.component.NeedlerPlayIcon
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerProgressRing
import app.needler.core.design.component.NeedlerSectionHeader
import app.needler.core.design.component.NeedlerSegmentedTabs
import app.needler.core.design.component.NeedlerStateBadge
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathClose
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.model.PullState
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestHistoryEntry
import app.needler.feature.pulls.common.PullsChip
import app.needler.feature.pulls.common.PullsFormat
import app.needler.feature.pulls.common.canStop
import app.needler.feature.pulls.common.chipFor
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The Pulls screen: `design/html/06-Pulls.html`, plus the two request lists the pack does not draw.
 *
 * A title, a count, **Clear done**, a tab row, and then one of three lists. The first is the pack's
 * own — the `NOW` block over the pulls the server is still working on, and the `EARLIER` block over
 * the ones it has finished with. The other two are [PullsLane.HISTORY] and [PullsLane.WANTED], drawn
 * by `RequestLanes.kt`.
 *
 * ## The tab row is borrowed, because the pack has none
 *
 * `design/html/06-Pulls.html` draws no tabs and mentions neither history nor wanted: it is a title,
 * a count, **Clear done** and one list. The idiom here is screen 02's, the library's
 * Albums / Artists / Songs control, drawn with [NeedlerSegmentedTabs] — the component that was
 * ported from it — in the same place under the same kind of title. [PullsLane] carries the full
 * argument, including the two alternatives rejected.
 *
 * Three strings had to be chosen and the first is the one worth defending: the queue's tab is
 * labelled **Pulls**, not "Active". It is not only `GET /api/v1/requests/active` — it is the mirrored
 * `pull` table, which holds finished and failed tasks too, so "Active" would misname two thirds of
 * its rows. It repeats the screen title for the same reason "Albums" repeats under "Library": the
 * screen's subject is now one of several lists about that subject.
 *
 * ## What the header does per lane
 *
 * The title never changes. Everything else does, because the three lanes are not three views of one
 * list: the count under the title comes from each lane's own formatter
 * ([PullsUiState.laneHeaderLine]); **Clear done** is drawn on the queue only, since nothing in
 * `/api/v1` deletes a history entry or a watch; and **Refresh** is drawn on the two lanes that are
 * fetched rather than polled. The held-items notice is the queue's, because `held_count` comes from
 * the download activity summary.
 *
 * The mini player and the bottom navigation drawn at the foot of screen 06 are
 * **not** here. They belong to `:app`'s navigation scaffold, which hosts this
 * screen in its content pane; drawing them again inside the feature would give
 * the app two of each. The tab badge is part of that scaffold too — see
 * [PullsUiState.badgeCount] for why this screen carries the number anyway.
 *
 * ## Two headings over three buckets
 *
 * REQUIREMENTS.md, "Queue screen requirements" item 1, asks for tasks "bucketed
 * into Active, Completed and Failed, matching the server's own grouping, sorted
 * newest first". The design draws two headings, not three: finished and failed
 * pulls share `EARLIER`.
 *
 * Both are followed. [PullsUiState] buckets exactly as the requirements say and
 * exposes all three; the screen draws the pack's two headings, and within
 * `EARLIER` the rows stay in the queue's single newest-first order rather than
 * being re-grouped — which is what reproduces the pack's own sample (two `Ready`
 * rows, then an older failure) without burying a failure that happened this
 * morning under a success from last week. A failed row is already distinguished
 * without a heading: it carries its reason in the subtitle and a **Retry** where
 * a finished one carries **Play**.
 *
 * ## The one control screen 06 does not draw
 *
 * Every active row that can be cancelled gets a **Cancel**, which the pack does
 * not show. REQUIREMENTS.md item 3 is explicit — "Allow cancel only while
 * searching, queued or downloading" — and a queue with no way out of it is not
 * the screen the requirements describe. It is drawn in the pack's own grammar,
 * stacked under the state in the trailing column exactly as **Play** is stacked
 * under `Ready` on a finished row, so nothing new was invented to fit it in.
 *
 * ## Why one row is hand-built
 *
 * The finished rows are [NeedlerAlbumRow], which was ported from this very
 * screen. The active row is not: the pack puts a progress bar *inside* the text
 * column, under the subtitle, and `NeedlerAlbumRow`'s column is fixed at title
 * and subtitle. [ActivePullRow] therefore lays out the same anatomy by hand
 * while still composing `:core:design`'s [AsyncAlbumArt], [NeedlerProgressRing],
 * [NeedlerLinearProgress] and [NeedlerStateBadge]. A third slot on
 * `NeedlerAlbumRow` — something like `secondary`, drawn under the subtitle —
 * would let this row use the component; that is a handover note, because
 * `:core:design` is not this module's to change.
 *
 * ## No row draws a blank where a name goes
 *
 * Nothing on this screen reads [Pull.albumTitle] directly. Every title slot goes
 * through [PullsFormat.albumTitle] and every action label through
 * [PullsFormat.albumPhrase], because the title is blank whenever the `album`
 * mirror has no name for the release group — on a real device that was 34 of 35
 * rows, and the queue was unusable: rows with no name, and a **Cancel** whose
 * content description read "Cancel the pull of " and stopped. REQUIREMENTS.md
 * "Accessibility" requires every control to carry a content description, and one
 * that names no target is not one.
 *
 * The missing data is fixed where it goes missing —
 * `DefaultPullRepository.refreshPulls` now takes the title from whichever lane
 * supplied it — so the fallbacks should be rare. They are not therefore
 * optional: the mirror can legitimately have nothing for a release group the
 * server itself cannot name, and a screen that renders a hole in that case is
 * broken for the one user it happens to.
 */
@Composable
fun PullsScreen(
    state: PullsUiState,
    widthSizeClass: WindowWidthSizeClass,
    onClearDone: () -> Unit,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onPlayAlbum: (ReleaseGroupMbid) -> Unit,
    onCancel: (Pull) -> Unit,
    onRetry: (Pull) -> Unit,
    onDismissNotice: () -> Unit,
    onSelectLane: (PullsLane) -> Unit,
    onRefreshLane: () -> Unit,
    onLoadMoreHistory: () -> Unit,
    onRetryRequest: (RequestHistoryEntry) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val wide: Boolean = widthSizeClass != WindowWidthSizeClass.Compact
    val gutter: Dp = if (wide) spacing.tabletGutter else spacing.phoneGutter

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier.padding(
                start = gutter,
                end = gutter,
                top = if (wide) spacing.step12 else spacing.step14,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.step6),
        ) {
            PullsHeader(
                state = state,
                onClearDone = onClearDone,
                onRefreshLane = onRefreshLane,
            )
            LaneTabs(lane = state.lane, onSelectLane = onSelectLane)

            // The result of the last action goes first and looks unlike anything else on the
            // screen: it is the only thing here the user caused, and the only thing that can be
            // dismissed. It used to be drawn *below* the standing notices and in the same bordered
            // box, so `screenshots/pulls-problem-phone.png` reads as two paragraphs of grey
            // telling the user that something they did failed - distinguished only by a thin
            // coloured outline nobody is looking for.
            state.notice?.let { notice ->
                NoticeCard(notice = notice, onDismiss = onDismissNotice)
            }

            HeaderBanner(lines = bannerLines(state))
        }

        Spacer(modifier = Modifier.height(spacing.step6))

        Box(modifier = Modifier.weight(1f)) {
            when (state.lane) {
                PullsLane.QUEUE -> QueueLane(
                    state = state,
                    widthSizeClass = widthSizeClass,
                    gutter = gutter,
                    onOpenAlbum = onOpenAlbum,
                    onPlayAlbum = onPlayAlbum,
                    onCancel = onCancel,
                    onRetry = onRetry,
                    onOpenSearch = onOpenSearch,
                )

                PullsLane.HISTORY -> HistoryLane(
                    state = state.history,
                    busy = state.busy,
                    now = state.renderedAt,
                    gutter = gutter,
                    onOpenAlbum = onOpenAlbum,
                    onRetryRequest = onRetryRequest,
                    onLoadMore = onLoadMoreHistory,
                    onTryAgain = onRefreshLane,
                    onOpenSearch = onOpenSearch,
                )

                PullsLane.WANTED -> WantedLane(
                    state = state.wanted,
                    now = state.renderedAt,
                    gutter = gutter,
                    onOpenAlbum = onOpenAlbum,
                    onTryAgain = onRefreshLane,
                    onOpenSearch = onOpenSearch,
                )
            }
        }
    }
}

/**
 * The pack's own list, unchanged: the queue, at one width or two.
 *
 * Lifted out of [PullsScreen] when the lanes arrived so that the three branches read as three
 * lanes rather than as one lane's four states beside two others. Nothing inside it changed.
 */
@Composable
private fun QueueLane(
    state: PullsUiState,
    widthSizeClass: WindowWidthSizeClass,
    gutter: Dp,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onPlayAlbum: (ReleaseGroupMbid) -> Unit,
    onCancel: (Pull) -> Unit,
    onRetry: (Pull) -> Unit,
    onOpenSearch: () -> Unit,
) {
    when {
        state.loading -> PullsSkeleton(gutter = gutter)

        state.showEmptyState -> PullsEmptyState(
            offline = state.offline,
            gutter = gutter,
            onOpenSearch = onOpenSearch,
        )

        // Expanded is the only width with room for two columns of
        // 56dp-artwork rows side by side. Medium — a foldable open, a
        // tablet in portrait — keeps the single list, because two panes
        // across 600dp would be narrower than the phone's one.
        widthSizeClass == WindowWidthSizeClass.Expanded -> PullsPanes(
            state = state,
            gutter = gutter,
            onOpenAlbum = onOpenAlbum,
            onPlayAlbum = onPlayAlbum,
            onCancel = onCancel,
            onRetry = onRetry,
        )

        else -> PullsList(
            state = state,
            gutter = gutter,
            onOpenAlbum = onOpenAlbum,
            onPlayAlbum = onPlayAlbum,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}

/**
 * Pulls / History / Wanted, in screen 02's segmented control.
 *
 * `horizontalScroll` for the reason `LibraryScreen` gives of the identical control: at 200% text the
 * three segments are wider than a 390dp phone, and [NeedlerSegmentedTabs] is a fixed `Row` that
 * would clip the last one in half. Scrolling keeps every option reachable by touch and by TalkBack
 * without changing how the control looks at normal sizes, where it never overflows. The component
 * itself wants to handle this; that is already a handover note against `:core:design`.
 *
 * The group carries an `aria-label` equivalent so TalkBack says what is being chosen before it says
 * which one is chosen — "Request list, Pulls, tab, 1 of 3, selected".
 */
@Composable
private fun LaneTabs(lane: PullsLane, onSelectLane: (PullsLane) -> Unit) {
    NeedlerSegmentedTabs(
        options = PullsLane.labels,
        selectedIndex = lane.ordinal,
        onSelect = { index -> onSelectLane(PullsLane.entries[index]) },
        label = "Request list",
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    )
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

/**
 * `PULLS`, the showing lane's count, and the one control that lane has.
 *
 * The count is a polite live region: it changes under the user as the poller
 * lands, and a screen reader that has just been told "2 in progress" should hear
 * the new figure rather than keep the old one. It is also the only place on the
 * screen where a list's size is stated, which makes it the line worth
 * announcing. It now changes on a tab tap as well, which is the other reason to
 * announce it — the figure under the title is how a listener knows the tab took.
 *
 * One action slot, and which control fills it is the lane's business:
 * **Clear done** on the queue, **Refresh** on the two lanes that are fetched
 * rather than polled, nothing when neither applies. Drawing both would put two
 * pills in a 390dp header beside a title, and at 200% text there is not room
 * for one of them and the title.
 */
@Composable
private fun PullsHeader(
    state: PullsUiState,
    onClearDone: () -> Unit,
    onRefreshLane: () -> Unit,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.step6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.step2),
        ) {
            Text(
                text = TITLE,
                style = typography.screenTitle,
                color = colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            // Nothing at all is better than an empty line: every lane has its
            // own empty state and none of them needs a subtitle saying zero.
            if (state.laneHeaderLine.isNotBlank()) {
                Text(
                    text = state.laneHeaderLine,
                    style = typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = state.laneHeaderLine
                    },
                )
            }
        }
        when {
            state.showClearDone -> RowAction(label = "Clear finished pulls from this list") {
                NeedlerPillButton(
                    text = CLEAR_DONE,
                    onClick = onClearDone,
                )
            }

            state.showRefresh -> RowAction(label = refreshLabel(state.lane)) {
                NeedlerPillButton(
                    text = REFRESH,
                    onClick = onRefreshLane,
                    // Disabled while the fetch it would start is already running, so a second tap
                    // cannot queue a second call against a lane that answers in one.
                    enabled = !state.laneFetching,
                )
            }
        }
    }
}

/** What TalkBack hears on **Refresh**, which on its own does not say what it refreshes. */
private fun refreshLabel(lane: PullsLane): String = when (lane) {
    PullsLane.QUEUE -> "Refresh"
    PullsLane.HISTORY -> "Ask the server for your request history again"
    PullsLane.WANTED -> "Ask the server for the wanted list again"
}

// ---------------------------------------------------------------------------
// The queue
// ---------------------------------------------------------------------------

/**
 * The phone and foldable layout: one scrolling list with the pack's two headings.
 *
 * An empty section keeps its heading and says it is empty, exactly as a tablet pane does. The two
 * widths used to disagree — the tablet kept a half-width column holding one sentence and the phone
 * dropped the whole section silently — so a user who turned their tablet found a section they had
 * been reading simply gone. REQUIREMENTS.md, "Tablet layout": "This is one navigation model at two
 * widths … Nothing is tablet-only, so no feature needs building twice."
 */
@Composable
private fun PullsList(
    state: PullsUiState,
    gutter: Dp,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onPlayAlbum: (ReleaseGroupMbid) -> Unit,
    onCancel: (Pull) -> Unit,
    onRetry: (Pull) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            end = gutter,
            bottom = NeedlerTheme.spacing.step12,
        ),
    ) {
        pullSection(
            heading = HEADING_NOW,
            pulls = state.active,
            now = state.renderedAt,
            busy = state.busy,
            emptyLine = EMPTY_NOW,
            onOpenAlbum = onOpenAlbum,
            onPlayAlbum = onPlayAlbum,
            onCancel = onCancel,
            onRetry = onRetry,
        )
        pullSection(
            heading = HEADING_EARLIER,
            pulls = state.earlier,
            now = state.renderedAt,
            busy = state.busy,
            emptyLine = EMPTY_EARLIER,
            onOpenAlbum = onOpenAlbum,
            onPlayAlbum = onPlayAlbum,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}

/**
 * The tablet layout: the pack's two blocks as two panes.
 *
 * REQUIREMENTS.md, "Tablet layout": "This is one navigation model at two widths,
 * driven by `WindowSizeClass`. Nothing is tablet-only, so no feature needs
 * building twice." These are the same two sections, the same rows and the same
 * actions, laid beside each other instead of above — which is what the width is
 * for. Stretching one column of 56dp thumbnails across a 780dp content pane
 * would use the space without spending it on anything.
 *
 * Unlike the phone list, an empty section here keeps its heading and says it is
 * empty. A pane that simply vanished would leave the other one looking
 * off-centre and the user wondering which half they were reading.
 */
@Composable
private fun PullsPanes(
    state: PullsUiState,
    gutter: Dp,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onPlayAlbum: (ReleaseGroupMbid) -> Unit,
    onCancel: (Pull) -> Unit,
    onRetry: (Pull) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter),
        horizontalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step16),
    ) {
        PullsPane(
            heading = HEADING_NOW,
            pulls = state.active,
            emptyLine = EMPTY_NOW,
            state = state,
            modifier = Modifier.weight(1f),
            onOpenAlbum = onOpenAlbum,
            onPlayAlbum = onPlayAlbum,
            onCancel = onCancel,
            onRetry = onRetry,
        )
        PullsPane(
            heading = HEADING_EARLIER,
            pulls = state.earlier,
            emptyLine = EMPTY_EARLIER,
            state = state,
            modifier = Modifier.weight(1f),
            onOpenAlbum = onOpenAlbum,
            onPlayAlbum = onPlayAlbum,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}

@Composable
private fun PullsPane(
    heading: String,
    pulls: List<Pull>,
    emptyLine: String,
    state: PullsUiState,
    modifier: Modifier = Modifier,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onPlayAlbum: (ReleaseGroupMbid) -> Unit,
    onCancel: (Pull) -> Unit,
    onRetry: (Pull) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = NeedlerTheme.spacing.step12),
    ) {
        pullSection(
            heading = heading,
            pulls = pulls,
            now = state.renderedAt,
            busy = state.busy,
            emptyLine = emptyLine,
            onOpenAlbum = onOpenAlbum,
            onPlayAlbum = onPlayAlbum,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}

/**
 * One of the pack's blocks: an overline and its rows.
 *
 * Written as a `LazyListScope` extension rather than a composable so both
 * layouts build their lists from the same code — the phone puts two of these in
 * one column, the tablet puts one in each of two.
 *
 * @param emptyLine what to say when the section has nothing in it. Both layouts now pass one: the
 *   phone used to pass `null` and drop the heading with it, which is the disagreement with the
 *   tablet that [PullsList] records.
 */
private fun LazyListScope.pullSection(
    heading: String,
    pulls: List<Pull>,
    now: Instant,
    busy: Boolean,
    emptyLine: String,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onPlayAlbum: (ReleaseGroupMbid) -> Unit,
    onCancel: (Pull) -> Unit,
    onRetry: (Pull) -> Unit,
) {
    item(key = "heading-" + heading) {
        NeedlerSectionHeader(title = heading)
    }

    if (pulls.isEmpty()) {
        item(key = "empty-" + heading) {
            Text(
                text = emptyLine,
                style = NeedlerTheme.typography.caption,
                color = NeedlerTheme.colors.textMuted,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }

    // The key is prefixed with the heading because a pull moves from one section
    // to the other as it finishes, and an unprefixed key would make Compose try
    // to reuse a row that has changed shape entirely.
    items(items = pulls, key = { pull -> heading + "-" + pull.releaseGroupMbid.value }) { pull ->
        PullRow(
            pull = pull,
            now = now,
            busy = busy,
            onOpenAlbum = onOpenAlbum,
            onPlayAlbum = onPlayAlbum,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }

    item(key = "gap-" + heading) {
        Spacer(modifier = Modifier.height(NeedlerTheme.spacing.sectionGap))
    }
}

@Composable
private fun PullRow(
    pull: Pull,
    now: Instant,
    busy: Boolean,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onPlayAlbum: (ReleaseGroupMbid) -> Unit,
    onCancel: (Pull) -> Unit,
    onRetry: (Pull) -> Unit,
) {
    when (pull.bucket) {
        PullBucket.ACTIVE -> ActivePullRow(
            pull = pull,
            now = now,
            busy = busy,
            onOpenAlbum = onOpenAlbum,
            onCancel = onCancel,
        )

        PullBucket.COMPLETED, PullBucket.FAILED -> FinishedPullRow(
            pull = pull,
            now = now,
            busy = busy,
            onOpenAlbum = onOpenAlbum,
            onPlayAlbum = onPlayAlbum,
            onRetry = onRetry,
        )
    }
}

/**
 * A pull the server is still working on: the pack's `NOW` rows.
 *
 * Two shapes, both drawn on screen 06 and both produced by the same code. A task
 * the server has reported progress for gets the filled ring and the bar; one it
 * has not - searching, queued, parked for approval - gets plain artwork. The
 * difference is entirely whether
 * [app.needler.core.domain.model.PullProgress.fraction] has an answer, which is
 * the one place that decides between `progress_percent`, the byte counters and
 * the file counters.
 *
 * The chip is drawn either way. It used to be replaced by a bare `62%` whenever
 * there was a percentage, which left the one row actually in flight as the only
 * row on the list whose state the trailing column did not name - visible on the
 * top row of `screenshots/pulls-queue-phone.png`. [NeedlerAlbumBadge.Pulling]
 * carries the figure itself, so nothing is lost by keeping the word beside it.
 *
 * It is *plain* artwork rather than an empty ring, which is what this row drew
 * until a device showed 35 rings at zero over 35 pulls that were not
 * downloading. [PullArtwork] carries that argument.
 *
 * Tapping the row opens the album rather than doing anything to the pull. The
 * album screen is where a pull can be watched, cancelled and retried with room
 * to explain itself; this screen is the list.
 */
@Composable
private fun ActivePullRow(
    pull: Pull,
    now: Instant,
    busy: Boolean,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onCancel: (Pull) -> Unit,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val sizes = NeedlerTheme.sizes
    val fraction: Float? = pull.progress.fraction
    val spoken: String = PullsFormat.spokenRow(pull, now)
    val cancelLabel: String = "Stop the pull of " + PullsFormat.albumPhrase(pull)
    val cancellable: Boolean = pull.canStop
    // The only pull row that is not `NeedlerAlbumRow`, because the progress bar belongs inside the
    // text column and that component has no slot under its subtitle. It therefore has to apply
    // `NeedlerRowLayout` itself, and `screenshots/pulls-large-text-phone.png` is what happens when
    // it does not: `FinishedPullRow` next to it stacked and wrapped `Two Star & The Dream Police`
    // in full, while this row still drew `Black Clas...` over `Yussef Daye...` beside an unweighted
    // status block.
    val stacked: Boolean = NeedlerRowLayout.stacksTrailing
    val statusAction: @Composable () -> Unit = {
        if (cancellable) {
            RowAction(label = cancelLabel) {
                NeedlerPillButton(
                    text = "Stop",
                    onClick = { onCancel(pull) },
                    enabled = !busy,
                )
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = sizes.albumRowMinHeight)
            .clickable(role = Role.Button, onClick = { onOpenAlbum(pull.releaseGroupMbid) })
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                // Belt and braces. The pill below is its own accessibility node,
                // but a merged row is one focus target and a custom action is
                // the reading that cannot be missed from the TalkBack menu.
                if (cancellable) {
                    customActions = listOf(
                        CustomAccessibilityAction(cancelLabel) {
                            onCancel(pull)
                            true
                        },
                    )
                }
            }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `fraction`, not `fraction ?: 0f`. See [PullArtwork].
        PullArtwork(pull = pull, ring = fraction)
        Column(
            modifier = Modifier.weight(
                if (stacked) 1f else NeedlerRowLayout.TITLE_WEIGHT,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = PullsFormat.albumTitle(pull),
                style = typography.rowTitle,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rowSubtitle(pull, now),
                style = typography.meta,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (fraction != null) {
                Spacer(modifier = Modifier.height(6.dp))
                NeedlerLinearProgress(
                    progress = fraction,
                    // The server's colour, not the device's. See [PullArtwork].
                    color = colors.accent,
                    // No percentage in here: the value is announced from the
                    // bar's own range info, and the row already says it once.
                    contentDescription = "Pull progress",
                )
            }
            // Below the title, under the bar it refers to, and across the row's full width.
            if (stacked) {
                Spacer(modifier = Modifier.height(6.dp))
                StatusColumn(chip = chipFor(pull), action = statusAction)
            }
        }
        if (!stacked) {
            StatusColumn(
                chip = chipFor(pull),
                modifier = Modifier
                    .weight(NeedlerRowLayout.TRAILING_WEIGHT, fill = false)
                    .align(Alignment.Top),
                action = statusAction,
            )
        }
    }
}

/**
 * A pull the server has finished with: the pack's `EARLIER` rows.
 *
 * A finished one gets the `Ready` badge and a **Play** pill; anything in the
 * failed bucket gets its state as a badge, its reason in the subtitle and a
 * **Retry**.
 *
 * ## Every row names its state
 *
 * This row used to draw no badge at all in the failed bucket, on the argument
 * that the reason in the subtitle was already the label and a badge beside it
 * would say the same thing twice. That was wrong in two ways. The trailing
 * column is where this list puts state — `Pulling 62%`, `Searching`, `Ready` —
 * so a row that left it empty but for a Retry pill made the one outcome a user
 * most needs to spot the only one they had to read prose to find. And the three
 * end states are not one state: "no source found", "some tracks did not arrive"
 * and "cancelled" are different outcomes with different next steps, and the
 * badge is what separates them at a glance.
 *
 * The split that avoids the repetition is the one [PullsFormat.stateDetail]
 * already uses everywhere else: the badge carries the state's **name**, the
 * subtitle carries the **explanation**. So a cancelled pull no longer repeats
 * "cancelled" in its line, and a part-delivered one says how far it got.
 *
 * A part-delivered pull sits in the failed bucket and offers **Retry**, but it
 * is still an album that plays — REQUIREMENTS.md, "Partial content is a normal
 * state" — and tapping the row opens it, where the missing tracks are listed in
 * their right positions with their own retries.
 */
@Composable
private fun FinishedPullRow(
    pull: Pull,
    now: Instant,
    busy: Boolean,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onPlayAlbum: (ReleaseGroupMbid) -> Unit,
    onRetry: (Pull) -> Unit,
) {
    val ready: Boolean = pull.state == PullState.COMPLETED
    NeedlerAlbumRow(
        title = PullsFormat.albumTitle(pull),
        subtitle = rowSubtitle(pull, now),
        onClick = { onOpenAlbum(pull.releaseGroupMbid) },
        contentDescription = PullsFormat.spokenRow(pull, now),
        artwork = { PullArtwork(pull = pull, ring = null) },
        trailing = {
            StatusColumn(
                chip = chipFor(pull),
                modifier = Modifier.align(Alignment.Top),
            ) {
                if (ready) {
                    RowAction(label = "Play " + PullsFormat.albumPhrase(pull)) {
                        NeedlerPrimaryButton(
                            text = "Play",
                            onClick = { onPlayAlbum(pull.releaseGroupMbid) },
                            size = NeedlerButtonSize.Compact,
                            leadingIcon = { tint -> NeedlerPlayIcon(tint = tint, size = 14.dp) },
                        )
                    }
                } else if (pull.canRetry) {
                    RowAction(label = "Retry the pull of " + PullsFormat.albumPhrase(pull)) {
                        NeedlerPillButton(
                            text = "Retry",
                            onClick = { onRetry(pull) },
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
 * The 56dp square, with the pack's scrim and progress ring over it while a pull
 * is running.
 *
 * The artwork model is [ArtworkRef.Catalogue]: a pull is, by definition, an
 * album the server did not have when it was requested, so the Subsonic
 * `getCoverArt` lane has nothing to answer with until it lands. As in
 * `:feature:library`, the ref is handed to Coil unresolved — turning it into a
 * URL needs the server address and `/api/v1`, neither of which a feature module
 * can see — and **no mapper exists yet**, so every square currently renders as
 * the placeholder tint. That is a handover note, not a defect in this screen.
 *
 * ## Unknown is not zero, here as everywhere else
 *
 * This used to be called with `fraction ?: 0f`, so an active pull the server had
 * reported no progress for drew the scrim and a ring at zero. A device with 35
 * pulls, every one of them parked for a manual source pick and none of them
 * downloading, showed 35 grey rings over 35 covers - a progress indicator for a
 * state that has no progress, which reads as a stalled download or as a
 * rendering fault.
 *
 * `PullsFormat`'s first stated rule is the one that was being broken: "**Unknown
 * is not zero.** A pull with no byte counters, no file counters and no
 * `progress_percent` draws no bar and no percentage rather than a convincing
 * `0%`." The bar and the percentage already obeyed it - the row draws neither
 * when [app.needler.core.domain.model.PullProgress.fraction] has no answer - and
 * the artwork was the one place that did not. It does now.
 *
 * The scrim goes with the ring rather than staying on its own. Its whole job is
 * to make a ring legible over a cover; with no ring to carry, it is a cover
 * dimmed for no reason, and it was dimming precisely the rows the user is being
 * asked to go and do something about.
 *
 * @param ring `null` for a finished pull, which the pack draws with plain
 *   artwork, and for an active one whose progress the server has not reported.
 *   A figure draws the pack's scrim and ring over the cover.
 */
@Composable
private fun PullArtwork(pull: Pull, ring: Float?) {
    val shape = NeedlerTheme.shapes.artworkThumb
    Box(
        modifier = Modifier.size(NeedlerTheme.sizes.artworkRow),
        contentAlignment = Alignment.Center,
    ) {
        AsyncAlbumArt(
            model = ArtworkRef.Catalogue(pull.releaseGroupMbid),
            // The row already names the album; a second reading would have
            // TalkBack say it twice.
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            shape = shape,
        )
        if (ring != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(NeedlerTheme.colors.artworkScrimStrong),
                contentAlignment = Alignment.Center,
            ) {
                // Accent, not the pack's green. See the hue note on [PullsScreen].
                NeedlerProgressRing(progress = ring, color = NeedlerTheme.colors.accent)
            }
        }
    }
}

/**
 * The row's second line: **the artist**, and whatever else fits beside the artist.
 *
 * Normally [PullsFormat.subtitle] verbatim, with two departures, both of them the same defect in
 * two shapes. A device with 35 pulls parked for a manual source pick drew the first on all 35 rows:
 *
 * ```
 * Death's Dateless Night              Needs attention on the server
 * Paul Kelly · a source
 * needs picking on th...                               [ Cancel ]
 * ```
 *
 * and, once the state was suppressed, the second on the same rows:
 *
 * ```
 * Death's Dateless Night              Needs attention on the server
 * Paul Kelly · Try MP3
 * 320-plus kbps, the...                                [ Cancel ]
 * ```
 *
 * The slot had not changed; only which sentence was being poured into it. So the rule is about the
 * slot. **The subtitle is the artist's line**, and a part joins it only when it is short enough to
 * share it: a badge's own explanation is dropped by [badgeSaysTheDetail], and a server-written
 * quality sentence by [qualityProse], which carries the measurements and says where that string is
 * drawn in full instead.
 *
 * ## Why this is a narrow fix and not "put only the artist in the subtitle"
 *
 * Dropping every state from every subtitle was the other option and it is wrong. The split this
 * screen uses everywhere — stated in [FinishedPullRow]'s KDoc and in `PullsFormat.stateDetail` —
 * is that **the badge carries the state's name and the subtitle carries the explanation**, and on
 * nine of the ten states those are different facts that the user needs both of: `Pulling` with
 * "12 of 19 files", `Searching` with "asking slskd", `Failed` with "no source found". Deleting the
 * second of each pair to fix the one row that repeats itself would cost the screen most of what it
 * says.
 *
 * So the rule is the narrow one, and it is the rule `PullsFormat.spokenRow` already applies for the
 * identical reason — it filters the state out of its own detail parts, with a comment about
 * "cancelled, cancelled". A screen reader was protected from this and a sighted user was not.
 *
 * ## The other half, since fixed
 *
 * `NeedlerAlbumBadge.NeedsAttention` used to render the sentence "Needs attention on the server" in
 * a 13sp/600 trailing column, which is what squeezed the subtitle into two narrow lines in the first
 * place - this file could only stop *adding* to a slot the badge had already taken. That string was
 * `:core:design`'s and was shortened to "Needs attention" on 2026-10-04, returning 81dp to the text
 * column and with it every parked title that had been ellipsised. The full sentence survives in the
 * badge's spoken label, so nothing was lost but the width.
 */
internal fun rowSubtitle(pull: Pull, now: Instant): String {
    val stated: String? = PullsFormat.stateDetail(pull).takeIf { badgeSaysTheDetail(pull) }
    val prose: String? = qualityProse(pull)
    val parts: List<String> = buildList {
        // Not the artist when the title slot is already drawing it: `PullsFormat.albumTitle` now
        // promotes the artist into the title of a row the mirror could not name, and the line would
        // otherwise repeat the one name the row has.
        PullsFormat.artistBeside(PullsFormat.albumTitle(pull), pull.artistName)?.let { add(it) }
        // `stated` is exactly the filter `PullsFormat.spokenRow` applies to the same list, so a
        // repeat is dropped from both; `prose` is this line's alone, because the spoken reading has
        // no column to overflow and is the one place the whole sentence still lands.
        addAll(PullsFormat.detailParts(pull, now).filterNot { it == stated || it == prose })
    }
    if (parts.isNotEmpty()) return parts.joinToString(separator = " · ")
    // Suppressing something must never empty the line. A pull whose album the mirror cannot name an
    // artist for has nothing else to say, and a blank second line under a title is the hole this
    // screen was once unusable for - see `PullsFormat.albumTitle`. Better to repeat the badge, or
    // even to draw the long sentence, than to draw nothing, so in that one case what was taken out
    // is given back - the shorter of the two first.
    return stated ?: prose ?: PullsFormat.subtitle(pull, now)
}

/**
 * `quality_snapshot_summary` when the server sent a sentence rather than a label, which this row
 * does not draw.
 *
 * ## The slot is one line and the server writes to no length
 *
 * The device measured it. `screenshots/pulls-queue-phone.png` and
 * `screenshots/pulls-all-awaiting-review-phone.png` are the same row at its two widths: the text
 * column is about 198dp beside a short trailing column. It was about 70dp beside the then
 * sentence-long `Needs attention on the server` badge - roughly 34 and 12 characters of 13sp `meta` -
 * and is about 155dp now that the badge draws "Needs attention". The slot is the artist's, and
 * whatever shares it has one line at best even at the recovered width.
 *
 * Every other part of [PullsFormat.detailParts] is written to fit that: the counters are figures,
 * the relative day is two words, and the state explanations are this app's own wording, the longest
 * of them 33 characters. `quality_snapshot_summary` is the one part the **server** writes, with no
 * length contract anywhere in the API, and a server that answered `Try MP3 320-plus kbps, the
 * server will keep looking for FLAC` put a 60-character sentence in a 12-character slot. It drew as
 * `320-plus kbps, the...`, which tells the reader nothing while costing the row its second line.
 *
 * ## Which is why the advice goes where the requirement already puts it
 *
 * REQUIREMENTS.md "Design pack discrepancies" settles where this string is shown: "the album
 * screen, the request sheet, and the pull's own detail". A row in a list is none of the three. All
 * three draw it as wrapping body copy with room for a sentence - `AlbumScreen` renders it under the
 * **Pull** button and `RequestSheetState.qualityNote` carries it into the sheet - and tapping this
 * row opens exactly that album screen. Nothing is lost by leaving it out here, and a reader who
 * wants it is one tap from all of it.
 *
 * It is also still **spoken** in full: the row's content description is `PullsFormat.spokenRow`,
 * which composes the same detail parts and is not touched by this. A screen reader has no column to
 * run out of, so the one reading that can carry a sentence keeps it.
 *
 * A label still draws, because a label fits and the pack asks for it: screen 06 writes `FLAC` and
 * `MP3 320` on its finished rows, and those arrive in this very field. The cut is at
 * [QUALITY_LABEL_MAX_CHARS], which is about the slot rather than about the string's grammar - there
 * is no reliable way to tell prose from a label by punctuation, and a 24-character label is one
 * that can still share a line with an artist's name.
 *
 * ## Rejected
 *
 *  * **A third line in the row.** [ActivePullRow] lays out its own column and could take one;
 *    [FinishedPullRow] is `NeedlerAlbumRow`, whose column is fixed at title and subtitle, and
 *    `:core:design` is not this module's to change. The advice would appear on active rows and
 *    vanish on finished ones, which is worse than consistent absence - and a taller row makes the
 *    clipping at the foot of the list, which a device already reported, strictly worse.
 *  * **Dropping the field from the row outright.** It would take `FLAC` and `MP3 320` off the
 *    finished rows with it, which is the pack's own content and fits the slot it is drawn in.
 *  * **A blanket cap on every detail part.** The failure reason can be [Pull.error], which the
 *    server also writes freely - but it is the only account of a failure the user will ever get,
 *    and `PullsFormat.failureReason` says so. A truncated reason beats no reason; truncated advice
 *    about what to request next time does not beat the full copy of it one tap away.
 */
private fun qualityProse(pull: Pull): String? = pull.qualityPolicySummary
    ?.takeIf { it.isNotBlank() && it.trim().length > QUALITY_LABEL_MAX_CHARS }

/**
 * The longest `quality_snapshot_summary` this row will draw.
 *
 * 24 characters is what fits beside an artist's name on the wider of the two slots measured in
 * [qualityProse]: about 34 characters of 13sp `meta`, less a twelve-character artist and the
 * three-character separator. `FLAC` and `MP3 320` are 4 and 7; the sentence a device sent was 60.
 */
private const val QUALITY_LABEL_MAX_CHARS: Int = 24

/**
 * Whether the badge beside this row already says what [PullsFormat.stateDetail] would say.
 *
 * One exhaustive `when` rather than a string comparison, and it is the chip table
 * (`app.needler.feature.pulls.common.chipFor`) read a second way: that one picks the chip, this one
 * records whether the chip it picked leaves the subtitle anything to add. Exhaustive so that a new [PullState] stops this
 * file compiling and has to be decided rather than defaulted — which is the guarantee a heuristic
 * over the two strings could not give, since one of them belongs to `:core:design` and the other to
 * `PullsFormat`, and neither is this work's to change.
 *
 * Only one state answers true, and it is the one a device found: `AWAITING_SOURCE_REVIEW` badges as
 * "Needs attention on the server" and details as "a source needs picking on the server" — the same
 * sentence twice, and the copy that got truncated was the one under the title.
 *
 * Every other state's detail is genuinely more than its badge, and the two are both needed:
 * `Pulling` does not say "12 of 19 files", `Searching` does not say which source is being asked,
 * `Waiting` does not say it is an administrator being waited on, and `Failed` does not say no
 * source was found. Suppressing those would fix one row by emptying nine.
 */
internal fun badgeSaysTheDetail(pull: Pull): Boolean = when (pull.state) {
    PullState.AWAITING_SOURCE_REVIEW -> true

    PullState.PENDING_APPROVAL,
    PullState.SEARCHING,
    PullState.QUEUED,
    PullState.DOWNLOADING,
    PullState.PROCESSING,
    PullState.COMPLETED,
    PullState.FAILED,
    PullState.PARTIAL,
    PullState.CANCELLED -> false
}

/**
 * The trailing column of every row on this screen: one word, and at most one control under it.
 *
 * ## Why it is a fixed width and aligned to the top
 *
 * It was neither, and a reviewer measured what that costs. Each row laid its own trailing column
 * out independently and right-aligned it, so seven labels of six different lengths began at six
 * different x positions down one list — `62%`, `Searching`, `Waiting`, `Needs attention`,
 * `Ready`, `Failed`, `Partly delivered` in `screenshots/pulls-every-state-phone.png`, with nothing
 * for the eye to run down. And the column was centred in a row whose height depends on whether a
 * pill follows the word, so the word's own baseline moved from row to row as well.
 *
 * One width fixes the first: the column is the same box on every row, so every word starts at the
 * same x. [Alignment.Top] fixes the second: the word sits against the top of the row beside the
 * title it describes, and a pill appearing beneath it no longer shifts it.
 *
 * [STATUS_COLUMN_DP] is 124dp at the default text size: the widest label is `Needs attention` with
 * its 16dp clock, and 112dp - the arithmetic width - wrapped it onto two lines on every row of
 * `screenshots/pulls-all-awaiting-review-phone.png`, so the figure is the measured one with slack.
 * It leaves the text column 142dp of the 266dp a 390dp phone row has to divide, and every line this
 * module composes is written to fit that - see `PullsFormat.stateDetail`, where two were shortened
 * for it. The width follows the font scale so a 200% reader gets a column the words still fit in,
 * capped because past about 1.5x the title is the thing that needs the room.
 *
 * Rejected: `widthIn(min = ...)`. Each row is its own `Row`, so a minimum still lets one long label
 * widen one row's column and move that row's words — which is the ragged edge, not a fix for it.
 *
 * ## Past `NeedlerRowLayout.STACK_ABOVE_FONT_SCALE` it is not a column at all
 *
 * The scaling width above is the right answer only while this sits *beside* a title. Past that
 * threshold the rows it belongs to draw it **below** the title at the row's full width, and a
 * 124dp-times-scale box inside a full-width slot is a narrow column with dead space to the right of
 * it - which is what `screenshots/pulls-large-text-phone.png` showed for the settled rows.
 *
 * So the alignment argument above is suspended exactly when the thing it aligns against is gone:
 * stacked, the chip and its action sit side by side across the width. There is no ragged edge to
 * prevent, because every stacked block starts at the same x already - the row's left margin.
 */
@Composable
internal fun StatusColumn(
    chip: PullsChip,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit,
) {
    if (NeedlerRowLayout.stacksTrailing) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateChip(chip = chip)
            action()
        }
        return
    }
    val scale: Float = LocalDensity.current.fontScale.coerceIn(1f, STATUS_COLUMN_MAX_SCALE)
    Column(
        modifier = modifier.width(STATUS_COLUMN_DP * scale),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StateChip(chip = chip)
        action()
    }
}

/**
 * One chip, from either half of [PullsChip].
 *
 * The badge half is `:core:design`'s own component. The other half is the one word that module has
 * no badge for, drawn in the same 13sp/600 and the same secondary tint the glyph-less badges beside
 * it use, with its text read from `NeedlerAlbumSource` rather than written here — REQUIREMENTS.md,
 * "Where a record is": "nothing is allowed to write one of these words as a literal". See
 * [PullsChip.NotRetrieved] for why this screen needs it and `:core:design` does not yet have it.
 */
@Composable
private fun StateChip(chip: PullsChip) {
    when (chip) {
        is PullsChip.Badge -> NeedlerStateBadge(badge = chip.badge)

        PullsChip.NotRetrieved -> Text(
            text = chip.word,
            style = NeedlerTheme.typography.metaStrong,
            // No hue, because the state has none: REQUIREMENTS.md gives `Server` the accent and
            // `Device` the positive and leaves this one uncoloured, since a record that is nowhere
            // is not somewhere in a third colour.
            color = NeedlerTheme.colors.textSecondary,
            maxLines = 2,
        )
    }
}

/** The widest chip label at the default text size, measured rather than computed. See [StatusColumn]. */
private val STATUS_COLUMN_DP: Dp = 124.dp

/** Past this the title needs the width more than the chip does. See [StatusColumn]. */
private const val STATUS_COLUMN_MAX_SCALE: Float = 1.5f

/**
 * Keeps an interactive control inside a merged row reachable on its own.
 *
 * [NeedlerAlbumRow] and [ActivePullRow] both merge their descendants, so that
 * TalkBack reads a row as one sentence instead of five fragments. A merge
 * swallows any descendant that is not itself a merging boundary — which is what
 * a bare button is — and the control then cannot be activated separately from
 * the row. Wrapping it in a node that merges makes it a boundary, which is the
 * same trick `NeedlerIconButton` plays on itself.
 */
@Composable
private fun RowAction(label: String, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = label
        },
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Notices, loading and empty
// ---------------------------------------------------------------------------

/**
 * The standing facts about this screen, as one box of at most two short lines.
 *
 * ## Why one box
 *
 * There used to be three, and they stacked. `screenshots/pulls-held-offline-phone.png` spends about
 * 300px of a 844px phone on two visually identical bordered boxes before a single row is drawn, and
 * on the same screen the offline fact was told three different ways: an inline banner on the queue,
 * a differently worded one on the two read-through lanes, and a full-screen takeover on a lane with
 * nothing cached. Three treatments and three wordings for one fact is three things to read and
 * learn instead of one.
 *
 * So: one component, one wording per fact, and a hard cap of two lines, which is what stops the
 * header growing without limit as facts accumulate. [bannerLines] decides which two.
 *
 * REQUIREMENTS.md, "Offline is a first-class state, not an error" is why none of this is dismissible
 * and none of it is tinted as a problem: these say what is true, and the rows below them are still
 * worth reading.
 *
 * Rejected: keeping a separate box per fact and simply shortening each. Two boxes of one line are
 * still two borders, two paddings and two things that look like warnings, and the stacking returns
 * the moment a third fact is true.
 */
@Composable
private fun HeaderBanner(lines: List<String>) {
    if (lines.isEmpty()) return
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    val spoken: String = lines.joinToString(separator = " ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lines.forEach { line ->
            Text(
                text = line,
                style = NeedlerTheme.typography.caption,
                color = colors.textSecondary,
                overflow = TextOverflow.Visible,
            )
        }
    }
}

/**
 * Which standing facts the header states, newest-constraint first, and never more than two.
 *
 * The order is what a user can do about each. There is no point telling someone twelve pulls need a
 * source picking on a server they currently cannot reach, so offline leads; the parked count comes
 * next because it is about rows that are on screen; the held count comes last because it is a
 * figure about rows that are not.
 *
 * **The parked line is new and it is the one a device needed.**
 * `screenshots/pulls-all-awaiting-review-phone.png` is twelve rows every one of which reads
 * `Needs attention`, under a header that claimed "12 in progress", with no banner at all - so
 * nothing on the screen said that the thing to do was open DroppedNeedle's web interface, and the
 * one component that says exactly that was drawn only for a different population. `held_count` and
 * the parked rows are two populations, counted by two sources, and `PullsLane`'s own KDoc requires
 * each figure to name its own: hence two sentences rather than one summed figure.
 *
 * Both queue-only. REQUIREMENTS.md, "Queue screen requirements" item 5 takes `held_count` from the
 * download activity summary, and a history entry or a watch can be neither held nor parked.
 */
private fun bannerLines(state: PullsUiState): List<String> = buildList {
    if (state.showOfflineBanner) add(OFFLINE_NOTE)
    if (state.lane == PullsLane.QUEUE) {
        if (state.waitingCount > 0) add(parkedNote(state.waitingCount))
        if (state.heldCount > 0) add(heldNote(state.heldCount))
    }
}.take(MAX_BANNER_LINES)

/**
 * The parked line: what the twelve rows are waiting for, and who can move them.
 *
 * REQUIREMENTS.md, "Acquisition lifecycle", derives this state client-side precisely because "a
 * human has to pick a source in DroppedNeedle's own web interface", and item 6 adds the approval
 * queue. Neither is actionable from this app, so the sentence says where it is actionable rather
 * than offering a control that cannot work - the same decision [heldNote] records.
 */
private fun parkedNote(count: Int): String =
    PullsFormat.plural(count.toLong(), "pull") +
        " need an approval or a source pick, both done in DroppedNeedle's web interface."

/**
 * Held and quarantined items, as a count and a sentence.
 *
 * REQUIREMENTS.md, "Queue screen requirements" item 5: "Surface `held_count`
 * from the activity summary as a read-only notice. Held and quarantined items
 * need the web UI in v1." Read-only is the whole of it - there is no endpoint in
 * `PullRepository` that could resolve one, so offering a button here would be
 * offering something that cannot work. Saying where the work has to happen is
 * more use than saying nothing.
 */
private fun heldNote(count: Int): String =
    PullsFormat.plural(count.toLong(), "item") +
        " held for review, released only from DroppedNeedle's web interface."

/** Two lines of caption is about 90px; three is a header taller than the first row under it. */
private const val MAX_BANNER_LINES: Int = 2

/**
 * The result of the last action: dismissible, assertive, and the one thing here that is not grey.
 *
 * It is drawn as a filled card with a tinted bar down its leading edge rather than as a hairline
 * box. `screenshots/pulls-problem-phone.png` is why: the error from a refused cancel sat *below* a
 * standing notice, in the same bordered box, in the same secondary text colour, distinguished only
 * by the outline being a different hue - which is colour as the sole channel, and a channel the
 * green and the blue on this screen already measure 1.01:1 apart on. A fill, a bar and a dismiss
 * control are three differences that are not colour.
 *
 * It also sits above the standing banner now. This is the only thing on the screen the user caused,
 * and it is the only one that goes away.
 */
@Composable
private fun NoticeCard(notice: PullsNotice, onDismiss: () -> Unit) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    // Accent for an accepted cancel, retry or clear: all three are things the *server* has agreed
    // to, and this screen's green now means on this device and nothing else.
    val tint = if (notice.isProblem) colors.destructive else colors.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceRaised)
            .padding(end = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = notice.message
                liveRegion = LiveRegionMode.Assertive
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(NOTICE_BAR_DP)
                .height(NOTICE_BAR_HEIGHT_DP)
                .background(tint),
        )
        Text(
            text = notice.message,
            style = NeedlerTheme.typography.caption,
            color = colors.textPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
        )
        NeedlerIconButton(
            contentDescription = "Dismiss",
            onClick = onDismiss,
            visualSize = 36.dp,
        ) {
            NeedlerStrokeIcon(pathData = PathClose, tint = colors.textMuted, size = 16.dp)
        }
    }
}

/** The notice's leading bar: wide enough to read as a deliberate mark, not as a border. */
private val NOTICE_BAR_DP: Dp = 4.dp

/** Tall enough to cover a two-line message, which is the longest `ProblemMessages` writes. */
private val NOTICE_BAR_HEIGHT_DP: Dp = 56.dp

/**
 * The loading state.
 *
 * Empty rows rather than a spinner, because what is being waited for is a local
 * database read that finishes in single-digit milliseconds — a spinner would
 * flash and be gone. The blocks hold the layout still so the list does not jump
 * when the first rows arrive.
 */
@Composable
private fun PullsSkeleton(gutter: Dp) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter)
            .semantics(mergeDescendants = true) {
                contentDescription = "Loading your pulls"
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        repeat(5) {
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
                            .fillMaxWidth(0.6f)
                            .height(12.dp)
                            .clip(NeedlerTheme.shapes.progress)
                            .background(colors.surface),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .height(10.dp)
                            .clip(NeedlerTheme.shapes.progress)
                            .background(colors.surface),
                    )
                }
            }
        }
    }
}

/**
 * Nothing has ever been pulled.
 *
 * ## It now offers the thing it names
 *
 * The copy read "Find an album under Search and tap Pull" and there was no way to get to Search
 * from it, on the argument that "the way out of this state is on another screen, so the copy names
 * it rather than offering a button that would only navigate". A button that only navigates is
 * exactly what an empty state is for: `screenshots/playlists-empty-phone.png` is this app's own
 * template - a headline, an explanation that teaches something true, and the action right there -
 * and REQUIREMENTS.md "Accessibility" wants every route reachable as a control, which an instruction
 * in prose is not. Three empty states on this screen made the same mistake; all three now carry the
 * same button to the same place.
 *
 * The third paragraph stays and is the thing that teaches: REQUIREMENTS.md makes the tab badge "the
 * reliable channel" for pull state, and a user who has never seen a pull has no way of knowing that
 * a notification is the unreliable one.
 */
@Composable
private fun PullsEmptyState(offline: Boolean, gutter: Dp, onOpenSearch: () -> Unit) {
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
            text = "No pulls yet",
            style = typography.displayCompact,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = if (offline) {
                "Nothing has been requested from this server, and there is no connection to " +
                    "request anything over. Everything already in your library still plays."
            } else {
                "A pull asks the server to go and get a record it has not got. The server does " +
                    "the downloading over its own connection, and this screen shows it searching, " +
                    "fetching and importing."
            },
            style = typography.body,
            color = colors.textSecondary,
        )
        Text(
            text = "The number on the Pulls tab is the count to trust: it is read from this " +
                "device and is right whenever you open the app, whether or not a notification " +
                "ever arrived.",
            style = typography.caption,
            color = colors.textMuted,
        )
        // Offered offline too. Search reaches the mirrored catalogue with no connection -
        // REQUIREMENTS.md, "Search behaviour" - and the request sheet journals a pull for the
        // write queue to replay, so the button leads somewhere useful either way.
        NeedlerPrimaryButton(text = FIND_MUSIC, onClick = onOpenSearch)
    }
}

private const val TITLE: String = "PULLS"

private const val CLEAR_DONE: String = "Clear done"

/** The header's other control, on the two lanes that are fetched rather than polled. */
private const val REFRESH: String = "Refresh"

/** The pack's two overlines, drawn uppercase by `NeedlerSectionHeader`. */
private const val HEADING_NOW: String = "Now"

private const val HEADING_EARLIER: String = "Earlier"

private const val EMPTY_NOW: String = "Nothing is being acquired right now."

private const val EMPTY_EARLIER: String = "Nothing has finished yet."

/** The one way out of every empty state on this screen. See `PullsEmptyState`. */
internal const val FIND_MUSIC: String = "Find music to pull"

/**
 * The one offline sentence, for all three lanes and for the banner and the empty pane alike.
 *
 * There were two, and they were argued for: the queue's rows come from the mirror and are the last
 * thing the *server* said, while a read-through lane's rows are the page this *session* fetched, so
 * "the last figures the server sent" would overstate how current the second lot are. True, and not
 * worth two sentences. A user is told one thing - there is no connection, what is on screen is not
 * live, and nothing they do here is lost - and that is true of all three lanes. The second sentence
 * was also a third treatment of a fact already told two other ways on the same screen.
 *
 * REQUIREMENTS.md, "Offline is a first-class state, not an error": it states what is true and offers
 * nothing to dismiss. The write-queue promise is the part worth keeping from the queue's old
 * wording, because it is the one thing a user would otherwise assume had failed.
 *
 * Rejected: keeping the distinction and merging only the *treatment*. The two sentences differ in a
 * detail no user acts on, and the cost of the precision was that the same fact looked like two
 * different facts when a user moved between tabs.
 */
internal const val OFFLINE_NOTE: String =
    "Offline, so this is the last the server said. A Stop or Retry made here is sent when you " +
        "are back online."

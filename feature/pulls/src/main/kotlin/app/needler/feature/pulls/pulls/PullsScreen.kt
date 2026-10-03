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
import app.needler.core.design.component.NeedlerPlayIcon
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerProgressRing
import app.needler.core.design.component.NeedlerSectionHeader
import app.needler.core.design.component.NeedlerSegmentedTabs
import app.needler.core.design.component.NeedlerStateBadge
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathClose
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.design.theme.tabularNumerals
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.model.PullState
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestHistoryEntry
import app.needler.feature.pulls.common.PullsFormat
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

            // `held_count` is the download activity summary's, so it belongs to the queue. A
            // history entry and a watch cannot be held: there is nothing on either lane for the
            // server to be holding.
            if (state.lane == PullsLane.QUEUE && state.heldCount > 0) {
                HeldNotice(count = state.heldCount)
            }

            // Two different offline facts, told apart because the honest sentence differs. The
            // queue's rows come from the mirror and are the last thing the *server* said; a
            // read-through lane's rows are the page this *session* happened to fetch. A lane with
            // no rows at all says so in the pane below instead, where there is room to explain.
            when {
                state.lane == PullsLane.QUEUE && state.offline -> OfflineNote()
                state.laneIsStale -> StaleLaneNote()
            }

            state.notice?.let { notice ->
                NoticeCard(notice = notice, onDismiss = onDismissNotice)
            }
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
                )

                PullsLane.HISTORY -> HistoryLane(
                    state = state.history,
                    offline = state.offline,
                    busy = state.busy,
                    now = state.renderedAt,
                    gutter = gutter,
                    onOpenAlbum = onOpenAlbum,
                    onRetryRequest = onRetryRequest,
                    onLoadMore = onLoadMoreHistory,
                    onTryAgain = onRefreshLane,
                )

                PullsLane.WANTED -> WantedLane(
                    state = state.wanted,
                    offline = state.offline,
                    now = state.renderedAt,
                    gutter = gutter,
                    onOpenAlbum = onOpenAlbum,
                    onTryAgain = onRefreshLane,
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
) {
    when {
        state.loading -> PullsSkeleton(gutter = gutter)

        state.showEmptyState -> PullsEmptyState(offline = state.offline, gutter = gutter)

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

/** The phone and foldable layout: one scrolling list with the pack's two headings. */
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
            emptyLine = null,
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
            emptyLine = null,
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
 * @param emptyLine what to say when the section has nothing in it, or `null` to
 *   drop the whole section, heading included. The pack draws no empty block, so
 *   the single-column layout passes `null`.
 */
private fun LazyListScope.pullSection(
    heading: String,
    pulls: List<Pull>,
    now: Instant,
    busy: Boolean,
    emptyLine: String?,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onPlayAlbum: (ReleaseGroupMbid) -> Unit,
    onCancel: (Pull) -> Unit,
    onRetry: (Pull) -> Unit,
) {
    if (pulls.isEmpty() && emptyLine == null) return

    item(key = "heading-" + heading) {
        NeedlerSectionHeader(title = heading)
    }

    if (pulls.isEmpty() && emptyLine != null) {
        // Bound to a non-null local so the lambda below captures a plain String
        // rather than relying on a smart cast surviving into a closure.
        val line: String = emptyLine
        item(key = "empty-" + heading) {
            Text(
                text = line,
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
 * the server has reported progress for gets the filled ring, the bar and the
 * percentage; one it has not - searching, queued, parked for approval - gets
 * plain artwork and a [NeedlerStateBadge] where the percentage would be. The
 * difference is entirely whether
 * [app.needler.core.domain.model.PullProgress.fraction] has an answer, which is
 * the one place that decides between `progress_percent`, the byte counters and
 * the file counters.
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
    val percent: Int? = PullsFormat.percent(fraction)
    val spoken: String = PullsFormat.spokenRow(pull, now)
    val cancelLabel: String = "Cancel the pull of " + PullsFormat.albumPhrase(pull)
    val cancellable: Boolean = pull.canCancel

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
            modifier = Modifier.weight(1f),
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
                    // No percentage in here: the value is announced from the
                    // bar's own range info, and the row already says it once.
                    contentDescription = "Pull progress",
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (percent != null) {
                Text(
                    text = percent.toString() + "%",
                    // Tabular, so the row does not twitch as the figure counts up.
                    style = typography.metaStrong.tabularNumerals(),
                    color = colors.positive,
                    maxLines = 1,
                )
            } else {
                NeedlerStateBadge(badge = badgeFor(pull))
            }
            if (cancellable) {
                RowAction(label = cancelLabel) {
                    NeedlerPillButton(
                        text = "Cancel",
                        onClick = { onCancel(pull) },
                        enabled = !busy,
                    )
                }
            }
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
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NeedlerStateBadge(badge = badgeFor(pull))
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
                NeedlerProgressRing(progress = ring)
            }
        }
    }
}

/**
 * The row's second line: the artist, and whatever the badge beside it does not already say.
 *
 * Normally [PullsFormat.subtitle] verbatim. The one departure is the state whose **badge label is
 * itself the whole explanation**, where drawing both printed the same fact twice — once in full in
 * the trailing column, and once wrapped and truncated under the title where the artist belongs.
 * A device with 35 pulls parked for a manual source pick showed it on all 35 rows:
 *
 * ```
 * Death's Dateless Night              Needs attention on the server
 * Paul Kelly · a source
 * needs picking on th...                               [ Cancel ]
 * ```
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
 * ## What is not fixed here
 *
 * `NeedlerAlbumBadge.NeedsAttention` renders the sentence "Needs attention on the server" in a
 * 13sp/600 trailing column, which is what squeezed the subtitle into two narrow lines in the first
 * place. A shorter label would fix the layout as well as the repetition, but that string is
 * `:core:design`'s and not this work's to change. It is in the handover notes.
 */
internal fun rowSubtitle(pull: Pull, now: Instant): String {
    val stated: String? = PullsFormat.stateDetail(pull).takeIf { badgeSaysTheDetail(pull) }
    val parts: List<String> = buildList {
        if (pull.artistName.isNotBlank()) add(pull.artistName)
        // Exactly the filter `PullsFormat.spokenRow` applies to the same list, so what is read and
        // what is heard stay the same facts.
        addAll(PullsFormat.detailParts(pull, now).filterNot { it == stated })
    }
    // Suppressing a repeat must never empty the line. A pull whose album the mirror cannot name an
    // artist for has nothing else to say, and a blank second line under a title is the hole this
    // screen was once unusable for - see `PullsFormat.albumTitle`. Better to repeat the badge than
    // to draw nothing, so in that one case the suppression is given back.
    if (parts.isEmpty()) return PullsFormat.subtitle(pull, now)
    return parts.joinToString(separator = " · ")
}

/**
 * Whether the badge beside this row already says what [PullsFormat.stateDetail] would say.
 *
 * One exhaustive `when` rather than a string comparison, and it sits beside [badgeFor] because it
 * is the same table read a second way: that one picks the badge, this one records whether the badge
 * it picked leaves the subtitle anything to add. Exhaustive so that a new [PullState] stops this
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
 * Which of the pack's badges this pull's state wears.
 *
 * `QUEUED`, `DOWNLOADING` and `PROCESSING` all take `Pulling`, because the
 * pack has one badge for the whole of what REQUIREMENTS.md calls the `Acquiring`
 * state and the subtitle carries the distinction — "waiting for a download
 * slot", "12 of 19 files", "importing". The failed states map to `NoSource` for
 * completeness; the screen does not draw a badge on a failed row, which is why
 * that branch is not reached in practice.
 */
internal fun badgeFor(pull: Pull): NeedlerAlbumBadge = when (pull.state) {
    PullState.PENDING_APPROVAL -> NeedlerAlbumBadge.Waiting
    PullState.SEARCHING -> NeedlerAlbumBadge.Searching
    PullState.AWAITING_SOURCE_REVIEW -> NeedlerAlbumBadge.NeedsAttention
    PullState.QUEUED,
    PullState.DOWNLOADING,
    PullState.PROCESSING -> NeedlerAlbumBadge.Pulling(PullsFormat.percent(pull.progress.fraction))

    PullState.COMPLETED -> NeedlerAlbumBadge.Ready

    // The state's name only. Why it failed, how far a part-delivered pull got
    // and who cancelled it are all in the subtitle, from
    // [PullsFormat.stateDetail] - a badge that repeated them would be the
    // duplication this row was once drawn without a badge to avoid.
    PullState.FAILED -> NeedlerAlbumBadge.Failed
    PullState.PARTIAL -> NeedlerAlbumBadge.PartlyDelivered
    PullState.CANCELLED -> NeedlerAlbumBadge.Cancelled
}

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
 * Held and quarantined items, as a count and a sentence.
 *
 * REQUIREMENTS.md, "Queue screen requirements" item 5: "Surface `held_count`
 * from the activity summary as a read-only notice. Held and quarantined items
 * need the web UI in v1." Read-only is the whole of it — there is no endpoint in
 * `PullRepository` that could resolve one, so offering a button here would be
 * offering something that cannot work. Saying where the work has to happen is
 * more use than saying nothing.
 */
@Composable
private fun HeldNotice(count: Int) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    val message: String = PullsFormat.plural(count.toLong(), "item") +
        " held for review on the server. Releasing them needs DroppedNeedle's own web interface; " +
        "Needler can only report the count."
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = message
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Text(
            text = message,
            style = NeedlerTheme.typography.caption,
            color = colors.textSecondary,
            overflow = TextOverflow.Visible,
        )
    }
}

/**
 * The offline line.
 *
 * Deliberately not an error and deliberately not dismissible. REQUIREMENTS.md:
 * "Offline is a first-class state, not an error." What it says is what the
 * architecture guarantees: the rows come from the mirror, so they are the last
 * thing the server said rather than nothing at all, and a cancel or retry made
 * here is journalled and replayed on reconnect rather than lost.
 */
@Composable
private fun OfflineNote() {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = OFFLINE_NOTE
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Text(
            text = OFFLINE_NOTE,
            style = NeedlerTheme.typography.caption,
            color = colors.textSecondary,
            overflow = TextOverflow.Visible,
        )
    }
}

/**
 * The offline line for a lane that has no mirror behind it.
 *
 * A different sentence from [OfflineNote]'s, because a different thing is true. The queue's rows
 * come from the `pull` table and are the last state the *server* reported, whenever that was; these
 * rows are the page this *session* fetched while it still had a connection, and the server may have
 * moved on since. Saying "the last figures the server sent" over them would overstate how current
 * they are.
 *
 * Still not an error and still not dismissible, for the reason [OfflineNote] gives:
 * REQUIREMENTS.md, "Offline is a first-class state, not an error". The rows are kept rather than
 * blanked, which is the whole point of the note existing — a list the user was reading should not
 * vanish because the radio did.
 */
@Composable
private fun StaleLaneNote() {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = STALE_LANE_NOTE
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Text(
            text = STALE_LANE_NOTE,
            style = NeedlerTheme.typography.caption,
            color = colors.textSecondary,
            overflow = TextOverflow.Visible,
        )
    }
}

/** A one-line result of the last action, dismissible. */
@Composable
private fun NoticeCard(notice: PullsNotice, onDismiss: () -> Unit) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    val tint = if (notice.isProblem) colors.destructive else colors.positive
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, tint, shape)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = notice.message
                liveRegion = LiveRegionMode.Assertive
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = notice.message,
            style = NeedlerTheme.typography.caption,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
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
 * The way out of this state is on another screen, so the copy names it rather
 * than offering a button that would only navigate. It also says what the tab
 * badge is for, because REQUIREMENTS.md makes the badge "the reliable channel"
 * for pull state and a user who has never seen a pull has no way of knowing
 * that a notification is the unreliable one.
 */
@Composable
private fun PullsEmptyState(offline: Boolean, gutter: Dp) {
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
                "Nothing has been requested from this server yet. Find an album under Search " +
                    "and tap Pull; the server does the downloading over its own connection, and " +
                    "this screen shows it searching, fetching and importing."
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

internal const val OFFLINE_NOTE: String =
    "Offline. These are the last figures the server sent. Pulls carry on at the server's end, " +
        "and a cancel or retry made here is sent as soon as you are back online."

/** See [StaleLaneNote] for why this is not [OFFLINE_NOTE]. */
internal const val STALE_LANE_NOTE: String =
    "Offline. This is what was loaded while you were connected, and the server may have moved " +
        "on since. These lists are kept on the server, so there is nothing newer on this device " +
        "to show."

package app.needler.widget.pulls

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
// No import for defaultWeight(): Glance declares it as a member of RowScope and ColumnScope, the
// way Compose declares weight(), so it resolves from the Row's own receiver and importing it is an
// unresolved reference rather than a tidy-up.
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.model.PullState
import app.needler.core.domain.repository.PullRepository
import app.needler.widget.R
import app.needler.widget.internal.WidgetDependencies
import app.needler.widget.internal.WidgetDimensions
import app.needler.widget.internal.WidgetFormat
import app.needler.widget.internal.WidgetLaunch
import app.needler.widget.internal.WidgetSquareDimensions
import app.needler.widget.internal.WidgetText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The pull widget: the right-hand square card on `design/html/15-Widget.html` and
 * `design/html/18-TabletWidget.html`.
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Widgets" asks for "the active pull with its percentage",
 * and the pack draws that as a download glyph, the eyebrow "Pulls", a 36px percentage, the album and a
 * progress bar - the whole card in the positive green. "Pull" is the product's word for asking the
 * server to acquire an album you do not own (REQUIREMENTS.md "Vocabulary"), and the card must use it:
 * a widget that said "downloading" would be talking about getting bytes onto this phone, which is
 * "pull local" and a different feature.
 *
 * ## What it reads, and what it must not
 *
 * [PullRepository], through the domain interface, as REQUIREMENTS.md "Widgets" requires - "the domain
 * repositories and the `PlaybackController`, never through `:core:data`". `observePulls` is served from
 * the mirrored `pull` table, so the card draws instantly and offline; the numbers on it move when the
 * pollers REQUIREMENTS.md "Polling schedule" describes write new task state into the mirror.
 *
 * **Nothing here polls.** No `refreshPulls`, no `refreshActivitySummary`, no timer. A launcher asks a
 * widget to redraw for reasons of its own - a resize, a page swipe, a reboot - and a card that fetched
 * from the server on each of those would be a home screen quietly polling DroppedNeedle forever, on
 * whatever connection the phone happened to be on. REQUIREMENTS.md "Battery and data" and "Polling
 * schedule" put that schedule in one place, in background work, and this card is a reader of its
 * results.
 *
 * ## How it goes stale, and the one signal that fixes it
 *
 * While the process is alive the Room query is collected inside the Glance session, so every poll that
 * writes a new percentage redraws the card. Dead process, and the launcher keeps the last
 * `RemoteViews`: a pull that finished overnight can sit at 62 percent until something wakes Needler,
 * and unlike the now-playing card there is no press that would correct it, because the card is a link.
 *
 * [app.needler.widget.NeedlerWidgets.refreshPulls] is the fix, and the signal it wants is the one the
 * product already computes for exactly this purpose: the activity summary's `revision` moving.
 * REQUIREMENTS.md "Polling schedule" has the cheap summary poll run in the background from every
 * fifteen minutes to every six hours precisely so that a no-change poll costs nothing, and a poll whose
 * revision *did* move is the definition of "the pull card is now wrong". `android:updatePeriodMillis`
 * is 0 for the usual reason: the platform clamps it to thirty minutes and wakes the device to honour
 * it, which would be a second, worse poller running beside the good one.
 *
 * ## No transport, no cancel, no retry
 *
 * The pack draws none, and it is right not to. `PullRepository.cancelTask` is legal only while a pull is
 * searching, queued or downloading - the server refuses it during `processing`, because files are being
 * moved - so a cancel button on a widget would be a control that is sometimes a no-op, on a surface
 * that cannot explain why. Tapping the card opens Pulls, where the state, the legality of each action
 * and the reason a pull failed are all on screen.
 */
internal class PullWidget : GlanceAppWidget() {

    /** [SizeMode.Exact], for the reasons `RecentlyAddedWidget` sets out: one fluid card, real width. */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val pulls: PullRepository = WidgetDependencies.pullRepository(context)
        val models: Flow<PullCardModel> = pulls
            .observePulls(PullBucket.ACTIVE)
            .map { active -> PullCardModel.of(active) }
        val openPulls: Action = WidgetLaunch.openPulls(context)

        provideContent {
            val model: PullCardModel by models.collectAsState(initial = PullCardModel.Idle)
            PullCard(model = model, openPulls = openPulls)
        }
    }
}

/**
 * The card: a glyph and an eyebrow over a percentage, an album and a bar.
 *
 * `appWidgetBackground` is what tells Android 12 and later that this view *is* the widget's background,
 * so the launcher clips it to the system's own widget corner radius rather than letting the card's 22dp
 * corners sit inside a square of card colour.
 *
 * The pack's `justify-content: space-between` is a weighted `Spacer`, as on the recently added card:
 * Glance has no such alignment, so the two blocks are pinned to the top and the bottom of whatever
 * height the launcher gave the card and the slack goes between them.
 */
@Composable
private fun PullCard(model: PullCardModel, openPulls: Action) {
    val context = LocalContext.current
    val showBar: Boolean = LocalSize.current.height >= WidgetSquareDimensions.barMinHeight

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_card))
            .padding(WidgetDimensions.cardPadding)
            .clickable(openPulls),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(
                    if (model.hasActivePull) {
                        R.drawable.widget_ic_pull
                    } else {
                        R.drawable.widget_ic_pull_idle
                    },
                ),
                contentDescription = null,
                modifier = GlanceModifier.size(WidgetSquareDimensions.pullGlyph),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = if (model.showsCount) {
                    context.getString(R.string.widget_pulls_eyebrow_count, model.activeCount)
                } else {
                    context.getString(R.string.widget_pulls_eyebrow)
                },
                style = WidgetText.eyebrow,
                maxLines = 1,
            )
        }

        Spacer(GlanceModifier.defaultWeight())

        Text(
            text = model.percent ?: context.getString(R.string.widget_pull_unknown_percent),
            style = if (model.hasActivePull) WidgetText.pullPercent else WidgetText.pullPercentIdle,
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(WidgetSquareDimensions.pullRowGap))
        Text(
            text = titleLine(model, context),
            style = WidgetText.pullTitle,
            maxLines = 1,
        )
        if (showBar) {
            Spacer(GlanceModifier.height(WidgetSquareDimensions.pullRowGap))
            ProgressBar(model)
        }
    }
}

/**
 * The line under the percentage: the album, with the stage in front of it when there is no number.
 *
 * `Searching · Black Classical Music` rather than a bare album title, because a card showing an em dash
 * over an album name says only that something is wrong with the card. The separator is the pack's own,
 * the one `WidgetFormat` already uses between an artist and a record.
 *
 * With a percentage the stage is left out on purpose: a number is already the statement that this pull
 * is downloading, and `Pulling · 62% · Black Classical Music` is three ways of saying one thing on a
 * card 138dp wide inside its padding.
 *
 * **This line is never blank.** A pull whose `album_title` the server did not send reads "Untitled
 * album", in the same words the Pulls screen uses for the same absence. The guard is here because it
 * was once missing everywhere: the downloads lane's title had no reader in main source, and on a device
 * 34 of 35 pulls arrived with nothing in it. The data path is fixed and the fallback stays, because a
 * home screen showing an empty line looks like a broken widget and gives the user nothing to act on.
 */
private fun titleLine(model: PullCardModel, context: Context): String {
    if (!model.hasActivePull) return context.getString(R.string.widget_pull_idle)
    val album: String = if (model.hasAlbumTitle) {
        model.albumTitle
    } else {
        context.getString(R.string.widget_untitled_album)
    }
    if (!model.needsStageLabel) return album
    return WidgetFormat.withSeparator(stageLabel(model.stage, context), album)
}

/**
 * The product's own word for a stage, as `:core:design`'s `NeedlerAlbumBadge` labels it.
 *
 * Restated rather than shared, for the reason `WidgetFormat` gives at length: a Glance widget cannot
 * see `:core:design`, because Glance is a different runtime with its own `TextStyle` and no
 * `MaterialTheme`, and `widget/build.gradle.kts` declares the duplication as Glance's rather than ours
 * to avoid. They must stay the same words - a pull that reads "Searching" in the app and something else
 * on the home screen is a bug even though neither is wrong.
 *
 * Two deliberate differences from the badge, both because a 166dp card is not a 390dp row:
 *
 *  * `AWAITING_SOURCE_REVIEW` is "Needs attention" rather than the badge's "Needs attention on the
 *    server". The long form is four words past what fits, and the card's tap opens Pulls, which draws
 *    the full sentence.
 *  * `PROCESSING` is "Importing" rather than the badge's "Pulling". The Pulls screen already uses that
 *    word for this state in its own subtitle, and here it is the only thing distinguishing a pull that
 *    is moving files from one that is still fetching them.
 *
 * The four finished states cannot reach this function - `PullCardModel.of` keeps only active pulls, and
 * a finished pull is by definition not one - but the `when` names them rather than falling through, so
 * that adding a state to `PullState` is a compile error here instead of a silent "Pulling".
 */
private fun stageLabel(stage: PullState?, context: Context): String? {
    val resource: Int = when (stage) {
        null -> return null
        PullState.PENDING_APPROVAL -> R.string.widget_pull_stage_waiting
        PullState.SEARCHING -> R.string.widget_pull_stage_searching
        PullState.AWAITING_SOURCE_REVIEW -> R.string.widget_pull_stage_needs_attention
        PullState.QUEUED -> R.string.widget_pull_stage_queued
        PullState.DOWNLOADING -> R.string.widget_pull_stage_pulling
        PullState.PROCESSING -> R.string.widget_pull_stage_importing
        PullState.COMPLETED,
        PullState.PARTIAL,
        PullState.FAILED,
        PullState.CANCELLED -> return null
    }
    return context.getString(resource)
}

/**
 * `height: 4px; border-radius: 2px`, filled to the pull's own fraction.
 *
 * The fill's width is computed rather than weighted, exactly as the now-playing card's position bar is
 * and for the same reason: Glance has no fractional weight, and no progress component whose 4dp height
 * the pack specifies can be pinned to. So the width is worked out in pixels from the widget's real size
 * - [LocalSize] less the card's padding on both sides.
 *
 * An unknown fraction draws the empty track and nothing else. A pull the server is still searching for
 * is not zero percent downloaded, and a bar at zero and a bar of unknown length look identical while
 * only one of them is a lie; the same call `WidgetFormat.timecode` makes for a length it does not know.
 */
@Composable
private fun ProgressBar(model: PullCardModel) {
    val barWidth: Dp = (LocalSize.current.width - WidgetDimensions.cardPadding * 2).coerceAtLeast(0.dp)
    val fraction: Float = model.barFraction

    Box(
        modifier = GlanceModifier
            .width(barWidth)
            .height(WidgetDimensions.barHeight)
            .background(ImageProvider(R.drawable.widget_progress_track)),
    ) {
        if (fraction > 0f) {
            Spacer(
                GlanceModifier
                    .width(barWidth * fraction)
                    .height(WidgetDimensions.barHeight)
                    .background(ImageProvider(R.drawable.widget_pull_progress_fill)),
            )
        }
    }
}

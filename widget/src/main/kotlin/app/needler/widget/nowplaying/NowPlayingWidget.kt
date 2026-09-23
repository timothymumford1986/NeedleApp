package app.needler.widget.nowplaying

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
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
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.widget.R
import app.needler.widget.internal.WidgetArtwork
import app.needler.widget.internal.WidgetDependencies
import app.needler.widget.internal.WidgetDimensions
import app.needler.widget.internal.WidgetText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The now-playing widget: `design/html/15-Widget.html` on the phone and
 * `design/html/18-TabletWidget.html` on the tablet.
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Widgets" asks for "artwork, title, artist, position and
 * transport controls", and REQUIREMENTS.md "Playback" puts the whole thing in context: playback runs
 * through a single Media3 `MediaLibraryService`, and "the app's UI is one more client of that
 * session, not the owner of the player". A widget is one more client again. It owns nothing, it
 * decides nothing, and it must agree with the lock screen, Android Auto and Wear at all times,
 * because they are all looking at the same session.
 *
 * ## How state reaches the launcher
 *
 * This is the part of a widget that is easy to get wrong, so it is worth being precise about what
 * actually runs where.
 *
 * A Glance widget is **not** rendered in the launcher's process. [provideGlance] runs in *this*
 * app's process - the receiver in `AndroidManifest.xml` declares no `android:process`, so the
 * composition happens beside the rest of Needler, with the whole Hilt graph available. What crosses
 * the process boundary is the `RemoteViews` the composition produces: a flattened view tree the
 * launcher inflates and draws. So the question is never "how does the widget read the session" - it
 * reads it exactly as the player screen does - but "how often may it push a new tree across, and
 * what happens when this process is not running".
 *
 * Three mechanisms, in the order they matter:
 *
 *  1. **The session, live, through [PlaybackController].** Not a second copy of playback state, not
 *     a `DataStore` mirror, not Glance's own `stateDefinition`: the `@Singleton` bound in
 *     `:player:service`, which is a Media3 `MediaController` over the one session. Whatever the
 *     player screen sees, this sees, at the same instant and in the same domain types. Inventing a
 *     widget-shaped copy of playback state would be the classic two-sources-of-truth bug - a widget
 *     saying "paused" over audible playback - and REQUIREMENTS.md's player boundary exists to make
 *     it impossible. While this process is alive, [nowPlayingModels] is collected inside the Glance
 *     session and every emission republishes the `RemoteViews`.
 *  2. **The tap itself.** Each transport button is an `actionRunCallback`, which wakes this process
 *     if it is not running, issues the command against the same controller, and republishes. See
 *     `TransportActions.kt`.
 *  3. **`APPWIDGET_UPDATE`.** The platform's own broadcast, on add, on resize and after a reboot.
 *
 * `android:updatePeriodMillis` is 0. The platform clamps it to thirty minutes and wakes the device
 * to honour it, which is simultaneously far too slow to be a transport and too expensive to be
 * worth having.
 *
 * **The known gap, stated rather than hidden:** when nothing is playing, Android is free to kill
 * this process, and when it does the launcher keeps showing the last `RemoteViews` we published. A
 * widget can therefore sit on a paused card for as long as nobody touches it. It self-corrects the
 * moment anything wakes the process, and it is never *wrong* while playback is running, because a
 * foreground media service keeps the process alive by definition. Closing the gap properly needs a
 * push from the other side - the player service calling
 * [app.needler.widget.NeedlerWidgets.refresh] when the session changes - which is a one-line change
 * in `:player:service` or `:app` and is deliberately not made from here: this module is a leaf, and
 * a leaf reaching up to schedule work in its own consumer is how module graphs become cycles.
 *
 * ## Position, and why it is sampled rather than subscribed
 *
 * `PlaybackController` splits position into [PlaybackController.observeProgress] specifically so
 * that a surface bound to the state does not recompose several times a second. On a screen that
 * costs a redraw; here it would cost a `RemoteViews` rebuilt and pushed across a Binder, four times
 * a second, for as long as the widget is on a home screen. That is not a thing a widget may do.
 *
 * So the position is read as a periodic sample ([PROGRESS_SAMPLE_MS]), and only while a track is
 * actually playing - a paused position cannot move, and an idle session has none. The bar is exact
 * at every moment a person changed something - a skip, a pause, a seek, a track ending - and at
 * worst ten seconds behind in between, which on the pack's 4dp bar is about three percent of its
 * width. Dropping the row entirely was the alternative, and it was rejected because REQUIREMENTS.md
 * names position as part of this widget; drawing a scrubber that ticks was never an option.
 *
 * ## One composition, both artboards
 *
 * Screens 15 and 18 draw the same card at 350dp and 420dp with identical internals, so there is no
 * phone layout and no tablet layout - there is one fluid card and [SizeMode.Exact], which hands the
 * composition the size the launcher actually allocated. The two thresholds in [WidgetDimensions]
 * describe what the card sheds when a user resizes it below what the design assumes, in the order
 * that hurts least: the position row first, then Previous and Next.
 *
 * ## What is deliberately not here
 *
 *  * **The other two widgets.** REQUIREMENTS.md "Widgets" and `widget/build.gradle.kts` both name
 *    three - now playing, recently added, and the active pull with its percentage, all drawn on
 *    screens 15 and 18. Only this one is written. The other two need `LibraryRepository` and
 *    `PullRepository` rather than the session, and they will bring their own receiver, their own
 *    `appwidget-provider` and their own entry in the manifest.
 *  * **Glance's `stateDefinition`.** Left at its default and never read. There is nothing for a
 *    widget to persist: the session is the state, and a `DataStore` file per widget instance would
 *    be a stale copy of it that outlives the thing it describes.
 *  * **Motion.** The pack spins the record mark on a 3.6s loop. `RemoteViews` has no animator a
 *    widget can drive, and REQUIREMENTS.md "Motion" requires every looping animation to be
 *    suppressed when `ANIMATOR_DURATION_SCALE` is zero anyway. The mark is drawn still.
 *  * **The shuffle and repeat controls, the output name, the crate.** None of them are on screens
 *    15 or 18. A widget is a glance, and the pack drew exactly what a glance is worth.
 */
internal class NowPlayingWidget : GlanceAppWidget() {

    /**
     * [SizeMode.Exact] rather than `Responsive`.
     *
     * `Responsive` is the right answer when a widget has genuinely different layouts at different
     * breakpoints; this one has a single fluid layout that wants to know its real width so it can
     * size the position bar, which Glance has no fractional weight to do for it. `Exact` also means
     * a launcher on a grid the design never anticipated still gets a card that fits it.
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val controller: PlaybackController = WidgetDependencies.playbackController(context)
        val artwork = WidgetArtwork(context)
        val coverPx: Int = WidgetArtwork.pixels(context, WidgetDimensions.artwork.value)
        val cornerPx: Float =
            WidgetDimensions.artworkCorner.value * context.resources.displayMetrics.density
        val models: Flow<NowPlayingModel> = nowPlayingModels(controller, artwork, coverPx, cornerPx)
        val openApp: Action = openAppAction(context)

        provideContent {
            val model: NowPlayingModel by models.collectAsState(initial = NowPlayingModel.Idle)
            NowPlayingCard(model = model, openApp = openApp)
        }
    }

    /**
     * The card's state: session state at full fidelity, position at a trickle, artwork fetched once
     * per track.
     *
     * The shape matters. `flatMapLatest` means a track change cancels the previous track's position
     * sampler and its in-flight artwork request, rather than letting a slow cover for the track
     * before last arrive and overwrite the current one. And because the artwork fetch happens in the
     * transform rather than inside the sampled flow, a cover is fetched once per track and not once
     * every ten seconds - Coil's memory cache would make the repeat cheap, but "cheap" is not "not
     * done" and this runs on someone's home screen.
     *
     * Sampling happens only while something is actually playing, which is the point most easily
     * missed: a paused track's position cannot move, so one reading is the whole truth and a widget
     * that went on republishing every ten seconds over a paused card would be burning a Binder
     * transaction to redraw an identical picture. With nothing loaded at all - the widget's
     * commonest state, per REQUIREMENTS.md "Widgets" - this flow emits once and then goes quiet
     * until the session changes.
     */
    private fun nowPlayingModels(
        controller: PlaybackController,
        artwork: WidgetArtwork,
        coverPx: Int,
        cornerPx: Float,
    ): Flow<NowPlayingModel> = controller.observeState().flatMapLatest { state ->
        val item = state.currentItem ?: return@flatMapLatest flowOf(NowPlayingModel.Idle)
        val cover = artwork.load(item.track.artwork, coverPx, cornerPx)
        if (state.isPlaying) {
            sampledProgress(controller).map { progress ->
                NowPlayingModel.of(state, progress, cover)
            }
        } else {
            flowOf(NowPlayingModel.of(state, controller.observeProgress().first(), cover))
        }
    }

    /**
     * [PlaybackController.observeProgress], read once every [PROGRESS_SAMPLE_MS] rather than
     * collected.
     *
     * Written as a loop over `first()` rather than with a `sample` operator on purpose: `sample`
     * leaves the upstream running at its own rate, which for that flow means the session is polled
     * four times a second for readings that are thrown away. This takes one reading, lets the
     * collection end, and sleeps. The first emission is immediate, so the bar is drawn correctly the
     * instant the card appears rather than ten seconds later.
     */
    private fun sampledProgress(controller: PlaybackController): Flow<PlaybackProgress> = flow {
        while (true) {
            emit(controller.observeProgress().first())
            delay(PROGRESS_SAMPLE_MS)
        }
    }

    private companion object {

        /**
         * How often the position bar is re-read while something is loaded.
         *
         * Ten seconds is about three percent of the pack's bar on a three-minute track - below the
         * point where a person can see that it is behind - and it is two orders of magnitude fewer
         * Binder transactions than subscribing to the tick would be. It is the one number to turn if
         * the bar ever feels stale; it is not a number to turn down casually.
         */
        const val PROGRESS_SAMPLE_MS: Long = 10_000L
    }
}

/**
 * The tap target for the card itself: open Needler.
 *
 * It resolves the launcher intent through `PackageManager` rather than naming `MainActivity`
 * directly, for two reasons. `:app` depends on `:widget`, so this module cannot see that class
 * without inverting the module graph. And the launch intent carries `ACTION_MAIN` and
 * `CATEGORY_LAUNCHER`, which is what makes Android *resume the existing task* instead of starting a
 * second copy of a single-activity app - a bare `ComponentName` would leave a user with two Needlers
 * in their recents.
 *
 * The literal fallback is the component `:app`'s manifest declares, `app.needler/.MainActivity`, and
 * it should never be reached: `getLaunchIntentForPackage` only returns null when the package has no
 * launcher activity at all.
 */
private fun openAppAction(context: Context): Action {
    val launch: Intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(context.packageName, FALLBACK_MAIN_ACTIVITY)
        }
    return actionStartActivity(launch)
}

/** `:app`'s one activity, as `app/src/main/AndroidManifest.xml` declares it. */
private const val FALLBACK_MAIN_ACTIVITY: String = "app.needler.MainActivity"

/**
 * The card: a 22dp translucent surface with a cover, two lines, the transport and the position row.
 *
 * `appWidgetBackground` is what tells Android 12 and later that this view *is* the widget's
 * background, so the launcher clips it to the system's own widget corner radius instead of letting
 * the card's 22dp corners sit inside a square of card colour.
 */
@Composable
private fun NowPlayingCard(model: NowPlayingModel, openApp: Action) {
    val size = LocalSize.current
    val showSkip: Boolean = model.hasTrack && size.width >= WidgetDimensions.skipButtonsMinWidth
    val showPosition: Boolean = model.hasTrack &&
        size.width >= WidgetDimensions.positionRowMinWidth &&
        size.height >= WidgetDimensions.positionRowMinHeight

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_card))
            .padding(WidgetDimensions.cardPadding)
            .clickable(openApp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(model)
            Spacer(GlanceModifier.width(WidgetDimensions.artworkGap))
            Titles(model, GlanceModifier.defaultWeight())
            Transport(model = model, showSkip = showSkip)
        }

        if (showPosition) {
            Spacer(GlanceModifier.height(WidgetDimensions.rowGap))
            PositionRow(model)
        }
    }
}

/**
 * The 64dp cover.
 *
 * The placeholder tint is the `Box`'s background rather than a branch, so it is already painted when
 * the bitmap is null - no artwork yet, no network, or nothing playing - and the card never shows a
 * hole. REQUIREMENTS.md "Widgets" asks for exactly this: render sensibly with no network.
 */
@Composable
private fun Cover(model: NowPlayingModel) {
    Box(
        modifier = GlanceModifier
            .size(WidgetDimensions.artwork)
            .background(ImageProvider(R.drawable.widget_artwork_placeholder)),
        contentAlignment = Alignment.Center,
    ) {
        val cover = model.cover
        if (cover != null) {
            Image(
                provider = ImageProvider(cover),
                contentDescription = model.artworkDescription,
                modifier = GlanceModifier.size(WidgetDimensions.artwork),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * The eyebrow, the title and the artist line.
 *
 * The empty state prints "Nothing playing", which is the wording every other transport surface in
 * the product already uses (`PlayerUiState.title`), over a second line that says what to do about
 * it. Screens 15 and 18 draw only the playing case, so this copy is ours rather than the pack's -
 * but it is the pack's *structure*, two lines in the same two sizes and colours, so the card does
 * not change shape when playback stops.
 */
@Composable
private fun Titles(model: NowPlayingModel, modifier: GlanceModifier) {
    val context = LocalContext.current
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(R.drawable.widget_record_mark),
                contentDescription = null,
                modifier = GlanceModifier.size(WidgetDimensions.recordMark),
            )
            Spacer(GlanceModifier.width(WidgetDimensions.eyebrowGap))
            Text(
                text = context.getString(R.string.widget_wordmark),
                style = WidgetText.eyebrow,
                maxLines = 1,
            )
        }
        Text(
            text = if (model.hasTrack) {
                model.title
            } else {
                context.getString(R.string.widget_nothing_playing)
            },
            style = WidgetText.title,
            maxLines = 1,
        )
        Text(
            text = if (model.hasTrack) {
                model.subtitle
            } else {
                context.getString(R.string.widget_nothing_playing_detail)
            },
            style = WidgetText.subtitle,
            maxLines = 1,
        )
    }
}

/**
 * Previous, Play/Pause, Next.
 *
 * Previous and Next are hidden when nothing is loaded, because there is nothing to skip to and a
 * dead button is worse than no button. Play survives, and it is not decoration:
 * `PlaybackController.play()` restores the persisted crate when the session has nothing loaded, so
 * pressing it on an empty widget after a restart picks up where the user left off - which is what
 * the same press on a Bluetooth remote already does.
 */
@Composable
private fun Transport(model: NowPlayingModel, showSkip: Boolean) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showSkip) {
            SecondaryButton(
                icon = R.drawable.widget_ic_previous,
                description = context.getString(R.string.widget_action_previous),
                onClick = actionRunCallback<PreviousAction>(),
            )
        }
        Box(
            modifier = GlanceModifier
                .size(WidgetDimensions.primaryButton)
                .background(ImageProvider(R.drawable.widget_transport_primary))
                .clickable(actionRunCallback<PlayPauseAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(
                    if (model.isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play,
                ),
                contentDescription = context.getString(
                    if (model.isPlaying) R.string.widget_action_pause else R.string.widget_action_play,
                ),
                modifier = GlanceModifier.size(WidgetDimensions.transportIcon),
            )
        }
        if (showSkip) {
            SecondaryButton(
                icon = R.drawable.widget_ic_next,
                description = context.getString(R.string.widget_action_next),
                onClick = actionRunCallback<NextAction>(),
            )
        }
    }
}

/** A 40dp transparent tap target with a 22dp glyph in it, as the pack draws Previous and Next. */
@Composable
private fun SecondaryButton(icon: Int, description: String, onClick: Action) {
    Box(
        modifier = GlanceModifier
            .size(WidgetDimensions.secondaryButton)
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = description,
            modifier = GlanceModifier.size(WidgetDimensions.transportIcon),
        )
    }
}

/**
 * `1:16 ——————— 3:20`.
 *
 * The bar's width is computed rather than weighted. Glance has no fractional weight and no progress
 * component whose 4dp height the pack specifies can be pinned to, so the only way to draw a
 * proportional bar is to work out the pixels: the widget's real width from [LocalSize], less the
 * card's padding, less the two fixed timecode slots and the two gaps the pack draws between them.
 * That is also why the timecodes get a fixed slot at all - see `WidgetDimensions.timecodeWidth`.
 *
 * An unknown length draws an empty track. It is the same call `WidgetFormat.timecode` makes when it
 * prints `--:--`: a bar at zero and a bar of unknown length look identical, and only one of them is
 * a lie.
 */
@Composable
private fun PositionRow(model: NowPlayingModel) {
    val available: Dp = LocalSize.current.width - WidgetDimensions.cardPadding * 2
    val barWidth: Dp = (
        available - WidgetDimensions.timecodeWidth * 2 - WidgetDimensions.positionGap * 2
        ).coerceAtLeast(0.dp)
    val fraction: Float = model.fraction ?: 0f

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model.elapsed,
            modifier = GlanceModifier.width(WidgetDimensions.timecodeWidth),
            style = WidgetText.timecode,
            maxLines = 1,
        )
        Spacer(GlanceModifier.width(WidgetDimensions.positionGap))
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
                        .background(ImageProvider(R.drawable.widget_progress_fill)),
                )
            }
        }
        Spacer(GlanceModifier.width(WidgetDimensions.positionGap))
        Text(
            text = model.total,
            modifier = GlanceModifier.width(WidgetDimensions.timecodeWidth),
            style = WidgetText.timecodeEnd,
            maxLines = 1,
        )
    }
}

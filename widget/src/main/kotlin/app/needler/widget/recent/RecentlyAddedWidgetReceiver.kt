package app.needler.widget.recent

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The `AppWidgetProvider` for the recently added card, declared in `widget/src/main/AndroidManifest.xml`.
 *
 * It does nothing but name its widget. `GlanceAppWidgetReceiver` is a `BroadcastReceiver` that handles
 * `APPWIDGET_UPDATE`, `APPWIDGET_DELETED` and the resize broadcasts for us and hands each one to
 * Glance's session machinery; overriding `onUpdate` or `onReceive` here would be taking work back off a
 * component that already does it correctly.
 *
 * ## Why it is a receiver of its own
 *
 * A receiver is how Android identifies a *kind* of widget - in the picker, in its own bookkeeping and
 * in the `appwidget-provider` metadata that gives the card its size and its description. The three
 * cards on `design/html/15-Widget.html` are three kinds, not three sizes of one, so they cannot share
 * one. See `NowPlayingWidgetReceiver` for the same note from the other side.
 *
 * ## No `@AndroidEntryPoint`
 *
 * It would not help. Hilt field-injects a `BroadcastReceiver` for the duration of `onReceive`, and
 * [glanceAppWidget] is read - and `provideGlance` runs, and the composition lives - outside that
 * window. The widget pulls what it needs from the graph instead; see
 * `app.needler.widget.internal.WidgetEntryPoint`.
 */
public class RecentlyAddedWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = RecentlyAddedWidget()
}

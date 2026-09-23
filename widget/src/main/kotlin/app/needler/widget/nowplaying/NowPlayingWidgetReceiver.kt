package app.needler.widget.nowplaying

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The `AppWidgetProvider` the platform talks to, declared in `widget/src/main/AndroidManifest.xml`.
 *
 * It does nothing but name its widget. `GlanceAppWidgetReceiver` is a `BroadcastReceiver` that
 * handles `APPWIDGET_UPDATE`, `APPWIDGET_DELETED` and the resize broadcasts for us, and hands each
 * one to Glance's session machinery; overriding `onUpdate` or `onReceive` here would be taking work
 * back off a component that already does it correctly.
 *
 * ## No `@AndroidEntryPoint`
 *
 * It would not help. Hilt field-injects a `BroadcastReceiver` for the duration of `onReceive`, and
 * [glanceAppWidget] is read - and `provideGlance` runs, and the composition lives - outside that
 * window. The widget pulls what it needs from the graph instead; see
 * `app.needler.widget.internal.WidgetEntryPoint`.
 *
 * ## One receiver per widget
 *
 * The other two widgets REQUIREMENTS.md "Widgets" asks for - recently added, and the active pull
 * with its percentage, both drawn on `design/html/15-Widget.html` and
 * `design/html/18-TabletWidget.html` - will each get their own receiver and their own
 * `appwidget-provider`. A receiver is how Android identifies a *kind* of widget in the picker and in
 * its own bookkeeping, so they cannot share one.
 */
public class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

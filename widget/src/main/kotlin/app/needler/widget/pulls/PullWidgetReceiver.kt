package app.needler.widget.pulls

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The `AppWidgetProvider` for the pull card, declared in `widget/src/main/AndroidManifest.xml`.
 *
 * It does nothing but name its widget, for the reasons `RecentlyAddedWidgetReceiver` sets out: a
 * receiver is how Android identifies a *kind* of widget, `GlanceAppWidgetReceiver` already handles every
 * broadcast correctly, and Hilt's field injection does not reach a `GlanceAppWidget`'s composition - so
 * the widget pulls from the graph through `app.needler.widget.internal.WidgetEntryPoint` instead.
 */
public class PullWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = PullWidget()
}

# Keep rules :widget ships to whatever application consumes it.
#
# :app's release build is minified and resource-shrunk (see
# build-logic/.../AndroidApplicationConventionPlugin.kt), and every rule here
# exists because R8 cannot see a caller that only exists at runtime. A widget is
# unusually exposed to this: nothing in it is reached from an entry point R8
# understands, and the failure mode is a crash inside the *launcher's* tap
# handler on a release build and nowhere else, which is about the worst place in
# the product to find out.

# Glance instantiates an ActionCallback reflectively, by the class name it wrote
# into the PendingIntent when the composition was built. R8 sees three classes
# nobody constructs. These are the widget's transport buttons; see
# widget/src/main/kotlin/app/needler/widget/nowplaying/TransportActions.kt.
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    <init>();
}

# The AppWidgetProvider itself is kept by AGP because AndroidManifest.xml names
# it, and the GlanceAppWidget it returns is reachable from there, so neither
# needs a rule. Nothing else in this module is reflected over: the palette, the
# formatting and the model are all ordinary calls.

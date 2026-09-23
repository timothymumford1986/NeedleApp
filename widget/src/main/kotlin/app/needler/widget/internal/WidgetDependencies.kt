package app.needler.widget.internal

import android.content.Context
import app.needler.core.domain.playback.PlaybackController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * How a widget reaches the application graph.
 *
 * A `GlanceAppWidget` is not an injectable Android component. It is constructed by Glance, from a
 * `GlanceAppWidgetReceiver`, at a moment nobody gets a constructor call at - and `@AndroidEntryPoint`
 * on a `BroadcastReceiver` only field-injects for the duration of `onReceive`, which is over long
 * before `provideGlance` finishes composing. The same is true of an `ActionCallback`, which Glance
 * instantiates reflectively by class name when a transport button is tapped.
 *
 * So the widgets pull rather than being injected. That is what an `@EntryPoint` is for, and it is
 * the pattern Glance's own documentation uses.
 *
 * ## Why this interface is not `internal`
 *
 * Every other type in this module is. This one is public because Hilt's processor generates Java
 * that implements it, and a Kotlin `internal` member's JVM name carries a module-name suffix that
 * generated Java cannot spell. Nothing outside `:widget` has any reason to use it: `:app` depends on
 * this module, not the other way round, and the one thing another module might legitimately want -
 * a way to nudge the widgets - is [app.needler.widget.NeedlerWidgets].
 *
 * ## What is deliberately not here
 *
 * Only [PlaybackController]. REQUIREMENTS.md "Widgets" says the widgets read through the domain
 * repositories and the controller and "never through `:core:data`", and the module's build file
 * declares `:core:domain` alone, so there is no way to break that rule by accident. The recently
 * added and pull-progress widgets on screens 15 and 18 will add `LibraryRepository` and
 * `PullRepository` here when they are written; they are not listed in advance, because an entry
 * point that names a dependency nothing asks for is a dependency the Hilt graph must satisfy at
 * build time for no reason.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
public interface WidgetEntryPoint {

    /**
     * The one `@Singleton` bound in `:player:service`'s `PlayerServiceBindings`, and therefore the
     * *same* object the player screen drives.
     *
     * This is the whole reason the widgets need no state of their own: there is one Media3 session,
     * one controller over it, and every surface that shows a transport is a client of that. See
     * `NowPlayingWidget`'s KDoc for what that means for a process that comes and goes.
     */
    public fun playbackController(): PlaybackController
}

/** Convenience over [WidgetEntryPoint], so no caller has to name `EntryPointAccessors` twice. */
internal object WidgetDependencies {

    /**
     * @param context any context. The application context is taken from it, because a widget's
     *   context is a receiver's or a Glance session's and neither outlives the work being done.
     */
    fun playbackController(context: Context): PlaybackController =
        EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .playbackController()
}

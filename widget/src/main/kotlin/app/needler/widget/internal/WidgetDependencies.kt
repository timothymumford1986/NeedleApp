package app.needler.widget.internal

import android.content.Context
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PullRepository
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
 * ## Three dependencies, and why the list stops there
 *
 * REQUIREMENTS.md "Widgets" says the widgets read through the domain repositories and the
 * [PlaybackController] and "never through `:core:data`", and the module's build file declares
 * `:core:domain` alone, so there is no way to break that rule by accident. The three named below
 * are one per widget on screens 15 and 18 and nothing more: an entry point that lists a dependency
 * no widget asks for is a dependency the Hilt graph must satisfy at build time for no reason, so a
 * fourth belongs here only when a fourth card needs it.
 *
 * None of the three is a use case. A widget places no request, pins nothing and resolves no
 * playable source - it reads, and it links into the app for anything else - so the use cases in
 * `:core:domain` have no caller here.
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

    /**
     * The owned library, for the recently added card on screens 15 and 18.
     *
     * Every `observe` on it is served from the Room mirror, which is exactly what makes it safe on a
     * home screen: REQUIREMENTS.md "Library browse" has all library browsing read the mirror "so it
     * works identically online and offline", so the newest album is a local query that cannot fail
     * and cannot block on a server that is switched off. The widget never calls a `refresh` - a
     * widget must not decide to go to the network; sync does that, on its own schedule.
     */
    public fun libraryRepository(): LibraryRepository

    /**
     * Pulls, for the pull card on screens 15 and 18.
     *
     * Also mirror-backed, and also read-only from here. The pollers REQUIREMENTS.md "Polling
     * schedule" describes are what move the numbers; a widget that called `refreshPulls` itself
     * would be a home screen quietly polling a server every time a launcher asked it to redraw.
     */
    public fun pullRepository(): PullRepository
}

/** Convenience over [WidgetEntryPoint], so no caller has to name `EntryPointAccessors` twice. */
internal object WidgetDependencies {

    /**
     * @param context any context. The application context is taken from it, because a widget's
     *   context is a receiver's or a Glance session's and neither outlives the work being done.
     */
    fun playbackController(context: Context): PlaybackController = entryPoint(context).playbackController()

    /** @param context any context; see [playbackController]. */
    fun libraryRepository(context: Context): LibraryRepository = entryPoint(context).libraryRepository()

    /** @param context any context; see [playbackController]. */
    fun pullRepository(context: Context): PullRepository = entryPoint(context).pullRepository()

    private fun entryPoint(context: Context): WidgetEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
}

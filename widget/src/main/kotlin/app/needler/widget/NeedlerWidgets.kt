package app.needler.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import app.needler.widget.nowplaying.NowPlayingWidget

/**
 * The only thing `:widget` exposes to the rest of the project: a way to say "playback changed, redraw".
 *
 * ## Why this exists
 *
 * A Glance widget composes in this app's process and publishes a `RemoteViews` the launcher draws.
 * While the process is alive the now-playing card follows the session on its own, because it
 * collects `PlaybackController`'s flows - see `NowPlayingWidget`, "How state reaches the launcher".
 * When the process is *not* alive, and nothing is playing that is the normal case, the launcher
 * keeps showing whatever was published last. A widget can therefore sit on a stale card until
 * something wakes Needler.
 *
 * The fix is a push from whoever is still running when that happens, which is the Media3 service.
 * `:player:service` (or `:app`, which is where the two already meet) can call this when the session
 * reports a change it cares about - a track change, a play, a pause, a stop - and the widgets
 * redraw whether or not anyone is looking at a Glance session.
 *
 * It is deliberately not wired from here. `:app` depends on `:widget`; `:widget` must not depend on
 * `:player:service` or reach up into its consumer to schedule work, or the module graph gains a
 * cycle for the sake of one function call. This is the seam, and the call site belongs on the other
 * side of it.
 *
 * ## Why it is not a "widget repository"
 *
 * Because there is nothing to store. The session is the state. Anything this module cached would be
 * a second copy of playback state that outlives the session it describes, which is the exact bug the
 * player boundary in REQUIREMENTS.md "Playback" exists to prevent.
 */
public object NeedlerWidgets {

    /**
     * Redraws every placed Needler widget from the session as it is right now.
     *
     * Safe to call when no widget is placed - `updateAll` simply finds none - and safe to call
     * often, though "often" should mean "when playback actually changed" rather than on a timer:
     * every call is a composition and a `RemoteViews` pushed across a Binder to the launcher.
     *
     * Suspends because Glance's update is suspending. Call it from whatever scope the caller already
     * has; it does not need a foreground anything.
     */
    public suspend fun refresh(context: Context) {
        NowPlayingWidget().updateAll(context)
    }
}

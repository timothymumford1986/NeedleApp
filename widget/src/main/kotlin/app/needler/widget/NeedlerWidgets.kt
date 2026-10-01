package app.needler.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import app.needler.widget.nowplaying.NowPlayingWidget
import app.needler.widget.pulls.PullWidget
import app.needler.widget.recent.RecentlyAddedWidget

/**
 * The only thing `:widget` exposes to the rest of the project: a way to say "that changed, redraw".
 *
 * ## Why this exists
 *
 * A Glance widget composes in this app's process and publishes a `RemoteViews` the launcher draws.
 * While the process is alive each card follows its own source on its own, because each collects a flow:
 * the now-playing card collects `PlaybackController`, the recently added card a `LibraryRepository`
 * query against the Room mirror, and the pull card a `PullRepository` one. When the process is *not*
 * alive - and with nothing playing that is the normal case - the launcher keeps showing whatever was
 * published last. A widget can therefore sit on a stale card until something wakes Needler.
 *
 * The fix is a push from whoever is still running when that happens. There are three such callers and
 * three signals, which is why there are three targeted functions below rather than one:
 *
 * | Card | Goes stale when | Push it from |
 * | --- | --- | --- |
 * | Now playing | the session changed with no Glance session live | `:player:service`, on a session change |
 * | Recently added | sync wrote a newer album | the sync worker, when a library sync finishes |
 * | Pulls | a poll moved a percentage, or a pull landed | the pull poller, when the activity summary's revision moves |
 *
 * Each is cheap to call and none of them needs a foreground anything, but they are not interchangeable:
 * pushing all three on a pull poll means two compositions and two `RemoteViews` across a Binder to
 * redraw two pictures that did not change.
 *
 * It is deliberately not wired from here. `:app` depends on `:widget`; `:widget` must not depend on
 * `:player:service`, `:core:data` or the workers, or reach up into its consumer to schedule work, or
 * the module graph gains a cycle for the sake of a function call. This is the seam, and every call site
 * belongs on the other side of it.
 *
 * ## Why it is not a "widget repository"
 *
 * Because there is nothing to store. The session is the state, and for the other two cards the mirror
 * is. Anything this module cached would be a second copy that outlives the thing it describes, which is
 * the exact bug the player boundary in REQUIREMENTS.md "Playback" exists to prevent - and, for the
 * library and the pull queue, a second copy of the mirror REQUIREMENTS.md "Architecture" makes the one
 * read path.
 */
public object NeedlerWidgets {

    /**
     * Redraws every placed Needler widget from its own source as it is right now.
     *
     * The blunt instrument, and the right one in exactly two situations: something happened that could
     * have changed all three - a sign-in, a sign-out, a restore from backup - or the caller does not
     * know what changed. Prefer one of the targeted functions everywhere else; each call here is three
     * compositions and up to three `RemoteViews` pushed across a Binder to the launcher.
     *
     * Safe to call when no widget is placed - `updateAll` simply finds none - and safe to call often,
     * though "often" should mean "when something actually changed" rather than on a timer.
     *
     * Suspends because Glance's update is suspending. Call it from whatever scope the caller already
     * has; it does not need a foreground anything.
     */
    public suspend fun refresh(context: Context) {
        refreshNowPlaying(context)
        refreshRecentlyAdded(context)
        refreshPulls(context)
    }

    /**
     * Redraws the now-playing card from the session.
     *
     * The signal is a session change the user would notice: a track change, a play, a pause, a stop.
     * `:player:service` - or `:app`, which is where the two already meet - is the caller, because the
     * Media3 service is what is still running when this matters.
     *
     * Not a tick. Position on that card is a periodic sample by design (see `NowPlayingWidget`,
     * "Position, and why it is sampled rather than subscribed"), and calling this four times a second
     * to move a 4dp bar is precisely what that design exists to avoid.
     */
    public suspend fun refreshNowPlaying(context: Context) {
        NowPlayingWidget().updateAll(context)
    }

    /**
     * Redraws the recently added card from the mirror.
     *
     * The signal is a finished library sync - the point at which a newer album exists to show. Calling
     * it per album written would redraw the card once for every record in a first full sync; calling it
     * when the sync completes redraws it once, with the right answer.
     *
     * It is worth calling even when the caller cannot tell whether the newest album changed: the query
     * behind the card is a single indexed Room read, so a redraw that produces the same card costs one
     * composition, and the alternative is the caller reimplementing "is this album newer than the one
     * the widget drew", which it has no way to know.
     */
    public suspend fun refreshRecentlyAdded(context: Context) {
        RecentlyAddedWidget().updateAll(context)
    }

    /**
     * Redraws the pull card from the mirror.
     *
     * The signal is the activity summary's `revision` moving, which REQUIREMENTS.md "Polling schedule"
     * already has the background poller checking on its own schedule and which is defined to mean
     * "something about the download queue changed". A landed pull is the case that matters most: it is
     * the difference between a home screen saying 94 percent and a home screen saying nothing is
     * pulling, and it is exactly when Needler is least likely to be running.
     *
     * An unchanged revision needs no call. That is the whole point of the cheap poll.
     */
    public suspend fun refreshPulls(context: Context) {
        PullWidget().updateAll(context)
    }
}

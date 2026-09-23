package app.needler.widget.nowplaying

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import app.needler.core.domain.playback.PlaybackController
import app.needler.widget.internal.WidgetDependencies

/**
 * What the three transport buttons on the now-playing card actually do.
 *
 * Each is an `ActionCallback`: Glance turns it into a `PendingIntent` that the launcher fires, which
 * wakes this app's process if it is not running, delivers to Glance's own broadcast receiver, and
 * runs [ActionCallback.onAction] here. The whole round trip happens on our side of the process
 * boundary, so a button press is an ordinary call on an ordinary singleton.
 *
 * ## They drive the session, not a player
 *
 * Every one of them goes through [PlaybackController], the `@Singleton` `:player:service` binds over
 * the one Media3 session. REQUIREMENTS.md "Playback" is explicit that the lock screen, the widgets,
 * Android Auto, Bluetooth controls and Wear all drive the same session and that no surface owns the
 * player - so a widget cannot have its own idea of what Next means. In particular
 * [PlaybackController.skipToPrevious] restarts the current track past a short threshold rather than
 * always leaving it, and that threshold belongs to the implementation precisely so the widget and
 * the lock screen cannot disagree about what the same button does. Nothing here re-implements it.
 *
 * ## Why each one republishes
 *
 * The card is also being recomposed by the flow in `NowPlayingWidget`, so the explicit
 * [androidx.glance.appwidget.GlanceAppWidget.update] looks redundant - and it is, whenever a Glance
 * session happens to be live. It is here for the case that is not: a press that woke a dead process
 * has no session yet, and without the update the launcher would go on showing the card it had. The
 * cost of the redundancy is one extra `RemoteViews` push per tap; the cost of leaving it out is a
 * button that visibly does nothing.
 *
 * The redraw is deliberately *after* the command and takes whatever the session reports at that
 * instant. A Media3 controller applies a transport command optimistically, so Play flips the glyph
 * straight away; a command that has to restore the crate from disk first will still read as stopped
 * for a moment, and the flow corrects it when the session does.
 *
 * ## Why these classes are not `internal`
 *
 * Glance instantiates an `ActionCallback` reflectively, by the class name it wrote into the
 * `PendingIntent`. That is also why `consumer-rules.pro` keeps them: R8 has no way to see that
 * anything calls these constructors, and a stripped callback is a transport button that throws
 * inside the launcher's tap handler on a release build and nowhere else.
 */
public class PlayPauseAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        WidgetDependencies.playbackController(context).playPause()
        NowPlayingWidget().update(context, glanceId)
    }
}

/**
 * Previous.
 *
 * Note it is [PlaybackController.skipToPrevious] and not a seek to zero: past a short way into a
 * track the session restarts it instead of leaving it, which is what every other player does and
 * what a user pressing this expects.
 */
public class PreviousAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        WidgetDependencies.playbackController(context).skipToPrevious()
        NowPlayingWidget().update(context, glanceId)
    }
}

/** Next. Skips to the next row of the crate; the session decides what that means at the end. */
public class NextAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        WidgetDependencies.playbackController(context).skipToNext()
        NowPlayingWidget().update(context, glanceId)
    }
}

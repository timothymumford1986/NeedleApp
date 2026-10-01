package app.needler.widget.recent

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.widget.NeedlerWidgets
import app.needler.widget.internal.WidgetDependencies

/**
 * What the Play disc on the recently added card does: replace the crate with that album and start it.
 *
 * Glance turns this into a `PendingIntent` that the launcher fires, which wakes this app's process if
 * it is not running, delivers to Glance's own broadcast receiver, and runs [onAction] here. The whole
 * round trip happens on our side of the process boundary, so a press is an ordinary call on an
 * ordinary singleton. See `TransportActions.kt` for the same mechanism on the now-playing card.
 *
 * ## It drives the session, not a player
 *
 * `PlaybackController.playAlbum` is the one command in the product that means "play this record", and
 * `:player:service` implements it over the single Media3 session. REQUIREMENTS.md "Playback" is
 * explicit that every surface drives that session and none owns the player, so a widget cannot have its
 * own idea of what playing an album means - the crate it builds, the order it plays in and what happens
 * to whatever was playing before are the session's business. In particular nothing here reads the
 * album's tracks: that would be a second implementation of the same decision, reachable only from a
 * home screen, and it would diverge the first time the session's own rules changed.
 *
 * ## Why the MBID arrives as a parameter
 *
 * It was written into the `PendingIntent` when the card was composed, so the disc plays **the album the
 * user was looking at**. Reading "the newest album" again inside the callback would look equivalent and
 * would occasionally play the wrong record: a sync that landed between the draw and the press changes
 * the answer, and the user pressed a disc next to a cover and a title that named something else.
 *
 * A blank or missing parameter is a no-op rather than a fallback. There is no sensible guess - the card
 * only draws the disc when it has an album (see `RecentlyAddedModel.canPlay`) - and
 * [ReleaseGroupMbid] rejects a blank value anyway, so guessing would trade a dead press for a crash
 * inside the launcher's tap handler.
 *
 * ## What it refreshes, and what it deliberately does not
 *
 * The now-playing card, not this one. This card shows the newest album and playing that album does not
 * change which album is newest, so redrawing it would be a `RemoteViews` pushed across a Binder to
 * produce an identical picture. The now-playing card *is* now wrong - it was showing something else, or
 * nothing - and a press that woke a dead process has no Glance session to correct it, which is exactly
 * the case [NeedlerWidgets.refreshNowPlaying] exists for.
 *
 * ## Why this class is not `internal`
 *
 * Glance instantiates an `ActionCallback` reflectively, by the class name it wrote into the
 * `PendingIntent`. That is also why `consumer-rules.pro` keeps it: R8 has no way to see that anything
 * calls this constructor, and a stripped callback is a button that throws inside the launcher's tap
 * handler on a release build and nowhere else.
 */
public class PlayRecentAlbumAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val mbid: String = parameters[AlbumMbidKey]?.takeIf { it.isNotBlank() } ?: return
        WidgetDependencies.playbackController(context).playAlbum(ReleaseGroupMbid(mbid))
        NeedlerWidgets.refreshNowPlaying(context)
    }

    public companion object {

        /**
         * The release-group MBID of the album the card was drawing, as a bare string.
         *
         * `ActionParameters` is a bundle by another name, so the value has to be something a
         * `PendingIntent` can carry. The domain wrapper is rebuilt on this side of the press.
         */
        public val AlbumMbidKey: ActionParameters.Key<String> =
            ActionParameters.Key<String>("app.needler.widget.recent.ALBUM_MBID")
    }
}

package app.needler.wear.local

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.needler.wear.NeedlerWearActivity

/**
 * The watch's own player: the thing that makes a sound when the phone is not there.
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Wear OS" puts "playback of on-device audio synced from the
 * phone over the data layer" in Wear's v1 scope, and this is the playback half of it. Everything it
 * plays came off [app.needler.wear.store.WearAudioStore]; nothing here touches a network, and the module
 * has no server credentials to do so with even if it wanted to.
 *
 * ## Why a session service and not a library service
 *
 * `:player:service` uses `MediaLibraryService` and says why: "Android Auto needs `MediaLibraryService`
 * and its `MediaBrowserService` intent filter, and REQUIREMENTS.md wants Auto from the start precisely
 * because retrofitting browse means restructuring playback."
 *
 * There is no browse client on a watch. Nothing on Wear OS asks a media app for a content tree - the
 * system's own media controls drive a session, not a browser - so the browse tree would be code with no
 * caller, built over a store whose whole contents fit on one screen. `MediaSessionService` is the whole
 * of what is needed, and it is what gives the watch's system media controls, its Bluetooth headset
 * buttons and its ongoing activity something to talk to.
 *
 * ## Why a session at all, rather than an `ExoPlayer` in the activity
 *
 * Because the screen goes off. A watch plays for a run or a commute with the display dark and the app
 * not in the foreground, which needs a foreground service holding a wake lock - and `MediaSessionService`
 * is the platform's answer to precisely that, with the notification, the audio focus, the media buttons
 * and the foreground promotion already in it. An `ExoPlayer` owned by the activity would stop when the
 * wrist dropped.
 *
 * ## Wake mode
 *
 * `WAKE_MODE_LOCAL`, not `WAKE_MODE_NETWORK`. The phone's player takes a wifi lock as well "because the
 * same player streams and plays local files"; this one only ever reads a file out of app-private
 * storage, and a wifi lock on a watch is battery spent on a radio nothing is using.
 *
 * ## No data source factory, and no extension hint
 *
 * The default `ExoPlayer` reads a `file://` URI through its own data source, and its extractors sniff the
 * container. That is why [app.needler.wear.store.WearAudioStore.AUDIO_SUFFIX] can be a fixed `.audio`
 * rather than the track's real format: the filename is a pure function of the track key, and Media3
 * works out what the bytes are by looking at them.
 *
 * The equaliser, the crossfade and the write-through cache datasource that `:player:service` builds are
 * all deliberately absent. None is drawn on a watch, the store is already on disk so there is nothing to
 * write through, and a 10-band IIR kernel on a watch's CPU is battery spent on a difference nobody can
 * hear through the Bluetooth codec the audio is going out over anyway.
 */
@OptIn(UnstableApi::class)
class WearLocalPlayerService : MediaSessionService() {

    private var player: ExoPlayer? = null

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val exoPlayer: ExoPlayer = ExoPlayer.Builder(this)
            // Audio focus is handed to ExoPlayer, which is the only correct answer - it ducks for a
            // navigation prompt and pauses for a permanent loss. Implementing that by hand means
            // reimplementing AudioManager.requestAudioFocus and getting the API 26 focus-request path
            // wrong, which is the same argument NeedlerPlayerFactory makes on the phone.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            // Bluetooth earbuds disconnecting means pause, immediately. On a watch this is not an edge
            // case: the earbuds are the only output there is.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val builder: MediaSession.Builder = MediaSession.Builder(this, exoPlayer).setId(SESSION_ID)
        sessionActivityIntent()?.let { intent -> builder.setSessionActivity(intent) }

        player = exoPlayer
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * The user swiped the watch app away.
     *
     * Paused playback is torn down, playing audio survives. The same decision `NeedlerPlaybackService`
     * makes on the phone and for the same reason: "dismissing the app's window is not the same as saying
     * stop the music". It matters more here, because a watch app is dismissed by a swipe that is one
     * gesture away from the one that goes back a screen.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current: Player? = player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    /**
     * What the watch's media controls open.
     *
     * Null rather than a throw when the activity cannot be resolved: a session with no activity is a
     * session whose notification is not tappable, which is a smaller loss than a service that will not
     * start.
     */
    private fun sessionActivityIntent(): PendingIntent? {
        val intent = Intent(this, NeedlerWearActivity::class.java)
        return try {
            PendingIntent.getActivity(
                this,
                0,
                intent,
                // Immutable because nothing is meant to fill anything in, and mandatory from API 31 -
                // a mutable PendingIntent with no explicit component is the pattern the platform
                // refuses outright.
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } catch (failure: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        /**
         * The session id.
         *
         * Distinct from the phone's, though nothing would collide - the two are separate processes on
         * separate devices - because a session id turns up in bug reports and "needler-wear" says which
         * device made the sound.
         */
        const val SESSION_ID: String = "needler-wear"
    }
}

package app.needler.player.service.session

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PlaybackSettingsRepository
import app.needler.player.service.audio.EqualiserAudioProcessor
import app.needler.player.service.media.TrackCatalogue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The one `MediaLibraryService`, and therefore the one thing in Needler that makes a sound.
 *
 * Every surface drives this session: the app's own UI, the lock screen and the notification, the Glance
 * widgets, Bluetooth and car head-unit buttons, Android Auto and the Wear companion. The app's UI is one more
 * client of the session, not the owner of the player - which is the whole reason `PlaybackController` is
 * declared in `:core:domain` and implemented here.
 *
 * ## Why a library service rather than a session service
 *
 * `MediaSessionService` would be enough for the phone. Android Auto needs `MediaLibraryService` and its
 * `MediaBrowserService` intent filter, and REQUIREMENTS.md wants Auto from the start precisely because
 * retrofitting browse means restructuring playback. The tree itself is the next piece of work; the shape it
 * has to grow into is here already.
 *
 * ## Foreground service
 *
 * Media3 owns the foreground promotion and the notification. The service type is `mediaPlayback`, which is
 * mandatory from `targetSdk` 34 and is declared in this module's manifest next to the class it names. It is
 * the only foreground service in the app: downloads run as expedited `WorkManager` jobs instead.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
public class NeedlerPlaybackService : MediaLibraryService() {

    @Inject internal lateinit var playerFactory: NeedlerPlayerFactory

    @Inject internal lateinit var settingsRepository: PlaybackSettingsRepository

    @Inject internal lateinit var libraryRepository: LibraryRepository

    @Inject internal lateinit var equaliser: EqualiserAudioProcessor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null
    private var coordinator: PlaybackCoordinator? = null

    override fun onCreate() {
        super.onCreate()
        val exoPlayer: ExoPlayer = playerFactory.create()
        val catalogue = TrackCatalogue(libraryRepository)
        val playbackCoordinator = PlaybackCoordinator(
            player = exoPlayer,
            settingsRepository = settingsRepository,
            catalogue = catalogue,
            equaliser = equaliser,
            scope = scope,
        )
        val callback = NeedlerSessionCallback(
            catalogue = catalogue,
            settingsRepository = settingsRepository,
            coordinator = playbackCoordinator,
            scope = scope,
        )

        val builder = MediaLibrarySession.Builder(this, exoPlayer, callback)
            .setId(SESSION_ID)
        sessionActivityIntent()?.let { builder.setSessionActivity(it) }

        player = exoPlayer
        coordinator = playbackCoordinator
        session = builder.build()
        playbackCoordinator.start()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /**
     * The user swiped the app away from recents.
     *
     * Paused playback is torn down - nothing is coming out of the speaker and the notification would be a
     * ghost of an app the user just dismissed. Playing audio survives, because dismissing the app's window is
     * not the same as saying "stop the music", and every other player on the platform behaves this way.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current: Player? = player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        coordinator?.stop()
        coordinator = null
        session?.release()
        session = null
        player?.release()
        player = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The `PendingIntent` the notification and the lock screen open.
     *
     * Resolved from the package manager rather than naming `MainActivity`, because `:player:service` must not
     * depend on `:app` - dependencies run one way, and a service module reaching back into the application
     * module is how that stops being true.
     */
    private fun sessionActivityIntent(): PendingIntent? {
        val launch: Intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        /** Stable, so a controller reconnecting after a process death finds the same session. */
        const val SESSION_ID: String = "needler-playback"
    }
}

package app.needler.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import app.needler.wear.local.WearLocalPlayback
import app.needler.wear.playback.DataLayerPlaybackClient
import app.needler.wear.playback.WearCrateState
import app.needler.wear.playback.WearPlaybackClient
import app.needler.wear.playback.WearPlaybackSource
import app.needler.wear.playback.WearPlaybackState
import app.needler.wear.store.WearAudioStore
import app.needler.wear.store.WearStoreContents
import app.needler.wear.store.WearStoredAlbum
import app.needler.wear.sync.WearOfferState
import app.needler.wear.sync.WearOnWatchState
import app.needler.wear.sync.WearSyncCoordinator
import app.needler.wear.sync.WearSyncSelection
import app.needler.wear.ui.CrateScreen
import app.needler.wear.ui.NeedlerWearTheme
import app.needler.wear.ui.NowPlayingScreen
import app.needler.wear.ui.OnWatchScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The watch's one activity.
 *
 * Needler on the watch is three screens - the transport, the crate and the on-watch library - so this is
 * a single activity hosting a single nav graph, in the same spirit as the phone's `MainActivity` and for
 * much simpler reasons.
 *
 * ## No Hilt, on purpose
 *
 * `wear/build.gradle.kts` spells this out: "No Hilt. Hilt needs an `@HiltAndroidApp` Application class
 * and the watch app does not have one yet. Add `id(\"needler.hilt\")` at the same time as that class,
 * not before."
 *
 * On-device playback added three more dependencies and that comment anticipated the moment: "When
 * on-device playback arrives and there are several, that is the moment to add the Application class and
 * the plugin together." It is still not taken, and the reason is that the three new objects are not
 * activity-scoped - [WearAudioStore], [WearSyncCoordinator] and the session behind
 * [app.needler.wear.local.WearLocalPlayerService] all have to be reachable from a
 * `WearableListenerService` that Google Play services constructs itself, which Hilt cannot inject
 * without a generated superclass between the two. So they are process singletons with a `get(context)`,
 * which is what `:widget` does for a related reason, and Hilt would add a graph over the top of them
 * rather than replacing anything.
 *
 * ## No ViewModel either, and that is the less obvious call
 *
 * `androidx.lifecycle:lifecycle-viewmodel-compose` and `lifecycle-runtime-compose` are not
 * dependencies of this module, so neither `viewModel()` nor `collectAsStateWithLifecycle()` is
 * available, and adding them to a module whose whole state is a handful of values would be a poor trade.
 * What a ViewModel would buy - surviving a configuration change - is close to worthless on a watch, which
 * does not rotate and has no multi-window.
 *
 * What is *not* worthless is stopping the data-layer listeners when the screen goes away, and that is
 * what [onStart] and [onStop] do below. Composition is not disposed when a watch screen turns off, so
 * collecting inside the composition alone would keep Play services listeners registered all day,
 * waking this process for every track change on the phone while nobody is looking at the watch. It
 * would also hold the *phone's* publishing window open for ever: collecting
 * [WearPlaybackClient.observe] is what asks the phone to publish at all, so the start/stop pair here is
 * what bounds the cost on both devices. See [app.needler.wear.playback.WearPlaybackProtocol]'s "The
 * phone does not publish all day".
 *
 * The scope is created and cancelled by hand rather than through `lifecycleScope`, because that lives
 * in `lifecycle-runtime-ktx` and this module does not declare it: eight lines of explicit scope beat a
 * dependency taken on a transitive guess.
 *
 * The states are [MutableStateFlow]s held by the activity, so a stop and a later start show the
 * last known values immediately and then correct them, rather than flashing "Connecting" every time
 * the user lowers and raises their wrist.
 *
 * ## Which source the transport shows
 *
 * [WearPlaybackSource] records the rule and there is no picker: the watch's own session wins whenever it
 * has something loaded. So [transportState] is the local state when [WearLocalPlayback.isPlayingLocally]
 * is true and the phone's otherwise, and the screen prints which. The user switches to the watch by
 * playing something from [OnWatchScreen], and back by letting the local session end - never by finding a
 * toggle.
 *
 * ## Why the local session is connected conditionally
 *
 * Binding a `MediaController` starts [app.needler.wear.local.WearLocalPlayerService], which is process
 * weight on a watch, so it happens only when the store has something in it - the one circumstance in
 * which local playback is possible at all. A watch with an empty store never binds, and
 * [WearLocalPlayback.connect] records the hazard in full.
 *
 * ## Notification permission
 *
 * Asked for once, and only when the user first plays something locally. Media3 owns the foreground
 * promotion and the media notification, which on Wear OS is what the system's own media controls read; on
 * API 33 and up a denied `POST_NOTIFICATIONS` means the service still runs and the controls simply are
 * not there. Asking at that moment rather than at launch is deliberate: a permission prompt on first
 * launch, for a feature the user has not reached, is the prompt everybody denies.
 */
class NeedlerWearActivity : ComponentActivity() {

    private val client: WearPlaybackClient by lazy { DataLayerPlaybackClient(applicationContext) }

    private val store: WearAudioStore by lazy { WearAudioStore.get(applicationContext) }

    private val coordinator: WearSyncCoordinator by lazy {
        WearSyncCoordinator.get(applicationContext)
    }

    private val local: WearLocalPlayback by lazy {
        WearLocalPlayback(context = applicationContext, store = store)
    }

    private val phonePlayback: MutableStateFlow<WearPlaybackState> =
        MutableStateFlow(WearPlaybackState.Connecting)

    private val phoneCrate: MutableStateFlow<WearCrateState> =
        MutableStateFlow(WearCrateState.Connecting)

    private val offer: MutableStateFlow<WearOfferState> =
        MutableStateFlow(WearOfferState.Loading)

    /** Alive only between [onStart] and [onStop]; see the class note. */
    private var collection: CoroutineScope? = null

    /**
     * Lives as long as the activity, for the two things that must outlive [collection].
     *
     * A command launched from the composition rides `rememberCoroutineScope`, and the collections ride
     * [collection] - but releasing the local controller happens *because* [collection] is going away, so
     * it cannot be launched into it. Cancelled in [onDestroy].
     */
    private val activityScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Requests `POST_NOTIFICATIONS`.
     *
     * The result is deliberately ignored. A denial costs the watch's system media controls and nothing
     * else - the session still plays - so there is nothing to tell the user and nothing to re-ask.
     */
    private val notificationPermission: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NeedlerWearTheme {
                val navController: NavHostController = rememberSwipeDismissableNavController()
                val commands: CoroutineScope = rememberCoroutineScope()

                val phoneState: WearPlaybackState by phonePlayback.collectAsState()
                val phoneRows: WearCrateState by phoneCrate.collectAsState()
                val localState: WearPlaybackState by local.state.collectAsState()
                val localRows: WearCrateState by local.inTheCrate.collectAsState()
                val playingLocally: Boolean by local.isPlayingLocally.collectAsState()
                val contents: WearStoreContents by store.contents.collectAsState()
                val selection: WearSyncSelection by coordinator.selection.collectAsState()
                val offered: WearOfferState by offer.collectAsState()

                val source: WearPlaybackSource = if (playingLocally) {
                    WearPlaybackSource.WATCH
                } else {
                    WearPlaybackSource.PHONE
                }
                val transportState: WearPlaybackState =
                    if (playingLocally) localState else phoneState
                val crateRows: WearCrateState = if (playingLocally) localRows else phoneRows

                // Artwork is fetched on its own, keyed on the identifier and the source rather than on
                // the state, so a pause or a buffering flicker does not re-request a cover that has not
                // changed. The key going null resets it to null, which is what the placeholder is for.
                // The source is part of the key because the two covers come from different places: the
                // phone's arrives as a transport Asset, the watch's is a file in its own store.
                val artworkId: String? =
                    (transportState as? WearPlaybackState.NowPlaying)?.artworkId
                val artwork: State<ImageBitmap?> =
                    produceState<ImageBitmap?>(null, artworkId, source) {
                        value = artworkId?.let { id ->
                            if (source == WearPlaybackSource.WATCH) {
                                local.loadArtwork(id)
                            } else {
                                client.loadArtwork(id)
                            }
                        }
                    }

                val onWatch: WearOnWatchState = WearOnWatchState.of(
                    contents = contents,
                    selection = selection,
                    offer = offered,
                )

                SwipeDismissableNavHost(
                    navController = navController,
                    startDestination = ROUTE_NOW_PLAYING,
                ) {
                    composable(ROUTE_NOW_PLAYING) {
                        NowPlayingScreen(
                            state = transportState,
                            artwork = artwork.value,
                            source = source,
                            // Both sources are fire-and-forget: a data-layer message has no meaningful
                            // result, and a Media3 command is applied asynchronously by the session. The
                            // confirmation a user sees is the next state arriving, either way.
                            onPlayPause = {
                                commands.launch {
                                    if (playingLocally) local.playPause() else client.playPause()
                                }
                            },
                            onPrevious = {
                                commands.launch {
                                    if (playingLocally) {
                                        local.skipToPrevious()
                                    } else {
                                        client.skipToPrevious()
                                    }
                                }
                            },
                            onNext = {
                                commands.launch {
                                    if (playingLocally) local.skipToNext() else client.skipToNext()
                                }
                            },
                            onOpenCrate = { navController.navigate(ROUTE_CRATE) },
                            onOpenOnWatch = { navController.navigate(ROUTE_ON_WATCH) },
                        )
                    }

                    composable(ROUTE_CRATE) {
                        CrateScreen(
                            state = crateRows,
                            // No navigation back on a tap. Whichever source is playing starts the track
                            // and the next state says so; popping the crate as well would take the list
                            // away from someone who was choosing the one after it too.
                            onPlayRow = { rowId ->
                                commands.launch {
                                    if (playingLocally) {
                                        local.skipToRow(rowId)
                                    } else {
                                        client.skipToRow(rowId)
                                    }
                                }
                            },
                        )
                    }

                    composable(ROUTE_ON_WATCH) {
                        OnWatchScreen(
                            state = onWatch,
                            onPlayAlbum = { albumKey ->
                                commands.launch { playFromWatch(albumKey) }
                            },
                            onAddAlbum = { albumKey ->
                                commands.launch { coordinator.select(albumKey) }
                            },
                            onRemoveAlbum = { albumKey ->
                                commands.launch { coordinator.removeFromWatch(albumKey) }
                            },
                            onSyncNow = {
                                commands.launch { coordinator.startSession(urgent = true) }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        collection = scope
        scope.launch {
            client.observe().collect { next -> phonePlayback.value = next }
        }
        // A second collector rather than a combined flow: the two data items change on completely
        // different schedules, and this is the seam PlaybackController draws for the same reason.
        scope.launch {
            client.observeCrate().collect { next -> phoneCrate.value = next }
        }
        scope.launch {
            coordinator.observeOffer().collect { next -> offer.value = next }
        }
        scope.launch {
            // Reads the persisted selection, sweeps anything an interrupted transfer left, and takes in
            // whatever the phone published while the app was closed. It is also the only place the store
            // is scanned on a cold start, so the on-watch screen has something to draw before it opens.
            coordinator.load()
            // A session, so the phone publishes a fresh offer and picks up where the transfer left off.
            // Not urgent: nothing has been asked for, so the charger rule applies.
            coordinator.startSession(urgent = false)
            // Only now, when the store's contents are known. Binding a controller starts the local
            // service, so a watch holding nothing never does it.
            if (store.contents.value.trackCount > 0) local.connect()
        }
    }

    override fun onStop() {
        super.onStop()
        // Cancels the collections, which cancels the flows, which removes the data-layer listeners and
        // stops renewing the phone's publishing window.
        collection?.cancel()
        collection = null
        // The controller goes; the session does not. Music started on the watch keeps playing with the
        // screen off, which is the whole point of it being a foreground service.
        activityScope.launch { local.disconnect() }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    /**
     * Plays an album out of the watch's own store.
     *
     * The notification permission is asked for here rather than at launch: this is the first moment it
     * buys the user anything. See the class note.
     */
    private suspend fun playFromWatch(albumKey: String) {
        requestNotificationPermissionIfNeeded()
        val album: WearStoredAlbum = store.contents.value.album(albumKey) ?: return
        local.playAlbum(album = album, startIndex = 0)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted: Boolean = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        /** Nav route for the transport screen. A local route, not a data-layer path. */
        const val ROUTE_NOW_PLAYING: String = "now-playing"

        /** Nav route for the crate. */
        const val ROUTE_CRATE: String = "crate"

        /** Nav route for the on-watch library. */
        const val ROUTE_ON_WATCH: String = "on-watch"
    }
}

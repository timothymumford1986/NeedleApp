package app.needler.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import app.needler.wear.playback.DataLayerPlaybackClient
import app.needler.wear.playback.WearPlaybackClient
import app.needler.wear.playback.WearPlaybackState
import app.needler.wear.ui.NeedlerWearTheme
import app.needler.wear.ui.NowPlayingScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The watch's one activity.
 *
 * Needler on the watch is a single screen, so this is a single activity hosting a single composable,
 * in the same spirit as the phone's `MainActivity` and for much simpler reasons.
 *
 * ## No Hilt, on purpose
 *
 * `wear/build.gradle.kts` spells this out: "No Hilt. Hilt needs an `@HiltAndroidApp` Application class
 * and the watch app does not have one yet. Add `id(\"needler.hilt\")` at the same time as that class,
 * not before." So the one dependency this app has is constructed here, by hand. It is one object with
 * one constructor argument; a graph would be ceremony. When the crate and on-device playback arrive
 * and there are several, that is the moment to add the Application class and the plugin together.
 *
 * ## No ViewModel either, and that is the less obvious call
 *
 * `androidx.lifecycle:lifecycle-viewmodel-compose` and `lifecycle-runtime-compose` are not
 * dependencies of this module, so neither `viewModel()` nor `collectAsStateWithLifecycle()` is
 * available, and adding them to a module whose whole state is one value would be a poor trade. What a
 * ViewModel would buy - surviving a configuration change - is close to worthless on a watch, which
 * does not rotate and has no multi-window.
 *
 * What is *not* worthless is stopping the data-layer listener when the screen goes away, and that is
 * what [onStart] and [onStop] do below. Composition is not disposed when a watch screen turns off, so
 * collecting inside the composition alone would keep a Play services listener registered all day,
 * waking this process for every track change on the phone while nobody is looking at the watch. The
 * scope is created and cancelled by hand rather than through `lifecycleScope`, because that lives in
 * `lifecycle-runtime-ktx` and this module does not declare it: eight lines of explicit scope beat a
 * dependency taken on a transitive guess.
 *
 * The state itself is a [MutableStateFlow] held by the activity, so a stop and a later start show the
 * last known value immediately and then correct it, rather than flashing "Connecting" every time the
 * user lowers and raises their wrist.
 */
class NeedlerWearActivity : ComponentActivity() {

    private val client: WearPlaybackClient by lazy { DataLayerPlaybackClient(applicationContext) }

    private val playback: MutableStateFlow<WearPlaybackState> =
        MutableStateFlow(WearPlaybackState.Connecting)

    /** Alive only between [onStart] and [onStop]; see the class note. */
    private var collection: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NeedlerWearTheme {
                val state: WearPlaybackState by playback.collectAsState()
                val commands: CoroutineScope = rememberCoroutineScope()

                // Artwork is fetched on its own, keyed on the identifier rather than the state, so a
                // pause or a buffering flicker does not re-request a cover that has not changed. The
                // key going null (nothing playing, or a track with no artwork) resets it to null,
                // which is what the screen's placeholder is for.
                val artworkId: String? = (state as? WearPlaybackState.NowPlaying)?.artworkId
                val artwork: State<ImageBitmap?> = produceState<ImageBitmap?>(null, artworkId) {
                    value = artworkId?.let { id -> client.loadArtwork(id) }
                }

                NowPlayingScreen(
                    state = state,
                    artwork = artwork.value,
                    // Commands are fire-and-forget by design - see WearPlaybackClient on why none of
                    // them returns a result - so they are launched and forgotten. The confirmation a
                    // user sees is the next snapshot arriving with a changed state.
                    onPlayPause = { commands.launch { client.playPause() } },
                    onPrevious = { commands.launch { client.skipToPrevious() } },
                    onNext = { commands.launch { client.skipToNext() } },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        collection = scope
        scope.launch {
            client.observe().collect { next -> playback.value = next }
        }
    }

    override fun onStop() {
        super.onStop()
        // Cancels the collection, which cancels the flow, which removes the data-layer listener.
        collection?.cancel()
        collection = null
    }
}

package app.needler.wear.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * [WearPlaybackClient] over the Wearable data layer: `DataClient` for state, `MessageClient` for
 * commands, `NodeClient` to know whether the phone is there at all.
 *
 * Everything Google Play services touches in this module is behind this class. Above it, the screen
 * sees [WearPlaybackState] and three suspending functions - see [WearPlaybackClient] for why that
 * seam is where it is.
 *
 * ## Nothing here throws at the caller
 *
 * Every data-layer call is wrapped, and a failure becomes a truthful state rather than an exception.
 * This is not defensive habit: the failure modes are ordinary, not exceptional. Google Play services
 * may be absent or out of date on the watch; the two apps may be signed with different keys, which
 * the data layer treats as two unrelated apps; the phone app may simply never have been installed.
 * All three are a permanent "the phone is not there", which is a thing to *show*, not to crash on.
 *
 * ## Why the connected-node check comes first
 *
 * A retained data item outlives the connection that delivered it. Reading one while no node is
 * connected would render a now-playing screen, with a live-looking transport, describing whatever was
 * playing when the watch last saw the phone - and every button on it would silently do nothing. So
 * the node check gates the retained read: with no node, the answer is
 * [WearPlaybackState.PhoneUnreachable] regardless of what is cached. Once a change event arrives the
 * link is proven live by the event itself, so no further checking is needed.
 *
 * ## Lifecycle
 *
 * [observe] is cold. The listener is registered when collection starts and removed when it stops, so
 * the cost of keeping a data-layer listener alive is paid only while something is collecting -
 * `NeedlerWearActivity` collects between `onStart` and `onStop` and no longer. There is deliberately
 * no `WearableListenerService`: this app has nothing to do with a snapshot while its screen is off,
 * and a manifest-declared listener service would be woken for every update all day. A tile or a
 * complication would change that calculation; neither is built.
 */
class DataLayerPlaybackClient(context: Context) : WearPlaybackClient {

    private val appContext: Context = context.applicationContext
    private val dataClient: DataClient = Wearable.getDataClient(appContext)
    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)
    private val nodeClient: NodeClient = Wearable.getNodeClient(appContext)

    /**
     * The artwork asset from the most recent snapshot, and its id.
     *
     * Held here rather than in [WearPlaybackState] so the UI model stays free of Google Play services
     * types. An `Asset` in a `DataMap` is only a digest - the bytes are fetched separately - so
     * retaining one after the event buffer has been released is safe and is exactly how the data
     * layer is meant to be used.
     *
     * Volatile because the write happens on the data-layer listener's thread and the read happens on
     * whichever coroutine the screen is loading artwork from.
     */
    @Volatile
    private var offeredArtworkId: String? = null

    @Volatile
    private var offeredArtwork: Asset? = null

    /**
     * The last decoded bitmap, so that a pause, a skip within an album or a recomposition does not
     * re-fetch a cover that has not changed. One entry is enough: the watch shows one track.
     */
    @Volatile
    private var decodedArtworkId: String? = null

    @Volatile
    private var decodedArtwork: ImageBitmap? = null

    override fun observe(): Flow<WearPlaybackState> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { events ->
            // The buffer is released as soon as this callback returns, so every item is decoded
            // synchronously here. Only the last relevant event matters: these are states, not a log,
            // and an intermediate one has already been superseded.
            var latest: WearPlaybackState? = null
            for (event in events) {
                val item: DataItem = event.dataItem
                if (item.uri.path != WearPlaybackProtocol.PATH_NOW_PLAYING) continue
                latest = if (event.type == DataEvent.TYPE_DELETED) {
                    // The phone withdrew the snapshot: the session went away entirely.
                    WearPlaybackState.Idle
                } else {
                    decode(item)
                }
            }
            val resolved: WearPlaybackState? = latest
            if (resolved != null) trySend(resolved)
        }

        val nodes: List<Node> = connectedNodes()
        if (nodes.isEmpty()) {
            send(WearPlaybackState.PhoneUnreachable)
        } else {
            // A reachable phone that has published nothing is idle, not silent.
            send(readRetainedSnapshot() ?: WearPlaybackState.Idle)
        }

        // Registered even when no node is connected: pairing can complete while this screen is up,
        // and the first snapshot afterwards should simply appear.
        orNullOnFailure { dataClient.addListener(listener).awaitResult() }

        awaitClose { orNullOnFailure { dataClient.removeListener(listener) } }
    }.conflate()

    override suspend fun loadArtwork(artworkId: String): ImageBitmap? {
        val alreadyDecoded: ImageBitmap? = decodedArtwork
        if (alreadyDecoded != null && decodedArtworkId == artworkId) return alreadyDecoded

        // Only serve the asset that belongs to the id being asked for. A skip between the snapshot
        // arriving and this call landing would otherwise paint the previous track's cover.
        val asset: Asset = offeredArtwork?.takeIf { offeredArtworkId == artworkId } ?: return null

        val bitmap: Bitmap = withContext(Dispatchers.IO) {
            orNullOnFailure {
                dataClient.getFdForAsset(asset).awaitResult().inputStream?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }
        } ?: return null

        val image: ImageBitmap = bitmap.asImageBitmap()
        decodedArtworkId = artworkId
        decodedArtwork = image
        return image
    }

    override suspend fun playPause() {
        broadcast(WearPlaybackProtocol.PATH_PLAY_PAUSE)
    }

    override suspend fun skipToNext() {
        broadcast(WearPlaybackProtocol.PATH_NEXT)
    }

    override suspend fun skipToPrevious() {
        broadcast(WearPlaybackProtocol.PATH_PREVIOUS)
    }

    /**
     * Sends [path] to every connected node.
     *
     * Every node, rather than the one running Needler, because narrowing it needs a `CapabilityClient`
     * capability declared by the phone app in `res/values/wear.xml` - which is in `:app`, not here.
     * Broadcasting is harmless in the meantime: a node with no listener for the path drops the
     * message. It is still worth narrowing later, for the ordinary reason that shouting a command at
     * every paired device is not a thing to leave in place once the alternative is available.
     *
     * A failed send is swallowed. There is nothing useful to say about it: the honest feedback that a
     * command worked is the next snapshot, and one that did not work produces no snapshot.
     */
    private suspend fun broadcast(path: String) {
        for (node in connectedNodes()) {
            orNullOnFailure { messageClient.sendMessage(node.id, path, EMPTY_PAYLOAD).awaitResult() }
        }
    }

    private suspend fun connectedNodes(): List<Node> =
        orNullOnFailure { nodeClient.connectedNodes.awaitResult() } ?: emptyList()

    /**
     * The copy of the now-playing item that Google Play services already holds on this watch.
     *
     * This is the reason state is a data item rather than a message: it is here before the phone is
     * asked anything, so a watch app opened from the launcher draws the right thing on its first
     * frame instead of a spinner.
     */
    private suspend fun readRetainedSnapshot(): WearPlaybackState? {
        val buffer: DataItemBuffer =
            orNullOnFailure { dataClient.dataItems.awaitResult() } ?: return null
        return try {
            var found: WearPlaybackState? = null
            for (item in buffer) {
                if (item.uri.path == WearPlaybackProtocol.PATH_NOW_PLAYING) found = decode(item)
            }
            found
        } finally {
            // A data buffer holds a native allocation; it is released even when decoding throws.
            buffer.release()
        }
    }

    /**
     * One [DataItem] to one [WearPlaybackState], plus the side effect of remembering the artwork
     * asset for a later [loadArtwork].
     *
     * Missing keys fall back rather than failing. The phone and the watch are two separately
     * installed APKs with independent version codes - `wear/build.gradle.kts` sets `versionName` and
     * `versionCode` for this module alone precisely so they can ship apart - so a watch talking to an
     * older or newer phone is a normal situation, not a corrupt one. A snapshot with a key this build
     * does not know about renders with the keys it does.
     */
    private fun decode(item: DataItem): WearPlaybackState {
        val map: DataMap = DataMapItem.fromDataItem(item).dataMap
        if (!map.getBoolean(WearPlaybackProtocol.KEY_HAS_ITEM, false)) {
            return WearPlaybackState.Idle
        }

        val artworkId: String? =
            map.getString(WearPlaybackProtocol.KEY_ARTWORK_ID)?.takeIf { it.isNotBlank() }
        offeredArtworkId = artworkId
        offeredArtwork = map.getAsset(WearPlaybackProtocol.KEY_ARTWORK)

        return WearPlaybackState.NowPlaying(
            title = map.getString(WearPlaybackProtocol.KEY_TITLE, ""),
            artist = map.getString(WearPlaybackProtocol.KEY_ARTIST, ""),
            album = map.getString(WearPlaybackProtocol.KEY_ALBUM)?.takeIf { it.isNotBlank() },
            isPlaying = map.getBoolean(WearPlaybackProtocol.KEY_IS_PLAYING, false),
            isBuffering = map.getBoolean(WearPlaybackProtocol.KEY_IS_BUFFERING, false),
            artworkId = artworkId,
        )
    }

    private companion object {
        /**
         * Commands carry no arguments; the path is the whole instruction.
         *
         * An empty array rather than null, because `MessageClient.sendMessage` is the one data-layer
         * call whose payload nullability has moved between Play services versions, and an empty array
         * means the same thing to the phone either way.
         */
        val EMPTY_PAYLOAD: ByteArray = ByteArray(0)
    }
}

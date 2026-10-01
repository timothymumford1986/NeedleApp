package app.needler.wear

import android.content.Context
import android.os.SystemClock
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.playback.PlaybackState
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone half of the Wear bridge: it publishes [WearPlaybackProtocol.PATH_NOW_PLAYING] and
 * [WearPlaybackProtocol.PATH_CRATE] from `PlaybackController`'s own flows.
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Wear OS" makes the watch a client of a data-layer bridge
 * rather than of the Media3 session, because a `MediaController` cannot reach a `MediaSession` in
 * another process on another device. This is the end of that bridge that can see the session. It reads
 * through [PlaybackController] like every other surface - the widgets, Android Auto, the player screen
 * - so nothing here touches Media3, `:core:data` or a `MediaController`, and "The player boundary"
 * holds.
 *
 * ## The lifecycle decision, which is the whole design
 *
 * Two constraints pull in opposite directions.
 *
 * REQUIREMENTS.md "Battery and data" forbids "long-lived connections while backgrounded", and a
 * publisher that observed the session from process start would be exactly that: a Bluetooth write per
 * track, all day, for a watch whose screen is off and whose app is not running. Collecting
 * `observeState()` also *connects* a `MediaController`, which starts the playback service - so a
 * publisher that ran unconditionally would keep the media service bound whether or not anybody was
 * listening to anything.
 *
 * The other constraint is that a `WearableListenerService` is not a place to observe anything. Google
 * Play services starts it for one message and stops it when it goes idle, which is right for a command
 * and useless for a flow: a collector started in `onMessageReceived` dies with the callback.
 *
 * So the publisher is a `@Singleton` with a window, and the window is opened by the watch.
 * [NeedlerWearListenerService] calls [onStateRequested] when a
 * [WearPlaybackProtocol.PATH_REQUEST_STATE] message arrives, which publishes a snapshot and then keeps
 * observing for [WearPlaybackProtocol.STATE_WINDOW_MS]. A watch that is still showing a transport
 * renews it, a watch whose wrist has dropped stops renewing, and the window lapses on its own. The
 * argument for pulling rather than pushing, and for a lapsing window rather than an explicit "I have
 * gone away" message, is recorded in the protocol under "The phone does not publish all day"; the
 * short version is that the teardown half of a handshake is the half that goes missing when a watch
 * walks out of range.
 *
 * Every inbound command renews the window too, so that a watch which pressed pause sees the pause even
 * if its renewal was the message that went astray.
 *
 * ## Why it survives the service that woke it
 *
 * The window outlives [NeedlerWearListenerService] by design - it is held in this singleton's own
 * scope, not the service's. What it does *not* outlive is the process, and that is deliberate rather
 * than overlooked: while music is playing the playback service is in the foreground and the process is
 * not going anywhere, which is precisely when there is something to publish. With nothing playing the
 * process is cacheable and may be reclaimed mid-window - and the only thing lost is the publishing of
 * a state that is not changing. The next request-state starts the process again, because a
 * `WearableListenerService` message does exactly that.
 *
 * There is therefore nothing to wire into `NeedlerApplication` or `MainActivity`. Nothing starts this
 * class; the watch does, by asking. A publisher started at application launch would be the very thing
 * the window exists to avoid.
 */
@Singleton
class WearStatePublisher @Inject constructor(
    @ApplicationContext context: Context,
    private val controller: PlaybackController,
    private val artwork: WearArtworkAssets,
) {

    private val dataClient: DataClient = Wearable.getDataClient(context)

    /**
     * The scope the publishing window lives in.
     *
     * Application-scoped and never cancelled, for the same reason `:core:data`'s `DataScope` is: it
     * has to outlive the `WearableListenerService` callback that opens a window, which is over in
     * milliseconds. `SupervisorJob` so that one window failing - Google Play services missing, a
     * session that will not connect - does not poison the scope for every later one. The default
     * dispatcher because the two flows arrive on the main one already - `PlaybackController` applies
     * `flowOn` itself - and everything this class does with what they emit is encoding.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guards [publishingWindow] and [windowExpiresAt] against two messages arriving at once. */
    private val windowLock: Mutex = Mutex()

    private var publishingWindow: Job? = null

    /**
     * When the current window lapses, on the monotonic clock.
     *
     * `elapsedRealtime`, not wall clock: a window must not be extended or ended by a user correcting
     * their timezone. Volatile because it is written under [windowLock] and read by the window's own
     * deadline loop, which does not take the lock - it only ever needs the latest value.
     */
    @Volatile
    private var windowExpiresAt: Long = 0L

    /**
     * A watch has asked for state: publish now, and keep publishing for a while.
     *
     * Idempotent and cheap to repeat. When a window is already open this only pushes its deadline out,
     * which is what a renewal is; the wire is already current, so there is nothing to publish.
     *
     * Suspends until the first snapshot has actually been put, or until [FIRST_PUBLISH_TIMEOUT_MS],
     * whichever comes first. That matters because the caller is a `WearableListenerService` callback,
     * and the service is alive only while the callback runs: returning immediately would let Google
     * Play services stop the service, and on a phone with nothing else running, kill the process,
     * before the snapshot had left the building. The timeout is there because
     * `PlaybackController`'s commands deliberately wait for the session to connect, and a session that
     * never connects must not hold a Play services dispatch thread for ever.
     */
    suspend fun onStateRequested() {
        val awaitFirstPublish: CompletableDeferred<Unit>? = windowLock.withLock {
            windowExpiresAt = SystemClock.elapsedRealtime() + WearPlaybackProtocol.STATE_WINDOW_MS
            if (publishingWindow?.isActive == true) {
                // Already observing. The new deadline above is the whole effect of this call.
                null
            } else {
                val firstPublish: CompletableDeferred<Unit> = CompletableDeferred()
                publishingWindow = scope.launch { publishWhileWatched(firstPublish) }
                firstPublish
            }
        }
        // Awaited outside the lock: a second request arriving while the session connects should renew
        // the deadline immediately rather than queue behind this one's first publish.
        if (awaitFirstPublish != null) {
            withTimeoutOrNull(FIRST_PUBLISH_TIMEOUT_MS) { awaitFirstPublish.await() }
        }
    }

    /**
     * Observes both flows until the window lapses.
     *
     * The deadline is checked by re-reading [windowExpiresAt] after each sleep rather than by a
     * timeout, which is what makes a renewal free: extending the window is one volatile write by
     * whoever handled the message, and this loop simply sleeps again.
     *
     * The collectors are one child job so that the deadline can end them both together, and the
     * deadline is a second child that ends when they do. Both directions matter: without the first,
     * the phone publishes after the watch has stopped asking; without the second, a window whose
     * collectors ended early - Google Play services missing, a session that will not connect - would
     * still look active to [onStateRequested] for the rest of the minute, and a watch asking again
     * would extend a window with nothing left in it instead of starting a new one.
     */
    private suspend fun publishWhileWatched(firstPublish: CompletableDeferred<Unit>) {
        try {
            coroutineScope {
                val collectors: Job = launch {
                    launch { publishStateChanges(firstPublish) }
                    launch { publishCrateChanges() }
                }
                val deadline: Job = launch {
                    while (true) {
                        val remaining: Long = windowExpiresAt - SystemClock.elapsedRealtime()
                        if (remaining <= 0L) break
                        delay(remaining)
                    }
                    collectors.cancel()
                }
                collectors.invokeOnCompletion { deadline.cancel() }
            }
        } finally {
            // Releases anyone waiting on a window that ended without ever publishing - a session that
            // never connected, a Play services failure - rather than making them wait out the
            // timeout.
            firstPublish.complete(Unit)
        }
    }

    /**
     * Republishes the now-playing item on every session change a person caused.
     *
     * `observeState()` emits once on collection and then on change only, so the first emission is the
     * snapshot a request-state asked for and no separate one-shot read is needed. A second read would
     * publish the same state twice with two different [WearPlaybackProtocol.KEY_PUBLISHED_AT] values,
     * which is two Bluetooth syncs for one fact.
     *
     * A failure ends this collector quietly and leaves the last published item standing, which is the
     * honest outcome: the watch keeps showing what it was last told and its next renewal tries again.
     */
    private suspend fun publishStateChanges(firstPublish: CompletableDeferred<Unit>) {
        orNullOnFailure {
            controller.observeState().collect { state: PlaybackState ->
                publishNowPlaying(WearSnapshots.nowPlaying(state))
                firstPublish.complete(Unit)
            }
        }
    }

    /** The same, for the crate. A separate flow, so a crate edit does not republish the transport. */
    private suspend fun publishCrateChanges() {
        orNullOnFailure {
            controller.observeQueue().collect { queue: PlayQueue ->
                publishCrate(WearSnapshots.crateWindow(queue))
            }
        }
    }

    private suspend fun publishNowPlaying(snapshot: WearNowPlaying) {
        val request: PutDataMapRequest =
            PutDataMapRequest.create(WearPlaybackProtocol.PATH_NOW_PLAYING)
        val map: DataMap = request.dataMap
        map.putBoolean(WearPlaybackProtocol.KEY_HAS_ITEM, snapshot.hasItem)
        map.putString(WearPlaybackProtocol.KEY_TITLE, snapshot.title)
        map.putString(WearPlaybackProtocol.KEY_ARTIST, snapshot.artist)
        // Blank rather than absent: the watch reads a blank album as "no third line", and a map whose
        // shape does not change with the data is one less thing to get wrong on either side.
        map.putString(WearPlaybackProtocol.KEY_ALBUM, snapshot.album ?: "")
        map.putBoolean(WearPlaybackProtocol.KEY_IS_PLAYING, snapshot.isPlaying)
        map.putBoolean(WearPlaybackProtocol.KEY_IS_BUFFERING, snapshot.isBuffering)

        val artworkId: String? = snapshot.artworkId
        val ref: ArtworkRef? = snapshot.artwork
        if (artworkId != null && ref != null) {
            // Both keys or neither. An id with no asset would send the watch looking for bytes that
            // are not coming, and it caches decoded covers by that id.
            //
            // Capped, because a cover is worth less than a transport. The first publish of a window is
            // what the listener service is holding a Play services thread for, and a cover fetched
            // over a slow home connection must not be what decides when the watch learns that
            // something is playing. A miss leaves the watch drawing its record placeholder, which is
            // a complete state, and the next state change tries again.
            val asset: Asset? = withTimeoutOrNull(ARTWORK_TIMEOUT_MS) {
                artwork.assetFor(artworkId, ref)
            }
            if (asset != null) {
                map.putString(WearPlaybackProtocol.KEY_ARTWORK_ID, artworkId)
                map.putAsset(WearPlaybackProtocol.KEY_ARTWORK, asset)
            }
        }
        publish(request)
    }

    private suspend fun publishCrate(crate: WearCrateWindow) {
        val request: PutDataMapRequest = PutDataMapRequest.create(WearPlaybackProtocol.PATH_CRATE)
        val map: DataMap = request.dataMap
        val encoded: ArrayList<DataMap> = ArrayList(crate.rows.size)
        for (row in crate.rows) {
            val rowMap = DataMap()
            rowMap.putString(WearPlaybackProtocol.KEY_ROW_ID, row.id)
            rowMap.putString(WearPlaybackProtocol.KEY_ROW_TITLE, row.title)
            rowMap.putString(WearPlaybackProtocol.KEY_ROW_ARTIST, row.artist)
            encoded.add(rowMap)
        }
        map.putDataMapArrayList(WearPlaybackProtocol.KEY_CRATE_ROWS, encoded)
        map.putInt(WearPlaybackProtocol.KEY_CRATE_CURRENT, crate.currentIndexInWindow)
        map.putInt(WearPlaybackProtocol.KEY_CRATE_WINDOW_START, crate.windowStart)
        map.putInt(WearPlaybackProtocol.KEY_CRATE_TOTAL, crate.total)
        publish(request)
    }

    /**
     * Puts one item, urgently.
     *
     * `setUrgent()` is not optional here and is the easiest thing in the data layer to leave out. A
     * data item published without it syncs "when convenient", which Google Play services is entitled
     * to read as tens of minutes; a now-playing snapshot that arrives after the song has finished is
     * worse than none. The two items Needler publishes are both small and both only published while a
     * watch is looking, so there is nothing here that wants batching.
     *
     * [WearPlaybackProtocol.KEY_PUBLISHED_AT] is stamped here, in the one place every publish passes
     * through, so no encoder can forget it and have its update silently swallowed as a byte-identical
     * put. Wall clock rather than a counter on purpose: a counter restarts at zero with the process,
     * and the first publish after a restart could then be byte-identical to the retained copy from
     * before it.
     */
    private suspend fun publish(request: PutDataMapRequest) {
        request.dataMap.putLong(WearPlaybackProtocol.KEY_PUBLISHED_AT, System.currentTimeMillis())
        orNullOnFailure {
            dataClient.putDataItem(request.asPutDataRequest().setUrgent()).awaitResult()
        }
    }

    private companion object {
        /**
         * How long [onStateRequested] will hold its caller while the first snapshot goes out.
         *
         * Long enough to cover a cold `MediaController` bind, which is the slow case and the common
         * one - a watch app opened while the phone has not played anything yet. Short enough that a
         * session which never connects cannot wedge a Google Play services dispatch thread.
         */
        const val FIRST_PUBLISH_TIMEOUT_MS: Long = 5_000L

        /**
         * How long a cover may hold up the item it belongs to.
         *
         * Comfortably inside [FIRST_PUBLISH_TIMEOUT_MS], so the artwork is never the reason a caller
         * times out - it is the reason the *cover* is missing from one publish, which the watch draws
         * a placeholder for.
         */
        const val ARTWORK_TIMEOUT_MS: Long = 3_000L
    }
}

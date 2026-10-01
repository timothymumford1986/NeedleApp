package app.needler.wear.sync

import android.content.Context
import android.os.BatteryManager
import app.needler.wear.playback.WearPlaybackProtocol
import app.needler.wear.playback.awaitResult
import app.needler.wear.playback.orNullOnFailure
import app.needler.wear.store.WearAudioKey
import app.needler.wear.store.WearAudioStore
import app.needler.wear.store.WearIngestOutcome
import app.needler.wear.store.WearRemoved
import app.needler.wear.store.WearStoreContents
import app.needler.wear.store.WearTrackRecord
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
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The watch's end of the audio sync: it publishes what the user wants, asks the phone to act, and pulls
 * arriving tracks into [WearAudioStore].
 *
 * Everything Google Play services touches on this side of the sync is here, exactly as
 * [app.needler.wear.playback.DataLayerPlaybackClient] holds it for the transport. The decisions are in
 * [WearSyncSelection] and [WearActiveAlbum], which are pure and tested.
 *
 * ## The pipeline, from this end
 *
 * 1. The user selects an album. [select] persists it, publishes the selection and nudges.
 * 2. The phone reads the selection, works out the next tracks and publishes them as data items.
 * 3. A track item arriving wakes [NeedlerWearSyncService], which calls [ingestPending].
 * 4. [ingestPending] writes what it finds, republishes the selection - now reporting more held tracks -
 *    and nudges again, which is what makes the phone publish the next tracks and delete the finished
 *    ones.
 *
 * The watch is the clock. Nothing here polls and nothing runs on a timer: every pass is caused by
 * something arriving or by the user doing something.
 *
 * ## Why the retained set is the source of truth, not the event
 *
 * [ingestPending] does not read the `DataEvent` that woke it. It reads **every** retained item under the
 * sync paths and ingests all of them. That is deliberate and it is what makes the pipeline self-healing
 * in three situations an event-driven version gets wrong:
 *
 *  * Two items arrive in one batch and the callback is cut short after the first. The second is still
 *    retained, and the next pass finds it.
 *  * The watch app is killed, or the watch is rebooted, mid-transfer. Nothing replays the event; the
 *    items are still there, and [ingestPending] on next launch picks them up.
 *  * An ingest fails for a transient reason. The item is still published, so the retry is free - which
 *    is the argument for not putting this on `WorkManager`. That would mean a new dependency and an
 *    initialiser to configure in a module with no Hilt, to buy reliability that a retained data item
 *    already provides.
 *
 * ## Deleting the items is the phone's job
 *
 * The watch never deletes a track item it has ingested, even though it could. A deletion replicates, so
 * two nodes deleting the same item is a race whose loser sees a delete event for something it is in the
 * middle of writing. The phone publishes and the phone deletes; the watch reports what it holds and lets
 * the phone draw the conclusion. That is also why an [WearIngestOutcome.AlreadyHeld] is reported as a
 * success - it is what tells the phone the item has done its job.
 *
 * ## One instance per process
 *
 * Same reason as [WearAudioStore.get]: the activity and the sync service both need this object and
 * neither can be injected, because `wear/build.gradle.kts` deliberately has no `@HiltAndroidApp` class.
 */
class WearSyncCoordinator private constructor(
    context: Context,
    private val store: WearAudioStore,
) {

    private val appContext: Context = context.applicationContext
    private val dataClient: DataClient = Wearable.getDataClient(appContext)
    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)
    private val nodeClient: NodeClient = Wearable.getNodeClient(appContext)

    /**
     * Guards the selection file and the publish, so two taps in quick succession cannot interleave a
     * read-modify-write of the user's choices.
     */
    private val lock: Mutex = Mutex()

    private val selectionState: MutableStateFlow<WearSyncSelection> =
        MutableStateFlow(WearSyncSelection.Empty)

    /** What the user has asked for. Loaded from disk by [load]; empty until then. */
    val selection: StateFlow<WearSyncSelection> = selectionState.asStateFlow()

    /**
     * Advanced once per sync session, and read only when every wanted album is already full.
     *
     * See [WearActiveAlbum] for what it is for. Not persisted: losing it restarts the staleness rotation
     * at the first album, which costs one redundant comparison.
     */
    @Volatile
    private var rotation: Int = 0

    /**
     * Reads the persisted selection, tidies the store and takes in anything already waiting.
     *
     * Called when the watch app starts. The sweep is first because a store with an interrupted transfer
     * in it would otherwise report holding a track it cannot play, and the phone would believe that
     * track was done.
     */
    suspend fun load() {
        lock.withLock {
            selectionState.value = WearSyncSelection.decode(readSelectionFile())
        }
        store.sweep()
        store.refresh()
        ingestPending()
    }

    /**
     * Begins a sync session: advance the staleness rotation, publish the selection, and ask the phone to
     * act.
     *
     * Called when the on-watch screen opens and when the user asks to sync. [urgent] is the difference
     * between the two: see [WearPlaybackProtocol.NUDGE_NOW].
     */
    suspend fun startSession(urgent: Boolean) {
        rotation += 1
        publishSelection()
        nudge(urgent = urgent)
    }

    /**
     * Adds an album to what the user wants on the watch, and starts fetching it now.
     *
     * Urgent, because the user has just asked: [WearPlaybackProtocol.KEY_WATCH_CHARGING] would otherwise
     * hold the transfer until the watch was on its charger, and somebody selecting an album is usually
     * about to walk out of the door with it.
     *
     * @return false when the selection was already full - see [WearPlaybackProtocol.MAX_WANTED_ALBUMS] -
     *   or the album could not fit, so the screen can say which rather than appearing to ignore the tap.
     */
    suspend fun select(albumKey: String): Boolean {
        val updated: WearSyncSelection = lock.withLock {
            val current: WearSyncSelection = selectionState.value
            val next: WearSyncSelection = current.with(albumKey)
            if (next == current) return@withLock null
            writeSelectionFile(next)
            selectionState.value = next
            next
        } ?: return false
        publishSelection(updated)
        nudge(urgent = true)
        return true
    }

    /**
     * Stops fetching an album, leaving whatever already arrived on the watch.
     *
     * Two separate actions, and the separation is the point: tracks that have crossed the link cost
     * minutes each, and un-wanting an album is not a request to throw them away. [removeFromWatch] is
     * the one that deletes bytes.
     */
    suspend fun deselect(albumKey: String) {
        val updated: WearSyncSelection = lock.withLock {
            val current: WearSyncSelection = selectionState.value
            val next: WearSyncSelection = current.without(albumKey)
            if (next == current) return@withLock null
            writeSelectionFile(next)
            selectionState.value = next
            next
        } ?: return
        publishSelection(updated)
        // Nudged so the phone stops publishing items for an album nobody wants and deletes the ones it
        // has already put up, which is what frees the asset storage on both nodes.
        nudge(urgent = false)
    }

    /**
     * Deletes an album's bytes from the watch and stops wanting it.
     *
     * Both, because a remove that left the album selected would have the pipeline fetch it straight back
     * - which is the one outcome that would make the control untrustworthy, in the same way
     * REQUIREMENTS.md describes for the phone: "a 'remove' that leaves the usage figure unchanged is the
     * one thing that would make this whole screen untrustworthy."
     */
    suspend fun removeFromWatch(albumKey: String): WearRemoved {
        deselect(albumKey)
        val removed: WearRemoved = store.removeAlbum(albumKey)
        publishSelection()
        return removed
    }

    /** Deletes everything on the watch and clears the selection. */
    suspend fun removeAllFromWatch(): WearRemoved {
        lock.withLock {
            writeSelectionFile(WearSyncSelection.Empty)
            selectionState.value = WearSyncSelection.Empty
        }
        val removed: WearRemoved = store.removeAll()
        publishSelection()
        nudge(urgent = false)
        return removed
    }

    /**
     * The phone's offer of downloadable albums.
     *
     * Cold, and shaped exactly like [app.needler.wear.playback.WearPlaybackClient.observe]: the node
     * check gates the retained read, because a retained offer outlives the link that delivered it and a
     * picker whose every tap does nothing is worse than a picker that says the phone is not there.
     *
     * It does not nudge. The offer changes when the user downloads something on the phone, which is rare
     * compared with a watch screen opening, so asking for it is [startSession]'s job and this is only
     * the reading.
     */
    fun observeOffer(): Flow<WearOfferState> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { events ->
            // The buffer is released the moment this returns, so everything is decoded synchronously
            // here. Only the last event for the path matters: this is a state, not a log.
            var latest: WearOfferState? = null
            for (event in events) {
                val item: DataItem = event.dataItem
                if (item.uri.path != WearPlaybackProtocol.PATH_SYNC_OFFER) continue
                latest = if (event.type == DataEvent.TYPE_DELETED) {
                    WearOfferState.Offered.Nothing
                } else {
                    decodeOffer(item)
                }
            }
            latest?.let { resolved -> trySend(resolved) }
        }

        val nodes: List<Node> = connectedNodes()
        if (nodes.isEmpty()) {
            send(WearOfferState.PhoneUnreachable)
        } else {
            send(readRetainedOffer() ?: WearOfferState.Offered.Nothing)
        }

        // Registered even with no node connected: pairing can complete while this screen is up, and the
        // first offer afterwards should simply appear.
        orNullOnFailure { dataClient.addListener(listener).awaitResult() }

        awaitClose { orNullOnFailure { dataClient.removeListener(listener) } }
    }.conflate()

    /**
     * Publishes what the watch wants and what it holds.
     *
     * [WearPlaybackProtocol.KEY_PUBLISHED_AT] is stamped, unlike on a track item, and the asymmetry is
     * deliberate: this item is a few kilobytes, so a redundant replication of it costs nothing, and the
     * one thing that must never happen is a selection the phone does not see because the bytes happened
     * to match a copy that never made it across the link.
     */
    suspend fun publishSelection(selection: WearSyncSelection = selectionState.value) {
        val contents: WearStoreContents = store.contents.value
        val offered: Map<String, Int> = lastOfferedTrackCounts
        val heldCounts: Map<String, Int> = contents.albums.associate { album ->
            album.albumKey to album.trackCount
        }
        val active: String = WearActiveAlbum.of(
            wanted = selection.wantedAlbums,
            offeredTrackCounts = offered,
            heldCounts = heldCounts,
            rotation = rotation,
        )

        val request: PutDataMapRequest =
            PutDataMapRequest.create(WearPlaybackProtocol.PATH_SYNC_SELECTION)
        val map: DataMap = request.dataMap
        map.putStringArrayList(WearPlaybackProtocol.KEY_WANTED_ALBUMS, selection.forWire())
        map.putString(WearPlaybackProtocol.KEY_ACTIVE_ALBUM, active)
        map.putStringArrayList(
            WearPlaybackProtocol.KEY_HELD_TRACKS,
            ArrayList(
                contents.heldEntriesFor(
                    albumKey = active,
                    limit = WearPlaybackProtocol.MAX_HELD_TRACKS,
                ),
            ),
        )
        map.putBoolean(WearPlaybackProtocol.KEY_WATCH_CHARGING, isCharging())
        map.putLong(WearPlaybackProtocol.KEY_WATCH_FREE_BYTES, store.space().usableBytes)
        map.putLong(WearPlaybackProtocol.KEY_PUBLISHED_AT, System.currentTimeMillis())

        orNullOnFailure {
            dataClient.putDataItem(request.asPutDataRequest().setUrgent()).awaitResult()
        }
    }

    /**
     * Asks the phone to read the selection and publish what it asks for.
     *
     * Broadcast to every connected node rather than to the one running Needler, for the reason
     * `DataLayerPlaybackClient.broadcast` records: narrowing it needs a capability declared in `:app`'s
     * `res/values/wear.xml`, which is not this module's file. A node with no listener for the path drops
     * the message.
     */
    suspend fun nudge(urgent: Boolean) {
        val payload: ByteArray = byteArrayOf(
            if (urgent) WearPlaybackProtocol.NUDGE_NOW else WearPlaybackProtocol.NUDGE_WHEN_CHARGING,
        )
        for (node in connectedNodes()) {
            orNullOnFailure {
                messageClient.sendMessage(node.id, WearPlaybackProtocol.PATH_SYNC_NUDGE, payload)
                    .awaitResult()
            }
        }
    }

    /**
     * Takes in every track and cover the phone has published and the watch does not yet hold.
     *
     * Bounded by [MAX_INGESTS_PER_PASS] so one call cannot run indefinitely - the caller is a
     * `WearableListenerService` callback, and a pass that never returns is a service that never goes
     * idle. Anything left over is still retained, so the nudge at the end of this call brings the next
     * pass round to it.
     *
     * @return how many tracks were written.
     */
    suspend fun ingestPending(): Int {
        val items: List<DataItem> = readRetainedSyncItems()
        if (items.isEmpty()) return 0

        var ingested: Int = 0
        var progressed: Boolean = false
        var handled: Int = 0

        for (item in items) {
            if (handled >= MAX_INGESTS_PER_PASS) break
            val path: String = item.uri.path ?: continue
            when {
                path.startsWith(WearPlaybackProtocol.PATH_SYNC_TRACK_PREFIX) -> {
                    handled += 1
                    val outcome: WearIngestOutcome = ingestTrackItem(item)
                    if (outcome == WearIngestOutcome.Ingested) {
                        ingested += 1
                        progressed = true
                    } else if (outcome == WearIngestOutcome.AlreadyHeld) {
                        // Still progress as far as the phone is concerned: reporting it is what lets the
                        // phone delete the item and publish the next track.
                        progressed = true
                    }
                }

                path.startsWith(WearPlaybackProtocol.PATH_SYNC_COVER_PREFIX) -> {
                    handled += 1
                    if (ingestCoverItem(item)) progressed = true
                }
            }
        }

        if (progressed) {
            // The selection now reports more held tracks, which is the only thing that advances the
            // phone's plan; the nudge is what makes it look. Not urgent - the watch has not been asked
            // for anything new, it is reporting what it finished.
            publishSelection()
            nudge(urgent = false)
        }
        return ingested
    }

    // ---- internals -------------------------------------------------------------------------------

    /**
     * The offered track counts from the last offer this process decoded.
     *
     * Held because [publishSelection] needs them to choose an active album and may be called from the
     * sync service, where nothing is collecting [observeOffer]. Empty until an offer has been read, which
     * makes [WearActiveAlbum] answer "nothing to ask for" - correct, because a watch that has never seen
     * an offer cannot know whether an album is complete, and guessing would have it demand tracks the
     * phone may not have.
     */
    @Volatile
    private var lastOfferedTrackCounts: Map<String, Int> = emptyMap()

    private suspend fun ingestTrackItem(item: DataItem): WearIngestOutcome {
        val map: DataMap = DataMapItem.fromDataItem(item).dataMap
        val key: WearAudioKey =
            WearAudioKey.parse(map.getString(WearPlaybackProtocol.KEY_TRACK_KEY))
                ?: return WearIngestOutcome.Refused
        val asset: Asset = map.getAsset(WearPlaybackProtocol.KEY_TRACK_AUDIO)
            ?: return WearIngestOutcome.Refused

        val record = WearTrackRecord(
            key = key,
            title = map.getString(WearPlaybackProtocol.KEY_TRACK_TITLE, ""),
            artist = map.getString(WearPlaybackProtocol.KEY_TRACK_ARTIST, ""),
            albumTitle = map.getString(WearPlaybackProtocol.KEY_TRACK_ALBUM, ""),
            durationMs = map.getLong(WearPlaybackProtocol.KEY_TRACK_DURATION_MS, 0L),
            format = map.getString(WearPlaybackProtocol.KEY_TRACK_FORMAT, ""),
            sizeBytes = map.getLong(WearPlaybackProtocol.KEY_TRACK_BYTES, 0L),
            fingerprint = map.getString(WearPlaybackProtocol.KEY_TRACK_FINGERPRINT, ""),
        )
        // A track with no fingerprint could never be compared against the server, so it would keep
        // playing the pre-upgrade copy for ever. REQUIREMENTS.md "Invalidating upgraded files" is not
        // optional, so the honest answer is to refuse the bytes rather than hold un-checkable ones.
        if (record.fingerprint.isEmpty()) return WearIngestOutcome.Refused

        return store.ingestTrack(record) { openAsset(asset) }
    }

    private suspend fun ingestCoverItem(item: DataItem): Boolean {
        val map: DataMap = DataMapItem.fromDataItem(item).dataMap
        val albumKey: String = map.getString(WearPlaybackProtocol.KEY_COVER_ALBUM_KEY) ?: return false
        val asset: Asset = map.getAsset(WearPlaybackProtocol.KEY_COVER_IMAGE) ?: return false
        return store.ingestCover(albumKey) { openAsset(asset) }
    }

    /**
     * Resolves an `Asset` to its bytes.
     *
     * This is where the transfer actually happens, and it can take minutes: an `Asset` in a `DataMap` is
     * only a digest, and Google Play services fetches the bytes when somebody asks for them. That is
     * the property the whole design rests on - nothing of Needler's is awake while the bytes cross the
     * link - and it is why the caller holds a service callback open rather than a connection.
     */
    private suspend fun openAsset(asset: Asset): InputStream? = withContext(Dispatchers.IO) {
        orNullOnFailure { dataClient.getFdForAsset(asset).awaitResult().inputStream }
    }

    private suspend fun readRetainedSyncItems(): List<DataItem> {
        val buffer: DataItemBuffer =
            orNullOnFailure { dataClient.dataItems.awaitResult() } ?: return emptyList()
        return try {
            val found: MutableList<DataItem> = ArrayList()
            for (item in buffer) {
                val path: String = item.uri.path ?: continue
                if (!path.startsWith(WearPlaybackProtocol.SYNC_NAMESPACE)) continue
                if (path == WearPlaybackProtocol.PATH_SYNC_SELECTION) continue
                if (path == WearPlaybackProtocol.PATH_SYNC_OFFER) continue
                // Freeze() detaches the item from the buffer, so it stays readable after the release
                // below. Without it every field read after this loop would be reading freed memory.
                found.add(item.freeze())
            }
            found
        } finally {
            buffer.release()
        }
    }

    private suspend fun readRetainedOffer(): WearOfferState? {
        val buffer: DataItemBuffer =
            orNullOnFailure { dataClient.dataItems.awaitResult() } ?: return null
        return try {
            var found: WearOfferState? = null
            for (item in buffer) {
                if (item.uri.path == WearPlaybackProtocol.PATH_SYNC_OFFER) found = decodeOffer(item)
            }
            found
        } finally {
            buffer.release()
        }
    }

    /**
     * One offer item to one state.
     *
     * Every field is treated as untrustworthy, for the reason `WearCrateState.of` gives: the phone is a
     * separately installed APK with its own version code, so a row missing its album key is dropped
     * rather than published as an untappable line, and a negative count clamps rather than throwing. A
     * picker that renders slightly wrong against a mismatched phone build is a far better outcome than a
     * watch app that crashes whenever the two disagree.
     */
    private fun decodeOffer(item: DataItem): WearOfferState {
        val map: DataMap = DataMapItem.fromDataItem(item).dataMap
        val encoded: List<DataMap> =
            map.getDataMapArrayList(WearPlaybackProtocol.KEY_OFFER_ALBUMS) ?: emptyList()
        val albums: List<WearOfferedAlbum> = encoded.mapNotNull { row ->
            val albumKey: String = row.getString(WearPlaybackProtocol.KEY_ALBUM_KEY)
                ?.takeIf { key -> WearAudioKey.isSafeIdentifier(key) }
                ?: return@mapNotNull null
            WearOfferedAlbum(
                albumKey = albumKey,
                title = row.getString(WearPlaybackProtocol.KEY_ALBUM_TITLE, ""),
                artist = row.getString(WearPlaybackProtocol.KEY_ALBUM_ARTIST, ""),
                trackCount = row.getInt(WearPlaybackProtocol.KEY_ALBUM_TRACK_COUNT, 0)
                    .coerceAtLeast(0),
                sizeBytes = row.getLong(WearPlaybackProtocol.KEY_ALBUM_BYTES, 0L)
                    .coerceAtLeast(0L),
            )
        }
        lastOfferedTrackCounts = albums.associate { album -> album.albumKey to album.trackCount }
        return WearOfferState.Offered(
            albums = albums,
            notShown = map.getInt(WearPlaybackProtocol.KEY_OFFER_NOT_SHOWN, 0).coerceAtLeast(0),
        )
    }

    private suspend fun connectedNodes(): List<Node> =
        orNullOnFailure { nodeClient.connectedNodes.awaitResult() } ?: emptyList()

    /**
     * Whether the watch is on its charger.
     *
     * `BatteryManager.isCharging` rather than the sticky `ACTION_BATTERY_CHANGED` broadcast: it is one
     * call, it needs no receiver registration, and it has been available since API 23 against this
     * module's minSdk of 26. A missing service answers false, which errs towards not spending the
     * user's battery.
     */
    private fun isCharging(): Boolean {
        val manager: BatteryManager =
            appContext.getSystemService(BatteryManager::class.java) ?: return false
        return orNullOnFailure { manager.isCharging } ?: false
    }

    private fun selectionFile(): File = File(appContext.filesDir, SELECTION_FILE)

    private suspend fun readSelectionFile(): String? = withContext(Dispatchers.IO) {
        val file: File = selectionFile()
        if (!file.isFile) return@withContext null
        try {
            file.readText()
        } catch (failure: IOException) {
            null
        } catch (failure: SecurityException) {
            null
        }
    }

    /**
     * Writes the selection through a temporary file and a rename.
     *
     * A half-written selection would decode to a shorter list, which is the pipeline quietly forgetting
     * an album the user asked for - the sort of loss nobody notices until the album is not on the watch
     * when they need it.
     */
    private suspend fun writeSelectionFile(selection: WearSyncSelection) {
        withContext(Dispatchers.IO) {
            val file: File = selectionFile()
            val parent: File? = file.parentFile
            try {
                parent?.mkdirs()
                val temporary = File(file.parentFile, file.name + SELECTION_TEMP_SUFFIX)
                temporary.writeText(selection.encode())
                file.delete()
                if (!temporary.renameTo(file)) temporary.delete()
            } catch (failure: IOException) {
                // Nothing to report: the in-memory selection is still correct for this session, and the
                // next change tries again. REQUIREMENTS.md "Security" rules out debug logging in a
                // release build, and there is nothing here that could be said without a path in it.
            } catch (failure: SecurityException) {
                // As above.
            }
        }
    }

    companion object {

        /** Where the user's selection is persisted, inside app-private internal storage. */
        const val SELECTION_FILE: String = "on-watch/selection"

        /** Extension of the selection file mid-write, renamed into place on success. */
        const val SELECTION_TEMP_SUFFIX: String = ".tmp"

        /**
         * How many items one [ingestPending] pass will handle.
         *
         * [WearPlaybackProtocol.MAX_TRACKS_IN_FLIGHT] tracks plus a cover, which is everything a
         * well-behaved phone will have published at once. The cap exists for a phone that has published
         * more - an older build, or a leftover set nothing has deleted - so that one service callback
         * cannot turn into an hour of transfers. The remainder is still retained and the nudge at the end
         * of the pass brings the next one round to it.
         */
        const val MAX_INGESTS_PER_PASS: Int = WearPlaybackProtocol.MAX_TRACKS_IN_FLIGHT + 1

        @Volatile
        private var instance: WearSyncCoordinator? = null

        /** The one coordinator for this process. See the class note. */
        fun get(context: Context): WearSyncCoordinator {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: WearSyncCoordinator(
                    context = context.applicationContext,
                    store = WearAudioStore.get(context),
                ).also { created -> instance = created }
            }
        }
    }
}

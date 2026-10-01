package app.needler.wear

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.Pin
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PinRepository
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The phone half of on-device audio on the watch: one bounded pass per nudge.
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Wear OS" ends Wear's v1 scope with "playback of on-device
 * audio synced from the phone over the data layer". [WearPlaybackProtocol] records the contract and the
 * policy; this is the side of it that can see the server and the phone's own store.
 *
 * ## One pass, then return
 *
 * A nudge arrives at [NeedlerWearListenerService], which calls [onNudge]. That does four things and
 * stops: publish the offer, read the watch's selection, publish the next tracks it asks for, and delete
 * the items the watch has finished with. Nothing is held open and nothing is observed.
 *
 * That shape is what satisfies REQUIREMENTS.md "Battery and data", which forbids "long-lived connections
 * while backgrounded". The transfer itself is Google Play services' problem once the item is published:
 * the bytes are an `Asset`, the item is retained, and the two nodes exchange it whenever they can with
 * no process of Needler's awake on either side. [WearPlaybackProtocol] records why that beat a
 * `ChannelClient`, whose resume offsets would have cost exactly the held-open connection this avoids -
 * and would have let a stalled album transfer break the remote the user is actually using.
 *
 * ## Stateless between passes
 *
 * Nothing is remembered. Each pass recomputes the next tracks from the watch's reported holdings, so the
 * same input produces the same set, and re-publishing an identical item is a silent no-op in the data
 * layer. That is why a track item carries no [WearPlaybackProtocol.KEY_PUBLISHED_AT]: stamping one would
 * make every pass a change, and on this channel a change means re-sending thirty megabytes to answer
 * "nothing new".
 *
 * ## The charger rule
 *
 * By default nothing is published unless the watch says it is on its charger. Pushing an album over
 * Bluetooth is the most expensive thing this app can do to a watch battery, and a watch is the device
 * least able to afford it. The user overrides it by asking - [WearPlaybackProtocol.NUDGE_NOW] - because
 * somebody who has just selected an album is usually about to leave the house with it.
 *
 * A pass that will not publish still **deletes**, which is deliberate: the items the watch has already
 * taken in are holding asset storage on both devices, and letting them go costs no radio at all.
 *
 * ## Why the pass reads through domain repositories
 *
 * `PinRepository` and `LibraryRepository` are `:core:domain` interfaces, so this class touches neither
 * `:core:data` nor Room, and "The player boundary" holds as it does for `WearStatePublisher`. The one
 * thing it reaches outside the domain for is [WearArtworkAssets], which needs `:core:network` to fetch a
 * cover - and that class already records why, and is already used by the transport publisher.
 */
@Singleton
class WearAudioSync @Inject constructor(
    @ApplicationContext context: Context,
    private val pins: PinRepository,
    private val library: LibraryRepository,
    private val artwork: WearArtworkAssets,
) {

    private val dataClient: DataClient = Wearable.getDataClient(context)

    /**
     * Serialises passes.
     *
     * Two nudges can arrive together - the watch nudges after every ingest, and the user may tap at the
     * same moment - and two passes running at once would each compute the same next tracks and then each
     * delete what the other had just published. The lock makes the second pass see the first one's work,
     * which is the whole point of the pipeline being self-clocking.
     */
    private val passLock: Mutex = Mutex()

    /**
     * Answers one nudge.
     *
     * Suspends until the pass is done, because the caller is a `WearableListenerService` callback and the
     * service is alive only while it runs - the same reason `WearStatePublisher.onStateRequested` holds
     * its caller. The callback's own timeout bounds it.
     *
     * @param urgent the user asked for something now, so publish even off the charger.
     */
    suspend fun onNudge(urgent: Boolean) {
        passLock.withLock { pass(urgent = urgent) }
    }

    private suspend fun pass(urgent: Boolean) {
        publishOffer()

        val selection: WearSelectionSnapshot = readSelection() ?: WearSelectionSnapshot.None
        val activeAlbum: String = selection.activeAlbum

        val publishable: Boolean = urgent || selection.charging
        val chosen: List<WearSyncCandidate> = if (publishable && activeAlbum.isNotEmpty()) {
            val candidates: List<WearSyncCandidate> = candidatesFor(activeAlbum)
            WearSyncPlan.next(
                candidates = candidates,
                held = selection.held,
                limit = WearPlaybackProtocol.MAX_TRACKS_IN_FLIGHT,
                watchFreeBytes = selection.freeBytes,
            )
        } else {
            emptyList()
        }

        for (candidate in chosen) {
            publishTrack(candidate)
        }
        if (publishable && activeAlbum.isNotEmpty()) publishCover(activeAlbum)

        // Deleting is unconditional. An item the watch has taken in is holding asset storage on both
        // devices; letting it go costs no radio, and it is what makes room for the next one.
        val keep: Set<String> = chosen.mapTo(HashSet()) { candidate ->
            WearPlaybackProtocol.PATH_SYNC_TRACK_PREFIX + candidate.keyCanonical
        }
        val keepCovers: Set<String> = selection.wantedAlbums.mapTo(HashSet()) { albumKey ->
            WearPlaybackProtocol.PATH_SYNC_COVER_PREFIX + albumKey
        }
        deleteObsoleteItems(keepTracks = keep, keepCovers = keepCovers)
    }

    // ---- the offer -------------------------------------------------------------------------------

    /**
     * Publishes the albums the watch may ask for: the phone's Downloaded tier, newest first.
     *
     * The track count per album comes from the pin's own download state where that state carries one, and
     * from the album's length only when the download is complete - see [WearSyncPlan.downloadedTracks].
     * The alternative was a cache lookup per track, which for sixty albums is several hundred queries on
     * a nudge; a pin already knows how far its own download got.
     *
     * Urgent, unlike a track item. The offer is a few kilobytes and it is what a watch's picker is
     * waiting on, so it is worth the same "sync now" treatment the transport gets. A track item is not,
     * for the opposite reason.
     */
    private suspend fun publishOffer() {
        val albums: List<WearOfferAlbum> = orNullOnFailure { offerAlbums() } ?: return
        val window: WearOfferWindow =
            WearSyncPlan.offer(albums = albums, limit = WearPlaybackProtocol.MAX_OFFER_ALBUMS)

        val request: PutDataMapRequest =
            PutDataMapRequest.create(WearPlaybackProtocol.PATH_SYNC_OFFER)
        val map: DataMap = request.dataMap
        val encoded: ArrayList<DataMap> = ArrayList(window.rows.size)
        for (row in window.rows) {
            val rowMap = DataMap()
            rowMap.putString(WearPlaybackProtocol.KEY_ALBUM_KEY, row.albumKey)
            rowMap.putString(WearPlaybackProtocol.KEY_ALBUM_TITLE, row.title)
            rowMap.putString(WearPlaybackProtocol.KEY_ALBUM_ARTIST, row.artist)
            rowMap.putInt(WearPlaybackProtocol.KEY_ALBUM_TRACK_COUNT, row.downloadedTrackCount)
            rowMap.putLong(WearPlaybackProtocol.KEY_ALBUM_BYTES, row.sizeBytes)
            encoded.add(rowMap)
        }
        map.putDataMapArrayList(WearPlaybackProtocol.KEY_OFFER_ALBUMS, encoded)
        map.putInt(WearPlaybackProtocol.KEY_OFFER_NOT_SHOWN, window.notShown)
        // Stamped, unlike a track item: this map is small, so a redundant replication costs nothing, and
        // an offer the watch never sees because the bytes happened to match a copy that did not arrive
        // is the failure worth spending those bytes to avoid.
        map.putLong(WearPlaybackProtocol.KEY_PUBLISHED_AT, System.currentTimeMillis())

        orNullOnFailure {
            dataClient.putDataItem(request.asPutDataRequest().setUrgent()).awaitResult()
        }
    }

    private suspend fun offerAlbums(): List<WearOfferAlbum> {
        val downloaded: List<DownloadedAlbum> = pins.observeDownloadedAlbums().first()
        if (downloaded.isEmpty()) return emptyList()
        val states: Map<String, OfflineDownloadState> = pins.observePins().first()
            .associate { pin: Pin -> pin.releaseGroupMbid.value to pin.download }

        val rows: MutableList<WearOfferAlbum> = ArrayList(downloaded.size)
        for (album in downloaded) {
            val key: String = album.releaseGroupMbid.value
            val state: OfflineDownloadState = states[key] ?: OfflineDownloadState.Queued
            // The album's length is read only when the download state carries no count of its own, which
            // is the Complete case. One indexed query per completed album, on a nudge.
            val albumTrackCount: Int? = if (state == OfflineDownloadState.Complete) {
                orNullOnFailure { library.getAlbum(album.releaseGroupMbid)?.trackCount }
            } else {
                null
            }
            rows.add(
                WearOfferAlbum(
                    albumKey = key,
                    title = album.title,
                    artist = album.artistName,
                    downloadedTrackCount = WearSyncPlan.downloadedTracks(state, albumTrackCount),
                    sizeBytes = album.sizeBytes,
                    downloadedAtEpochMs = album.pinnedAt.toEpochMilliseconds(),
                ),
            )
        }
        return rows
    }

    // ---- the tracks ------------------------------------------------------------------------------

    /**
     * Every track of one album the phone could send, in record order.
     *
     * Four conditions, and each one is a requirement rather than a precaution:
     *
     *  * **Cached at all.** No row, nothing to send.
     *  * **Complete.** REQUIREMENTS.md: "a truncated file published as complete is the silent failure
     *    this whole store is built to prevent". Sending a partial file would put that failure on the
     *    wrist, where it would play and stop halfway through.
     *  * **Pinned.** This is where the sync policy is enforced in code. The pinned flag *is*
     *    REQUIREMENTS.md's "Downloaded" tier, and the Downloaded tier is fetched with `download?id=`,
     *    which "serves original bytes only". So the transcode rule - "Only original-format bytes are ever
     *    retained... the next play finds bytes in the cache, plays them, and the user, who owns a FLAC,
     *    never learns that one commute quietly downgraded their library" - is satisfied by construction,
     *    and a lossy copy cannot become the watch's permanent version of a track. An unpinned row is a
     *    cached-while-listening row and is never offered, however convenient it would be.
     *  * **Not stale on the phone.** If the phone's own bytes have been superseded, sending them would
     *    propagate the pre-upgrade copy one device further out, where it is far harder to replace. The
     *    phone re-downloads them itself; the next pass sends the new bytes, whose fingerprint differs
     *    from what the watch reports, which is what makes the watch replace its copy.
     *
     * The fingerprint describes **the bytes being sent** - `CachedAudio.sourceHandle`, the handle they
     * were downloaded with - and not what the server says now. That is the same distinction
     * REQUIREMENTS.md draws for the phone's own store: the fingerprint is "a record of the file as it was
     * at download time, which sync never touches", and comparing anything else "compares the mirror
     * against itself".
     */
    private suspend fun candidatesFor(albumKey: String): List<WearSyncCandidate> {
        val mbid: ReleaseGroupMbid = orNullOnFailure { ReleaseGroupMbid(albumKey) } ?: return emptyList()
        val tracks: List<Track> =
            orNullOnFailure { library.observeAlbumTracks(mbid).first() } ?: return emptyList()
        if (tracks.isEmpty()) return emptyList()

        val candidates: MutableList<WearSyncCandidate> = ArrayList(tracks.size)
        for (track in tracks) {
            val cached: CachedAudio = orNullOnFailure { pins.getCachedAudio(track.key) } ?: continue
            if (!cached.isComplete) continue
            if (!cached.pinned) continue
            if (cached.isStaleFor(track.fetch)) continue
            if (cached.filePath.isEmpty()) continue
            candidates.add(
                WearSyncCandidate(
                    keyCanonical = track.key.canonicalString,
                    fingerprint = WearSyncFingerprint.of(cached.sourceHandle),
                    filePath = cached.filePath,
                    sizeBytes = cached.sizeOnDiskBytes,
                    title = track.title,
                    artist = track.artistName,
                    albumTitle = track.albumTitle ?: "",
                    durationMs = track.durationMs ?: 0L,
                    format = cached.sourceHandle.format?.name ?: "",
                ),
            )
        }
        // Left in the order the repository emitted, which is record order - disc, then track. That is
        // the order the album plays in and the order the pipeline should fill it, so a part-transferred
        // album on the watch is a record missing its later tracks rather than a shuffled fragment.
        return candidates
    }

    /**
     * Publishes one track and its bytes.
     *
     * `createFromFd`, not `createFromBytes`: a FLAC track is tens of megabytes, and reading one into a
     * `ByteArray` to hand it to Google Play services would be tens of megabytes of heap on a phone that
     * may be doing something else. The descriptor is closed once the put has resolved.
     *
     * **Not urgent, deliberately.** `setUrgent()` tells Play services to sync now rather than "when
     * convenient", and `WearStatePublisher` is emphatic that it is "not optional" for a transport
     * snapshot, because "a now-playing snapshot that arrives after the song has finished is worse than
     * none". An album is the opposite case: it is minutes of radio that nobody is waiting on this second,
     * and letting Play services choose its moment is how the transfer stays out of the way of the
     * messages the remote is carrying.
     */
    private suspend fun publishTrack(candidate: WearSyncCandidate) {
        val file = File(candidate.filePath)
        if (!file.isFile) return
        // Re-checked against the file rather than trusted from the row, which is the rule REQUIREMENTS.md
        // records as part of the downloads fix: "a row claiming a file is checked against the file
        // existing". A row and a file that disagree about length would have the watch reject the transfer
        // after paying for it.
        if (file.length() != candidate.sizeBytes) return

        val descriptor: ParcelFileDescriptor = orNullOnFailure {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } ?: return

        try {
            val request: PutDataMapRequest = PutDataMapRequest.create(
                WearPlaybackProtocol.PATH_SYNC_TRACK_PREFIX + candidate.keyCanonical,
            )
            val map: DataMap = request.dataMap
            map.putString(WearPlaybackProtocol.KEY_TRACK_KEY, candidate.keyCanonical)
            map.putString(WearPlaybackProtocol.KEY_TRACK_TITLE, candidate.title)
            map.putString(WearPlaybackProtocol.KEY_TRACK_ARTIST, candidate.artist)
            map.putString(WearPlaybackProtocol.KEY_TRACK_ALBUM, candidate.albumTitle)
            map.putLong(WearPlaybackProtocol.KEY_TRACK_DURATION_MS, candidate.durationMs)
            map.putString(WearPlaybackProtocol.KEY_TRACK_FORMAT, candidate.format)
            map.putLong(WearPlaybackProtocol.KEY_TRACK_BYTES, candidate.sizeBytes)
            map.putString(WearPlaybackProtocol.KEY_TRACK_FINGERPRINT, candidate.fingerprint)
            map.putAsset(WearPlaybackProtocol.KEY_TRACK_AUDIO, Asset.createFromFd(descriptor))
            // No KEY_PUBLISHED_AT. See the class note: a re-put of an identical item must stay a no-op.
            orNullOnFailure { dataClient.putDataItem(request.asPutDataRequest()).awaitResult() }
        } finally {
            orNullOnFailure { descriptor.close() }
        }
    }

    /**
     * Publishes one album cover, so the watch's own transport is not the only screen in the app drawing a
     * placeholder.
     *
     * Reuses [WearArtworkAssets], which asks the server for a 200 px square and forwards the bytes
     * untouched. One consequence is worth knowing: that class caches exactly one asset, so a cover
     * published here evicts the transport's and vice versa. The cost is one extra HTTP GET of 10 to 20 KB
     * when the two alternate, which is less than the second cache would be worth.
     */
    private suspend fun publishCover(albumKey: String) {
        val mbid: ReleaseGroupMbid = orNullOnFailure { ReleaseGroupMbid(albumKey) } ?: return
        val album: Album = orNullOnFailure { library.getAlbum(mbid) } ?: return
        val ref: ArtworkRef = album.artwork ?: return
        val asset: Asset = artwork.assetFor(albumKey, ref) ?: return

        val request: PutDataMapRequest = PutDataMapRequest.create(
            WearPlaybackProtocol.PATH_SYNC_COVER_PREFIX + albumKey,
        )
        val map: DataMap = request.dataMap
        map.putString(WearPlaybackProtocol.KEY_COVER_ALBUM_KEY, albumKey)
        map.putAsset(WearPlaybackProtocol.KEY_COVER_IMAGE, asset)
        // No KEY_PUBLISHED_AT, for the same reason a track item has none.
        orNullOnFailure { dataClient.putDataItem(request.asPutDataRequest()).awaitResult() }
    }

    // ---- reading and deleting --------------------------------------------------------------------

    /**
     * Reads the item the **watch** publishes.
     *
     * Pulled rather than listened for. The phone's manifest filter names `MESSAGE_RECEIVED` and
     * `CAPABILITY_CHANGED` and not `DATA_CHANGED`, and it stays that way on purpose: a phone woken by
     * every selection change would be woken by its own watch reporting each track it ingested, which is
     * once per track for a whole album. The nudge is one message and it says everything a data-changed
     * event would have.
     */
    private suspend fun readSelection(): WearSelectionSnapshot? {
        val buffer: DataItemBuffer =
            orNullOnFailure { dataClient.dataItems.awaitResult() } ?: return null
        return try {
            var found: WearSelectionSnapshot? = null
            for (item in buffer) {
                if (item.uri.path != WearPlaybackProtocol.PATH_SYNC_SELECTION) continue
                found = decodeSelection(item)
            }
            found
        } finally {
            buffer.release()
        }
    }

    /**
     * Deletes every track and cover item that is not in the sets to keep.
     *
     * Read-then-delete by the item's own URI rather than by rebuilding one. A data-layer URI carries the
     * authority of the node that published it, so the URI the buffer hands back is the only one certain
     * to name the right item - and it means this needs no constant for the `wear` scheme.
     *
     * Only track and cover paths are considered. The selection is the watch's item and the offer is one
     * this phone means to keep, and deleting either would be a race with the node that owns it.
     */
    private suspend fun deleteObsoleteItems(keepTracks: Set<String>, keepCovers: Set<String>) {
        val doomed: List<Uri> = orNullOnFailure { obsoleteUris(keepTracks, keepCovers) } ?: return
        for (uri in doomed) {
            orNullOnFailure { dataClient.deleteDataItems(uri).awaitResult() }
        }
    }

    private suspend fun obsoleteUris(keepTracks: Set<String>, keepCovers: Set<String>): List<Uri> {
        val buffer: DataItemBuffer =
            orNullOnFailure { dataClient.dataItems.awaitResult() } ?: return emptyList()
        return try {
            val doomed: MutableList<Uri> = ArrayList()
            for (item in buffer) {
                val path: String = item.uri.path ?: continue
                val obsolete: Boolean = when {
                    path.startsWith(WearPlaybackProtocol.PATH_SYNC_TRACK_PREFIX) ->
                        path !in keepTracks

                    path.startsWith(WearPlaybackProtocol.PATH_SYNC_COVER_PREFIX) ->
                        path !in keepCovers

                    else -> false
                }
                if (obsolete) doomed.add(item.uri)
            }
            doomed
        } finally {
            buffer.release()
        }
    }

    private fun decodeSelection(item: DataItem): WearSelectionSnapshot {
        val map: DataMap = DataMapItem.fromDataItem(item).dataMap
        return WearSelectionSnapshot(
            wantedAlbums = map.getStringArrayList(WearPlaybackProtocol.KEY_WANTED_ALBUMS)
                ?.filterNotNull()
                ?: emptyList(),
            activeAlbum = map.getString(WearPlaybackProtocol.KEY_ACTIVE_ALBUM, ""),
            held = WearHeldTracks.parse(
                map.getStringArrayList(WearPlaybackProtocol.KEY_HELD_TRACKS)?.filterNotNull(),
            ),
            charging = map.getBoolean(WearPlaybackProtocol.KEY_WATCH_CHARGING, false),
            freeBytes = map.getLong(WearPlaybackProtocol.KEY_WATCH_FREE_BYTES, 0L)
                .coerceAtLeast(0L),
        )
    }
}

/**
 * The watch's selection item, decoded.
 *
 * Every field is defaulted rather than required, for the reason `DataLayerPlaybackClient` gives about the
 * transport: the two APKs have independent version codes so they can ship apart, and a watch on an older
 * build is a normal situation. A selection this phone cannot fully read still produces a pass that does
 * something sensible - [None] does nothing at all, which is the right answer to a watch that has asked
 * for nothing.
 */
internal data class WearSelectionSnapshot(
    val wantedAlbums: List<String>,
    val activeAlbum: String,
    /** Track key to the fingerprint the watch holds, from [WearHeldTracks.parse]. */
    val held: Map<String, String>,
    val charging: Boolean,
    /** The watch's own reading of its usable space, or 0 when it did not say. */
    val freeBytes: Long,
) {
    internal companion object {
        /** No selection published: a watch that has never asked for anything. */
        val None: WearSelectionSnapshot = WearSelectionSnapshot(
            wantedAlbums = emptyList(),
            activeAlbum = "",
            held = emptyMap(),
            charging = false,
            freeBytes = 0L,
        )
    }
}

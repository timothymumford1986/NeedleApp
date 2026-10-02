package app.needler.wear.store

import android.content.Context
import app.needler.wear.playback.WearPlaybackProtocol
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The audio the watch holds: app-private files, and a small sidecar beside each one.
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Wear OS" ends Wear's v1 scope with "playback of on-device
 * audio synced from the phone over the data layer". This is the on-device half of that. Bytes arrive
 * through [app.needler.wear.sync.NeedlerWearSyncService] and leave through
 * [app.needler.wear.local.WearLocalPlayback], and nothing else touches the directory.
 *
 * ## Why there is no index
 *
 * A row in a table pointing at a file is the arrangement REQUIREMENTS.md records a bug against: "a row
 * claiming a file is checked against the file existing" is listed as part of the fix for downloads that
 * were not retained, because the two can disagree. On a watch the disagreement is cheaper to avoid than
 * to detect - so the sidecar *is* the row, it sits in the same directory as the bytes it describes, and
 * the two are written in an order that makes a crash produce a missing track rather than a broken one:
 *
 *  1. Bytes go to a part file.
 *  2. The part is checked against the length the phone declared.
 *  3. Any old sidecar is removed.
 *  4. The part is renamed into place as the audio file.
 *  5. The sidecar is written, last, through a temporary file and a rename.
 *
 * **The sidecar is the commit record.** Audio with no sidecar is an interrupted transfer; a sidecar with
 * no audio is a deleted file nothing cleaned up. [sweep] removes both, and [contents] never lists
 * either - which is the same rule as the phone's, and the reason step 5 is last rather than first.
 *
 * ## A part file here stands for nothing
 *
 * The phone's download store keeps its partials deliberately, because they are "the bytes a `Range` GET
 * resumes from" and deleting them "would make every interruption cost the whole track again". The
 * watch's cannot be: a data-layer `Asset` transfer has no offset to resume at - see
 * [WearPlaybackProtocol] for why an `Asset` was chosen over a `ChannelClient` anyway - so a part file
 * left behind by a dead process is bytes nothing will ever continue. [sweep] deletes them, and the unit
 * of restart is one track.
 *
 * ## One instance per process
 *
 * Two things in this app need the same store and neither can inject it: the activity, which has no
 * Hilt because `wear/build.gradle.kts` deliberately has no `@HiltAndroidApp` class, and the sync
 * service, which Google Play services constructs itself. So the instance is held in the companion,
 * keyed on nothing because there is only ever one directory. See [get].
 *
 * ## Threading
 *
 * Every mutation holds [writeLock] and runs on [Dispatchers.IO]. The lock matters because the sync
 * service and the on-watch screen are in the same process and can both be removing and ingesting at
 * once; the dispatcher matters because a directory scan on the main thread is a dropped frame on a
 * device with one small core.
 *
 * [contents] is a `StateFlow` the UI collects and the writers update. It starts empty rather than
 * scanning at construction: a scan is disk work, and doing it in a constructor would put it on whatever
 * thread happened to build the store. [refresh] is how it is filled, and every mutation ends with one.
 *
 * ## The constructor is `internal`, and that is what makes the commit order testable
 *
 * [get] is how the app obtains this, and nothing outside the module has a reason to construct one. It is
 * `internal` rather than `private` for one reason: a `Context` cannot be had in a JVM unit test, and a
 * `java.io.File` can - so `WearAudioStoreTest` builds a store over a temporary directory and exercises
 * the very things this class exists to get right. REQUIREMENTS.md records the phone's version of those as
 * an actual shipped bug ("a row claiming a file is checked against the file existing"), which is reason
 * enough not to leave them behind a constructor no test can reach.
 *
 * A temporary directory is not a temporary *volume*, though, which is why [freeSpace] is a second
 * parameter: see [WearFreeSpace] for the thirteen assertions that turned out to be measuring the build
 * machine's drive rather than the store.
 *
 * @param root the store's directory. The app's `filesDir`; a temporary folder in a test.
 * @param freeSpace how room on that volume is read. Defaults to [FileWearFreeSpace] over the same
 *   [root], which is the real measurement and the only thing the app ever passes.
 */
class WearAudioStore internal constructor(
    private val root: File,
    private val freeSpace: WearFreeSpace = FileWearFreeSpace(root),
) {

    private val writeLock: Mutex = Mutex()

    private val state: MutableStateFlow<WearStoreContents> =
        MutableStateFlow(WearStoreContents.NotScanned)

    /**
     * What the watch holds, as far as the last [refresh] knows.
     *
     * [WearStoreContents.NotScanned] until something calls [refresh], which is a distinct state from
     * "nothing on the watch" for the reason [app.needler.wear.playback.WearPlaybackState.Connecting] is
     * distinct from `Idle`: an empty list shown before the first scan reads as a lost library.
     */
    val contents: StateFlow<WearStoreContents> = state.asStateFlow()

    /** Rescans the directory and republishes [contents]. Cheap enough to call after every change. */
    suspend fun refresh(): WearStoreContents = withContext(Dispatchers.IO) {
        val scanned: WearStoreContents = scan()
        state.value = scanned
        scanned
    }

    /**
     * Room on the volume the store sits on, read fresh.
     *
     * Delegated to [freeSpace] rather than measured here, which is what lets a test state the volume it
     * is testing against instead of inheriting the build machine's - see [WearFreeSpace]. The
     * measurement itself, and the reasons for every part of it, moved to [FileWearFreeSpace] unchanged.
     *
     * Still a function and still called on every room check rather than read once and kept, for the
     * reason REQUIREMENTS.md gives for the phone: "it is read fresh every time, because a stale figure
     * is precisely how a cache overshoots." A cache in front of this would be the one way the seam could
     * change what the app does, so there is not one.
     */
    fun space(): WearStoreSpace = freeSpace.read()

    /**
     * Writes one transferred track into the store.
     *
     * @param record everything about the track except its bytes, decoded from the data item.
     * @param openSource opens the transferred bytes. A lambda rather than an `InputStream` because
     *   resolving a data-layer `Asset` is itself a suspending round trip, and it must not happen until
     *   the room check has passed - fetching thirty megabytes to discover there was nowhere to put them
     *   is the one mistake this order avoids.
     */
    suspend fun ingestTrack(
        record: WearTrackRecord,
        openSource: suspend () -> InputStream?,
    ): WearIngestOutcome = withContext(Dispatchers.IO) {
        if (record.sizeBytes <= 0L || record.sizeBytes > WearPlaybackProtocol.MAX_TRACK_BYTES) {
            // A declared size of zero means the phone published a track item it could not size, and a
            // size past the ceiling should never have been published at all. Either way the watch
            // cannot check what arrives against anything, and an unverifiable file is exactly what the
            // commit order exists to keep out of the store.
            return@withContext WearIngestOutcome.Refused
        }

        val outcome: WearIngestOutcome = writeLock.withLock {
            val audio: File = audioFileFor(record.key)
            val sidecar: File = sidecarFileFor(record.key)
            val existing: WearTrackRecord? = readSidecar(sidecar)
            if (existing != null &&
                existing.fingerprint == record.fingerprint &&
                audio.isFile &&
                audio.length() == existing.sizeBytes
            ) {
                // Already here, and the bytes are the ones this fingerprint describes. Saying so lets
                // the phone delete the item it published rather than transferring it again.
                return@withLock WearIngestOutcome.AlreadyHeld
            }

            if (!space().canAccept(record.sizeBytes)) {
                return@withLock WearIngestOutcome.NoRoom
            }
            if (!ensureDirectories()) return@withLock WearIngestOutcome.Failed

            val part: File = partFileFor(record.key)
            val written: Long = copyToPart(part, openSource) ?: return@withLock WearIngestOutcome.Failed

            if (written != record.sizeBytes) {
                // Short or long means the transfer did not deliver what the phone described. The part
                // is not a resume point here, so it goes; the next pass republishes the same item and
                // starts again. REQUIREMENTS.md's rule for the phone applies to the reason rather than
                // the remedy: "a truncated file published as complete is the silent failure this whole
                // store is built to prevent."
                part.delete()
                return@withLock WearIngestOutcome.Failed
            }

            // Removed before the rename, so a crash in the next two steps leaves audio with no sidecar
            // - which sweeps clean - rather than new bytes described by an old record.
            sidecar.delete()
            // The old bytes go before the rename too. `File.renameTo` is explicitly platform-dependent
            // about an existing destination: POSIX `rename` replaces it, Windows refuses, so an upgrade
            // over a track the watch already held returned Failed on a JVM host. Deleting first is what
            // `writeSidecar` and `ingestCover` already do, and it narrows the crash window from "old
            // bytes with no sidecar" to "nothing", which is the cheaper of the two to sweep.
            audio.delete()
            if (!part.renameTo(audio)) {
                part.delete()
                return@withLock WearIngestOutcome.Failed
            }
            if (!writeSidecar(sidecar, record)) {
                // The bytes are good but nothing describes them, so they are not playable and not
                // reportable. Removing them keeps the invariant that everything in the store is
                // complete, at the cost of one retransfer.
                audio.delete()
                return@withLock WearIngestOutcome.Failed
            }
            WearIngestOutcome.Ingested
        }

        if (outcome == WearIngestOutcome.Ingested) refresh()
        outcome
    }

    /**
     * Writes one album cover.
     *
     * Failure is silent and returns false, because a cover is decoration: the on-watch screen and the
     * local transport both draw the record placeholder without one, which
     * [app.needler.wear.playback.WearPlaybackClient.loadArtwork] already establishes as "an ordinary
     * answer, not an error".
     */
    suspend fun ingestCover(
        albumKey: String,
        openSource: suspend () -> InputStream?,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!WearAudioKey.isSafeIdentifier(albumKey)) return@withContext false
        writeLock.withLock {
            if (!ensureDirectories()) return@withLock false
            val target: File = coverFileFor(albumKey)
            val part = File(target.parentFile, target.name + PART_SUFFIX)
            val written: Long = copyToPart(part, openSource) ?: return@withLock false
            if (written <= 0L || written > MAX_COVER_BYTES) {
                part.delete()
                return@withLock false
            }
            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                return@withLock false
            }
            true
        }
    }

    /** The cover for [albumKey], or null when the watch has none. */
    fun coverFile(albumKey: String): File? {
        if (!WearAudioKey.isSafeIdentifier(albumKey)) return null
        return coverFileFor(albumKey).takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * The playable file for [key], or null when the watch does not hold it.
     *
     * Checked against the sidecar rather than just the file existing, so a track whose sidecar went
     * missing is not played: without the sidecar nothing knows what the bytes are, what they weigh, or
     * which server file they came from, and a track the staleness check cannot reach is a track that
     * plays the pre-upgrade copy for ever.
     */
    fun playableFile(key: WearAudioKey): File? {
        val record: WearTrackRecord = readSidecar(sidecarFileFor(key)) ?: return null
        val audio: File = audioFileFor(key)
        if (!audio.isFile || audio.length() != record.sizeBytes) return null
        return audio
    }

    /**
     * Removes one album and reports what that freed.
     *
     * The figure is the point, not a courtesy. REQUIREMENTS.md is explicit that on the phone "removing a
     * download deletes the bytes there and then, and reports how many were freed... a 'remove' that
     * leaves the usage figure unchanged is the one thing that would make this whole screen
     * untrustworthy." A watch has less room and one lever, so it matters more here.
     */
    suspend fun removeAlbum(albumKey: String): WearRemoved = withContext(Dispatchers.IO) {
        val removed: WearRemoved = writeLock.withLock {
            val held: List<WearTrackRecord> = readAllSidecars().filter { it.albumKey == albumKey }
            var bytes: Long = 0L
            var tracks: Int = 0
            for (record in held) {
                if (deleteTrackFiles(record)) {
                    bytes += record.sizeBytes
                    tracks += 1
                }
            }
            if (WearAudioKey.isSafeIdentifier(albumKey)) coverFileFor(albumKey).delete()
            WearRemoved(trackCount = tracks, freedBytes = bytes)
        }
        refresh()
        removed
    }

    /** Removes everything. The watch's equivalent of "Remove all from device". */
    suspend fun removeAll(): WearRemoved = withContext(Dispatchers.IO) {
        val removed: WearRemoved = writeLock.withLock {
            val held: List<WearTrackRecord> = readAllSidecars()
            var bytes: Long = 0L
            var tracks: Int = 0
            for (record in held) {
                if (deleteTrackFiles(record)) {
                    bytes += record.sizeBytes
                    tracks += 1
                }
            }
            // Anything left is an orphan, a part file or a cover; the sweep below takes the first two
            // and the covers directory is emptied wholesale because nothing references it any more.
            coverDirectory().listFiles()?.forEach { file -> file.delete() }
            sweepLocked()
            WearRemoved(trackCount = tracks, freedBytes = bytes)
        }
        refresh()
        removed
    }

    /**
     * Deletes what nothing can play: audio with no sidecar, sidecars with no audio, and every part file.
     *
     * Called when the app starts and after an interrupted ingest, not on a timer. Unlike the phone's
     * `sweepOrphanedParts`, this needs no list of transfers in flight to be safe: a part file only
     * exists while [ingestTrack] holds [writeLock], so anything found outside that lock was left by a
     * process that is gone.
     *
     * @return how many files were removed.
     */
    suspend fun sweep(): Int = withContext(Dispatchers.IO) {
        val removed: Int = writeLock.withLock { sweepLocked() }
        if (removed > 0) refresh()
        removed
    }

    // ---- internals -------------------------------------------------------------------------------

    private fun sweepLocked(): Int {
        var removed: Int = 0
        val files: Array<File> = audioDirectory().listFiles() ?: return 0
        val audioStems: MutableSet<String> = HashSet()
        val sidecarStems: MutableSet<String> = HashSet()

        for (file in files) {
            val name: String = file.name
            when {
                name.endsWith(PART_SUFFIX) -> if (file.delete()) removed += 1
                name.endsWith(SIDECAR_TEMP_SUFFIX) -> if (file.delete()) removed += 1
                name.endsWith(AUDIO_SUFFIX) -> audioStems.add(name.removeSuffix(AUDIO_SUFFIX))
                name.endsWith(SIDECAR_SUFFIX) -> sidecarStems.add(name.removeSuffix(SIDECAR_SUFFIX))
            }
        }

        for (stem in audioStems) {
            if (stem in sidecarStems) continue
            if (File(audioDirectory(), stem + AUDIO_SUFFIX).delete()) removed += 1
        }
        for (stem in sidecarStems) {
            if (stem in audioStems) continue
            if (File(audioDirectory(), stem + SIDECAR_SUFFIX).delete()) removed += 1
        }
        return removed
    }

    private fun scan(): WearStoreContents {
        val records: List<WearTrackRecord> = readAllSidecars()
        return WearStoreContents.of(records = records, space = space())
    }

    /**
     * Every sidecar whose audio file is present and the right length.
     *
     * The length check is the "a row claiming a file is checked against the file existing" rule with
     * one extra condition, and the extra condition is the one that matters: a file of the wrong length
     * is a transfer that was interrupted after the rename, and playing it would stop halfway through
     * with nothing reporting an error.
     */
    private fun readAllSidecars(): List<WearTrackRecord> {
        val files: Array<File> = audioDirectory().listFiles() ?: return emptyList()
        val records: MutableList<WearTrackRecord> = ArrayList()
        for (file in files) {
            if (!file.name.endsWith(SIDECAR_SUFFIX)) continue
            val record: WearTrackRecord = readSidecar(file) ?: continue
            val audio: File = audioFileFor(record.key)
            if (!audio.isFile || audio.length() != record.sizeBytes) continue
            records.add(record)
        }
        return records
    }

    private fun readSidecar(file: File): WearTrackRecord? {
        if (!file.isFile) return null
        val text: String = try {
            file.readText()
        } catch (failure: IOException) {
            return null
        } catch (failure: SecurityException) {
            return null
        }
        return WearTrackRecordCodec.decode(text)
    }

    /**
     * Writes the sidecar through a temporary file and a rename, so it is never observed half-written.
     *
     * A half-written sidecar would decode to null under [WearTrackRecordCodec], which sweeps the audio
     * beside it - so the atomic rename is the difference between one interrupted write and one lost
     * track.
     */
    private fun writeSidecar(sidecar: File, record: WearTrackRecord): Boolean {
        val temporary = File(sidecar.parentFile, sidecar.name + SIDECAR_TEMP_SUFFIX)
        return try {
            temporary.writeText(WearTrackRecordCodec.encode(record))
            sidecar.delete()
            temporary.renameTo(sidecar).also { renamed -> if (!renamed) temporary.delete() }
        } catch (failure: IOException) {
            temporary.delete()
            false
        } catch (failure: SecurityException) {
            temporary.delete()
            false
        }
    }

    /**
     * Copies [openSource] into [part], returning how many bytes arrived, or null on failure.
     *
     * The descriptor is synced before the stream closes. On a device that can lose power at any moment -
     * and a watch is exactly that device - an unsynced file that has been renamed into place is a file
     * whose name says "complete" and whose contents may not be.
     *
     * A cancellation is rethrown rather than reported as a failure, for the reason `GmsTasks.kt` gives:
     * swallowing one turns "this coroutine was cancelled" into "this call returned null" and lets work
     * continue inside a scope that has already gone.
     */
    private suspend fun copyToPart(part: File, openSource: suspend () -> InputStream?): Long? {
        part.delete()
        val source: InputStream = try {
            openSource()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        } ?: return null

        return try {
            source.use { input ->
                FileOutputStream(part).use { output ->
                    val copied: Long = input.copyTo(output)
                    output.flush()
                    try {
                        output.fd.sync()
                    } catch (failure: IOException) {
                        // A volume that cannot be synced is not a reason to throw the bytes away; the
                        // length check above is still the thing that decides whether they are usable.
                    }
                    copied
                }
            }
        } catch (cancellation: CancellationException) {
            part.delete()
            throw cancellation
        } catch (failure: IOException) {
            part.delete()
            null
        } catch (failure: SecurityException) {
            part.delete()
            null
        }
    }

    private fun deleteTrackFiles(record: WearTrackRecord): Boolean {
        // Sidecar first: it is the commit record, so removing it is what makes the track cease to
        // exist as far as everything else is concerned. If the process dies between the two, the audio
        // is an orphan and the sweep reclaims it - the other order would leave a record pointing at
        // nothing, which is the state REQUIREMENTS.md records a bug about.
        val sidecarGone: Boolean = sidecarFileFor(record.key).delete()
        val audioGone: Boolean = audioFileFor(record.key).delete()
        return sidecarGone || audioGone
    }

    private fun ensureDirectories(): Boolean = try {
        audioDirectory().mkdirs()
        coverDirectory().mkdirs()
        audioDirectory().isDirectory && coverDirectory().isDirectory
    } catch (failure: SecurityException) {
        false
    }

    private fun audioDirectory(): File = File(root, AUDIO_DIRECTORY)

    private fun coverDirectory(): File = File(root, COVER_DIRECTORY)

    private fun audioFileFor(key: WearAudioKey): File =
        File(audioDirectory(), key.fileStem + AUDIO_SUFFIX)

    private fun sidecarFileFor(key: WearAudioKey): File =
        File(audioDirectory(), key.fileStem + SIDECAR_SUFFIX)

    private fun partFileFor(key: WearAudioKey): File =
        File(audioDirectory(), key.fileStem + PART_SUFFIX)

    private fun coverFileFor(albumKey: String): File =
        File(coverDirectory(), albumKey + COVER_SUFFIX)

    companion object {

        /** The store's directory, inside app-private internal storage. */
        const val AUDIO_DIRECTORY: String = "on-watch/audio"

        /** Covers live in their own directory so a sweep of the audio one cannot reach them. */
        const val COVER_DIRECTORY: String = "on-watch/covers"

        /**
         * Extension of a complete audio file.
         *
         * Deliberately not the track's format. Media3 sniffs the container itself - the extension is
         * only ever a hint that reorders which extractor is tried first - so a filename that is a pure
         * function of the track key is worth more than one that is recognisable in a file browser. The
         * format is in the sidecar, where the quality badge reads it.
         */
        const val AUDIO_SUFFIX: String = ".audio"

        /** Extension of the sidecar, which is the commit record. */
        const val SIDECAR_SUFFIX: String = ".meta"

        /** Extension of a transfer in progress. Never a resume point; see the class note. */
        const val PART_SUFFIX: String = ".part"

        /** Extension of a sidecar mid-write, renamed into place on success. */
        const val SIDECAR_TEMP_SUFFIX: String = ".metatmp"

        /** Extension of an album cover. */
        const val COVER_SUFFIX: String = ".cover"

        /**
         * 256 KB, the same ceiling `WearArtworkAssets` puts on the transport's cover.
         *
         * A refusal of a full-size scan rather than a size the watch needs: 200 px of JPEG is 10 to
         * 20 KB, and two megabytes of cover over Bluetooth would take longer than the track it belongs
         * to.
         */
        const val MAX_COVER_BYTES: Long = 256L * 1024L

        @Volatile
        private var instance: WearAudioStore? = null

        /**
         * The one store for this process.
         *
         * Double-checked under the class monitor. The alternative - constructing one per caller - would
         * give the sync service and the on-watch screen separate [writeLock]s over the same directory,
         * which is the one thing the lock exists to prevent.
         */
        fun get(context: Context): WearAudioStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: WearAudioStore(context.applicationContext.filesDir).also { created ->
                    instance = created
                }
            }
        }
    }
}

/** What [WearAudioStore.ingestTrack] did with a transferred track. */
enum class WearIngestOutcome {
    /** Written, checked and committed. The watch can play it and reports holding it. */
    Ingested,

    /**
     * These exact bytes were already here.
     *
     * Not a failure and not a no-op to hide: it is what lets the phone delete the item it published and
     * move on to the next track, so the pipeline advances rather than stalling on a duplicate.
     */
    AlreadyHeld,

    /**
     * Writing them would take the watch below its free-space floor.
     *
     * Nothing is evicted for them - see [WearStoreSpace.canAccept]. The on-watch screen reports it and
     * the user removes an album.
     */
    NoRoom,

    /** The item described something this store will not write, such as a track past the size ceiling. */
    Refused,

    /** The transfer or the write failed. Transient: the next pass republishes the same item. */
    Failed,
}

/** What removing something from the watch actually freed. */
data class WearRemoved(
    val trackCount: Int,
    val freedBytes: Long,
) {
    companion object {
        /** Nothing was there. A success, not an error. */
        val Nothing: WearRemoved = WearRemoved(trackCount = 0, freedBytes = 0L)
    }
}

package app.needler.core.data.local.cache

import app.needler.core.data.local.dao.AudioCacheDao
import app.needler.core.data.local.entity.AudioCacheEntity
import app.needler.core.domain.cache.AudioCacheWriteHandle
import app.needler.core.domain.cache.AudioCacheWriter
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlayableSource
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

/**
 * The one place streamed audio becomes a file in the `audio_cache` store.
 *
 * Media3's `CacheDataSource` and `SimpleCache` are deliberately unused - see
 * [AudioCacheWriter] for the four reasons - so this is what "cache while listening" is made of: the
 * player service reads a stream once and the bytes land on disk on the way past, rather than the
 * app fetching the same track twice.
 *
 * It owns no policy. Whether there is room is [CacheIndex]'s answer, computed by [EvictionPlanner]
 * against the device's free-space floor; whether the bytes may be kept at all was decided by the
 * resolver and travels on [PlayableSource.Stream.cacheWhileStreaming]. This class enforces those
 * answers and the one rule that is genuinely its own: **a partial file is never published as a
 * complete track**.
 *
 * ## Order of operations on commit
 *
 * The bytes are renamed into place first and the row is written second, which is the mirror image of
 * the delete order in [CacheIndex]. A crash between the two leaves a file no row names, and the file
 * name is derived from the track key, so the next attempt at the same track overwrites it rather
 * than adding a second copy. The other order would publish a row pointing at a file that is still a
 * `.part`, and a row that claims a complete track is exactly the thing that must never be wrong: a
 * play would start, run to the end of the partial bytes and stop, with nothing anywhere reporting an
 * error.
 */
public class AudioCacheStoreWriter(
    private val audioCacheDao: AudioCacheDao,
    private val cacheIndex: CacheIndex,
    /** App-private internal storage: no permissions, and it goes away on uninstall. */
    private val audioDirectory: File,
    /**
     * Unlinks a file. Supplied rather than called directly for the same reason [CacheIndex] takes
     * one: exactly one layer is responsible for deleting bytes, and tests can watch it.
     */
    private val deleteFile: (String) -> Boolean = { path -> File(path).delete() },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioCacheWriter, AudioDownloadStore {

    override suspend fun openWrite(source: PlayableSource.Stream): AudioCacheWriteHandle? {
        // 1. Transcoded bytes are never retained. The resolver set this flag from the format it
        //    resolved; nothing downstream may override it, or a 320 kbps rendering becomes a FLAC
        //    track's permanent offline copy and the user is never told.
        if (!source.cacheWhileStreaming) return null

        // 2. An unknown length cannot be checked against the free-space floor before the write, and
        //    a floor discovered after the bytes are on disk is not a floor. The server reports a
        //    size for every library track, so refusing here costs a re-fetch in a case that should
        //    not arise rather than risking the one thing the floor exists to prevent.
        val expectedSize: Long = source.fetchHandle.sizeBytes ?: return null
        if (expectedSize <= 0L) return null

        // 3. Already on the device, from the same server-side file: there is nothing to gain by
        //    rewriting identical bytes. A row whose fingerprint has moved on is a different matter -
        //    those bytes are the pre-upgrade copy and this write replaces them.
        val existing: AudioCacheEntity? = audioCacheDao.get(
            releaseGroupMbid = source.key.releaseGroupMbid.value,
            discNo = source.key.discNumber,
            trackNo = source.key.trackNumber,
        )
        if (existing != null &&
            existing.complete &&
            existing.sourceFileId == source.fetchHandle.fileId.value
        ) {
            return null
        }

        // 4. Make room first, so the floor is a floor rather than a line the device drops below and
        //    then climbs back over. When the write cannot fit even with the whole cached tier gone,
        //    the plan says so and the bytes are skipped: evicting further would cost the user
        //    recently played music and still leave the write unable to fit.
        val (plan: EvictionPlan, _: EvictionResult) = cacheIndex.evictToFit(
            incomingBytes = expectedSize,
            deleteFile = deleteFile,
        )
        if (plan.skipsIncoming) return null

        val partFile: File = partFileFor(source.key)
        val stream: OutputStream = withContext(ioDispatcher) {
            try {
                audioDirectory.mkdirs()
                partFile.delete()
                BufferedOutputStream(partFile.outputStream())
            } catch (error: IOException) {
                // The store is unwritable. Playback is unaffected; it simply is not retained.
                null
            }
        } ?: return null

        return FileWriteHandle(
            key = source.key,
            expectedSizeBytes = expectedSize,
            fetchHandle = source.fetchHandle,
            partFile = partFile,
            targetFile = fileFor(source.key),
            stream = stream,
            existing = existing,
        )
    }

    /**
     * Opens, or re-opens, a deliberate download of one track: the "Pull local" write path.
     *
     * Everything the streaming path decides is decided here too, by the same code: whether the
     * bytes are already on the device, and whether keeping them leaves the device above its
     * free-space floor. Two things are different, and both are consequences of this being a
     * download rather than a stream.
     *
     * **The part file is not deleted.** [openWrite] deletes any `.part` before it starts, because a
     * stream always begins at byte zero and a leftover partial would be prefixed to it. A download
     * resumes: the bytes on disk are exactly what the `Range` header asks the server to continue
     * from, and throwing them away would make every interruption cost the whole track again. This
     * is the difference that makes a second entry point necessary rather than a flag.
     *
     * **Only the outstanding bytes are planned for.** Room is made for what is still to arrive, not
     * for the whole track: the partial file is already occupying its share of the disk, and
     * counting it twice would evict music to make room for bytes that are already there.
     *
     * There is no transcode check because there is nothing to check. Downloads fetch `download?id=`,
     * which serves original bytes only; a transcode can only come from `stream?id=` with a format
     * parameter, and this path never builds one.
     */
    override suspend fun openDownload(
        key: TrackKey,
        fetchHandle: TrackFetchHandle,
        expectedSizeBytes: Long?,
    ): AudioDownloadSlot {
        val existing: AudioCacheEntity? = audioCacheDao.get(
            releaseGroupMbid = key.releaseGroupMbid.value,
            discNo = key.discNumber,
            trackNo = key.trackNumber,
        )
        // Complete bytes from the same server-side file: nothing to fetch. This is what makes
        // pinning an album you have been streaming nearly free - the rows are promoted, not
        // re-downloaded.
        if (existing != null &&
            existing.complete &&
            existing.sourceFileId == fetchHandle.fileId.value
        ) {
            return AudioDownloadSlot.AlreadyOnDevice
        }

        val partFile: File = partFileFor(key)
        val onDisk: Long = withContext(ioDispatcher) {
            if (partFile.isFile) partFile.length() else 0L
        }
        val outstanding: Long = expectedSizeBytes
            ?.let { declared -> (declared - onDisk).coerceAtLeast(0L) }
            ?: 0L

        val (plan: EvictionPlan, _: EvictionResult) = cacheIndex.evictToFit(
            incomingBytes = outstanding,
            deleteFile = deleteFile,
        )
        // The cached-while-listening tier has given up everything it can and the write still would
        // not fit. The caller records why and stops; it must not evict downloads to make room,
        // because the user chose those and nothing in this app takes one away.
        if (plan.skipsIncoming) return AudioDownloadSlot.NoRoom

        val ready: Boolean = withContext(ioDispatcher) {
            try {
                audioDirectory.mkdirs()
                audioDirectory.isDirectory
            } catch (error: SecurityException) {
                false
            }
        }
        if (!ready) return AudioDownloadSlot.Unwritable

        return FileDownloadSlot(
            key = key,
            fetchHandle = fetchHandle,
            partFile = partFile,
            targetFile = fileFor(key),
            bytesOnDisk = onDisk,
            expectedSizeBytes = expectedSizeBytes,
            existing = existing,
        )
    }

    /** The published file for a track. Deterministic, so a retry reclaims an orphan of its own. */
    internal fun fileFor(key: TrackKey): File = File(audioDirectory, fileNameFor(key) + AUDIO_SUFFIX)

    /** The in-progress file. Nothing but this class ever looks at a `.part`. */
    internal fun partFileFor(key: TrackKey): File = File(audioDirectory, fileNameFor(key) + PART_SUFFIX)

    private fun fileNameFor(key: TrackKey): String =
        key.releaseGroupMbid.value + "_" + key.discNumber + "_" + key.trackNumber

    /**
     * One write in flight.
     *
     * Every transition is guarded by a [Mutex] because the three callers are not the same thread: a
     * Media3 loading thread feeds [write] while the playback thread can abandon the handle on a seek
     * or a track change. The states are deliberately few - open, finished, failed - and every exit
     * from "open" removes the partial file.
     */
    private inner class FileWriteHandle(
        override val key: TrackKey,
        override val expectedSizeBytes: Long?,
        private val fetchHandle: TrackFetchHandle,
        private val partFile: File,
        private val targetFile: File,
        private var stream: OutputStream?,
        private val existing: AudioCacheEntity?,
    ) : AudioCacheWriteHandle {

        private val mutex: Mutex = Mutex()
        private var written: Long = 0L
        private var failed: Boolean = false
        private var finished: Boolean = false

        override val bytesWritten: Long get() = written

        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            mutex.withLock {
                val target: OutputStream = stream ?: return
                try {
                    withContext(ioDispatcher) { target.write(bytes, offset, length) }
                    written += length
                } catch (error: IOException) {
                    // A cache write must never be able to fail the playback it is riding along
                    // with. The user is listening to music; a full disk is not a reason to stop the
                    // song, so the handle simply stops accepting bytes and will refuse to commit.
                    failed = true
                    discardLocked()
                }
            }
        }

        override suspend fun commit(): Outcome<CachedAudio> = mutex.withLock { commitLocked() }

        /** The body of [commit], called with the handle's lock already held. */
        private suspend fun commitLocked(): Outcome<CachedAudio> {
            if (finished) return Outcome.Failure(NeedlerError.Cancelled)
            if (failed) {
                discardLocked()
                return Outcome.Failure(NeedlerError.Unexpected("audio cache write failed"))
            }

            closeStreamLocked()

            val expected: Long? = expectedSizeBytes
            if (written <= 0L || (expected != null && written != expected)) {
                // A short body is a truncated fetch however cleanly the connection closed. Publishing
                // it would produce silent playback that stops early, months from now, with no error
                // to trace it by.
                discardLocked()
                return Outcome.Failure(
                    NeedlerError.ProtocolViolation(
                        "cached audio truncated for " + key.canonicalString +
                            ": wrote " + written + " of " + expected,
                    ),
                )
            }

            val published: Boolean = withContext(ioDispatcher) {
                targetFile.delete()
                partFile.renameTo(targetFile)
            }
            if (!published) {
                discardLocked()
                return Outcome.Failure(
                    NeedlerError.Unexpected("could not publish cached audio for " + key.canonicalString),
                )
            }

            val row: CachedAudio = publishRow(
                key = key,
                fetchHandle = fetchHandle,
                targetFile = targetFile,
                writtenBytes = written,
                existing = existing,
                // A streamed write never changes the tier. Pinning is the user's decision and is
                // made elsewhere; a row that was pinned before keeps its exemption from eviction.
                pinned = existing?.pinned ?: false,
            )
            finished = true
            return Outcome.Success(row)
        }

        override suspend fun abandon() {
            mutex.withLock {
                if (finished) return
                discardLocked()
            }
        }

        private fun discardLocked() {
            closeStreamLocked()
            deleteFile(partFile.path)
            finished = true
        }

        private fun closeStreamLocked() {
            val open: OutputStream = stream ?: return
            stream = null
            try {
                open.flush()
                open.close()
            } catch (error: IOException) {
                failed = true
            }
        }
    }

    /**
     * Writes the row that turns a file on disk into a track the app can find, and returns it.
     *
     * Shared by the streaming and the download paths on purpose. The fingerprint columns are the
     * reason: they are the record of the file **as it was fetched**, and they are what every later
     * staleness check compares against. Two call sites building that row independently is exactly
     * how one of them ends up writing the mirror's current values instead of the fetched ones, at
     * which point the check compares the mirror with itself and silently reports "unchanged" for
     * ever.
     *
     * Called only after the bytes are already at [targetFile], so a crash here leaves a file no row
     * names - which the next attempt at the same track overwrites, because the name is derived from
     * the key.
     */
    private suspend fun publishRow(
        key: TrackKey,
        fetchHandle: TrackFetchHandle,
        targetFile: File,
        writtenBytes: Long,
        existing: AudioCacheEntity?,
        pinned: Boolean,
    ): CachedAudio {
        val at: Long = nowMillis()
        val row = AudioCacheEntity(
            releaseGroupMbid = key.releaseGroupMbid.value,
            discNo = key.discNumber,
            trackNo = key.trackNumber,
            recordingMbid = existing?.recordingMbid,
            filePath = targetFile.path,
            sizeBytes = writtenBytes,
            complete = true,
            pinned = pinned,
            lastPlayedAt = existing?.lastPlayedAt ?: at,
            playCount = existing?.playCount ?: 0,
            downloadedAt = at,
            // The fingerprint of the file *as it was fetched*, which is what makes the staleness
            // check possible: sync overwrites the mirror, so a comparison against the mirror would
            // only ever compare it with itself.
            sourceFileId = fetchHandle.fileId.value,
            sourceSizeBytes = fetchHandle.sizeBytes,
            sourceDurationMs = fetchHandle.durationMs,
            sourceFormat = formatToken(fetchHandle.format),
            sourceBitrateKbps = fetchHandle.bitrateKbps,
        )
        audioCacheDao.upsert(row)
        return CachedAudio(
            key = key,
            filePath = row.filePath,
            sizeOnDiskBytes = row.sizeBytes,
            isComplete = true,
            lastPlayedAt = Instant.fromEpochMilliseconds(row.lastPlayedAt),
            pinned = row.pinned,
            sourceHandle = fetchHandle,
            downloadedAt = Instant.fromEpochMilliseconds(at),
        )
    }

    /**
     * One track's download, resumable across process death.
     *
     * Unlike the streaming handle this holds no open stream: the bytes are written by
     * `RangeDownloader` straight into [partFile], which is what lets a `Range` GET pick up where a
     * killed process left off. All this owns is the decision about when those bytes become a track.
     */
    private inner class FileDownloadSlot(
        override val key: TrackKey,
        private val fetchHandle: TrackFetchHandle,
        override val partFile: File,
        private val targetFile: File,
        override val bytesOnDisk: Long,
        override val expectedSizeBytes: Long?,
        private val existing: AudioCacheEntity?,
    ) : AudioDownloadSlot.Open {

        private val mutex: Mutex = Mutex()
        private var finished: Boolean = false

        override suspend fun commit(completeLengthBytes: Long?): Outcome<CachedAudio> =
            mutex.withLock {
                if (finished) return Outcome.Failure(NeedlerError.Cancelled)

                val written: Long = withContext(ioDispatcher) {
                    if (partFile.isFile) partFile.length() else 0L
                }
                // The transfer's own view of the file's length wins over the mirror's. The mirror
                // can legitimately be stale after a server-side quality upgrade, and failing a
                // download whose bytes are complete because a cached number disagrees would leave
                // the album permanently un-downloadable.
                val expected: Long? = completeLengthBytes ?: expectedSizeBytes
                if (written <= 0L || (expected != null && written < expected)) {
                    discardLocked()
                    return Outcome.Failure(
                        NeedlerError.ProtocolViolation(
                            "downloaded audio truncated for " + key.canonicalString +
                                ": wrote " + written + " of " + expected,
                        ),
                    )
                }

                val moved: Boolean = withContext(ioDispatcher) {
                    targetFile.delete()
                    partFile.renameTo(targetFile)
                }
                if (!moved) {
                    discardLocked()
                    return Outcome.Failure(
                        NeedlerError.Unexpected(
                            "could not publish downloaded audio for " + key.canonicalString,
                        ),
                    )
                }

                val row: CachedAudio = publishRow(
                    key = key,
                    fetchHandle = fetchHandle,
                    targetFile = targetFile,
                    writtenBytes = written,
                    existing = existing,
                    // These bytes were asked for, so they join the downloaded tier and are exempt
                    // from eviction from this moment on.
                    pinned = true,
                )
                finished = true
                Outcome.Success(row)
            }

        override suspend fun discard() {
            mutex.withLock { discardLocked() }
        }

        private fun discardLocked() {
            deleteFile(partFile.path)
            finished = true
        }
    }

    public companion object {

        /** Suffix of a published file. The extension is cosmetic; Media3 sniffs the container. */
        public const val AUDIO_SUFFIX: String = ".audio"

        /** Suffix of an in-progress file. Nothing outside this class reads one. */
        public const val PART_SUFFIX: String = ".part"

        /**
         * The token written to `audio_cache.source_format`.
         *
         * Lower-cased, because [app.needler.core.data.local.staleness.StalenessChecker] normalises
         * both sides that way before comparing. [AudioFormat.UNKNOWN] stores null rather than the
         * word: a null on either side means "unknown", never "changed", and writing a literal
         * "unknown" would turn a server that stopped reporting formats into a full re-download of
         * the user's offline library.
         */
        internal fun formatToken(format: AudioFormat?): String? = when (format) {
            null, AudioFormat.UNKNOWN -> null
            AudioFormat.OGG_VORBIS -> "ogg"
            else -> format.name.lowercase()
        }
    }
}

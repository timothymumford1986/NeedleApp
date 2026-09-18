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
) : AudioCacheWriter {

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

            val at: Long = nowMillis()
            val row: AudioCacheEntity = AudioCacheEntity(
                releaseGroupMbid = key.releaseGroupMbid.value,
                discNo = key.discNumber,
                trackNo = key.trackNumber,
                recordingMbid = existing?.recordingMbid,
                filePath = targetFile.path,
                sizeBytes = written,
                complete = true,
                // A streamed write never changes the tier. Pinning is the user's decision and is
                // made elsewhere; a row that was pinned before keeps its exemption from eviction.
                pinned = existing?.pinned ?: false,
                lastPlayedAt = existing?.lastPlayedAt ?: at,
                playCount = existing?.playCount ?: 0,
                downloadedAt = at,
                // The fingerprint of the file *as it was fetched*, which is what makes the staleness
                // check possible: sync overwrites the mirror, so a comparison against the mirror
                // would only ever compare it with itself.
                sourceFileId = fetchHandle.fileId.value,
                sourceSizeBytes = fetchHandle.sizeBytes,
                sourceDurationMs = fetchHandle.durationMs,
                sourceFormat = formatToken(fetchHandle.format),
                sourceBitrateKbps = fetchHandle.bitrateKbps,
            )
            audioCacheDao.upsert(row)
            finished = true

            return Outcome.Success(
                CachedAudio(
                    key = key,
                    filePath = row.filePath,
                    sizeOnDiskBytes = row.sizeBytes,
                    isComplete = true,
                    lastPlayedAt = Instant.fromEpochMilliseconds(row.lastPlayedAt),
                    pinned = row.pinned,
                    sourceHandle = fetchHandle,
                    downloadedAt = Instant.fromEpochMilliseconds(at),
                ),
            )
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

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
import kotlinx.coroutines.CancellationException
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
 *
 * ## Why the two write paths do not share a partial file
 *
 * A stream and a download of the same track write different files on their way in - [PART_SUFFIX]
 * for a download, [STREAM_PART_SUFFIX] for a stream - and only the published name is shared. They
 * used to share the partial as well, which is the defect REQUIREMENTS.md's "Offline and caching"
 * section records as "downloads are not retained": the two paths have opposite rules about a
 * leftover partial, and one store cannot honour both through one filename.
 *
 * [openWrite] must delete whatever partial it finds, because a stream always begins at byte zero and
 * a leftover would be prefixed to it. [openDownload] must keep it, because those bytes are exactly
 * what the next `Range` GET resumes from. With one name, playing any track of an album that is being
 * pulled deleted that track's download in flight - and the player abandons its handle on every track
 * change and every seek, which deletes the file again. The download then found its own partial
 * missing or shortened, refused to publish it, and threw away what was left: a `.part` that grew for
 * a few megabytes, vanished, and left nothing behind, over and over, with nothing written to
 * logcat. Two names cost one constant and make the interference impossible rather than unlikely.
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

        // 5. The stream's own partial, which is deliberately *not* the download's. Deleting it here
        //    is right for a stream and catastrophic for a download, and one filename cannot be both
        //    - see the class comment.
        val partFile: File = streamPartFileFor(source.key)
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
     * **The part file is not deleted.** [openWrite] deletes its own partial before it starts,
     * because a stream always begins at byte zero and a leftover would be prefixed to it. A download
     * resumes: the bytes on disk are exactly what the `Range` header asks the server to continue
     * from, and throwing them away would make every interruption cost the whole track again. This
     * is the difference that makes a second entry point necessary rather than a flag - and the
     * reason the two paths are given different partial file names, so that neither can act on the
     * other's rule. See the class comment.
     *
     * **A row is not evidence on its own.** [AudioDownloadSlot.AlreadyOnDevice] is answered only
     * when the bytes the row names are actually on the disk. `audio_cache` rows can outlive their
     * files by design - [CacheIndex.applyEviction] unlinks bytes before rows, and so does the
     * staleness eviction in `AlbumSyncer`, precisely so that a crash between the two leaves a cache
     * miss rather than a leak - and a row that has outlived its file would otherwise make this
     * function report a track as downloaded for ever. The album would go to `COMPLETE`, the green
     * check would be drawn, and playing it offline would find nothing: the same silent lie a
     * truncated file tells, arrived at from the index side.
     *
     * **A promotion is recorded.** These bytes were asked for, so a matching row that is still in
     * the cached-while-listening tier joins the downloaded tier here. Without this, a track whose
     * bytes were streamed *after* the album was pinned - `PinRepository.pinAlbum` flips the rows
     * that exist at the moment of the pin, and cannot flip one that does not exist yet - stays
     * `pinned = 0`, and an LRU pass is then free to delete a file the user explicitly asked to
     * keep. REQUIREMENTS.md: "Downloaded albums have no limit at all ... nothing evicts one."
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
        // Complete bytes from the same server-side file, *and* those bytes are still on the disk:
        // nothing to fetch. This is what makes pinning an album you have been streaming nearly free
        // - the rows are promoted, not re-downloaded. A row whose file has gone falls through to a
        // fresh download instead, because a row is a claim and the file is the evidence.
        if (existing != null &&
            existing.complete &&
            existing.sourceFileId == fetchHandle.fileId.value &&
            bytesArePresent(existing)
        ) {
            // The user asked for these bytes, so they belong to the tier that is never evicted. A
            // row streamed into place after the pin was recorded is still unpinned at this point.
            if (!existing.pinned) audioCacheDao.upsert(existing.copy(pinned = true))
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

    /**
     * Reclaims partial files that no longer stand for anything.
     *
     * A `.part` is only ever worth keeping while it is the resume point for a track that is not yet
     * on the device. Once the track *is* published, any partial left beside it is dead weight that
     * nothing will ever read again and only an uninstall would otherwise reclaim - REQUIREMENTS.md
     * is explicit that a file with no row is the leak worth avoiding.
     *
     * The rule is deliberately narrow: a partial is removed only when the same track has a complete
     * row **and** the bytes that row names are on the disk. Anything less would risk deleting the
     * partial of a download that is in flight right now, which is the very failure this sweep sits
     * next to. Nothing here touches a partial for a track that is not yet published, however old it
     * looks: age is not evidence, and the next attempt resumes from it.
     *
     * @return how many files were unlinked, for the caller's diagnostics.
     */
    override suspend fun sweepOrphanedParts(keys: Collection<TrackKey>): Int {
        var removed = 0
        for (key in keys) {
            val part: File = partFileFor(key)
            val present: Boolean = withContext(ioDispatcher) { part.isFile }
            if (!present) continue
            val row: AudioCacheEntity = audioCacheDao.get(
                releaseGroupMbid = key.releaseGroupMbid.value,
                discNo = key.discNumber,
                trackNo = key.trackNumber,
            ) ?: continue
            if (!row.complete || !bytesArePresent(row)) continue
            if (deleteFile(part.path)) removed++
        }
        return removed
    }

    /**
     * True when the bytes an `audio_cache` row names are really there.
     *
     * The length test is not pedantry. A row can outlive its file - eviction unlinks bytes before
     * rows on purpose - and it can also outlive *most* of its file if a write was interrupted after
     * the rename. Either way the honest answer to "is this track on the device" is no, and the only
     * thing that can give it is the filesystem.
     */
    private suspend fun bytesArePresent(row: AudioCacheEntity): Boolean = withContext(ioDispatcher) {
        val file = File(row.filePath)
        file.isFile && file.length() > 0L && file.length() >= row.sizeBytes
    }

    /** The published file for a track. Deterministic, so a retry reclaims an orphan of its own. */
    internal fun fileFor(key: TrackKey): File = File(audioDirectory, fileNameFor(key) + AUDIO_SUFFIX)

    /**
     * The in-progress file of a **download**. Nothing but this class ever looks at a `.part`.
     *
     * Survives across processes on purpose: it is the offset the next `Range` GET resumes from.
     */
    internal fun partFileFor(key: TrackKey): File = File(audioDirectory, fileNameFor(key) + PART_SUFFIX)

    /**
     * The in-progress file of a **stream**, which is a different file from [partFileFor].
     *
     * Separate because the two paths have opposite rules about a leftover partial and share nothing
     * but the published name. See the class comment for what sharing it cost.
     */
    internal fun streamPartFileFor(key: TrackKey): File =
        File(audioDirectory, fileNameFor(key) + STREAM_PART_SUFFIX)

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
                // made elsewhere; a row that was pinned before keeps its exemption from eviction,
                // which [publishRow] enforces by re-reading rather than by trusting this snapshot.
                pinnedByThisWrite = false,
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
     *
     * ## The row is re-read here, not taken from the caller's snapshot
     *
     * The caller's `existing` was read when the write was *opened*, which on a stream can be minutes
     * earlier and on a download can be before the same track's row existed at all. Writing the
     * snapshot back would silently undo whatever happened in between, and one of the things that
     * happens in between is a pin: a stream that opened before the album was pinned, or before the
     * download of that track committed, would rewrite `pinned = 0` over a downloaded row and hand a
     * file the user explicitly asked to keep straight to the LRU pass. REQUIREMENTS.md allows
     * exactly one direction of travel here - "Downloaded albums have no limit at all ... nothing
     * evicts one" - so the tier is the union of what the row already had and what this write claims,
     * never a replacement. The play counters are re-read for the same reason.
     *
     * @param pinnedByThisWrite whether *this* write puts the bytes in the downloaded tier. True for
     *   a download, because those bytes were asked for; false for a stream, because pinning is the
     *   user's decision and is made elsewhere. It can only ever add a row to the tier: a row already
     *   pinned stays pinned whatever this says.
     */
    private suspend fun publishRow(
        key: TrackKey,
        fetchHandle: TrackFetchHandle,
        targetFile: File,
        writtenBytes: Long,
        existing: AudioCacheEntity?,
        pinnedByThisWrite: Boolean,
    ): CachedAudio {
        val at: Long = nowMillis()
        // The row as it stands now, falling back to the open-time snapshot only when the row has
        // been dropped in the meantime - an eviction or a "remove from device" mid-write.
        val current: AudioCacheEntity? = audioCacheDao.get(
            releaseGroupMbid = key.releaseGroupMbid.value,
            discNo = key.discNumber,
            trackNo = key.trackNumber,
        ) ?: existing
        val row = AudioCacheEntity(
            releaseGroupMbid = key.releaseGroupMbid.value,
            discNo = key.discNumber,
            trackNo = key.trackNumber,
            recordingMbid = current?.recordingMbid,
            filePath = targetFile.path,
            sizeBytes = writtenBytes,
            complete = true,
            pinned = pinnedByThisWrite || current?.pinned == true,
            lastPlayedAt = current?.lastPlayedAt ?: at,
            playCount = current?.playCount ?: 0,
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

                // More bytes on disk than the whole file has. This is the one case where the
                // partial is *proven* wrong rather than merely incomplete - it cannot be resumed
                // into anything correct - so it is thrown away and the next attempt starts at zero.
                // It is also how a partial left by an older build, written under a different rule,
                // is detected and discarded rather than published as music.
                if (completeLengthBytes != null && written > completeLengthBytes) {
                    discardLocked()
                    return Outcome.Failure(
                        NeedlerError.ProtocolViolation(
                            "downloaded audio overran for " + key.canonicalString +
                                ": " + written + " on disk of " + completeLengthBytes,
                        ),
                    )
                }

                if (written <= 0L || (expected != null && written < expected)) {
                    // Refuse to publish, and **keep the bytes**. This is the correction that matters
                    // most: a short file is not a wrong file, it is an unfinished one, and the whole
                    // purpose of a deterministic part file is that the next `Range` GET continues
                    // from it. Deleting here turned every finalise failure into a fresh download of
                    // the whole track, which on a repeating failure means the device never retains
                    // anything however long the pull runs - the behaviour REQUIREMENTS.md records
                    // under "Offline and caching" as downloads not being retained.
                    //
                    // The slot is deliberately left unfinished: the caller reopens it, sees the
                    // bytes still there through `bytesOnDisk`, and resumes.
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

                val row: CachedAudio = try {
                    publishRow(
                        key = key,
                        fetchHandle = fetchHandle,
                        targetFile = targetFile,
                        writtenBytes = written,
                        existing = existing,
                        // These bytes were asked for, so they join the downloaded tier and are
                        // exempt from eviction from this moment on.
                        pinnedByThisWrite = true,
                    )
                } catch (cancellation: CancellationException) {
                    // Structured concurrency: a cancelled job is not a modelled failure, and the
                    // bytes on disk are fine. The album resumes.
                    throw cancellation
                } catch (error: Throwable) {
                    // The bytes are published and the index write failed - a full disk, a locked
                    // database, anything Room can throw. Left unguarded this escaped the worker,
                    // which `WorkManager` turns into a bare failure with the pin row still reading
                    // DOWNLOADING and nothing anywhere saying why. The file stays where it is: the
                    // name is derived from the track key, so the next attempt reclaims it rather
                    // than adding a second copy.
                    return Outcome.Failure(
                        NeedlerError.Unexpected(
                            detail = "could not index downloaded audio for " + key.canonicalString,
                            cause = error,
                        ),
                    )
                }
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

        /**
         * Suffix of a download in progress. Nothing outside this class reads one.
         *
         * Unchanged from the first release on purpose: a device that has already run a pull holds
         * partial downloads under this name, and renaming it would strand them as files no code
         * would ever resume from or collect.
         */
        public const val PART_SUFFIX: String = ".part"

        /**
         * Suffix of a stream in progress, which is a different file from [PART_SUFFIX].
         *
         * See the class comment for why the two paths may not share one. A partial left under this
         * name by a killed process is replaced wholesale by the next stream of the same track, so it
         * needs no resume rule and no sweep of its own.
         */
        public const val STREAM_PART_SUFFIX: String = ".streaming"

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

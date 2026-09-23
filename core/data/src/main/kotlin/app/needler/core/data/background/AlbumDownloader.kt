package app.needler.core.data.background

import app.needler.core.data.local.cache.AudioDownloadSlot
import app.needler.core.data.local.cache.AudioDownloadStore
import app.needler.core.data.local.dao.AlbumDao
import app.needler.core.data.local.dao.AudioCacheDao
import app.needler.core.data.local.dao.PinDao
import app.needler.core.data.local.dao.TrackDao
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.data.local.entity.PinEntity
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.data.mapper.EntityMappers
import app.needler.core.data.mapper.ErrorMapper
import app.needler.core.data.mapper.networkCall
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.network.media.RangeDownloadResult
import kotlinx.coroutines.CancellationException

/**
 * Pulls one pinned album's audio onto the device, track by track.
 *
 * This is what makes "Pull local" and "Keep pulled albums on device" real. `DefaultPinRepository`
 * records the pin and stops there, deliberately - the per-track fetches must survive the screen and
 * the process, so they are a `WorkManager` job. This class is the body of that job, split out from
 * the `Worker` so every decision in it can be tested on the JVM with no device and no server.
 *
 * ## What it does not decide
 *
 * Three rules are enforced elsewhere and are not restated here:
 *
 *  * **Free space.** [AudioDownloadStore.openDownload] makes room through the same `CacheIndex`
 *    pass the streaming writer uses, and answers [AudioDownloadSlot.NoRoom] when the device cannot
 *    take the bytes even with the whole cached-while-listening tier given up. This class records
 *    that and stops; it never evicts a download to make room for another, because the user chose
 *    those and nothing in the app takes one away.
 *  * **Transcodes are never retained.** Nothing to enforce: `download?id=` serves original bytes
 *    and has no format parameter.
 *  * **Publishing partial bytes.** The slot refuses to commit a short transfer. A truncated file
 *    published as a complete track is the silent failure the whole store is built to prevent.
 *  * **Whether a track is already on the device.** [AudioDownloadStore.openDownload] answers that
 *    from the index *and* the disk, and promotes a matching row into the downloaded tier. This class
 *    takes the answer at face value; it does not second-guess it by stat-ing files of its own.
 *
 * ## Resume
 *
 * Each track has one deterministic part file. An interrupted album resumes at the byte it reached,
 * because the next run opens the same slot, sees the bytes already there and sends a `Range`
 * header. That is why a killed process, a lost network or a user walking out of Wi-Fi costs
 * seconds rather than an album.
 *
 * Resume is also why a failed finalise is a retry rather than a restart. The slot refuses to publish
 * a short file, as it must, but it leaves the bytes alone; this class asks for another go and the
 * next `Range` GET continues from them. The two used to be one decision - refuse and delete - and
 * the cost of that was an album that could download the same track over and over for a quarter of
 * an hour and retain nothing, which is the failure REQUIREMENTS.md records under "Offline and
 * caching".
 *
 * ## Housekeeping
 *
 * A pass ends by sweeping part files whose tracks are now published. Nothing else in the app looks
 * at a partial, so an abandoned one is bytes only an uninstall would reclaim; and this is the one
 * place that can tell an abandoned partial from the resume point of a download in flight, because it
 * is the thing doing the downloading.
 *
 * ## Failure
 *
 * [AlbumDownloadOutcome] has three shapes on purpose. [AlbumDownloadOutcome.Retry] is a transport
 * problem and the job asks `WorkManager` for another go with backoff; [AlbumDownloadOutcome.Failed]
 * is permanent, records the reason on the pin row and stops, so the album screen can say what
 * happened rather than spinning for ever.
 */
public class AlbumDownloader(
    private val pinDao: PinDao,
    private val trackDao: TrackDao,
    private val audioCacheDao: AudioCacheDao,
    private val albumDao: AlbumDao,
    private val store: AudioDownloadStore,
    private val byteSource: TrackByteSource,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AlbumDownloadEngine {

    override suspend fun download(
        releaseGroupMbid: String,
        onProgress: suspend (AlbumDownloadProgress) -> Unit,
    ): AlbumDownloadOutcome {
        val pin: PinEntity = pinDao.getPin(releaseGroupMbid)
            ?: return AlbumDownloadOutcome.NothingToDo

        val tracks: List<TrackEntity> = trackDao.getAlbumTracks(releaseGroupMbid)
        // A part-delivered pull leaves tracks the server has no file for. They exist nowhere, on
        // the server or on any device, so they are not failures of this download - they are simply
        // not downloadable, and the album screen greys them in their right positions.
        val fetchable: List<TrackEntity> = tracks.filter(EntityMappers::hasPlayableFile)

        if (fetchable.isEmpty()) {
            recordProgress(
                pin = pin,
                state = DownloadStateDb.PARTIAL,
                complete = onDeviceCount(releaseGroupMbid),
                total = tracks.size,
                error = if (tracks.isEmpty()) "album has no tracks yet" else null,
            )
            return AlbumDownloadOutcome.NothingToDo
        }

        recordProgress(
            pin = pin,
            state = DownloadStateDb.DOWNLOADING,
            complete = onDeviceCount(releaseGroupMbid),
            total = tracks.size,
            error = null,
        )

        var completed = 0
        var bytes = 0L
        val keys: MutableList<TrackKey> = ArrayList(fetchable.size)

        for (track in fetchable) {
            val key = TrackKey(
                releaseGroupMbid = ReleaseGroupMbid(track.releaseGroupMbid),
                discNumber = track.discNo,
                trackNumber = track.trackNo,
            )
            keys.add(key)
            val handle: TrackFetchHandle = EntityMappers.fetchHandle(track)

            when (val outcome: TrackOutcome = attemptTrack(key, handle)) {
                is TrackOutcome.Done -> {
                    completed++
                    bytes += outcome.sizeBytes
                    recordProgress(
                        pin = pin,
                        state = DownloadStateDb.DOWNLOADING,
                        complete = completed,
                        total = tracks.size,
                        downloadedBytes = bytes,
                        error = null,
                    )
                    onProgress(
                        AlbumDownloadProgress(
                            releaseGroupMbid = releaseGroupMbid,
                            tracksComplete = completed,
                            tracksTotal = tracks.size,
                            downloadedBytes = bytes,
                        ),
                    )
                }

                is TrackOutcome.Retry -> {
                    // Leave the row as DOWNLOADING: the album is mid-flight, not broken, and the
                    // screen should keep showing progress rather than an error the user cannot act
                    // on. The bytes already on disk stay there for the retry to resume from.
                    recordProgress(
                        pin = pin,
                        state = DownloadStateDb.DOWNLOADING,
                        complete = completed,
                        total = tracks.size,
                        downloadedBytes = bytes,
                        error = null,
                    )
                    return AlbumDownloadOutcome.Retry(outcome.error)
                }

                is TrackOutcome.Fatal -> {
                    recordProgress(
                        pin = pin,
                        state = if (completed > 0) DownloadStateDb.PARTIAL else DownloadStateDb.FAILED,
                        complete = completed,
                        total = tracks.size,
                        downloadedBytes = bytes,
                        error = outcome.error.diagnostic,
                    )
                    return AlbumDownloadOutcome.Failed(outcome.error)
                }
            }
        }

        // Every track this pass could fetch has been fetched, so any part file still sitting beside
        // a published track is dead weight. The sweep is here and not on a timer because this is the
        // only place that knows no download of these tracks is in flight; it refuses to touch a
        // partial whose track is not on the device, which is what makes it safe to call at all.
        sweepOrphanedParts(keys)

        val onDevice: Int = onDeviceCount(releaseGroupMbid)
        val everyTrackLanded: Boolean = onDevice >= tracks.size && tracks.isNotEmpty()
        recordProgress(
            pin = pin,
            state = if (everyTrackLanded) DownloadStateDb.COMPLETE else DownloadStateDb.PARTIAL,
            complete = onDevice,
            total = tracks.size,
            downloadedBytes = bytes,
            error = null,
        )
        return AlbumDownloadOutcome.Success(tracksDownloaded = completed, bytes = bytes)
    }

    /**
     * [downloadTrack] with a net under it.
     *
     * Everything the transfer itself can throw is already folded onto [NeedlerError] by
     * `networkCall`, but the store is not a network call and can still throw: Room can fail the
     * index write on a full disk or a locked database, and the filesystem can fail a rename. Left
     * unguarded those escaped the `Worker`, which `WorkManager` records as a plain failure - no
     * retry, the pin row frozen on `DOWNLOADING`, and nothing anywhere saying what happened. That is
     * precisely the shape of failure REQUIREMENTS.md's "Offline and caching" note describes, where
     * the app writes nothing to logcat and the cause stays open.
     *
     * An unexpected throwable is treated as retryable. It is not a judgement that the next attempt
     * will work; it is that a permanent failure must be one the album screen can *explain*, and an
     * unrecognised exception is not. Cancellation is re-thrown untouched: a stopped job is not a
     * failure, and swallowing it would break structured concurrency.
     */
    private suspend fun attemptTrack(key: TrackKey, handle: TrackFetchHandle): TrackOutcome = try {
        downloadTrack(key, handle)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        TrackOutcome.Retry(ErrorMapper.toNeedlerError(error))
    }

    /**
     * One track, including the one retry that a `416` earns.
     *
     * `416` means the partial file's length no longer matches the server's file - almost always a
     * quality upgrade replacing the bytes underneath us. The only correct response is to throw the
     * partial away and fetch from zero, which is what REQUIREMENTS.md's failure table says and what
     * the single re-attempt here does. A second `416` on a fresh file is a server problem, not a
     * stale partial, so it is not retried again.
     */
    private suspend fun downloadTrack(key: TrackKey, handle: TrackFetchHandle): TrackOutcome {
        var resume = true
        var attempt = 0
        while (true) {
            when (val slot: AudioDownloadSlot = store.openDownload(key, handle)) {
                AudioDownloadSlot.AlreadyOnDevice ->
                    return TrackOutcome.Done(sizeBytes = handle.sizeBytes ?: 0L)

                AudioDownloadSlot.NoRoom ->
                    return TrackOutcome.Fatal(
                        NeedlerError.InsufficientStorage(requiredBytes = handle.sizeBytes),
                    )

                AudioDownloadSlot.Unwritable ->
                    return TrackOutcome.Retry(NeedlerError.Unexpected("audio store is not writable"))

                is AudioDownloadSlot.Open -> {
                    val transfer: Outcome<RangeDownloadResult> = networkCall(downloadContext = true) {
                        byteSource.download(
                            fileId = handle.fileId.value,
                            target = slot.partFile,
                            resume = resume,
                        )
                    }
                    when (transfer) {
                        is Outcome.Failure -> {
                            val error: NeedlerError = transfer.error
                            if (error is NeedlerError.RangeNotSatisfiable && attempt == 0) {
                                slot.discard()
                                resume = false
                                attempt++
                                continue
                            }
                            return if (error.isRetryable) {
                                TrackOutcome.Retry(error)
                            } else {
                                TrackOutcome.Fatal(error)
                            }
                        }

                        is Outcome.Success -> {
                            val result: RangeDownloadResult = transfer.value
                            if (!result.complete) {
                                // The body ended early. The bytes stay on disk; the next attempt
                                // resumes from them rather than starting again.
                                return TrackOutcome.Retry(
                                    NeedlerError.Offline(OfflineCause.TIMEOUT),
                                )
                            }
                            return when (val committed = slot.commit(result.completeLength)) {
                                // A refusal to publish is not a refusal to keep. The slot leaves the
                                // partial where it is unless it has been proven wrong, so this retry
                                // resumes from the bytes already fetched rather than paying for the
                                // whole track again - which is the difference between an album that
                                // eventually lands and one that never does.
                                is Outcome.Failure -> TrackOutcome.Retry(committed.error)
                                is Outcome.Success -> TrackOutcome.Done(
                                    sizeBytes = committed.value.sizeOnDiskBytes,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Reclaims part files whose tracks are now on the device.
     *
     * Deliberately the last thing a pass does, and deliberately unable to fail it. A sweep that
     * throws has cost the user some disk space; an album that fails because its housekeeping threw
     * has cost them the music, and that is not a trade worth making. Cancellation is re-thrown, as
     * everywhere: a stopped job has simply stopped.
     */
    private suspend fun sweepOrphanedParts(keys: List<TrackKey>) {
        try {
            store.sweepOrphanedParts(keys)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return
        }
    }

    private suspend fun onDeviceCount(releaseGroupMbid: String): Int =
        audioCacheDao.getAlbumRows(releaseGroupMbid).count { it.complete }

    private suspend fun recordProgress(
        pin: PinEntity,
        state: DownloadStateDb,
        complete: Int,
        total: Int,
        downloadedBytes: Long? = null,
        error: String?,
    ) {
        pinDao.setDownloadProgress(
            releaseGroupMbid = pin.releaseGroupMbid,
            downloadState = state,
            tracksComplete = complete,
            tracksTotal = if (total > 0) total else pin.tracksTotal,
            downloadedBytes = downloadedBytes ?: pin.downloadedBytes,
            totalBytes = pin.totalBytes,
            error = error,
            updatedAt = nowMillis(),
        )
    }

    /** The album's title and artist, for the job's progress notification. */
    public suspend fun headline(releaseGroupMbid: String): AlbumHeadline? =
        albumDao.getAlbum(releaseGroupMbid)?.let { AlbumHeadline(it.title, it.artistName) }

    private sealed interface TrackOutcome {
        data class Done(val sizeBytes: Long) : TrackOutcome
        data class Retry(val error: NeedlerError) : TrackOutcome
        data class Fatal(val error: NeedlerError) : TrackOutcome
    }
}

/** The part of [AlbumDownloader] a `Worker` needs, so the worker can be given a fake. */
public interface AlbumDownloadEngine {
    public suspend fun download(
        releaseGroupMbid: String,
        onProgress: suspend (AlbumDownloadProgress) -> Unit = {},
    ): AlbumDownloadOutcome
}

/** Live progress, so the album screen's download state moves while the job runs. */
public data class AlbumDownloadProgress(
    val releaseGroupMbid: String,
    val tracksComplete: Int,
    val tracksTotal: Int,
    val downloadedBytes: Long,
)

/** How one pass over an album ended. */
public sealed interface AlbumDownloadOutcome {

    /** Everything fetchable is on the device. */
    public data class Success(val tracksDownloaded: Int, val bytes: Long) : AlbumDownloadOutcome

    /** A transport problem. The job asks for another go with backoff; the bytes on disk survive. */
    public data class Retry(val error: NeedlerError) : AlbumDownloadOutcome

    /**
     * Permanent: the administrator disabled library download, or the device has no room.
     *
     * Recorded on the pin row with its reason, so the album screen says what happened instead of
     * showing a spinner that never resolves.
     */
    public data class Failed(val error: NeedlerError) : AlbumDownloadOutcome

    /** There was no pin, or the album has no downloadable tracks. Not a failure. */
    public data object NothingToDo : AlbumDownloadOutcome
}

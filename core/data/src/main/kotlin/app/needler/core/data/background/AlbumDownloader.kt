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
import app.needler.core.data.mapper.networkCall
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.network.media.RangeDownloadResult

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
 *
 * ## Resume
 *
 * Each track has one deterministic part file. An interrupted album resumes at the byte it reached,
 * because the next run opens the same slot, sees the bytes already there and sends a `Range`
 * header. That is why a killed process, a lost network or a user walking out of Wi-Fi costs
 * seconds rather than an album.
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

        for (track in fetchable) {
            val key = TrackKey(
                releaseGroupMbid = ReleaseGroupMbid(track.releaseGroupMbid),
                discNumber = track.discNo,
                trackNumber = track.trackNo,
            )
            val handle: TrackFetchHandle = EntityMappers.fetchHandle(track)

            when (val outcome: TrackOutcome = downloadTrack(key, handle)) {
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

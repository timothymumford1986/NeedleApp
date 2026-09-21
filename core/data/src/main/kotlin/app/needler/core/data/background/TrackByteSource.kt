package app.needler.core.data.background

import app.needler.core.data.mapper.SubsonicIds
import app.needler.core.network.media.RangeDownloadResult
import app.needler.core.network.media.RangeDownloader
import app.needler.core.network.media.SubsonicMediaUrls
import java.io.File

/**
 * Where a downloaded track's bytes come from.
 *
 * An interface for one reason: the download engine's interesting behaviour is what it does when a
 * transfer goes wrong - resuming from a partial file, discarding after a `416`, giving up on a
 * `403` - and none of that should need a server, a socket or a device to test. `AlbumDownloader`
 * takes this; the tests give it a fake that can be told to fail in each of those ways.
 */
public interface TrackByteSource {

    /**
     * Fetches one track into [target], continuing from whatever is already on disk when [resume] is
     * true.
     *
     * Throws `NetworkError` - the caller maps it. Three shapes matter and are handled by name in
     * `AlbumDownloader`: `RangeNotSatisfiable` (the partial file's length no longer matches the
     * server's), `Forbidden` (library download is switched off by the administrator) and everything
     * retryable.
     */
    public suspend fun download(
        fileId: String,
        target: File,
        resume: Boolean = true,
        onProgress: ((bytesOnDisk: Long, completeLength: Long?) -> Unit)? = null,
    ): RangeDownloadResult
}

/**
 * [TrackByteSource] over `download?id=`, which is the endpoint REQUIREMENTS.md names.
 *
 * Per-track `Range` GETs against this URL resume after interruption and report progress per track.
 * `GET /api/v1/download/local/album/{id}` is deliberately not used: it zips server-side, cannot
 * resume, gives no per-track progress, and makes the server build an archive it then throws away.
 *
 * It also happens to be the reason the downloader needs no transcode check of its own - this
 * endpoint serves original bytes and has no format parameter to ask it for anything else.
 */
public class SubsonicTrackByteSource(
    private val downloader: RangeDownloader,
    private val mediaUrls: SubsonicMediaUrls,
) : TrackByteSource {

    override suspend fun download(
        fileId: String,
        target: File,
        resume: Boolean,
        onProgress: ((bytesOnDisk: Long, completeLength: Long?) -> Unit)?,
    ): RangeDownloadResult = downloader.downloadTo(
        // The URL is rebuilt on every attempt rather than stored: it carries the app-password in a
        // query parameter, so it must never be persisted, logged raw or put in an intent extra.
        url = mediaUrls.downloadUrl(SubsonicIds.trackId(fileId)),
        target = target,
        resume = resume,
        onProgress = onProgress,
    )
}

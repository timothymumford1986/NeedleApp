package app.needler.core.network.media

import app.needler.core.network.ApiLane
import app.needler.core.network.CredentialProvider
import app.needler.core.network.NeedlerHttpClient
import app.needler.core.network.NetworkError
import app.needler.core.network.RetryPolicy
import app.needler.core.network.describeForLog
import app.needler.core.network.internal.HttpEngine
import app.needler.core.network.redactUrl
import app.needler.core.network.subsonic.SubsonicEnvelopeParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** What one [RangeDownloader.downloadTo] call achieved. */
public data class RangeDownloadResult(
    /** Bytes this call appended or wrote. */
    public val bytesWritten: Long,
    /** Size of the file on disk afterwards. */
    public val totalBytes: Long,
    /** The whole file's length as the server reports it, when it said. */
    public val completeLength: Long?,
    /** True when the server honoured the `Range` header and answered `206`. */
    public val resumed: Boolean,
    /** True when [totalBytes] equals [completeLength], or the server gave no length. */
    public val complete: Boolean,
    public val contentType: String?,
)

/**
 * Resumable, non-buffering byte fetch for offline downloads ("Pull local").
 *
 * Bytes are streamed straight to disk in fixed-size chunks; nothing is held in memory, and audio
 * is never buffered through a DTO. Built for the `download?id=` endpoint, and usable for any
 * binary URL from [SubsonicMediaUrls].
 *
 * REQUIREMENTS.md requires two behaviours this class implements:
 *  * resume after interruption with a `Range` header, rather than restarting a track;
 *  * surface `416` distinctly as [NetworkError.RangeNotSatisfiable], because it means the cached
 *    length is wrong and the partial file must be discarded and refetched.
 *
 * The server also disables gzip on audio so ranges and seeking work; this class asks for
 * `Accept-Encoding: identity` anyway so no proxy re-introduces it.
 *
 * Direct streams are capped at eight per user and 32 server-wide, so callers must serialise
 * downloads and keep a slot free for playback. This class does not police that.
 */
public class RangeDownloader(
    http: NeedlerHttpClient,
    private val credentials: CredentialProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Downloads are long-lived and retried by `WorkManager`, so in-call retries stay minimal. */
    private val retryPolicy: RetryPolicy = RetryPolicy(maxRetries = 1),
) {

    private val engine = HttpEngine(http, credentials)

    /**
     * Shared with the rest of the module, and used **only** at the two ends of a download.
     *
     * Nothing is logged inside the copy loop. That loop runs once per 64 KiB, which for a FLAC
     * album is tens of thousands of iterations per track; a line there would cost more than the
     * download and would push every other line out of logcat's ring buffer, which is precisely the
     * opposite of what this instrumentation is for. `onProgress` already exists for anyone who
     * wants byte-by-byte feedback, and it reports to the caller rather than to the log.
     */
    private val log = http.log

    /**
     * Fetch [url] into [target], continuing from whatever is already on disk when [resume] is true.
     *
     * @param onProgress called with (bytes on disk, complete length or null) as bytes arrive. It
     *   must be cheap: it is invoked on the I/O dispatcher between chunk writes.
     * @throws NetworkError.RangeNotSatisfiable when the server rejects the requested range — the
     *   caller deletes [target] and calls again from zero.
     * @throws NetworkError.Forbidden when library download is disabled by the administrator.
     */
    public suspend fun downloadTo(
        url: String,
        target: File,
        resume: Boolean = true,
        onProgress: ((bytesOnDisk: Long, completeLength: Long?) -> Unit)? = null,
    ): RangeDownloadResult = withContext(ioDispatcher) {
        // Captured once so the copy loop can check for cancellation without a suspend point.
        val callContext = coroutineContext
        val httpUrl = url.toHttpUrlOrNull() ?: throw NetworkError.Serialisation(
            ApiLane.Subsonic,
            IllegalArgumentException("Not a valid HTTP URL"),
        )
        val existingBytes = if (resume && target.isFile) target.length() else 0L
        log.debug {
            "download starting " + redactUrl(httpUrl) + " into " + target.name +
                " from " + existingBytes + "B"
        }
        val builder = Request.Builder()
            .url(httpUrl)
            .get()
            .header("Accept-Encoding", "identity")
            .tag(ApiLane::class, ApiLane.Subsonic)
        if (existingBytes > 0) builder.header("Range", "bytes=$existingBytes-")

        engine.execute(builder.build(), media = true, policy = retryPolicy).use { response ->
            // Audio goes through the same edge proxy as the JSON lanes. Without this an
            // intercepted download writes a login page into the cache as though it were music.
            engine.requireNotInterceptedBinary(response, ApiLane.Subsonic)
            when {
                response.code == 416 -> {
                    val completeLength = HttpEngine.completeLengthOf(response)
                    log.warn {
                        "download range rejected " + redactUrl(httpUrl) + ": asked from " +
                            existingBytes + "B, server says the file is " +
                            (completeLength?.toString() ?: "an unknown size") +
                            " - the partial file must be discarded"
                    }
                    throw NetworkError.RangeNotSatisfiable(completeLength)
                }

                !response.isSuccessful -> throw engine.mapHttpFailure(
                    response,
                    response.body.string(),
                    ApiLane.Subsonic,
                )
            }

            // An auth failure on a binary endpoint comes back as HTTP 200 carrying a
            // status=failed envelope, not as a 401, so a JSON or XML body here is an error.
            val subtype = response.body.contentType()?.subtype?.lowercase()
            if (subtype == "json" || subtype == "xml") {
                val body = response.body.string()
                val envelope = SubsonicEnvelopeParser.parse(engine.json, body)
                val error = SubsonicEnvelopeParser.toNetworkError(
                    envelope.error,
                    HttpEngine.retryAfterSeconds(response),
                    credentials,
                )
                log.error {
                    "download refused " + redactUrl(httpUrl) + ": the server sent " + subtype +
                        " instead of audio, code=" + (envelope.error?.code?.toString() ?: "?") +
                        " mapped to " + error.describeForLog()
                }
                throw error
            }

            val partial = response.code == 206
            // A server that ignored the Range header restarts the file from byte zero.
            val startAt = if (partial) existingBytes else 0L
            val completeLength = resolveCompleteLength(response, startAt)
            if (target.parentFile?.exists() == false) target.parentFile?.mkdirs()

            var written = 0L
            try {
                FileOutputStream(target, startAt > 0).use { output ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    response.body.byteStream().use { input ->
                        while (true) {
                            callContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress?.invoke(startAt + written, completeLength)
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            } catch (failure: IOException) {
                // Distinct from a transport failure before the first byte: the socket died with a
                // part-written file on disk, and the size at the moment it died is what tells the
                // next attempt whether resume will help.
                log.error {
                    "download interrupted " + redactUrl(httpUrl) + " after " + written +
                        "B (" + (startAt + written) + "B on disk): " + failure.javaClass.simpleName
                }
                throw engine.mapTransportFailure(builder.build(), failure)
            }

            val totalBytes = startAt + written
            val result = RangeDownloadResult(
                bytesWritten = written,
                totalBytes = totalBytes,
                completeLength = completeLength,
                resumed = partial,
                complete = completeLength == null || totalBytes >= completeLength,
                contentType = response.body.contentType()?.toString(),
            )
            // The line the first of the two reported bugs needed. "Downloads run but retain
            // nothing" has two completely different causes - bytes that never arrived, and bytes
            // that arrived and were then discarded by whatever finalises the file - and this
            // module can settle which one it is in a single line: how many bytes were written,
            // how large the file is now, how large the server says it should be, and whether the
            // download is therefore finished. If this says `complete=true size=41283910` and the
            // file is gone a minute later, the fault is not here.
            log.info {
                "download finished " + redactUrl(httpUrl) + " -> HTTP " + response.code +
                    " wrote=" + written + "B size=" + totalBytes + "B of " +
                    (completeLength?.toString() ?: "unknown") + " resumed=" + partial +
                    " complete=" + result.complete + " type=" + (result.contentType ?: "none") +
                    " target=" + target.name
            }
            result
        }
    }

    /**
     * `HEAD` the URL for the file's length and type, without fetching bytes.
     *
     * Useful for the staleness check on sync: DroppedNeedle replaces files in place on a quality
     * upgrade, and a changed size means cached bytes must be evicted.
     */
    public suspend fun probeLength(url: String): Long? = withContext(ioDispatcher) {
        val httpUrl = url.toHttpUrlOrNull() ?: return@withContext null
        val request = Request.Builder()
            .url(httpUrl)
            .head()
            .header("Accept-Encoding", "identity")
            .tag(ApiLane::class, ApiLane.Subsonic)
            .build()
        engine.execute(request, media = true, policy = retryPolicy).use { response ->
            engine.requireNotInterceptedBinary(response, ApiLane.Subsonic)
            if (!response.isSuccessful) throw engine.mapHttpFailure(response, null, ApiLane.Subsonic)
            response.header("Content-Length")?.trim()?.toLongOrNull()
        }
    }

    /**
     * The whole file's length: `Content-Range: bytes a-b/total` when present, otherwise
     * `Content-Length` plus whatever was already on disk.
     */
    private fun resolveCompleteLength(response: Response, startAt: Long): Long? {
        HttpEngine.completeLengthOf(response)?.let { return it }
        val contentLength = response.header("Content-Length")?.trim()?.toLongOrNull() ?: return null
        return startAt + contentLength
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
    }
}

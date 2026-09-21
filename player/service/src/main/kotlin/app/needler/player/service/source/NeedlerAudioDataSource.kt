package app.needler.player.service.source

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.common.util.UnstableApi
import app.needler.core.domain.cache.AudioCacheWriteHandle
import app.needler.core.domain.cache.AudioCacheWriter
import app.needler.core.domain.model.CacheEvictionReason
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.PlayableSource
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.usecase.ResolvePlayableSourceUseCase
import app.needler.player.service.media.TrackUri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/**
 * The one `DataSource` Needler plays through: it resolves where a track's bytes come from at the moment it
 * is opened, reads them from disk or over HTTP, and writes streamed bytes into the audio store on the way
 * past.
 *
 * ## What it replaces
 *
 * `CacheDataSource` over `SimpleCache` would be six lines instead of this file, and REQUIREMENTS.md rules it
 * out for four reasons - the shortest being that it would keep a 320 kbps transcode as a FLAC track's
 * permanent offline copy. The other three: its evictor is a fixed byte cap, which is the storage budget this
 * product deliberately removed; it cannot be told that a downloaded album is never a candidate for
 * eviction; and it has nowhere to record the per-track fingerprint the staleness check compares against.
 *
 * ## Resolution happens on open, not on enqueue
 *
 * The `MediaItem` carries `needler://track/...` and nothing else, so every play asks
 * [ResolvePlayableSourceUseCase] afresh. A queue built an hour ago on Wi-Fi therefore does not still ask for
 * a transcode on mobile data, a track downloaded in the meantime plays from disk, and a cached copy the
 * server has since replaced is discarded rather than played. Baking a URL into the queue answers all three
 * questions once, far too early.
 *
 * ## Threading
 *
 * `open` and `read` are called on ExoPlayer's loading thread, which exists to block. The suspend calls into
 * the resolver and the store therefore run under `runBlocking` here, deliberately: the alternative is a
 * callback-shaped data source, and every byte of audio would go through it.
 */
@OptIn(UnstableApi::class)
public class NeedlerAudioDataSource(
    private val resolveSource: ResolvePlayableSourceUseCase,
    private val cacheWriter: AudioCacheWriter,
    private val pinRepository: PinRepository,
    private val audioUrls: AudioUrls,
    private val httpDataSourceFactory: HttpDataSource.Factory,
    private val retryPolicy: StreamRetryPolicy = StreamRetryPolicy(),
    /** Injected so a test does not sleep for real, and so the wait is visible. */
    private val sleep: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    /** Repo convention: the clock is a lambda so a test can pin it. */
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : BaseDataSource(true) {

    private var delegate: DataSource? = null
    private var sink: WriteThroughSink? = null
    private var openedUri: Uri? = null
    private var readToEnd: Boolean = false

    /**
     * Whether `transferStarted` was reported for this open.
     *
     * Media3 calls `close()` even when `open()` threw, and a `transferEnded` with no matching start is a
     * bandwidth meter told a transfer finished that never began.
     */
    private var started: Boolean = false

    /**
     * True while the bytes are coming off the device.
     *
     * The buffered position is reported as the full duration in that case: the bytes are all already there,
     * and a creeping buffer bar drawn over a local file is a lie.
     */
    public var isPlayingFromLocalFile: Boolean = false
        private set

    /** The last error this source failed with, for the session's own diagnostics. */
    public var lastError: NeedlerError? = null
        private set

    override fun open(dataSpec: DataSpec): Long {
        readToEnd = false
        started = false
        lastError = null
        val key: TrackKey = TrackUri.toTrackKey(dataSpec.uri.toString())
            ?: throw fail(NeedlerError.Unexpected("not a Needler track URI: " + dataSpec.uri.scheme))

        transferInitializing(dataSpec)

        var resolved: PlayableSource = runBlocking { resolveSource(key) }
        var plan: SourcePlan = SourcePlanner.plan(resolved)

        if (plan is SourcePlan.LocalFile) {
            val length: Long? = openLocalFile(plan, dataSpec)
            if (length != null) {
                isPlayingFromLocalFile = true
                started = true
                transferStarted(dataSpec)
                return length
            }
            // The row named a file that will not open: evicted between the resolve and the read, or a crash
            // landed between the two halves of an eviction. Drop the row and stream instead, rather than
            // failing a track whose bytes are perfectly available from the server.
            runBlocking { pinRepository.evictCachedAudio(key, CacheEvictionReason.RANGE_MISMATCH) }
            resolved = runBlocking { resolveSource(key) }
            plan = SourcePlanner.plan(resolved)
        }

        return when (val current: SourcePlan = plan) {
            is SourcePlan.NotPlayable -> throw fail(current.error)
            is SourcePlan.LocalFile -> throw fail(NeedlerError.NotFound("cached audio file"))
            is SourcePlan.HttpStream -> {
                isPlayingFromLocalFile = false
                val length: Long = openHttpStream(current, dataSpec)
                started = true
                transferStarted(dataSpec)
                length
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source: DataSource = delegate ?: throw fail(NeedlerError.Unexpected("read before open"))
        val read: Int = source.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) {
            readToEnd = true
            return read
        }
        if (read > 0) {
            sink?.write(buffer, offset, read)
            bytesTransferred(read)
        }
        return read
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        val currentSink: WriteThroughSink? = sink
        sink = null
        // The sink is finished before the delegate is closed, so a commit sees the final byte count.
        currentSink?.finish(readToEnd)
        val source: DataSource? = delegate
        delegate = null
        openedUri = null
        try {
            source?.close()
        } finally {
            if (started) {
                started = false
                transferEnded()
            }
        }
    }

    // ------------------------------------------------------------------ local file

    /** Opens the on-device copy, or returns null when the file will not open. */
    private fun openLocalFile(plan: SourcePlan.LocalFile, dataSpec: DataSpec): Long? {
        val file = File(plan.filePath)
        if (!file.isFile) return null
        val source = FileDataSource()
        val spec: DataSpec = dataSpec.buildUpon().setUri(Uri.fromFile(file)).build()
        return try {
            val length: Long = source.open(spec)
            delegate = source
            openedUri = source.uri
            runBlocking { pinRepository.recordPlayed(plan.key, Instant.fromEpochMilliseconds(nowMillis())) }
            length
        } catch (failure: IOException) {
            try {
                source.close()
            } catch (ignored: IOException) {
                // Closing a source that failed to open has nothing to report.
            }
            null
        }
    }

    // ---------------------------------------------------------------------- stream

    /**
     * Opens the HTTP stream, applying the recovery rules.
     *
     * The loop is short by design: one honoured `Retry-After`, then the transcode is abandoned in favour of
     * the original stream, and one retry without an assumed length for a `416`. Anything past that is
     * Media3's load-error policy's business, which is the layer that is allowed to back off for minutes.
     */
    private fun openHttpStream(plan: SourcePlan.HttpStream, dataSpec: DataSpec): Long {
        var format: StreamFormat = plan.format
        var spec: DataSpec = dataSpec
        var attempt = 1
        var lengthDiscarded = false

        while (true) {
            val url: String = audioUrls.streamUrl(plan.fetchHandle, format)
            val source: HttpDataSource = httpDataSourceFactory.createDataSource()
            // Identity encoding so no proxy re-introduces gzip: the server disables it on audio precisely
            // so ranges and seeking work, and a gzipped body makes both meaningless.
            source.setRequestProperty("Accept-Encoding", "identity")
            val request: DataSpec = spec.buildUpon().setUri(Uri.parse(url)).build()
            try {
                val length: Long = source.open(request)
                delegate = source
                openedUri = source.uri
                sink = openWriteThrough(plan, format, request)
                return length
            } catch (failure: Throwable) {
                closeQuietly(source)
                val invalid: HttpDataSource.InvalidResponseCodeException? =
                    failure as? HttpDataSource.InvalidResponseCodeException
                if (invalid == null) {
                    throw fail(PlaybackErrorMapper.fromTransport(failure), failure)
                }
                val httpFailure = HttpFailure(
                    statusCode = invalid.responseCode,
                    retryAfter = RetryAfterHeader.parse(headerOf(invalid, "Retry-After")),
                    bodyWasEnvelope = looksLikeEnvelope(invalid),
                )
                when (
                    val recovery: StreamRecovery = retryPolicy.recover(
                        failure = httpFailure,
                        attempt = attempt,
                        transcodeRequested = format != StreamFormat.Original,
                    )
                ) {
                    is StreamRecovery.WaitAndRetry -> {
                        sleep(recovery.delay.inWholeMilliseconds)
                        attempt++
                    }

                    StreamRecovery.FallBackToOriginal -> {
                        // REQUIREMENTS.md: a one-line notice, not a failure. The original stream is always
                        // available, and its bytes are the ones the store is allowed to keep.
                        format = StreamFormat.Original
                        attempt = 1
                    }

                    StreamRecovery.DiscardAssumedLength -> {
                        if (lengthDiscarded || spec.length == C.LENGTH_UNSET.toLong()) {
                            throw fail(NeedlerError.RangeNotSatisfiable, invalid)
                        }
                        spec = spec.buildUpon().setLength(C.LENGTH_UNSET.toLong()).build()
                        lengthDiscarded = true
                        attempt++
                    }

                    is StreamRecovery.Fail -> throw fail(recovery.error, invalid)
                }
            }
        }
    }

    /**
     * Opens a write-through handle, or returns null when these bytes must not be retained.
     *
     * Null is an ordinary answer and is respected silently. Four ways to get it, and the caller needs none of
     * them: the format is a transcode, the read does not start at byte zero, the store says there is no room,
     * or the track is already on the device.
     */
    private fun openWriteThrough(
        plan: SourcePlan.HttpStream,
        format: StreamFormat,
        request: DataSpec,
    ): WriteThroughSink? {
        // The format may have changed under us: a 429 on a transcode falls back to the original stream, and
        // original bytes are exactly the bytes the store is allowed to keep. So retention follows the format
        // actually fetched rather than the plan's, and the only way that widens the plan's answer is the
        // fall-back to Original - which is the one case where the resolver's "no" was about the transcode and
        // not about the track. The resolver sets cacheWhileStreaming from the format and nothing else
        // (ResolvePlayableSourceUseCase), so there is no other reason for a "no" to override here.
        val retainable: Boolean = plan.writeThrough || format == StreamFormat.Original
        if (!retainable) return null
        if (!SourcePlanner.mayWriteThrough(plan.copy(format = format, writeThrough = true), request.position)) {
            return null
        }
        val source = PlayableSource.Stream(
            key = plan.key,
            fetchHandle = plan.fetchHandle,
            format = format,
            cacheWhileStreaming = true,
        )
        val handle: AudioCacheWriteHandle = try {
            runBlocking { cacheWriter.openWrite(source) } ?: return null
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            // A store that will not open a write is not a reason to stop the music.
            return null
        }
        return WriteThroughSink(handle)
    }

    // ----------------------------------------------------------------------- plumbing

    private fun fail(error: NeedlerError, cause: Throwable? = null): NeedlerPlaybackException {
        lastError = error
        sink?.abandon()
        sink = null
        return NeedlerPlaybackException(error, cause)
    }

    private fun closeQuietly(source: DataSource) {
        try {
            source.close()
        } catch (ignored: IOException) {
            // Nothing to report from closing a source that never opened.
        }
    }

    private fun headerOf(failure: HttpDataSource.InvalidResponseCodeException, name: String): String? {
        val headers: Map<String, List<String>> = failure.headerFields
        val match: Map.Entry<String, List<String>>? = headers.entries.firstOrNull { entry ->
            entry.key != null && entry.key.equals(name, ignoreCase = true)
        }
        return match?.value?.firstOrNull()
    }

    /**
     * Whether the body looks like a Subsonic failure envelope rather than audio.
     *
     * The shim answers **HTTP 200 with a `status=failed` envelope** on the binary endpoints, so on those a
     * JSON or XML body is the only signal that anything went wrong. `InvalidResponseCodeException` only
     * exists for a non-200, so this is the belt to that braces: a `Content-Type` of JSON or XML on a failed
     * audio request says the same thing, and saying it here keeps a few kilobytes of error text out of the
     * audio store.
     */
    private fun looksLikeEnvelope(failure: HttpDataSource.InvalidResponseCodeException): Boolean {
        val contentType: String = headerOf(failure, "Content-Type")?.lowercase() ?: return false
        return contentType.contains("json") || contentType.contains("xml")
    }

    /** Creates these sources for the player. One instance per load, because each holds a delegate. */
    public class Factory(
        private val resolveSource: ResolvePlayableSourceUseCase,
        private val cacheWriter: AudioCacheWriter,
        private val pinRepository: PinRepository,
        private val audioUrls: AudioUrls,
        private val httpDataSourceFactory: HttpDataSource.Factory,
    ) : DataSource.Factory {

        override fun createDataSource(): DataSource = NeedlerAudioDataSource(
            resolveSource = resolveSource,
            cacheWriter = cacheWriter,
            pinRepository = pinRepository,
            audioUrls = audioUrls,
            httpDataSourceFactory = httpDataSourceFactory,
        )
    }
}

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
import app.needler.core.domain.cache.AudioRetentionEvent
import app.needler.core.domain.diagnostics.DiagnosticsLevel
import app.needler.core.domain.diagnostics.DiagnosticsSink
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
 * ## One play, one format - because Media3 re-opens at a byte offset
 *
 * Resolving afresh is right. Resolving afresh **and** honouring `DataSpec.position` on a later open was a
 * bug, and it needed no user action to fire.
 *
 * Media3 builds one of these per `ProgressiveMediaPeriod` and re-uses it for every load of that item.
 * `ProgressiveMediaPeriod.startLoading` opens the next load at
 * `seekMap.getSeekPoints(positionUs).first.position` - a **byte** offset into the stream the extractor
 * parsed - and its retry path resumes at the extractor's current input position, also in bytes. Neither
 * carries an `If-Range`: the etag is a local in `ExtractingLoadable.load`, null on each loadable's first
 * open, so a seek and a retry both arrive with a bare `Range` and nothing to validate it against.
 *
 * `ResolvePlayableSourceUseCase` reads `ConnectivityState`, so the format flips between two opens the
 * moment the device moves between Wi-Fi and mobile data - which is what a phone does on the way out of a
 * house, and the dropped connection is itself what triggers the retry. The offset and the stream then come
 * from different formats. Byte 4,000,000 of a FLAC and byte 4,000,000 of an MP3 320 are different music,
 * and the containers do not even agree on where the audio starts, so the result is a seek that lands
 * somewhere else, noise, or a decoder error - and nothing says anything, because the server answers `206`
 * for a range it can perfectly well satisfy.
 *
 * So [pinnedFormat] records the format that was actually served, and an open at a non-zero position that
 * resolves to a different one is served the **recorded** format instead. This is [StreamRecovery]'s own
 * rule about the `416` path - "serving the extractor bytes from a different offset than it asked for is
 * how a track plays as noise" - applied to the other half of the same request: the offset is meaningless
 * without the stream it was measured in.
 *
 * The cost is that a connectivity change takes effect at the next play rather than mid-track. That is the
 * better of the two behaviours anyway, and the three promises above are untouched, because every one of
 * them is about resolving per *play* and not per *open*: a read from byte zero always adopts whatever the
 * resolver now says.
 *
 * Retention was never at risk here and still is not. `SourcePlanner.mayWriteThrough` already refuses a
 * write for any read that does not start at byte zero, so no partly-written original was ever assembled
 * from two formats - REQUIREMENTS.md "Why transcoded bytes are never cached" is satisfied by that guard
 * and this change does not touch it.
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
    /**
     * Where a decision not to retain a stream is written down.
     *
     * Three rounds of instrumentation live here and the third one is the lesson. `AudioCacheStoreWriter`
     * reports every refusal it makes; the two guards below return before `openWrite` is ever called, so
     * a stream the store never hears about produced no line anywhere either. Both are now covered, and
     * a device on that build still produced nothing: `format=raw`, a full `Content-Length`, a track
     * played to the end, and ten log points silent. What none of them covered was the ending of a write
     * that *was* opened - the reader stopping short of the declared length, and [WriteThroughSink]
     * abandoning the handle over it without a word. A log that covers every refusal still says nothing
     * about the writes that were granted, so the sink is given this sink too and the store is left to
     * report the outcome of the commit.
     */
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.None,
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
     * The format whose bytes this source has already served, or null before the first open succeeded.
     *
     * Written once per successful open, from what was *fetched* rather than what was planned - the `429`
     * fall-back in [openHttpStream] changes the format after the plan is made, and the recorded value has
     * to be the stream the extractor actually measured its offsets in.
     *
     * Read by [heldFormat], which is the whole of the fix; see the class note "One play, one format".
     * The lifetime is one instance, which Media3 gives one `ProgressiveMediaPeriod` - so one play of one
     * item - and no synchronisation is needed because `open` and `read` are called on one loading thread.
     */
    private var pinnedFormat: StreamFormat? = null

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
                // On-device bytes are always the original file - REQUIREMENTS.md "Why transcoded bytes
                // are never cached" makes a download "that track's permanent offline version", which is
                // why there is no rung picker for pulling. So an offset taken against the local copy is
                // comparable to an Original stream and to nothing else, and recording that is what stops
                // a file evicted mid-play from being continued as a metered transcode.
                pinnedFormat = StreamFormat.Original
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
                val length: Long = openHttpStream(heldFormat(current, dataSpec), dataSpec)
                started = true
                transferStarted(dataSpec)
                length
            }
        }
    }

    /**
     * The plan to actually fetch: the resolver's, unless its format would contradict an offset already
     * measured in another one.
     *
     * Three cases, and the first two are the ordinary ones:
     *
     *  * **Nothing served yet, or the same format.** The resolver wins outright.
     *  * **A read from byte zero.** There is no offset to contradict, so a new format takes over and a
     *    rung or connectivity change takes effect from here. This is what keeps "resolution happens on
     *    open" true rather than turning it back into resolution on enqueue.
     *  * **A different format at a non-zero position.** The recorded format is used instead, and the
     *    disagreement is written down. The alternative - honour the resolver and serve the new stream at
     *    the old stream's offset - is the defect: the server answers `206` for a range it can satisfy,
     *    the extractor is handed a different piece of music than it asked for, and nothing anywhere
     *    reports a fault.
     *
     * [SourcePlan.HttpStream.writeThrough] is left exactly as the resolver set it. It has no say here,
     * because `SourcePlanner.mayWriteThrough` refuses every read that does not start at byte zero and
     * this branch only ever fires when the position is non-zero.
     */
    private fun heldFormat(plan: SourcePlan.HttpStream, dataSpec: DataSpec): SourcePlan.HttpStream {
        val held: StreamFormat = pinnedFormat ?: return plan
        if (held == plan.format || dataSpec.position == 0L) return plan
        diagnostics.record(
            DiagnosticsLevel.Warn,
            "held format for " + plan.key.canonicalString + " - the resolver now says " +
                AudioRetentionEvent.describe(plan.format) + " but byte " + dataSpec.position +
                " was measured in " + AudioRetentionEvent.describe(held) +
                ", so that stream is continued instead",
        )
        return plan.copy(format = held)
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

    /**
     * Ends the transfer, and the retention write with it.
     *
     * The order is the whole of it. The tail is taken first, while the response is still open; the sink
     * is finished second, so the commit sees the final byte count; the delegate is closed third. Closing
     * the delegate first would make the tail unreachable, and finishing the sink first would hand the
     * store a count the reader was about to add to.
     */
    override fun close() {
        val currentSink: WriteThroughSink? = sink
        sink = null
        val source: DataSource? = delegate
        if (currentSink != null && source != null && !readToEnd) {
            // The reader stopped before the end of the body. Usually that is Media3's extractor
            // declining to read a trailing tag it has no use for, which is a handful of bytes on a
            // track that played in full - and the only honest way to hold the write to the length
            // the response declared is to take them. WriteThroughSink.fillTail decides whether the
            // shortfall is small enough to be worth a read and writes down what it did.
            currentSink.fillTail { buffer, offset, length ->
                val read: Int = source.read(buffer, offset, length)
                // Real bytes off the network, reported to the bandwidth meter like every other read.
                if (read > 0) bytesTransferred(read)
                read
            }
        }
        currentSink?.finish(readToEnd)
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
     *
     * The one rule added to that table is positional, and it is the same rule as [heldFormat]'s: the
     * fall-back to the original stream is only available to a read that starts at byte zero, because a
     * non-zero offset was measured in the transcode and does not name the same music in the original.
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
                // What was fetched, not what was planned: the fall-back below can have moved it, and the
                // extractor's offsets from here on are measured in whatever actually arrived.
                pinnedFormat = format
                sink = openWriteThrough(plan, format, request, declaredWholeFileLength(length, request))
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
                        if (dataSpec.position != 0L) {
                            // The same hazard as a mid-track connectivity change, reached from inside this
                            // loop: byte `position` was measured in the transcode, and the original's bytes
                            // are not at the same offsets. There is no correct response to this request, so
                            // it fails - loudly, on PlaybackState.error for every surface - rather than
                            // serving the extractor a different piece of music than it asked for. Pressing
                            // play again builds a new period, which resolves the format afresh from zero.
                            throw fail(NeedlerError.StreamSlotsExhausted, invalid)
                        }
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
     * The length the response declared for the **whole file**, or null when it declared none this read can
     * honestly describe.
     *
     * `DataSource.open` answers how many bytes are readable from the position it was opened at, so its answer
     * is only the whole file's length when the read covers the whole file. Two conditions make that true and
     * both are checked rather than assumed: the read starts at byte zero, and it asked for no range of its own
     * - a length in the [DataSpec] means the answer describes that slice. Media3's progressive loader sets
     * neither, so the ordinary case passes; the `416` recovery in [openHttpStream] is the one place that
     * touches the spec's length, which is exactly the case this must not mistake for a whole file.
     *
     * `C.LENGTH_UNSET` is -1, so the non-positive test covers "the server sent no `Content-Length`" as well as
     * a nonsense reading. Null is not a failure: [AudioCacheWriter.openWrite] then falls back to the size the
     * mirror recorded, which is what this path had before the response arrived.
     */
    private fun declaredWholeFileLength(openedLength: Long, request: DataSpec): Long? {
        if (openedLength <= 0L) return null
        if (request.position != 0L) return null
        if (request.length != C.LENGTH_UNSET.toLong()) return null
        return openedLength
    }

    /**
     * Opens a write-through handle, or returns null when these bytes must not be retained.
     *
     * Null is an ordinary answer and is respected silently. Four ways to get it, and the caller needs none of
     * them: the format is a transcode, the read does not start at byte zero, the store says there is no room,
     * or the track is already on the device.
     *
     * [declaredLengthBytes] is the response's own view of how long the file is, and it is handed to the store
     * because the store's alternative is the metadata mirror - a number from the last sync, which a
     * server-side quality upgrade makes wrong. The store checks the finished write against it, so a stale
     * number there is a complete body recorded as truncated and thrown away, on every play of that track. See
     * [AudioCacheWriter.openWrite].
     */
    private fun openWriteThrough(
        plan: SourcePlan.HttpStream,
        format: StreamFormat,
        request: DataSpec,
        declaredLengthBytes: Long?,
    ): WriteThroughSink? {
        // The format may have changed under us: a 429 on a transcode falls back to the original stream, and
        // original bytes are exactly the bytes the store is allowed to keep. So retention follows the format
        // actually fetched rather than the plan's, and the only way that widens the plan's answer is the
        // fall-back to Original - which is the one case where the resolver's "no" was about the transcode and
        // not about the track. The resolver sets cacheWhileStreaming from the format and nothing else
        // (ResolvePlayableSourceUseCase), so there is no other reason for a "no" to override here.
        val retainable: Boolean = plan.writeThrough || format == StreamFormat.Original
        if (!retainable) {
            diagnostics.record(
                DiagnosticsLevel.Debug,
                "not cached " + plan.key.canonicalString + " - streaming " +
                    AudioRetentionEvent.describe(format) +
                    ", only original bytes are retained",
            )
            return null
        }
        if (!SourcePlanner.mayWriteThrough(plan.copy(format = format, writeThrough = true), request.position)) {
            // Almost always a read that does not start at byte zero. Keeping the tail of a track as
            // though it were the track is the same silent failure as keeping a truncated one, so the
            // refusal is right - but it has to be visible, because it is indistinguishable from a
            // write that succeeded when neither says anything.
            diagnostics.record(
                DiagnosticsLevel.Debug,
                "not cached " + plan.key.canonicalString + " - read starts at byte " +
                    request.position + ", only a read from zero can produce a whole file",
            )
            return null
        }
        val source = PlayableSource.Stream(
            key = plan.key,
            fetchHandle = plan.fetchHandle,
            format = format,
            cacheWhileStreaming = true,
        )
        val handle: AudioCacheWriteHandle = try {
            runBlocking { cacheWriter.openWrite(source, declaredLengthBytes) } ?: return null
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            // A store that will not open a write is not a reason to stop the music. It is a reason to
            // say so: the store logs its own refusals, but it cannot log the ones it never heard.
            diagnostics.record(
                DiagnosticsLevel.Warn,
                "not cached " + plan.key.canonicalString + " - the audio store threw opening a write: " +
                    (error.message ?: error::class.simpleName.orEmpty()),
            )
            return null
        }
        return WriteThroughSink(handle, diagnostics)
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
        private val diagnostics: DiagnosticsSink = DiagnosticsSink.None,
    ) : DataSource.Factory {

        override fun createDataSource(): DataSource = NeedlerAudioDataSource(
            resolveSource = resolveSource,
            cacheWriter = cacheWriter,
            pinRepository = pinRepository,
            audioUrls = audioUrls,
            httpDataSourceFactory = httpDataSourceFactory,
            diagnostics = diagnostics,
        )
    }
}

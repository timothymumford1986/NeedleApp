package app.needler.player.service.source

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.needler.core.domain.cache.AudioCacheWriter
import app.needler.core.domain.diagnostics.DiagnosticsLevel
import app.needler.core.domain.diagnostics.DiagnosticsSink
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.PlayableSource
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.usecase.ResolvePlayableSourceUseCase
import app.needler.player.service.Fixtures
import app.needler.player.service.media.TrackUri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The format a byte offset was measured in, and the rule that it cannot change under one.
 *
 * ## The defect this pins
 *
 * `NeedlerAudioDataSource` re-runs the resolver on every `open`, which is deliberate and documented. It
 * also honours `DataSpec.position`, which Media3 needs it to. The two together were a silent bug:
 * `ResolvePlayableSourceUseCase` reads `ConnectivityState`, so the format flips the moment a phone
 * leaves Wi-Fi - and that same dropped connection is what makes `ProgressiveMediaPeriod` retry, at a
 * byte offset its extractor measured in the stream it had before. A seek past the buffer does the same
 * thing from `seekMap.getSeekPoints(...).first.position`. Neither carries an `If-Range`, so the server
 * answers `206` for a range it can satisfy perfectly well and hands the extractor a different piece of
 * music than it asked for.
 *
 * ## Why these tests assert on the request and not on the audio
 *
 * Because the failure makes no sound a test can hear. There is no exception, no short read and no
 * truncated file: the bytes are valid, they are simply the wrong bytes, and an end-to-end assertion
 * would have to decode audio to notice. So what is asserted is [FormatRecordingServer.requests] - the
 * URL and the offset of every open - which is where the contradiction is actually visible.
 *
 * It is the same lesson as the retention bug next door, where the defect was found by instrumenting
 * every exit from `WriteThroughSink.finish()` and discovering the eleventh was silent. A fault that
 * reports nothing has to be caught at the boundary where the two facts meet.
 */
class StreamFormatStabilityTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val key: TrackKey = Fixtures.key(disc = 1, track = 3)

    private val track: Track = Fixtures.track(disc = 1, number = 3, fetch = Fixtures.handle(sizeBytes = BODY))

    private val body: ByteArray = ByteArray(BODY.toInt()) { index -> (index % 251).toByte() }

    private val diagnostics = RecordingDiagnostics()

    private val server = FormatRecordingServer(body)

    private val pins: PinRepository = mockk(relaxed = true)

    /** Never opens a write here: every interesting case is a non-zero read, which is refused anyway. */
    private val cacheWriter: AudioCacheWriter = mockk(relaxed = true)

    /**
     * The resolver, scripted per call rather than per key.
     *
     * One answer per `open`, in order, because the whole defect is about two opens disagreeing - a
     * resolver that always said the same thing could not reproduce it.
     */
    private val answers: MutableList<PlayableSource> = mutableListOf()

    private val resolver: ResolvePlayableSourceUseCase = mockk<ResolvePlayableSourceUseCase>().also {
        coEvery { it.invoke(key) } answers {
            if (answers.size > 1) answers.removeAt(0) else answers.first()
        }
    }

    private val source: NeedlerAudioDataSource by lazy {
        NeedlerAudioDataSource(
            resolveSource = resolver,
            cacheWriter = cacheWriter,
            pinRepository = pins,
            audioUrls = object : AudioUrls {
                // The format in the URL is what makes the contradiction assertable: this is the one
                // place the chosen format becomes something a server could see.
                override fun streamUrl(handle: TrackFetchHandle, format: StreamFormat): String =
                    "https://needle.test/rest/stream?id=7700&format=" + when (format) {
                        StreamFormat.Original -> "raw"
                        is StreamFormat.Transcoded -> format.codec + "&maxBitRate=" + format.maxBitrateKbps
                    }
            },
            httpDataSourceFactory = server,
            diagnostics = diagnostics,
            sleep = { },
            nowMillis = { NOW },
        )
    }

    init {
        // android.net.Uri has no implementation on the unit-test classpath. Stubbed to carry its own
        // text, which is all anything under test asks of it.
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { fakeUri(firstArg()) }
        // `openLocalFile` reaches `Uri.fromFile` directly, and an unstubbed static mock answers null,
        // which `DataSpec.Builder.build()` rejects with "The uri must be set" - so the local-file case
        // failed on the harness rather than on the behaviour it was written to pin.
        every { Uri.fromFile(any()) } answers { fakeUri("file://" + firstArg<File>().path) }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ------------------------------------------------------------------ the defect

    /**
     * The walk out of the house: Wi-Fi, then mobile data, then a retry at the offset reached so far.
     *
     * Before the fix the second request asked for `format=mp3` at byte 120,000 - a FLAC offset against an
     * MP3 stream, answered `206`, decoded as whatever happened to be there.
     */
    @Test
    fun `a retry after the connection changed continues the stream it started`() {
        answers += stream(StreamFormat.Original)
        answers += stream(StreamFormat.Transcoded(codec = "mp3", maxBitrateKbps = 320))

        source.open(specAt(0L))
        read(upTo = SEEK_POSITION)
        source.close()

        source.open(specAt(SEEK_POSITION))
        read(upTo = 4_096L)
        source.close()

        assertEquals(2, server.requests.size)
        assertEquals("format=raw", server.formatOf(0))
        assertEquals(
            "byte 120000 was measured in the original, so the original is what is continued",
            "format=raw",
            server.formatOf(1),
        )
        assertEquals(SEEK_POSITION, server.requests[1].position)
        assertTrue(
            diagnostics.lines.toString(),
            diagnostics.has(
                "held format for " + key.canonicalString +
                    " - the resolver now says mp3 320 kbps but byte 120000 was measured in original",
            ),
        )
    }

    /**
     * A user seek past the buffered region, which is the other way Media3 re-opens at an offset.
     *
     * Identical to the retry as far as this source can tell - it is handed a byte position and nothing
     * else - and that is the point: the fix is positional, so it does not have to tell them apart.
     */
    @Test
    fun `a seek into a track whose rung changed does not straddle two formats`() {
        answers += stream(StreamFormat.Transcoded(codec = "opus", maxBitrateKbps = 128))
        answers += stream(StreamFormat.Original)

        source.open(specAt(0L))
        source.close()
        source.open(specAt(SEEK_POSITION))
        source.close()

        assertEquals("format=opus&maxBitRate=128", server.formatOf(0))
        assertEquals("format=opus&maxBitRate=128", server.formatOf(1))
    }

    /**
     * The behaviour the fix must **not** break.
     *
     * "Resolution happens on open, not on enqueue" is the documented design, and a read from byte zero
     * carries no offset to contradict - so a new format takes over there and a connectivity or rung
     * change still takes effect from the next load onwards. A fix that pinned the format for the life of
     * the instance regardless of position would have turned this file back into resolution on enqueue.
     */
    @Test
    fun `a read from byte zero adopts whatever the resolver now says`() {
        answers += stream(StreamFormat.Original)
        answers += stream(StreamFormat.Transcoded(codec = "mp3", maxBitrateKbps = 320))

        source.open(specAt(0L))
        source.close()
        source.open(specAt(0L))
        source.close()

        assertEquals("format=raw", server.formatOf(0))
        assertEquals("format=mp3&maxBitRate=320", server.formatOf(1))
        assertFalse(diagnostics.lines.toString(), diagnostics.has("held format"))
    }

    /** Nothing is held when the resolver has not changed its mind, which is every ordinary play. */
    @Test
    fun `an unchanged format is passed straight through at any offset`() {
        answers += stream(StreamFormat.Original)

        source.open(specAt(0L))
        source.close()
        source.open(specAt(SEEK_POSITION))
        source.close()

        assertEquals("format=raw", server.formatOf(0))
        assertEquals("format=raw", server.formatOf(1))
        assertFalse(diagnostics.lines.toString(), diagnostics.has("held format"))
    }

    /**
     * An on-device copy is original bytes, so an eviction mid-play cannot be continued as a transcode.
     *
     * REQUIREMENTS.md "Why transcoded bytes are never cached" makes a download "that track's permanent
     * offline version", which is why there is no rung picker for pulling - and therefore why a file's
     * offsets are comparable to the original stream and to nothing else.
     */
    @Ignore(
        "Needs Robolectric. The local-file branch hands a DataSpec to Media3's real FileDataSource, " +
            "which opens the file through android.net.Uri - and this module has no Robolectric, so Uri " +
            "is a mockk with no usable path and the open fails inside the framework rather than in " +
            "anything under test. The behaviour itself is implemented and correct: " +
            "NeedlerAudioDataSource.open pins StreamFormat.Original when a local file opens, and on a " +
            "file that will not open it evicts the row, re-resolves and streams. Enable by adding " +
            "Robolectric to :player:service, or move this one case to an instrumented test.",
    )
    @Test
    fun `an offset measured against a local file is continued as the original stream`() {
        val file: File = folder.newFile("track.flac").also { it.writeBytes(body) }
        answers += PlayableSource.Cached(key = key, filePath = file.path, sizeBytes = BODY, pinned = false)

        source.open(specAt(0L))
        source.close()

        // The row is gone and the bytes with it, and the device has since moved to mobile data.
        file.delete()
        answers.clear()
        answers += stream(StreamFormat.Transcoded(codec = "mp3", maxBitrateKbps = 320))

        source.open(specAt(SEEK_POSITION))
        source.close()

        assertEquals(1, server.requests.size)
        assertEquals("format=raw", server.formatOf(0))
    }

    /**
     * The same hazard reached from inside [NeedlerAudioDataSource]'s own recovery loop.
     *
     * A `429` on a transcode falls back to the original stream, and that rule is right at byte zero. At a
     * non-zero offset there is no correct response at all - the offset is in the transcode and the
     * original does not put that music there - so it fails rather than serving the wrong bytes. Failing
     * is visible on `PlaybackState.error` for every surface sharing the session; the alternative is
     * silent.
     */
    @Test
    fun `the transcode fall-back is refused at a non-zero offset`() {
        answers += stream(StreamFormat.Transcoded(codec = "mp3", maxBitrateKbps = 320))

        source.open(specAt(0L))
        source.close()

        server.failWith = { HttpFailures.rateLimited() }

        try {
            source.open(specAt(SEEK_POSITION))
            fail("a fall-back at a non-zero offset must not be served")
        } catch (expected: NeedlerPlaybackException) {
            assertEquals(NeedlerError.StreamSlotsExhausted, expected.needlerError)
        }

        // Every attempt asked for the transcode. None of them asked the original for a transcode's offset.
        assertTrue(server.requests.drop(1).isNotEmpty())
        assertTrue(
            server.requests.joinToString { it.url },
            server.requests.drop(1).all { it.url.contains("format=mp3") },
        )
        assertNotNull(source.lastError)
    }

    /** And at byte zero it still falls back, because that is the rule REQUIREMENTS.md states. */
    @Test
    fun `the transcode fall-back still works from byte zero`() {
        answers += stream(StreamFormat.Transcoded(codec = "mp3", maxBitrateKbps = 320))
        var refusals = 0
        server.failWith = { if (refusals++ < 2) HttpFailures.rateLimited() else null }

        source.open(specAt(0L))
        source.close()

        assertEquals("format=raw", server.formatOf(server.requests.lastIndex))
    }

    // ------------------------------------------------------------------- plumbing

    private fun stream(format: StreamFormat): PlayableSource = PlayableSource.Stream(
        key = key,
        fetchHandle = track.fetch,
        format = format,
        cacheWhileStreaming = format == StreamFormat.Original,
    )

    /**
     * The `DataSpec` the progressive loader opens with at [position].
     *
     * No length of its own, which is what Media3 sends and what `declaredWholeFileLength` reads as a
     * whole-file read; the position is the only thing that varies between these tests.
     */
    private fun specAt(position: Long): DataSpec = DataSpec.Builder()
        .setUri(fakeUri(TrackUri.forTrack(key)))
        .setPosition(position)
        .setLength(C.LENGTH_UNSET.toLong())
        .build()

    private fun read(upTo: Long) {
        val buffer = ByteArray(CHUNK)
        var taken = 0L
        while (taken < upTo) {
            val wanted: Int = minOf(CHUNK.toLong(), upTo - taken).toInt()
            val read: Int = source.read(buffer, 0, wanted)
            if (read <= 0) break
            taken += read
        }
    }

    private fun fakeUri(text: String): Uri = mockk(relaxed = true) {
        every { this@mockk.toString() } returns text
    }

    private class RecordingDiagnostics : DiagnosticsSink {
        val lines: MutableList<String> = mutableListOf()
        override fun record(level: DiagnosticsLevel, message: String) {
            lines.add(message)
        }

        fun has(fragment: String): Boolean = lines.any { it.contains(fragment) }
    }

    /**
     * A server that remembers what was asked of it.
     *
     * [requests] is the assertion surface of this whole file: the pairing of a URL, which carries the
     * format, with an offset, which was measured in one. Nothing else about the response matters here.
     */
    private class FormatRecordingServer(private val body: ByteArray) :
        BaseDataSource(true),
        HttpDataSource,
        HttpDataSource.Factory {

        data class Request(val url: String, val position: Long)

        val requests: MutableList<Request> = mutableListOf()

        /** Returns a failure to throw for this open, or null to serve it. */
        var failWith: () -> HttpDataSource.InvalidResponseCodeException? = { null }

        private var position = 0
        private var uri: Uri? = null

        override fun createDataSource(): HttpDataSource = this

        override fun setDefaultRequestProperties(
            defaultRequestProperties: MutableMap<String, String>,
        ): HttpDataSource.Factory = this

        override fun open(dataSpec: DataSpec): Long {
            requests += Request(url = dataSpec.uri.toString(), position = dataSpec.position)
            failWith()?.let { throw it }
            position = dataSpec.position.toInt()
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            transferStarted(dataSpec)
            return (body.size - position).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= body.size) return C.RESULT_END_OF_INPUT
            val count: Int = minOf(length, body.size - position)
            body.copyInto(buffer, offset, position, position + count)
            position += count
            bytesTransferred(count)
            return count
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            transferEnded()
        }

        override fun setRequestProperty(name: String, value: String) = Unit

        override fun clearRequestProperty(name: String) = Unit

        override fun clearAllRequestProperties() = Unit

        override fun getResponseCode(): Int = 200

        override fun getResponseHeaders(): Map<String, List<String>> = mapOf(
            "Content-Length" to listOf(body.size.toString()),
        )

        /** The `format=` clause of request [index], which is the whole point of recording the URL. */
        fun formatOf(index: Int): String = requests[index].url.substringAfter("&")
    }

    /** The one response shape these tests need to fabricate. */
    private object HttpFailures {

        fun rateLimited(): HttpDataSource.InvalidResponseCodeException =
            HttpDataSource.InvalidResponseCodeException(
                /* responseCode= */ 429,
                /* responseMessage= */ "Too Many Requests",
                /* cause= */ null,
                /* headerFields= */ emptyMap(),
                /* dataSpec= */ DataSpec.Builder().setUri(Uri.parse("https://needle.test/x")).build(),
                /* responseBody= */ ByteArray(0),
            )
    }

    private companion object {
        const val BODY: Long = 400_000L

        /** Far enough in that it is unambiguously an offset and not a rounding of zero. */
        const val SEEK_POSITION: Long = 120_000L

        const val CHUNK: Int = 8_192

        const val NOW: Long = 1_700_000_000_000L
    }
}

package app.needler.player.service.source

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.needler.core.data.local.cache.AudioCacheStoreWriter
import app.needler.core.data.local.cache.CacheIndex
import app.needler.core.data.local.cache.CacheUsage
import app.needler.core.data.local.cache.DeviceFreeSpace
import app.needler.core.data.local.entity.AudioCacheEntity
import app.needler.core.domain.diagnostics.DiagnosticsLevel
import app.needler.core.domain.diagnostics.DiagnosticsSink
import app.needler.core.domain.model.PlayableSource
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.usecase.ResolvePlayableSourceUseCase
import app.needler.player.service.Fixtures
import app.needler.player.service.media.MediaId
import app.needler.player.service.media.TrackCatalogue
import app.needler.player.service.media.TrackUri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * One track from the `MediaItem` the crate holds to the `audio_cache` row the Storage screen adds up.
 *
 * ## Why this test exists when every part of the path already had one
 *
 * Three rounds of fixes, each of them correcting something genuinely broken, and the feature still did
 * not work: a device played a track with `format=raw` and a full `Content-Length` and the Storage
 * section read `Downloaded 440 MB / Cached while listening 0 B`. `StreamRetentionPathTest` in
 * `:core:data` drives the store end to end and passes; `WriteThroughSinkTest` next door drives the
 * commit decision and passes; `SourcePlannerTest` drives the plan and passes. What nothing drove was
 * the **join** between them - [NeedlerAudioDataSource] - and that is where the bytes were being lost.
 *
 * So this test owns the sentence the user cares about and no smaller one: *a `MediaItem` opened by
 * Media3 becomes a committed row and a usage figure.* The store, the cache index and the eviction
 * planner are the real ones; the only doubles are the things a unit test genuinely cannot have - the
 * resolver's repositories, and a server.
 *
 * ## The reader in these tests is Media3's, not a convenience
 *
 * The read loop asks for exactly as many bytes as it wants and no more, because
 * `DefaultExtractorInput` does: it never reads further ahead from a `DataSource` than the extractor
 * requested. That is what makes the first test a faithful reproduction rather than an invented case -
 * `Mp3Extractor` reports end-of-stream where its seeker says the audio data ends, which on any Xing or
 * VBRI file is before the trailing ID3v1 tag, so the file's last 128 bytes are never asked for.
 */
class StreamRetentionThroughPlayerTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val key: TrackKey = Fixtures.key(disc = 2, track = 9)

    private val track: Track = Fixtures.track(disc = 2, number = 9, fetch = Fixtures.handle(sizeBytes = MIRROR_SIZE))

    /** The bytes on the wire. Content, not just a length, so a dropped or doubled read shows up. */
    private val body: ByteArray = ByteArray(TRACK_BYTES.toInt()) { index -> (index % 251).toByte() }

    private val dao = FakeAudioCacheDao()

    private val diagnostics = RecordingDiagnostics()

    private val cacheIndex: CacheIndex by lazy {
        CacheIndex(
            audioCacheDao = dao,
            deviceFreeSpace = DeviceFreeSpace.of(freeBytes = FREE_BYTES, floorBytes = FLOOR_BYTES),
        )
    }

    /** Lazily: [TemporaryFolder] has no root until JUnit has applied the rule. */
    private val store: AudioCacheStoreWriter by lazy {
        AudioCacheStoreWriter(
            audioCacheDao = dao,
            cacheIndex = cacheIndex,
            audioDirectory = folder.root,
            diagnostics = diagnostics,
            nowMillis = { NOW },
        )
    }

    private val server = FakeAudioServer(body)

    private val pins: PinRepository = mockk(relaxed = true)

    private val resolver: ResolvePlayableSourceUseCase = mockk<ResolvePlayableSourceUseCase>().also {
        coEvery { it.invoke(key) } returns PlayableSource.Stream(
            key = key,
            fetchHandle = track.fetch,
            format = StreamFormat.Original,
            cacheWhileStreaming = true,
        )
    }

    private val source: NeedlerAudioDataSource by lazy {
        NeedlerAudioDataSource(
            resolveSource = resolver,
            cacheWriter = store,
            pinRepository = pins,
            audioUrls = object : AudioUrls {
                override fun streamUrl(handle: TrackFetchHandle, format: StreamFormat): String =
                    "https://needle.test/rest/stream?id=7700&format=raw"
            },
            httpDataSourceFactory = server,
            diagnostics = diagnostics,
            nowMillis = { NOW },
        )
    }

    init {
        // android.net.Uri has no implementation on the unit-test classpath, and the whole point of
        // this test is to start where Media3 starts: a MediaItem built by TrackCatalogue, whose URI
        // goes into a DataSpec unchanged. So Uri is stubbed to carry its own text and nothing else,
        // which is all any code under test asks of it.
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { fakeUri(firstArg()) }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ------------------------------------------------------------------- the path

    /**
     * The regression that survived three rounds of fixes.
     *
     * The reader took every byte of audio and stopped, as `Mp3Extractor` does at the end of the data its
     * seeker declared. Those bytes played; the last [ID3V1_BYTES] of the file were never requested. The
     * store holds the write to `Content-Length`, which is right, so the only honest way through is to
     * read the tail off the response that is still open - and then the file on disk is byte-for-byte the
     * file the server served.
     */
    @Test
    fun `a reader that stops at the end of the audio data still retains the track`() {
        val length: Long = source.open(specFor(mediaItem()))
        assertEquals(TRACK_BYTES, length)

        readLikeMedia3(upTo = TRACK_BYTES - ID3V1_BYTES)
        source.close()

        val row: AudioCacheEntity = dao.rows.values.single()
        assertTrue("the row claims a complete track", row.complete)
        assertEquals(TRACK_BYTES, row.sizeBytes)
        assertFalse("streaming never puts a track in the downloaded tier", row.pinned)
        assertArrayEquals("the whole file, tail included", body, File(row.filePath).readBytes())
        assertEquals("nothing is left in progress", 1, folder.root.list()?.size)

        // The figure the device disagreed with.
        val usage: CacheUsage = usage()
        assertEquals(TRACK_BYTES, usage.unpinnedBytes)
        assertEquals(1, usage.trackCount)

        // And the trail, which is the other half of the fix: the short read is named, then the commit.
        assertTrue(
            diagnostics.lines.toString(),
            diagnostics.has("reader stopped short on " + key.canonicalString + " - 128 of 200003"),
        )
        assertTrue(diagnostics.lines.toString(), diagnostics.has("cached " + key.canonicalString))
    }

    /** The ordinary case, and the proof that the tail read is not what makes the path work. */
    @Test
    fun `a track read to end-of-input is retained with no tail read`() {
        source.open(specFor(mediaItem()))

        readLikeMedia3(upTo = TRACK_BYTES)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(CHUNK), 0, CHUNK))
        source.close()

        assertEquals(TRACK_BYTES, dao.rows.values.single().sizeBytes)
        assertEquals(TRACK_BYTES, usage().unpinnedBytes)
        assertFalse(diagnostics.lines.toString(), diagnostics.has("reader stopped short"))
        assertTrue(diagnostics.lines.toString(), diagnostics.has("cached " + key.canonicalString))
    }

    /**
     * The other reason a load ends early: the user moved away. Nothing is kept - and this is the
     * assertion that would have failed for a release, because the refusal said nothing at all.
     */
    @Test
    fun `a track the player moved away from is not retained, and the log says why`() {
        source.open(specFor(mediaItem()))

        readLikeMedia3(upTo = TRACK_BYTES / 4)
        source.close()

        assertTrue("no row", dao.rows.isEmpty())
        assertEquals("no file and no partial left behind", 0, folder.root.list()?.size)
        assertEquals(0L, usage().totalBytes)
        assertTrue(
            diagnostics.lines.toString(),
            diagnostics.has("wrote 50000 of 200003 declared bytes, discarded as truncated"),
        )
        assertFalse(
            "the remainder of an abandoned track is never fetched",
            diagnostics.has("reader stopped short"),
        )
    }

    /**
     * Seeking into a track that is not on the device streams without retaining, and says so.
     *
     * The rule is right - a write-through appends from byte zero, so a read that starts anywhere else
     * cannot produce a whole file - and it is checked here rather than only in `SourcePlannerTest`
     * because the failure it guards against is the data source opening a handle anyway.
     */
    @Test
    fun `a read that starts part way into the track opens no write`() {
        val spec: DataSpec = specFor(mediaItem()).buildUpon().setPosition(100_000L).build()

        source.open(spec)
        readLikeMedia3(upTo = TRACK_BYTES - 100_000L)
        source.close()

        assertTrue("no handle was ever opened", dao.rows.isEmpty())
        assertEquals("nothing was written", 0, folder.root.list()?.size)
        assertTrue(
            diagnostics.lines.toString(),
            diagnostics.has("read starts at byte 100000"),
        )
    }

    // ------------------------------------------------------------------- plumbing

    /** The item the crate holds, built by the class that builds it in production. */
    private fun mediaItem(): MediaItem =
        TrackCatalogue(mockk(relaxed = true)).mediaItemFor(track, MediaId.forQueueRow(key, sequence = 4))

    /**
     * The `DataSpec` Media3's progressive loader opens with: the item's URI, byte zero, no length of
     * its own. Getting either of the last two wrong is what [NeedlerAudioDataSource] reads as "this is
     * not a whole-file read", so they are spelled out rather than defaulted.
     */
    private fun specFor(item: MediaItem): DataSpec {
        val uri: Uri = requireNotNull(item.localConfiguration).uri
        assertEquals(TrackUri.forTrack(key), uri.toString())
        return DataSpec.Builder()
            .setUri(uri)
            .setPosition(0L)
            .setLength(C.LENGTH_UNSET.toLong())
            .build()
    }

    /**
     * Reads [upTo] bytes and not one more.
     *
     * `DefaultExtractorInput` never reads further from a `DataSource` than the extractor asked for, so
     * a reader that stops early leaves the rest of the body unread rather than buffered. A loop that
     * over-read by a chunk would hide the whole defect.
     */
    private fun readLikeMedia3(upTo: Long) {
        val buffer = ByteArray(CHUNK)
        var taken = 0L
        while (taken < upTo) {
            val wanted: Int = minOf(CHUNK.toLong(), upTo - taken).toInt()
            val read: Int = source.read(buffer, 0, wanted)
            if (read <= 0) break
            taken += read
        }
        assertEquals("the reader took what it asked for", upTo, taken)
    }

    private fun usage(): CacheUsage = runBlocking { cacheIndex.usage() }

    private fun fakeUri(text: String): Uri = mockk(relaxed = true) {
        every { this@mockk.toString() } returns text
    }

    /** Keeps every line, so a refusal can be asserted to have said something. */
    private class RecordingDiagnostics : DiagnosticsSink {
        val lines: MutableList<String> = mutableListOf()
        override fun record(level: DiagnosticsLevel, message: String) {
            lines.add(message)
        }

        fun has(fragment: String): Boolean = lines.any { it.contains(fragment) }
    }

    /**
     * One HTTP response, served the way the real one is: a `Content-Length` from the position asked
     * for, bytes in order, and end-of-input after the last one.
     */
    private class FakeAudioServer(private val body: ByteArray) :
        BaseDataSource(true),
        HttpDataSource,
        HttpDataSource.Factory {

        private var position = 0
        private var uri: Uri? = null

        override fun createDataSource(): HttpDataSource = this

        override fun setDefaultRequestProperties(
            defaultRequestProperties: MutableMap<String, String>,
        ): HttpDataSource.Factory = this

        override fun open(dataSpec: DataSpec): Long {
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
            "Content-Type" to listOf("audio/mpeg"),
            "Content-Length" to listOf(body.size.toString()),
        )
    }

    private companion object {

        /** Not a multiple of [CHUNK], so the last read of a whole-file read is a short one. */
        const val TRACK_BYTES: Long = 200_003L

        /**
         * An ID3v1 tag, which is what a reader that stopped at the end of the audio data left behind.
         * Fixed at 128 bytes by the format, which is why that is the number in the assertions.
         */
        const val ID3V1_BYTES: Long = 128L

        /** What the mirror recorded at the last sync: wrong, as a file replaced in place leaves it. */
        const val MIRROR_SIZE: Long = 199_992L

        const val CHUNK: Int = 8_192

        const val FREE_BYTES: Long = 8L * 1_024L * 1_024L * 1_024L
        const val FLOOR_BYTES: Long = 2L * 1_024L * 1_024L * 1_024L

        const val NOW: Long = 1_700_000_000_000L
    }
}

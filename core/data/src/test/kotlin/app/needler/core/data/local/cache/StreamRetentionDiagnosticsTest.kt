package app.needler.core.data.local.cache

import app.needler.core.data.fake.FakeAudioCacheDao
import app.needler.core.data.fake.RG
import app.needler.core.domain.cache.AudioCacheWriteHandle
import app.needler.core.domain.diagnostics.DiagnosticsLevel
import app.needler.core.domain.diagnostics.DiagnosticsSink
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlayableSource
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Every way the audio store declines to keep a stream, and the line each one now leaves behind.
 *
 * ## Why this test exists next to `StreamRetentionPathTest`
 *
 * That test insists a good write lands, because a wrong refusal is invisible from the outside. This one
 * closes the other half of the same gap: when a refusal *is* correct, a user reporting
 * `Cached while listening 0 B` has to be able to find out which of seven things happened. It could not.
 * Diagnosing a device that had played three tracks to completion took a full source trace, because
 * `openWrite` answers `null` for policy, for a missing `Content-Length`, for a nearly full volume and
 * for an unwritable store alike, and `commit` discards a truncated write with an error nobody displays.
 *
 * So these tests drive the real [AudioCacheStoreWriter] over the real [CacheIndex] and the real
 * [EvictionPlanner] into each of those states and assert on the whole recorded line. Asserting the
 * whole string is the point: a line that merely exists, or that says "not cached" without saying which
 * reason and with which numbers, is the defect rather than a fix for it.
 */
public class StreamRetentionDiagnosticsTest {

    @get:Rule
    public val folder: TemporaryFolder = TemporaryFolder()

    private val dao = FakeAudioCacheDao()

    private val key = TrackKey(ReleaseGroupMbid(RG), discNumber = 2, trackNumber = 9)

    private val sink = RecordingSink()

    /** The bytes on the wire, not a multiple of [CHUNK], so the final read is a short one. */
    private val track: ByteArray = ByteArray(TRACK_BYTES.toInt()) { index -> (index % 251).toByte() }

    private fun fetchHandle(sizeBytes: Long? = MIRROR_SIZE): TrackFetchHandle = TrackFetchHandle(
        fileId = FileId("7700"),
        sizeBytes = sizeBytes,
        durationMs = 245_000L,
        format = AudioFormat.MP3,
        bitrateKbps = 320,
    )

    /**
     * The store, over a real index and a real planner.
     *
     * [freeBytes] and [floorBytes] are what put the device below its floor; there is deliberately no
     * way to fake the plan itself, because the figures in the log line come out of it and a test
     * against a stubbed plan would assert that the store can print numbers it was handed.
     */
    private fun store(
        freeBytes: Long = FREE_BYTES,
        floorBytes: Long = FLOOR_BYTES,
    ): AudioCacheStoreWriter = AudioCacheStoreWriter(
        audioCacheDao = dao,
        cacheIndex = CacheIndex(
            audioCacheDao = dao,
            deviceFreeSpace = DeviceFreeSpace.of(freeBytes = freeBytes, floorBytes = floorBytes),
        ),
        audioDirectory = folder.root,
        diagnostics = sink,
        nowMillis = { NOW },
    )

    private fun stream(
        cacheWhileStreaming: Boolean = true,
        format: StreamFormat = StreamFormat.Original,
        sizeBytes: Long? = MIRROR_SIZE,
    ): PlayableSource.Stream = PlayableSource.Stream(
        key = key,
        fetchHandle = fetchHandle(sizeBytes),
        format = format,
        cacheWhileStreaming = cacheWhileStreaming,
    )

    /** Feeds bytes the way the data source does: a buffer, an offset into it, a short final read. */
    private suspend fun readThrough(handle: AudioCacheWriteHandle, upTo: Long = TRACK_BYTES) {
        var offset = 0
        while (offset < upTo) {
            val length: Int = minOf(CHUNK.toLong(), upTo - offset).toInt()
            handle.write(track, offset, length)
            offset += length
        }
    }

    @Test
    public fun `a transcode names the format instead of returning a bare null`(): Unit = runTest {
        // The likeliest explanation of an empty cache on a real device: "transcode on mobile data" plus
        // a metered connection. REQUIREMENTS.md "Why transcoded bytes are never cached" makes this
        // correct behaviour, which is exactly why it must be visible - a correct refusal that looks
        // identical to a broken one is what cost a full source trace.
        val handle: AudioCacheWriteHandle? = store().openWrite(
            stream(cacheWhileStreaming = false, format = StreamFormat.Transcoded.Mp3_320),
            declaredLengthBytes = TRACK_BYTES,
        )

        assertNull(handle)
        assertEquals(
            listOf(
                DiagnosticsLevel.Debug to
                    "not cached " + RG + "/2/9 - transcoded (mp3 320 kbps), " +
                    "only original bytes are retained",
            ),
            sink.lines,
        )
    }

    @Test
    public fun `a stream with no length from either side says which two sources were empty`(): Unit =
        runTest {
            // No `Content-Length` and no recorded size in the mirror. The track streams perfectly
            // and is never retained, on every play, and nothing else in the app mentions it.
            val handle: AudioCacheWriteHandle? = store().openWrite(
                stream(sizeBytes = null),
                declaredLengthBytes = null,
            )

            assertNull(handle)
            assertEquals(
                listOf(
                    DiagnosticsLevel.Warn to
                        "not cached " + RG + "/2/9 - no length declared by the response or the mirror",
                ),
                sink.lines,
            )
        }

    @Test
    public fun `a device below its floor reports the three figures and the remedy`(): Unit = runTest {
        // 2 GB free against a 5 GB floor: nothing is evictable, so the plan skips the incoming bytes
        // rather than forcing them in. REQUIREMENTS.md "Storage, and why there is no budget" point 5.
        // The floor is the larger of 2 GB and 2% of the volume, so this is an ordinary state on a
        // fairly full phone - and the only thing that changes it is the user removing something, which
        // is why the line has to say so.
        val handle: AudioCacheWriteHandle? = store(
            freeBytes = 2L * GIB,
            floorBytes = 5L * GIB,
        ).openWrite(stream(), declaredLengthBytes = TRACK_BYTES)

        assertNull(handle)
        assertEquals(
            listOf(
                DiagnosticsLevel.Warn to
                    "not cached " + RG + "/2/9 - 195.3 KB needed, 2.0 GB free after eviction, " +
                    "floor is 5.0 GB; remove a downloaded album to free room",
            ),
            sink.lines,
        )
    }

    @Test
    public fun `a retained track is recorded, and so is the play that finds it`(): Unit = runTest {
        // The positive line, which is what turns "no evidence" into evidence: with it, a bug report
        // showing three of these and a Storage section still reading 0 B points at the accounting
        // query rather than at this path.
        val writer: AudioCacheStoreWriter = store()
        val handle: AudioCacheWriteHandle =
            requireNotNull(writer.openWrite(stream(), declaredLengthBytes = TRACK_BYTES))
        readThrough(handle)
        assertTrue(handle.commit() is Outcome.Success)

        // Opening a write records nothing; only the outcome does. So a completed write is one line.
        assertEquals(
            DiagnosticsLevel.Info to
                "cached " + RG + "/2/9 - 195.3 KB written while listening",
            sink.lines.single(),
        )

        assertNull(writer.openWrite(stream(), declaredLengthBytes = TRACK_BYTES))
        assertEquals(
            DiagnosticsLevel.Debug to
                "not cached " + RG + "/2/9 - already on device, 195.3 KB from the same file",
            sink.lines.last(),
        )
        assertEquals(2, sink.lines.size)
    }

    @Test
    public fun `a truncated commit prints both byte counts, unrounded`(): Unit = runTest {
        // The failure REQUIREMENTS.md "Offline and caching" already records once: held to a length the
        // body did not match, the write is discarded - correctly - and nothing displayed the two
        // numbers whose disagreement was the whole diagnosis. Eleven bytes apart in the original
        // defect, which is why this line is not rounded to a human-readable size.
        val handle: AudioCacheWriteHandle =
            requireNotNull(store().openWrite(stream(), declaredLengthBytes = TRACK_BYTES))

        readThrough(handle, upTo = SHORT_BODY)
        val outcome: Outcome<CachedAudio> = handle.commit()

        assertTrue(outcome is Outcome.Failure)
        assertEquals(
            listOf(
                DiagnosticsLevel.Warn to
                    "not cached " + RG + "/2/9 - wrote " + SHORT_BODY + " of " + TRACK_BYTES +
                    " declared bytes, discarded as truncated",
            ),
            sink.lines,
        )
    }

    @Test
    public fun `a write the player abandoned records nothing`(): Unit = runTest {
        // A seek or a track change abandons the handle, and that is not a refusal to retain anything -
        // it is the user changing their mind. A line per abandoned handle would be the noisiest thing
        // in the buffer and would say nothing about the store.
        val handle: AudioCacheWriteHandle =
            requireNotNull(store().openWrite(stream(), declaredLengthBytes = TRACK_BYTES))

        readThrough(handle, upTo = SHORT_BODY)
        handle.abandon()

        assertEquals(emptyList<Pair<DiagnosticsLevel, String>>(), sink.lines)
    }

    /** Collects what was recorded, in order, so a test can assert on the whole line and its level. */
    private class RecordingSink : DiagnosticsSink {
        val lines: MutableList<Pair<DiagnosticsLevel, String>> = ArrayList()
        override fun record(level: DiagnosticsLevel, message: String) {
            lines.add(level to message)
        }
    }

    private companion object {

        /** Not a multiple of [CHUNK], so the final read is a short one. 195.3 KB when rendered. */
        private const val TRACK_BYTES: Long = 200_003L

        /** What the player managed before it stopped: enough to be a file, not enough to be a track. */
        private const val SHORT_BODY: Long = 60_000L

        /** The size the mirror recorded at the last sync: deliberately not what the server serves. */
        private const val MIRROR_SIZE: Long = 199_992L

        private const val CHUNK: Int = 8_192

        private const val GIB: Long = 1_024L * 1_024L * 1_024L

        private const val FREE_BYTES: Long = 8L * GIB

        private const val FLOOR_BYTES: Long = 2L * GIB

        private const val NOW: Long = 1_700_000_000_000L
    }
}

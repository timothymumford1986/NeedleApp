package app.needler.player.service.source

import app.needler.core.domain.cache.AudioCacheWriteHandle
import app.needler.core.domain.diagnostics.DiagnosticsLevel
import app.needler.core.domain.diagnostics.DiagnosticsSink
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.TrackKey
import app.needler.player.service.Fixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule this sink exists for: **a partial file is never published as a complete cached track.**
 *
 * A truncated file recorded as complete is the worst failure the audio store has, because it is silent. Months
 * later a play finds a local file, plays it, and stops two minutes into a four-minute song with no error
 * anywhere - so the user concludes their music is corrupt, and no log line disagrees.
 *
 * ## The fake enforces the declared length, because the real store does
 *
 * [FakeHandle.commit] refuses a write that is short of `expectedSizeBytes`, exactly as
 * `AudioCacheStoreWriter` does. That is not padding: the sink no longer compares the counts itself, and
 * a fake that committed anything it was handed would let a sink that published truncated bytes pass.
 */
class WriteThroughSinkTest {

    private class FakeHandle(
        override val key: TrackKey = Fixtures.key(),
        override val expectedSizeBytes: Long? = 10L,
        val failOnWrite: Boolean = false,
    ) : AudioCacheWriteHandle {

        val accepted = mutableListOf<Byte>()
        var committed = false
        var abandoned = false

        override val bytesWritten: Long get() = accepted.size.toLong()

        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (failOnWrite) throw IllegalStateException("disk full")
            for (index in offset until offset + length) accepted.add(bytes[index])
        }

        override suspend fun commit(): Outcome<CachedAudio> {
            if (abandoned) return Outcome.failure(NeedlerError.Cancelled)
            val expected: Long? = expectedSizeBytes
            if (bytesWritten <= 0L || (expected != null && bytesWritten != expected)) {
                abandoned = true
                return Outcome.failure(NeedlerError.ProtocolViolation("truncated"))
            }
            committed = true
            return Outcome.success(
                CachedAudio(
                    key = key,
                    filePath = "/data/audio/a.flac",
                    sizeOnDiskBytes = bytesWritten,
                    isComplete = true,
                    sourceHandle = Fixtures.handle(),
                ),
            )
        }

        override suspend fun abandon() {
            abandoned = true
        }
    }

    /** Collects the lines, so a refusal can be asserted to have said something. */
    private class RecordingSink : DiagnosticsSink {
        val lines = mutableListOf<String>()
        override fun record(level: DiagnosticsLevel, message: String) {
            lines.add(message)
        }
    }

    @Test
    fun `a complete stream is committed`() {
        val handle = FakeHandle(expectedSizeBytes = 4L)
        val sink = WriteThroughSink(handle)

        sink.write(byteArrayOf(1, 2, 3, 4), 0, 4)
        val published = sink.finish(readToEnd = true)

        assertTrue(published)
        assertTrue(handle.committed)
        assertFalse(handle.abandoned)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), handle.accepted.toByteArray())
    }

    /** A clean end-of-stream short of the declared length is still a truncated fetch. */
    @Test
    fun `a short body is abandoned rather than committed`() {
        val handle = FakeHandle(expectedSizeBytes = 10L)
        val sink = WriteThroughSink(handle)

        sink.write(byteArrayOf(1, 2, 3), 0, 3)
        val published = sink.finish(readToEnd = true)

        assertFalse(published)
        assertFalse(handle.committed)
        assertTrue(handle.abandoned)
    }

    /** A cancelled load, a dropped connection, a seek away from the track: all the same answer. */
    @Test
    fun `a load closed early short of the declared length is abandoned`() {
        val handle = FakeHandle(expectedSizeBytes = 10L)
        val sink = WriteThroughSink(handle)

        sink.write(byteArrayOf(1, 2, 3, 4), 0, 4)
        val published = sink.finish(readToEnd = false)

        assertFalse(published)
        assertFalse(handle.committed)
        assertTrue(handle.abandoned)
    }

    /**
     * The regression this sink was losing tracks to.
     *
     * Every byte the response declared is on disk, so the file is whole by measurement. Media3 closes a
     * `DataSource` when the load ends, and a load can end having taken the last byte without the reader
     * asking once more and being told there is nothing left. Requiring end-of-input *as well as* the byte
     * count threw those writes away - and a discarded write looks exactly like a write that was never
     * opened: the track plays, nothing is retained, and no error is raised anywhere.
     */
    @Test
    fun `a load that received every declared byte is committed without end-of-input`() {
        val handle = FakeHandle(expectedSizeBytes = 4L)
        val sink = WriteThroughSink(handle)

        sink.write(byteArrayOf(1, 2, 3, 4), 0, 4)
        val published = sink.finish(readToEnd = false)

        assertTrue(published)
        assertTrue(handle.committed)
        assertFalse(handle.abandoned)
    }

    /** With no declared length there is nothing to measure, so a clean end is all there is to go on. */
    @Test
    fun `an early close with no declared length is abandoned`() {
        val handle = FakeHandle(expectedSizeBytes = null)
        val sink = WriteThroughSink(handle)

        sink.write(byteArrayOf(1, 2, 3), 0, 3)

        assertFalse(sink.finish(readToEnd = false))
        assertTrue(handle.abandoned)
    }

    /**
     * A full disk is not a reason to stop the song. The write gives up, playback does not notice, and nothing
     * is published.
     */
    @Test
    fun `a failing write never throws into playback`() {
        val handle = FakeHandle(failOnWrite = true)
        val sink = WriteThroughSink(handle)

        sink.write(byteArrayOf(1, 2), 0, 2)

        assertTrue(sink.hasFailed)
        assertFalse(sink.finish(readToEnd = true))
        assertTrue(handle.abandoned)
    }

    @Test
    fun `a stream with no declared length commits on a clean end`() {
        val handle = FakeHandle(expectedSizeBytes = null)
        val sink = WriteThroughSink(handle)

        sink.write(byteArrayOf(7, 7, 7), 0, 3)

        assertTrue(sink.finish(readToEnd = true))
        assertEquals(3L, handle.bytesWritten)
    }

    @Test
    fun `abandon is idempotent`() {
        val handle = FakeHandle()
        val sink = WriteThroughSink(handle)

        sink.abandon()
        sink.abandon()

        assertTrue(handle.abandoned)
        assertFalse(handle.committed)
    }

    // ------------------------------------------------------------------ the tail

    /**
     * The defect, in one test: an extractor that stops at the end of the audio data.
     *
     * `Mp3Extractor` reports end-of-stream where its seeker says the audio data ends, so a trailing
     * ID3v1 tag is never asked for and the write finishes 128 bytes short of `Content-Length` on a track
     * that played in full. Those bytes are the real tail of the same response, so they are read and the
     * file is whole.
     */
    @Test
    fun `a reader that stopped at the end of the audio data has its tail taken`() {
        val handle = FakeHandle(expectedSizeBytes = 1_000L)
        val diagnostics = RecordingSink()
        val sink = WriteThroughSink(handle, diagnostics)

        sink.write(ByteArray(872) { 1 }, 0, 872)
        val taken: Long = sink.fillTail { buffer, offset, length ->
            for (index in offset until offset + length) buffer[index] = 2
            length
        }

        assertEquals(128L, taken)
        assertEquals(1_000L, handle.bytesWritten)
        assertTrue(sink.finish(readToEnd = false))
        assertTrue(handle.committed)
        assertEquals(1, diagnostics.lines.size)
        assertTrue(diagnostics.lines.single(), diagnostics.lines.single().contains("128 of 1000"))
    }

    /**
     * The other reason a reader stops short, and the reason the tail is bounded: the user moved away.
     *
     * Reading the remainder would fetch the rest of a track nobody is listening to, over a connection
     * the user may be paying for by the megabyte. So nothing is read, the store refuses the write, and
     * the refusal is the thing that gets a line.
     */
    @Test
    fun `a shortfall larger than the cap is not read`() {
        val handle = FakeHandle(expectedSizeBytes = 4_000_000L)
        val diagnostics = RecordingSink()
        val sink = WriteThroughSink(handle, diagnostics)

        sink.write(ByteArray(1_024) { 3 }, 0, 1_024)
        var reads = 0
        val taken: Long = sink.fillTail { _, _, length ->
            reads += 1
            length
        }

        assertEquals(0L, taken)
        assertEquals(0, reads)
        assertTrue(diagnostics.lines.isEmpty())
        assertFalse(sink.finish(readToEnd = false))
        assertTrue(handle.abandoned)
    }

    /** The ordinary case: the reader took every byte, so there is no tail and no read is attempted. */
    @Test
    fun `a complete read has no tail to take`() {
        val handle = FakeHandle(expectedSizeBytes = 4L)
        val sink = WriteThroughSink(handle)

        sink.write(byteArrayOf(1, 2, 3, 4), 0, 4)
        var reads = 0

        assertEquals(
            0L,
            sink.fillTail { _, _, length ->
                reads += 1
                length
            },
        )
        assertEquals(0, reads)
    }

    /**
     * A body that ends before the declared length is still truncated, however the tail was attempted.
     *
     * The tail read is not allowed to turn a short response into a complete one: it reports what it
     * actually took, and the store is the thing that refuses.
     */
    @Test
    fun `a body that ends inside the tail is still refused`() {
        val handle = FakeHandle(expectedSizeBytes = 1_000L)
        val diagnostics = RecordingSink()
        val sink = WriteThroughSink(handle, diagnostics)

        sink.write(ByteArray(900) { 1 }, 0, 900)
        val taken: Long = sink.fillTail { buffer, offset, _ ->
            buffer[offset] = 9
            -1
        }

        assertEquals(0L, taken)
        assertFalse(sink.finish(readToEnd = true))
        assertTrue(handle.abandoned)
        assertTrue(diagnostics.lines.single().contains("took 0 from the same response"))
    }

    /** No declared length means no number to aim at, so there is nothing a tail read could complete. */
    @Test
    fun `a stream with no declared length has no tail`() {
        val handle = FakeHandle(expectedSizeBytes = null)
        val sink = WriteThroughSink(handle)

        sink.write(byteArrayOf(1, 2), 0, 2)
        var reads = 0

        assertEquals(
            0L,
            sink.fillTail { _, _, length ->
                reads += 1
                length
            },
        )
        assertEquals(0, reads)
    }
}

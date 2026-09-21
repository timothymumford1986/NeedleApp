package app.needler.player.service.source

import app.needler.core.domain.cache.AudioCacheWriteHandle
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
    fun `a load that was closed early is abandoned`() {
        val handle = FakeHandle(expectedSizeBytes = 4L)
        val sink = WriteThroughSink(handle)

        sink.write(byteArrayOf(1, 2, 3, 4), 0, 4)
        val published = sink.finish(readToEnd = false)

        assertFalse(published)
        assertFalse(handle.committed)
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
}

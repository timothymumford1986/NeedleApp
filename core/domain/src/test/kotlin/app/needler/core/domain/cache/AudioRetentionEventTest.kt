package app.needler.core.domain.cache

import app.needler.core.domain.diagnostics.DiagnosticsLevel
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact words a bug report will contain, for every way the audio store declines to keep a stream.
 *
 * These assertions are on whole strings on purpose, which is unusual and is the right trade here. The
 * value of this work is not that a line exists - it is that the line says which of six things happened
 * and gives the numbers the decision was taken against. A test that asserted `line.contains("not
 * cached")` would pass on a line that named the wrong reason, which is the defect being fixed rather
 * than a regression of it.
 *
 * The levels are asserted beside the text because the pairing is the judgement: REQUIREMENTS.md
 * "Why transcoded bytes are never cached" makes [AudioRetentionEvent.TranscodedStream] correct
 * behaviour, so it must not carry a `WARN` badge, while [AudioRetentionEvent.LengthUnknown] is nobody's
 * choice and must.
 */
class AudioRetentionEventTest {

    private val key = TrackKey(ReleaseGroupMbid(RG), discNumber = 2, trackNumber = 9)

    @Test
    fun `a retained track says how much landed`() {
        val event = AudioRetentionEvent.Cached(key = key, bytes = 4_928_307L)

        assertEquals(DiagnosticsLevel.Info, event.level)
        assertEquals(
            "cached " + RG + "/2/9 - 4.7 MB written while listening",
            event.line,
        )
    }

    @Test
    fun `a transcode names the format rather than quoting the rule`() {
        // The most likely explanation of a device that streams perfectly and caches nothing, and the
        // reason this line exists at Debug rather than not existing at all.
        val event = AudioRetentionEvent.TranscodedStream(
            key = key,
            format = StreamFormat.Transcoded.Mp3_320,
        )

        assertEquals(DiagnosticsLevel.Debug, event.level)
        assertEquals(
            "not cached " + RG + "/2/9 - transcoded (mp3 320 kbps), only original bytes are retained",
            event.line,
        )
    }

    @Test
    fun `an original stream is describable too, for the line that should never appear`() {
        // Nothing constructs this pairing today - an Original stream is cacheable - and the formatter
        // must still answer, because a `when` that threw on it would turn a diagnostics write into a
        // crash on the playback path.
        assertEquals("original", AudioRetentionEvent.describe(StreamFormat.Original))
    }

    @Test
    fun `bytes already on the device are reported, not silently skipped`() {
        val event = AudioRetentionEvent.AlreadyOnDevice(key = key, bytes = 4_928_307L)

        assertEquals(DiagnosticsLevel.Debug, event.level)
        assertEquals(
            "not cached " + RG + "/2/9 - already on device, 4.7 MB from the same file",
            event.line,
        )
    }

    @Test
    fun `no declared length is a warning, because nobody chose it`() {
        val event = AudioRetentionEvent.LengthUnknown(key = key)

        assertEquals(DiagnosticsLevel.Warn, event.level)
        assertEquals(
            "not cached " + RG + "/2/9 - no length declared by the response or the mirror",
            event.line,
        )
    }

    @Test
    fun `the free-space refusal names the floor, the free space and the remedy`() {
        // The figures are a 256 GB phone's: the floor is max(2 GB, 2% of the volume), so about 5.1 GB,
        // and a device with 4 GB spare retains nothing at all by design. REQUIREMENTS.md "Storage, and
        // why there is no budget" point 6 says that case is a warning, not an eviction of downloads -
        // so the line has to say what the user can actually do about it.
        val event = AudioRetentionEvent.NoRoomAboveFloor(
            key = key,
            incomingBytes = 4_928_307L,
            freeBytes = 4L * 1_024L * 1_024L * 1_024L,
            floorBytes = 5_497_558_138L,
        )

        assertEquals(DiagnosticsLevel.Warn, event.level)
        assertEquals(
            "not cached " + RG + "/2/9 - 4.7 MB needed, 4.0 GB free after eviction, " +
                "floor is 5.1 GB; remove a downloaded album to free room",
            event.line,
        )
    }

    @Test
    fun `an unwritable store is not silence`() {
        val event = AudioRetentionEvent.StoreUnwritable(key = key)

        assertEquals(DiagnosticsLevel.Warn, event.level)
        assertEquals(
            "not cached " + RG + "/2/9 - the audio store could not be opened for writing",
            event.line,
        )
    }

    @Test
    fun `a truncated write reports both numbers exactly, not rounded`() {
        // Held to the mirror's remembered size while the server served the replaced file's bytes: the
        // two numbers side by side are the whole diagnosis, and neither of them was printed anywhere.
        // Eleven bytes apart, which is why this line must not be rounded - both would read 195.3 KB.
        val event = AudioRetentionEvent.IncompleteWrite(
            key = key,
            writtenBytes = 200_003L,
            declaredBytes = 199_992L,
        )

        assertEquals(DiagnosticsLevel.Warn, event.level)
        assertEquals(
            "not cached " + RG + "/2/9 - wrote 200003 of 199992 declared bytes, discarded as truncated",
            event.line,
        )
    }

    @Test
    fun `a write with nothing declared and nothing written says so`() {
        val event = AudioRetentionEvent.IncompleteWrite(
            key = key,
            writtenBytes = 0L,
            declaredBytes = null,
        )

        assertEquals(
            "not cached " + RG + "/2/9 - wrote 0 bytes with no declared length to check, discarded",
            event.line,
        )
    }

    private companion object {
        private const val RG: String = "d2b6bd7d-8d2d-4f33-9a8e-3cbb1cf1a2f1"
    }
}

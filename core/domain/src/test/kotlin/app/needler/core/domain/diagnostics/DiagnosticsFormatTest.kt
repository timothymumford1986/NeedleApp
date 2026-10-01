package app.needler.core.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two figures every diagnostics line is built from.
 *
 * Tested directly rather than through the lines that use them, because the arithmetic is where a
 * locale-free formatter goes wrong: rounding at the boundary, the carry from `1023.95 KB` to `1.0 MB`,
 * and the negative value that a failed `StatFs` reading arrives as.
 */
class DiagnosticsFormatTest {

    @Test
    fun `bytes below a kibibyte are exact`() {
        assertEquals("0 B", DiagnosticsFormat.bytes(0L))
        assertEquals("953 B", DiagnosticsFormat.bytes(953L))
        assertEquals("1023 B", DiagnosticsFormat.bytes(1_023L))
    }

    @Test
    fun `bytes pick the largest unit they reach`() {
        assertEquals("1.0 KB", DiagnosticsFormat.bytes(1_024L))
        assertEquals("195.3 KB", DiagnosticsFormat.bytes(200_003L))
        assertEquals("4.7 MB", DiagnosticsFormat.bytes(4_928_307L))
        assertEquals("2.0 GB", DiagnosticsFormat.bytes(2L * 1_024L * 1_024L * 1_024L))
        assertEquals("5.1 GB", DiagnosticsFormat.bytes(5_497_558_138L))
        assertEquals("1.0 TB", DiagnosticsFormat.bytes(1_024L * 1_024L * 1_024L * 1_024L))
    }

    @Test
    fun `rounding up at the top of a unit carries into the next whole number`() {
        // 1048000 bytes is 1023.4 KB, which must not print as 1024.0 KB and must not become 1.0 MB
        // until it actually reaches a mebibyte.
        assertEquals("1023.4 KB", DiagnosticsFormat.bytes(1_048_000L))
        // 1048575 is one byte short of a mebibyte: the tenths round to 10 and have to carry.
        assertEquals("1024.0 KB", DiagnosticsFormat.bytes(1_048_575L))
        assertEquals("1.0 MB", DiagnosticsFormat.bytes(1_048_576L))
    }

    @Test
    fun `a volume that could not be measured says unknown rather than inventing zero`() {
        // This is how FreeSpaceFloor.UNKNOWN arrives. Printing "0 B free" would read as a full disk.
        assertEquals("unknown", DiagnosticsFormat.bytes(-1L))
    }

    @Test
    fun `durations use the scale the reader needs`() {
        assertEquals("0 ms", DiagnosticsFormat.duration(0L))
        assertEquals("112 ms", DiagnosticsFormat.duration(112L))
        assertEquals("999 ms", DiagnosticsFormat.duration(999L))
        assertEquals("1.0 s", DiagnosticsFormat.duration(1_000L))
        assertEquals("2.4 s", DiagnosticsFormat.duration(2_351L))
        assertEquals("59.9 s", DiagnosticsFormat.duration(59_940L))
        assertEquals("3m 12s", DiagnosticsFormat.duration(192_400L))
        assertEquals("1m 0s", DiagnosticsFormat.duration(60_000L))
    }

    @Test
    fun `plural keeps the noun singular at one`() {
        assertEquals("1 album", DiagnosticsFormat.plural(1, "album"))
        assertEquals("0 albums", DiagnosticsFormat.plural(0, "album"))
        assertEquals("12 albums", DiagnosticsFormat.plural(12, "album"))
    }
}

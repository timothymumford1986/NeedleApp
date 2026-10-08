package app.needler.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule the certificate panel's fingerprint has: a byte is never split.
 *
 * It was, and a screenshot is how it was found rather than a test - `...D1:5` on one line and
 * `D:6C...` on the next, which is the byte `5D` torn in half on the one value the user is being
 * asked to compare character by character against their server. The text engine chose that break,
 * so these assertions are about the thing that took the choice away from it.
 *
 * The zero-width spaces are stripped before comparing in every test but the one that is about them.
 * They are break opportunities, not content: a reader of this file should see the bytes.
 */
class FingerprintRowsTest {

    @Test
    fun `a SHA-256 fingerprint is four rows of eight bytes`() {
        val rows: List<String> = fingerprintRows(SHA256).map(::withoutBreaks)

        assertEquals(
            listOf(
                "9F:86:D0:81:88:4C:7D:65",
                "9A:2F:EA:A0:C5:5A:D0:15",
                "A3:BF:4F:1B:2B:0B:82:2C",
                "D1:5D:6C:15:B0:F0:0A:08",
            ),
            rows,
        )
    }

    @Test
    fun `no row starts or ends on a separator, which is what splitting a byte looks like`() {
        for (row in fingerprintRows(SHA256).map(::withoutBreaks)) {
            assertTrue("row starts on a separator: " + row, !row.startsWith(":"))
            assertTrue("row ends on a separator: " + row, !row.endsWith(":"))
            for (byte in row.split(":")) {
                assertEquals("not a whole byte in " + row, 2, byte.length)
            }
        }
    }

    @Test
    fun `the rows still spell the fingerprint, in order and with nothing added or lost`() {
        val rejoined: String = fingerprintRows(SHA256).joinToString(":") { withoutBreaks(it) }

        assertEquals(SHA256, rejoined)
    }

    @Test
    fun `every break opportunity inside a row sits after a separator`() {
        // What stops the 200% text scale re-introducing the defect: the engine has somewhere to
        // break that is not the middle of a byte.
        val row: String = fingerprintRows(SHA256).first()

        assertEquals(7, row.count { it == BREAK })
        for (index in row.indices) {
            if (row[index] == BREAK) {
                assertEquals("break not after a separator at " + index, ':', row[index - 1])
            }
        }
    }

    @Test
    fun `a short fingerprint is one row`() {
        assertEquals(listOf("AB:CD:EF"), fingerprintRows("AB:CD:EF").map(::withoutBreaks))
    }

    @Test
    fun `a fingerprint with no separators is left exactly as it came`() {
        // Some other encoding, from a server or a test fixture. Chopping it every eight characters
        // would invent a grouping that means nothing, so it is passed through untouched.
        val unseparated = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

        assertEquals(listOf(unseparated), fingerprintRows(unseparated))
    }

    @Test
    fun `an empty fingerprint is one empty row rather than nothing to draw`() {
        assertEquals(listOf(""), fingerprintRows(""))
    }

    @Test
    fun `the row size is the only thing a caller can change`() {
        assertEquals(
            listOf("9F:86:D0:81", "88:4C:7D:65", "9A:2F:EA:A0", "C5:5A:D0:15"),
            fingerprintRows(SHA256.substringBefore(":A3"), bytesPerRow = 4).map(::withoutBreaks),
        )
    }

    private companion object {
        /** The fabricated self-signed fingerprint the Connect screenshots render. */
        const val SHA256: String =
            "9F:86:D0:81:88:4C:7D:65:9A:2F:EA:A0:C5:5A:D0:15:" +
                "A3:BF:4F:1B:2B:0B:82:2C:D1:5D:6C:15:B0:F0:0A:08"

        const val BREAK: Char = '\u200B'

        fun withoutBreaks(row: String): String = row.replace(BREAK.toString(), "")
    }
}

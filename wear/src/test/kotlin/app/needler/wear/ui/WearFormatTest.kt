package app.needler.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The watch's sizes, which are the only figures in this app a user makes a decision on.
 *
 * "Will this album fit" is answered by comparing two of these, so a formatter that rounded the wrong way
 * or printed a locale's comma-decimal would be the one place the user could not check the app's arithmetic
 * against their own watch's settings screen.
 */
class WearFormatTest {

    @Test
    fun `megabytes carry no decimal`() {
        // "48.3 MB" is noise on a screen an inch across; "48 MB" is the number somebody decides on.
        assertEquals("48 MB", WearFormat.bytes(48_300_000L))
        assertEquals("1 MB", WearFormat.bytes(1_000_000L))
        assertEquals("999 MB", WearFormat.bytes(999_999_999L))
    }

    @Test
    fun `gigabytes carry one decimal`() {
        // The difference between 1 GB and 1.9 GB is a whole album, so it is worth a digit.
        assertEquals("1.0 GB", WearFormat.bytes(1_000_000_000L))
        assertEquals("2.4 GB", WearFormat.bytes(2_400_000_000L))
        assertEquals("8.0 GB", WearFormat.bytes(8_000_000_000L))
    }

    @Test
    fun `units are decimal, as the platform's own storage screen counts them`() {
        // A watch that disagreed with its own settings screen about how much room it had would be the one
        // place the user could not check the figure. 1.5e9 bytes is 1.5 GB decimal and 1.397 GiB binary,
        // so this assertion fails if anyone "corrects" the constants to powers of two.
        assertEquals("1.5 GB", WearFormat.bytes(1_500_000_000L))
    }

    @Test
    fun `the decimal point is not a locale's`() {
        // Computed in tenths and concatenated, so a watch set to a comma-decimal locale does not render
        // "1,2 GB" beside a "1.2 GB" from elsewhere in the app.
        assertEquals("1.2 GB", WearFormat.bytes(1_250_000_000L))
    }

    @Test
    fun `anything under a megabyte says so rather than rounding to zero`() {
        assertEquals("under 1 MB", WearFormat.bytes(1L))
        assertEquals("under 1 MB", WearFormat.bytes(999_999L))
    }

    @Test
    fun `nothing and a negative both read as zero`() {
        // A negative can only come from arithmetic on an unreadable volume, and a minus sign on screen
        // reads as a bug rather than as "unknown".
        assertEquals("0 MB", WearFormat.bytes(0L))
        assertEquals("0 MB", WearFormat.bytes(-1L))
    }

    @Test
    fun `a track share reads as a share of what is obtainable`() {
        assertEquals("9 of 12", WearFormat.trackShare(held = 9, offered = 12))
        assertEquals("12 of 12", WearFormat.trackShare(held = 12, offered = 12))
    }

    @Test
    fun `no obtainable figure reads as a plain count`() {
        // The state an album is in once the phone has stopped offering it. "3 of 0" is nonsense; "3
        // tracks" is true.
        assertEquals("3 tracks", WearFormat.trackShare(held = 3, offered = 0))
        assertEquals("1 track", WearFormat.trackShare(held = 1, offered = 0))
        assertEquals("0 tracks", WearFormat.trackShare(held = 0, offered = 0))
    }

    @Test
    fun `a negative held count clamps`() {
        assertEquals("0 tracks", WearFormat.trackShare(held = -4, offered = 0))
    }
}

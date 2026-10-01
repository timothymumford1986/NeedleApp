package app.needler.widget

import app.needler.widget.internal.WidgetFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The strings the widget prints.
 *
 * `WidgetFormat` is a deliberate copy of two functions from `:feature:player`'s `PlayerFormat`,
 * because feature modules are not each other's dependencies. A copy is only acceptable while it
 * stays a copy, so these assertions are the same ones `PlayerFormatTest` makes, against the same
 * values: `1:16`, `21:04`, `1:02:03` and an unknown length that reads as unknown rather than as
 * `0:00`. If this file and `PlayerFormatTest` ever disagree, the product is printing two different
 * timecodes for the same track on two surfaces a user sees within seconds of each other.
 */
class WidgetFormatTest {

    @Test
    fun `a timecode has no hours until there are hours`() {
        assertEquals("0:00", WidgetFormat.timecode(0L))
        assertEquals("0:07", WidgetFormat.timecode(7_400L))
        // The elapsed time drawn on design/html/15-Widget.html.
        assertEquals("1:16", WidgetFormat.timecode(76_000L))
        // The length drawn beside it.
        assertEquals("3:20", WidgetFormat.timecode(200_000L))
        assertEquals("21:04", WidgetFormat.timecode(1_264_000L))
        assertEquals("1:02:03", WidgetFormat.timecode(3_723_000L))
    }

    @Test
    fun `an unknown length is not zero`() {
        assertEquals(WidgetFormat.UNKNOWN_TIME, WidgetFormat.timecode(null))
        assertEquals(WidgetFormat.UNKNOWN_TIME, WidgetFormat.timecode(-1L))
    }

    @Test
    fun `the subtitle is the pack's own line`() {
        // design/html/15-Widget.html: "The Marías · Submarine".
        assertEquals("The Marías · Submarine", WidgetFormat.artistAndAlbum(WidgetFixtures.sienna))
    }

    @Test
    fun `a track with no album prints the artist alone`() {
        assertEquals("Khruangbin", WidgetFormat.artistAndAlbum(WidgetFixtures.untitledAlbum))
    }

    @Test
    fun `nothing playing has no subtitle`() {
        assertEquals("", WidgetFormat.artistAndAlbum(null))
    }

    @Test
    fun `artwork is described the way the pack's alt text reads`() {
        // The pack's own alt: alt="Submarine by The Marías".
        assertEquals("Submarine by The Marías", WidgetFormat.artworkDescription(WidgetFixtures.sienna))
    }

    @Test
    fun `artwork with nothing to name stays out of the accessibility tree`() {
        assertNull(WidgetFormat.artworkDescription(null))
        // No album: the artist is still worth saying.
        assertEquals("Khruangbin", WidgetFormat.artworkDescription(WidgetFixtures.untitledAlbum))
    }

    @Test
    fun `a percentage is the pack's own number`() {
        // design/html/15-Widget.html draws the pull card at 62%.
        assertEquals("62%", WidgetFormat.percent(0.62f))
        assertEquals("0%", WidgetFormat.percent(0f))
        assertEquals("100%", WidgetFormat.percent(1f))
    }

    @Test
    fun `a percentage rounds the way the Pulls screen rounds it`() {
        // PullsFormat.percent rounds rather than truncating, so 61.6 must not read 61 on one
        // surface and 62 on the other.
        assertEquals("62%", WidgetFormat.percent(0.616f))
        assertEquals("61%", WidgetFormat.percent(0.614f))
    }

    @Test
    fun `a percentage outside the range is clamped rather than printed`() {
        // The server has reported over a hundred percent before; a bar cannot overfill and neither
        // can the number beside it.
        assertEquals("100%", WidgetFormat.percent(1.4f))
        assertEquals("0%", WidgetFormat.percent(-0.2f))
    }

    @Test
    fun `no reported progress is not zero percent`() {
        // A pull still searching for sources has not started, so there is no number to print. The
        // card draws an em dash and the stage word instead; see PullCardModel.
        assertNull(WidgetFormat.percent(null))
    }

    @Test
    fun `the separator joins whichever halves are there`() {
        assertEquals(
            "Searching · Black Classical Music",
            WidgetFormat.withSeparator("Searching", "Black Classical Music"),
        )
        assertEquals("Black Classical Music", WidgetFormat.withSeparator(null, "Black Classical Music"))
        assertEquals("Searching", WidgetFormat.withSeparator("Searching", null))
        // Blank is treated as absent, so a server that returned an empty album title does not leave
        // a card reading "Searching ·".
        assertEquals("Searching", WidgetFormat.withSeparator("Searching", "   "))
        assertEquals("", WidgetFormat.withSeparator(null, null))
    }
}

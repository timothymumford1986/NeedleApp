package app.needler.wear.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The decisions the watch makes about a crate item it has just read.
 *
 * [WearCrateState.of] exists as a separate, pure function precisely so this test can exist: the rest
 * of the decode is `DataMap` key reading, which needs Google Play services, while the arithmetic here
 * is what can be quietly wrong. Two failures in particular are invisible on a device until somebody
 * taps the wrong row: an index that is treated as an index into the whole crate rather than into the
 * published window, and a count of what is missing that goes negative and prints "+ -3 more".
 *
 * The inputs are all treated as untrustworthy on purpose. The phone that sends them is a separately
 * installed APK with its own version code, so a watch talking to an older or newer phone is a normal
 * situation rather than a corrupt one.
 */
class WearCrateStateTest {

    private val rows: List<WearCrateRow> = listOf(
        WearCrateRow(id = "1@album/1/1", title = "Sienna", artist = "The Marias"),
        WearCrateRow(id = "2@album/1/2", title = "Hush", artist = "The Marias"),
        WearCrateRow(id = "3@album/1/3", title = "No One Knows", artist = "The Marias"),
    )

    @Test
    fun `no rows is an empty crate, whatever else was sent`() {
        assertSame(
            WearCrateState.Empty,
            WearCrateState.of(rows = emptyList(), currentIndex = 0, windowStart = 7, total = 90),
        )
    }

    @Test
    fun `the window at the top of a short crate hides nothing`() {
        val state: WearCrateState.InTheCrate = inTheCrate(currentIndex = 0, windowStart = 0, total = 3)
        assertEquals(rows, state.rows)
        assertEquals(0, state.playingIndex)
        assertEquals(0, state.notShownAfter)
    }

    @Test
    fun `rows beyond the window are counted, not carried`() {
        // A 300-row crate with the listener two hundred rows down: the phone sent three rows starting
        // at 200, so 97 follow them.
        val state: WearCrateState.InTheCrate =
            inTheCrate(currentIndex = 0, windowStart = 200, total = 300)
        assertEquals(97, state.notShownAfter)
    }

    @Test
    fun `nothing playing is a crate with no highlighted row`() {
        // A restored crate before the first play: rows exist and none of them is current.
        val state: WearCrateState.InTheCrate = inTheCrate(
            currentIndex = WearPlaybackProtocol.NO_CURRENT_ROW,
            windowStart = 0,
            total = 3,
        )
        assertNull(state.playingIndex)
        assertEquals(rows, state.rows)
    }

    @Test
    fun `an index past the published rows means nothing is playing`() {
        // What a newer phone sending a longer window to an older watch could look like. Highlighting
        // nothing is right; throwing, or highlighting the last row, is not.
        assertNull(inTheCrate(currentIndex = 3, windowStart = 0, total = 3).playingIndex)
        assertNull(inTheCrate(currentIndex = 99, windowStart = 0, total = 3).playingIndex)
    }

    @Test
    fun `a total smaller than the window cannot produce a negative count`() {
        // A phone that published rows and a stale total, or a crate that shrank between the two
        // numbers being read. "+ -3 more on your phone" is the failure this clamp prevents.
        assertEquals(0, inTheCrate(currentIndex = 0, windowStart = 0, total = 1).notShownAfter)
        assertEquals(0, inTheCrate(currentIndex = 0, windowStart = 0, total = 0).notShownAfter)
    }

    @Test
    fun `a negative window start is treated as the top of the crate`() {
        // Nonsense from the wire, so the count is computed from zero rather than inflated by it: a
        // start of -5 against a total of 3 would otherwise report five extra rows that do not exist.
        assertEquals(0, inTheCrate(currentIndex = 0, windowStart = -5, total = 3).notShownAfter)
    }

    @Test
    fun `the playing index is an index into the window, not into the crate`() {
        // The window starts at the playing row today, so the phone sends 0 even when that row is the
        // two hundredth in the crate. The watch must use it as sent: reading it as a crate index would
        // highlight nothing and skip to the wrong track.
        val state: WearCrateState.InTheCrate =
            inTheCrate(currentIndex = 0, windowStart = 200, total = 300)
        assertEquals(0, state.playingIndex)
        assertEquals("1@album/1/1", state.rows[0].id)
    }

    private fun inTheCrate(
        currentIndex: Int,
        windowStart: Int,
        total: Int,
    ): WearCrateState.InTheCrate {
        val state: WearCrateState = WearCrateState.of(
            rows = rows,
            currentIndex = currentIndex,
            windowStart = windowStart,
            total = total,
        )
        return state as WearCrateState.InTheCrate
    }
}

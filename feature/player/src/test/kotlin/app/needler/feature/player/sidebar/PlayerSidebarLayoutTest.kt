package app.needler.feature.player.sidebar

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The height arithmetic behind the tablet player panel.
 *
 * ## What went wrong
 *
 * REQUIREMENTS.md makes the sidebar a `WindowSizeClass` decision - "one navigation model at two widths" -
 * and a phone turned to landscape is 844 dp wide, which is `Expanded`, so it composes the tablet's panel
 * into **390 dp of height**. Screen 09's own measurements need about 690 dp before the crate gets
 * anything. A `Column` measures its unweighted children against the height that is left, so the 300 dp
 * artwork box took all of it and the scrubber, the transport, the output chip and the crate were each
 * measured with a maximum height of zero: the accessibility tree reported every transport control at
 * `[0,0][0,0]`, with no play/pause node at all. The player was inoperable and invisible to TalkBack in
 * landscape.
 *
 * These are the assertions that would have caught it. They are plain JUnit on `Dp` values rather than a
 * rendered screen, because the failure was a decision about how to spend a height and not a matter of
 * pixels - and because a decision worth making is worth being able to check at every height, not only at
 * the two the design pack draws.
 */
class PlayerSidebarLayoutTest {

    @Test
    fun `a tablet gets the design pack, unscaled`() {
        // 800 dp: the pack's own artboard height for screen 09.
        assertEquals(1f, sidebarArtworkScale(available = 800.dp - 68.dp, full = true))
        assertTrue("the tablet must be above the adapt threshold", 800.dp >= sidebarFullHeight)
    }

    @Test
    fun `a phone in landscape drops the record rather than the transport`() {
        // 390 dp of window, less 16 dp of padding top and bottom in the compact rhythm.
        assertTrue("a landscape phone must adapt", 390.dp < sidebarFullHeight)
        assertNull(
            "there is no room for a sleeve at this height, and the controls come first",
            sidebarArtworkScale(available = 390.dp - 32.dp, full = false),
        )
    }

    @Test
    fun `a short tablet keeps a smaller record`() {
        // A 1024 by 600 tablet in landscape: too short for the pack, tall enough for a sleeve.
        val scale: Float? = sidebarArtworkScale(available = 600.dp - 32.dp, full = false)
        assertNotNull("600 dp should still afford a record", scale)
        assertTrue("it should be scaled down, not dropped: " + scale, scale!! < 1f)
        assertTrue("and not scaled to a smudge: " + scale, scale > 0.4f)
    }

    @Test
    fun `the record never grows past the pack`() {
        // An unusually tall panel that is still below the threshold cannot end up with a *larger* sleeve
        // than screen 09 draws.
        assertEquals(1f, sidebarArtworkScale(available = 4_000.dp, full = false))
    }

    @Test
    fun `the scale falls as the height does, and never goes negative`() {
        var previous = 1f
        for (height in intArrayOf(690, 640, 600, 560, 520, 480, 440, 400, 390, 320, 200, 0)) {
            val scale: Float? = sidebarArtworkScale(available = height.dp - 32.dp, full = false)
            if (scale == null) continue
            assertTrue("scale must be positive at " + height + " dp: " + scale, scale > 0f)
            assertTrue("scale must not rise as height falls at " + height + " dp", scale <= previous)
            previous = scale
        }
    }
}

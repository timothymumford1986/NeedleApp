package app.needler.feature.player.ui

import androidx.compose.ui.graphics.toArgb
import app.needler.core.design.theme.NeedlerDarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Shuffle and repeat have three appearances, not two.
 *
 * The device audit found both "markedly dimmer than the adjacent skip icons" and hard to read at all:
 * their off state and their unavailable state were the same `#6f7a68`, so a live control looked
 * inoperable. These are the assertions that keep the three apart, and they are plain colour comparisons
 * because the thing that was wrong was a token choice, not a rendering.
 *
 * `NeedlerDarkColors` is the one palette in the project - REQUIREMENTS.md "Design system": "No light
 * theme exists in the design pack" - so there is nothing to parameterise over.
 */
class TransportRowTest {

    private val colors = NeedlerDarkColors

    @Test
    fun `on is the accent`() {
        assertEquals(
            colors.accent,
            transportModeTint(colors, enabled = true, active = true),
        )
    }

    @Test
    fun `off is secondary text, not muted`() {
        assertEquals(
            colors.textSecondary,
            transportModeTint(colors, enabled = true, active = false),
        )
    }

    @Test
    fun `unavailable keeps muted, which is what that token is for`() {
        assertEquals(
            colors.textMuted,
            transportModeTint(colors, enabled = false, active = false),
        )
        // An unloaded crate cannot have shuffle meaningfully on, but if the state ever says so the
        // button must still read as unavailable rather than as the accent.
        assertEquals(
            colors.textMuted,
            transportModeTint(colors, enabled = false, active = true),
        )
    }

    /** The regression itself: off and unavailable were one colour, and a listener could not tell. */
    @Test
    fun `off and unavailable are different colours`() {
        assertNotEquals(
            transportModeTint(colors, enabled = true, active = false),
            transportModeTint(colors, enabled = false, active = false),
        )
    }

    /**
     * And the palette the decision rests on has not moved.
     *
     * REQUIREMENTS.md "Accessibility" records `#6f7a68` as kept despite failing AA, and names
     * `textSecondary` as what carries the pack's secondary text. If either value changes, the contrast
     * argument above needs re-making rather than silently inheriting a new one.
     */
    @Test
    fun `the two tokens are the pack's own values`() {
        assertEquals(0xFFA8B3A0.toInt(), colors.textSecondary.toArgb())
        assertEquals(0xFF6F7A68.toInt(), colors.textMuted.toArgb())
    }
}

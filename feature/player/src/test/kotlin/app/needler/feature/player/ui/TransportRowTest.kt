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
 * ## The argument, re-made
 *
 * [the two tokens are the pack's own values] is a tripwire: it pins the two values the separation
 * rests on so that moving either forces this paragraph to be rewritten instead of silently
 * inheriting a new ratio. It fired. `textMuted` moved from `#6f7a68` to `#828f7a` to clear WCAG AA
 * as the product's third tier of prose, and it was `textMuted` that drew the unavailable state - so
 * off against unavailable went from **2.06:1 to 1.56:1**, giving back a quarter of the separation
 * this class exists to hold.
 *
 * The resolution was to split the role rather than pick one of the two requirements: `NeedlerColors`
 * now has a `disabled` token holding the pack's drawn `#6f7a68` for inactive controls, which WCAG
 * 2.2 exempts from both 1.4.3 and 1.4.11 by name, while the prose tier keeps the lighter value. The
 * separation is back to **2.06:1** and the assertions below now pin `disabled`, which is the token
 * that actually decides this control's third appearance.
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
    fun `unavailable wears the disabled token, which is what that token is for`() {
        assertEquals(
            colors.disabled,
            transportModeTint(colors, enabled = false, active = false),
        )
        // An unloaded crate cannot have shuffle meaningfully on, but if the state ever says so the
        // button must still read as unavailable rather than as the accent.
        assertEquals(
            colors.disabled,
            transportModeTint(colors, enabled = false, active = true),
        )
    }

    /**
     * And not the prose tier, which is a lighter colour than this control may use.
     *
     * The regression that split the token: `textMuted` is 1.56:1 against `textSecondary`, so an
     * unavailable mode drawn in it is very nearly the off state beside it.
     */
    @Test
    fun `unavailable is not the third text tier`() {
        assertNotEquals(
            colors.textMuted,
            transportModeTint(colors, enabled = false, active = false),
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
     * `#6f7a68` is the pack's drawn value, kept on `disabled` because WCAG 2.2 exempts inactive
     * components from 1.4.3 and 1.4.11 by name; `textSecondary` carries the off state. If either
     * value changes, the contrast argument in this class's KDoc needs re-making rather than
     * silently inheriting a new one. That is not hypothetical - it has fired once already, and the
     * "## The argument, re-made" section is the result.
     */
    @Test
    fun `the two tokens are the pack's own values`() {
        assertEquals(0xFFA8B3A0.toInt(), colors.textSecondary.toArgb())
        assertEquals(0xFF6F7A68.toInt(), colors.disabled.toArgb())
    }
}

package app.needler.core.design.motion

import androidx.compose.foundation.Indication
import androidx.compose.ui.graphics.Color
import app.needler.core.design.theme.NeedlerDarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What reduced motion does to press feedback, asserted on the decision rather than on a frame.
 *
 * ## Why this is not a render
 *
 * An indication is a transient animation, so no screenshot holds it, and a Compose UI test can inject
 * a press but cannot read back which [Indication] drew the result - the same limitation
 * `PressIndicationOrderTest` records for the *shape* of a press. What is assertable is the decision:
 * given the reduced-motion flag and the fill a press will be drawn over, which indication is
 * substituted. [instantPressIndicationFor] is that decision with the composition taken out of it, and
 * [needlerPressIndication] is one expression on top.
 *
 * The composed half - `NeedlerTheme` installing the result as `LocalIndication` inside `MaterialTheme`
 * - is structural and is not covered here: it is one provider, in one file, and `MaterialTheme`
 * overwriting it would show up as every control in the application ignoring the setting rather than as
 * a subtle regression.
 *
 * ## The two properties worth pinning
 *
 * REQUIREMENTS.md "Motion" asks that animation be suppressed, and the decision recorded on
 * [needlerPressIndication] is that the pressed state must then appear *instantly*, not disappear. Both
 * halves of that are a test:
 *
 *  * reduced motion substitutes something, and
 *  * what it substitutes draws at a visible alpha.
 *
 * A regression to `indication = null` would pass the first and fail the second, which is exactly the
 * mistake the decision was taken to avoid.
 */
class PressFeedbackTest {

    @Test
    fun `normal motion substitutes nothing, so the platform's own press is kept`() {
        assertNull(
            "animations are on, so nothing should replace Material's ripple",
            instantPressIndicationFor(
                reducedMotion = false,
                surface = Color.Transparent,
                colors = NeedlerDarkColors,
            ),
        )
    }

    @Test
    fun `reduced motion substitutes an instant press rather than removing the press`() {
        val substituted: Indication? = instantPressIndicationFor(
            reducedMotion = true,
            surface = Color.Transparent,
            colors = NeedlerDarkColors,
        )

        assertNotNull(
            "reduced motion must still answer a press - silence is worse than abruptness",
            substituted,
        )
        assertTrue(
            "the substituted indication is " + substituted + ", which is not the instant press",
            substituted is InstantPressIndication,
        )
    }

    @Test
    fun `the instant press is drawn at a visible alpha`() {
        assertTrue(
            "a pressed control has to be visibly pressed; 0 alpha is `indication = null` in disguise",
            InstantPressIndication.PRESSED_STATE_LAYER_ALPHA > 0f,
        )
    }

    // ---- which way round the overlay goes -----------------------------------

    /**
     * A control with no fill of its own sits on the canvas, which is near-black, so the overlay is the
     * pale one. This is every transport glyph, every list row and the pull sheet's scrim.
     */
    @Test
    fun `a control with no fill takes the pale overlay`() {
        assertEquals(
            NeedlerDarkColors.textPrimary,
            overlayFor(Color.Transparent),
        )
    }

    /**
     * The accent and the positive are the pack's only pale fills - Play and Pull - and a pale overlay
     * on either is a press nobody can see.
     */
    @Test
    fun `the two pale fills take the dark overlay`() {
        assertEquals(NeedlerDarkColors.canvas, overlayFor(NeedlerDarkColors.accent))
        assertEquals(NeedlerDarkColors.canvas, overlayFor(NeedlerDarkColors.positive))
    }

    /** The surface levels are dark fills, so they keep the pale overlay a transparent control gets. */
    @Test
    fun `the surface fills keep the pale overlay`() {
        assertEquals(NeedlerDarkColors.textPrimary, overlayFor(NeedlerDarkColors.surface))
        assertEquals(NeedlerDarkColors.textPrimary, overlayFor(NeedlerDarkColors.surfaceRaised))
    }

    private fun overlayFor(surface: Color): Color {
        val substituted: Indication? = instantPressIndicationFor(
            reducedMotion = true,
            surface = surface,
            colors = NeedlerDarkColors,
        )
        return (substituted as InstantPressIndication).overlay
    }
}

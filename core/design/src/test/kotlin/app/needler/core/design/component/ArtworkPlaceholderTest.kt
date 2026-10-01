package app.needler.core.design.component

import androidx.compose.ui.graphics.Color
import app.needler.core.design.theme.NeedlerDarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of the artwork placeholder that are not drawing: which colour an identity gets, and
 * which letter a name gets.
 *
 * Both are plain functions rather than composables precisely so they can be asserted on here, with no
 * Robolectric and no rendering. What matters about them is not how they look but that they are
 * **stable** - the same album has to be the same colour on the library grid, in search results and in
 * a widget - and stability is exactly the property a screenshot cannot check.
 */
class ArtworkPlaceholderTest {

    // ---------------------------------------------------------------- the tint

    @Test
    fun `the same identity is always the same colour`() {
        val first: Color = artworkPlaceholderTint(SUBMARINE)
        val second: Color = artworkPlaceholderTint(SUBMARINE)

        assertEquals(first, second)
    }

    /**
     * Case is folded, so an id that reaches one screen lower-cased and another as typed does not draw
     * two colours for one record. MBIDs are lower-case hex by convention and not by guarantee.
     */
    @Test
    fun `case does not change the colour`() {
        assertEquals(
            artworkPlaceholderTint(SUBMARINE),
            artworkPlaceholderTint(SUBMARINE.uppercase()),
        )
    }

    @Test
    fun `different identities generally get different colours`() {
        val tints: List<Color> = IDENTITIES.map { artworkPlaceholderTint(it) }

        // Not "all different": there are six tints and more albums than that in any library, so
        // collisions are the design rather than a fault. What would be a fault is one colour for
        // everything, which is what a hash folded wrongly produces.
        assertTrue(
            "every identity got the same tint: " + tints.first(),
            tints.toSet().size > 1,
        )
    }

    @Test
    fun `every tint the function can produce is in the palette's own family`() {
        // Six tints, so enough identities to be confident of covering all of them.
        val tints: Set<Color> = (1..2_000)
            .map { artworkPlaceholderTint("rg-" + it) }
            .toSet()

        assertEquals(ARTWORK_TINT_COUNT, tints.size)
        tints.forEach { tint ->
            // Each one is a low mix into `surface`, so it stays darker than any text colour in the
            // palette and the letter drawn on it keeps its contrast. `textSecondary` is the dimmest
            // colour the placeholder ever draws a letter in.
            assertTrue(
                "tint " + tint + " is lighter than the text drawn on it",
                luminance(tint) < luminance(NeedlerDarkColors.textSecondary),
            )
            assertTrue("tint " + tint + " is not opaque", tint.alpha == 1f)
        }
    }

    /**
     * The letter is large text by construction, so 3:1 is the threshold rather than 4.5:1.
     *
     * Asserted rather than left in a comment because the tints are computed: a change to the mix
     * strengths or to the hues would silently take the letter below the line, and REQUIREMENTS.md
     * "Accessibility" already carries one knowingly-failing contrast decision. It is not carrying a
     * second one by accident.
     */
    @Test
    fun `the letter clears three to one on every tint`() {
        val letter: Color = NeedlerDarkColors.textSecondary
        (1..2_000).map { artworkPlaceholderTint("rg-" + it) }.toSet().forEach { tint ->
            val ratio: Double = contrast(letter, tint)
            assertTrue("contrast on " + tint + " was " + ratio, ratio >= 3.0)
        }
    }

    // ---------------------------------------------------------------- the letter

    @Test
    fun `the letter is the first letter of the name, uppercased`() {
        assertEquals("S", artworkPlaceholderInitial("Submarine"))
        assertEquals("T", artworkPlaceholderInitial("The Marías"))
        assertEquals("B", artworkPlaceholderInitial("boygenius"))
    }

    @Test
    fun `leading punctuation and space are skipped`() {
        assertEquals("A", artworkPlaceholderInitial("...And Justice for All"))
        assertEquals("H", artworkPlaceholderInitial("   Heaven"))
        assertEquals("M", artworkPlaceholderInitial("!!! Morning Glory"))
    }

    @Test
    fun `a digit counts`() {
        assertEquals("2", artworkPlaceholderInitial("21"))
    }

    @Test
    fun `a name with nothing usable in it gets no letter at all`() {
        assertNull(artworkPlaceholderInitial(null))
        assertNull(artworkPlaceholderInitial(""))
        assertNull(artworkPlaceholderInitial("   "))
        assertNull(artworkPlaceholderInitial("!!!"))
    }

    @Test
    fun `an accented first letter keeps its accent`() {
        assertEquals("Á", artworkPlaceholderInitial("Ágætis byrjun"))
    }

    @Test
    fun `two albums with the same title still get their own colours`() {
        // The whole reason the tint is keyed on the identity and not on the title.
        assertNotEquals(
            artworkPlaceholderTint("rg-greatest-hits-queen"),
            artworkPlaceholderTint("rg-greatest-hits-abba"),
        )
        assertEquals("G", artworkPlaceholderInitial("Greatest Hits"))
    }

    // ---------------------------------------------------------------- contrast maths

    /** WCAG relative luminance. */
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v: Double = value.toDouble()
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val first: Double = luminance(a)
        val second: Double = luminance(b)
        val lighter: Double = maxOf(first, second)
        val darker: Double = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private companion object {
        const val SUBMARINE: String = "d2b6bd7d-8d2d-4f33-9a8e-3cbb1cf1a2f1"

        val IDENTITIES: List<String> = listOf(
            SUBMARINE,
            "11111111-2222-3333-4444-555555555555",
            "8cfce742-445e-5e80-93a8-d8f924d56984",
            "rg-dragon",
            "rg-mordechai",
            "rg-bluerev",
            "rg-oncetwice",
            "rg-twostar",
        )
    }
}

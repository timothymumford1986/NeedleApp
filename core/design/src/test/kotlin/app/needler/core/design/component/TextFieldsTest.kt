package app.needler.core.design.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reveal toggle's spoken label, which is the only part of [NeedlerSecretTextField] a test can
 * reach without rendering - and the part most worth pinning.
 *
 * The toggle has no visible text, so the content description is the whole control as far as
 * TalkBack is concerned, and the device audit reads exactly these strings. Two properties matter
 * and neither is visible in a screenshot: the label has to say which state the field is in *and*
 * what a tap will do, and it has to **change** when the state changes. A toggle whose description
 * is the same before and after a press says nothing about whether the press worked, which is the
 * defect the audit looks for.
 */
class TextFieldsTest {

    @Test
    fun `the two states read differently`() {
        assertNotEquals(
            secretRevealContentDescription("Password", revealed = false),
            secretRevealContentDescription("Password", revealed = true),
        )
    }

    @Test
    fun `the masked label says it is hidden and that a tap shows it`() {
        val description: String = secretRevealContentDescription("Password", revealed = false)

        assertEquals("Password is hidden. Tap to show it.", description)
    }

    @Test
    fun `the revealed label says it is showing and that a tap hides it`() {
        val description: String = secretRevealContentDescription("Password", revealed = true)

        assertEquals("Password is showing. Tap to hide it.", description)
    }

    /**
     * Up to `ProxyCredentials.MAX_HEADERS` secret fields can share one form, so the name is part of
     * the label rather than a fixed "Password": four toggles announcing the same thing leave a
     * screen-reader user unable to tell which secret they have hold of.
     */
    @Test
    fun `the secret's name is what distinguishes one toggle from another`() {
        val first: String = secretRevealContentDescription("Header 1 value", revealed = false)
        val second: String = secretRevealContentDescription("Header 2 value", revealed = false)

        assertNotEquals(first, second)
        assertTrue(first, first.startsWith("Header 1 value"))
        assertTrue(second, second.startsWith("Header 2 value"))
    }
}

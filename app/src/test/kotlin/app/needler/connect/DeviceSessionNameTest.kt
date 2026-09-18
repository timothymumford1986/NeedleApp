package app.needler.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 80-character cap and the whitespace collapsing the server applies before
 * measuring.
 *
 * Both rules exist because the server rejects an over-long name outright, which
 * would fail onboarding at its very last step - and only on the devices with
 * the longest model names, which is the hardest kind of bug to see coming.
 */
class DeviceSessionNameTest {

    @Test
    fun `a pixel keeps its model name and drops the duplicated maker`() {
        assertEquals("Needler · Pixel 8", DeviceSessionName.of("Google", "Pixel 8"))
    }

    @Test
    fun `a maker that is not already in the model is kept`() {
        assertEquals("Needler · samsung SM-S918B", DeviceSessionName.of("samsung", "SM-S918B"))
    }

    @Test
    fun `whitespace is collapsed, as the server does before measuring`() {
        assertEquals(
            "Needler · Pixel 8 Pro",
            DeviceSessionName.of("  Google ", " Pixel   8\tPro "),
        )
    }

    @Test
    fun `a long model name is truncated client-side`() {
        val name = DeviceSessionName.of("VeryLongOem", "M".repeat(200))

        assertTrue("'$name' exceeds the cap", name.length <= DeviceSessionName.MAX_LENGTH)
        assertTrue("the product name must survive", name.startsWith("Needler · "))
    }

    @Test
    fun `an unknown device still gets a usable name`() {
        assertEquals("Needler · Android device", DeviceSessionName.of(null, null))
        assertEquals("Needler · Android device", DeviceSessionName.of("", "  "))
    }
}

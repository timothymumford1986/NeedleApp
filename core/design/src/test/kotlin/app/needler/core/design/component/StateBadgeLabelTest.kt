package app.needler.core.design.component

import androidx.compose.ui.graphics.Color
import app.needler.core.design.theme.NeedlerDarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words and the hues of the three states, pinned.
 *
 * REQUIREMENTS.md "Vocabulary" fixes exactly three words for where a record is, and a device audit has
 * twice caught a defect by reading the accessibility tree rather than the screen. Both of those make
 * these strings load-bearing: every one of them is read aloud somewhere, and a screenshot cannot tell
 * anyone that the word changed - it can only tell them that some pixels did.
 *
 * What is asserted here is not that the words are *nice*. It is that
 *
 *  * there are three of them and no more;
 *  * the spoken form of a state is the written form, so a screen-reader user learns one vocabulary;
 *  * the hue belongs to the state and does not move when emphasis moves, which is the defect this
 *    whole change exists to fix;
 *  * and the two hues clear WCAG AA on all three backgrounds a badge is drawn on, which REQUIREMENTS.md
 *    "Accessibility" requires to be stated as a measurement rather than assumed.
 */
class StateBadgeLabelTest {

    // ---------------------------------------------------------------- the three words

    @Test
    fun `there are exactly three states`() {
        assertEquals(3, NeedlerAlbumSource.entries.size)
    }

    @Test
    fun `each state renders its one fixed word`() {
        assertEquals("Not retrieved", NeedlerAlbumSource.NotRetrieved.label())
        assertEquals("Server", NeedlerAlbumSource.Server.label())
        assertEquals("Device", NeedlerAlbumSource.Device.label())
    }

    @Test
    fun `the quality tag labels are the state words with a colon`() {
        assertEquals("Server:", NeedlerAlbumSource.Server.tagLabel())
        assertEquals("Device:", NeedlerAlbumSource.Device.tagLabel())
    }

    /**
     * The retired vocabulary, named so that a reintroduction is a test failure rather than a review
     * comment. Nine words became three; these are the six that went.
     */
    @Test
    fun `no retired word survives on any badge`() {
        val retired: List<String> = listOf(
            "In library",
            "On device",
            "Pull local",
            "Downloaded",
            "Cached while listening",
            "Retrieve",
        )
        val rendered: List<String> = ALL_BADGES.flatMap { listOf(it.label(), it.accessibleLabel()) } +
            NeedlerAlbumSource.entries.map { it.label() }

        retired.forEach { word ->
            assertTrue(
                "a badge still renders the retired word " + word,
                rendered.none { it.equals(word, ignoreCase = true) },
            )
        }
    }

    // ---------------------------------------------------------------- the two location badges

    @Test
    fun `the two location badges render their state's word`() {
        assertEquals(
            NeedlerAlbumSource.Server.label(),
            NeedlerAlbumBadge.InLibrary.label(),
        )
        assertEquals(
            NeedlerAlbumSource.Device.label(),
            NeedlerAlbumBadge.OnDevice.label(),
        )
    }

    /**
     * What you see is what you hear, for the two states. The pull lifecycle's badges are allowed to say
     * more - a percentage has to be spoken as a word, and a download in flight says that it is not in
     * the user's way - but a location must not have a second name.
     */
    @Test
    fun `a location badge is spoken exactly as it is written`() {
        assertEquals(NeedlerAlbumBadge.InLibrary.label(), NeedlerAlbumBadge.InLibrary.accessibleLabel())
        assertEquals(NeedlerAlbumBadge.OnDevice.label(), NeedlerAlbumBadge.OnDevice.accessibleLabel())
    }

    // ---------------------------------------------------------------- the transition into the device

    @Test
    fun `the local download names its destination and says the album still plays`() {
        assertEquals("Pulling to device", NeedlerAlbumBadge.PullingToDevice().label())
        assertEquals(
            "Pulling to device, 62 percent, playing now",
            NeedlerAlbumBadge.PullingToDevice(percent = 62).accessibleLabel(),
        )
        // No percentage yet is a real state - nothing has landed - and it must not read as zero.
        assertEquals(
            "Pulling to device, playing now",
            NeedlerAlbumBadge.PullingToDevice().accessibleLabel(),
        )
    }

    /**
     * The hold has its own word because the badge that would otherwise carry it means bytes are
     * arriving, and the reason is the one thing the user can act on.
     */
    @Test
    fun `a download held for Wi-Fi says so`() {
        assertEquals("Waiting for Wi-Fi", NeedlerAlbumBadge.WaitingForWifi.label())
    }

    @Test
    fun `every badge has a non-blank word in both forms`() {
        ALL_BADGES.forEach { badge ->
            assertTrue(badge.toString() + " has no label", badge.label().isNotBlank())
            assertTrue(badge.toString() + " has no spoken label", badge.accessibleLabel().isNotBlank())
        }
    }

    // ---------------------------------------------------------------- the hues

    /**
     * The regression this change was asked for. The two quality tags used to hand the emphasised
     * colour to whichever one applied, so pulling an album local moved the green from `Server:` to
     * `Pulled:` and the hue meant "this one applies" rather than "this is the server".
     */
    @Test
    fun `the two sources take different hues and the server is not the device's`() {
        val server: Color = COLOURS.accent
        val device: Color = COLOURS.positive

        assertEquals(Color(0xFFAED5F2), server)
        assertEquals(Color(0xFFBBDB9B), device)
        assertTrue("the two states share a hue", server != device)
    }

    /**
     * A record heading for a store wears that store's colour for the whole journey, so an album
     * changes hue exactly once - when it arrives on the device. Without this, green would mean both
     * "on its way to the server" and "on the device".
     */
    @Test
    fun `a transition is the hue of where it is going`() {
        assertEquals(COLOURS.accent, badgeTint(NeedlerAlbumBadge.Pulling(percent = 10)))
        assertEquals(COLOURS.positive, badgeTint(NeedlerAlbumBadge.PullingToDevice(percent = 10)))
        assertEquals(COLOURS.accent, badgeTint(NeedlerAlbumBadge.InLibrary))
        assertEquals(COLOURS.positive, badgeTint(NeedlerAlbumBadge.OnDevice))
    }

    /** A hold claims neither store, because nothing is moving and the record has not gone anywhere. */
    @Test
    fun `a hold claims neither hue`() {
        assertEquals(COLOURS.textSecondary, badgeTint(NeedlerAlbumBadge.WaitingForWifi))
        assertEquals(COLOURS.textSecondary, badgeTint(NeedlerAlbumBadge.Waiting))
    }

    // ---------------------------------------------------------------- the measurements

    /**
     * REQUIREMENTS.md "Accessibility" states ratios rather than assuming them - it is the section that
     * records `#6f7a68` at 4.21:1 on the canvas and keeps it anyway - so the two badge hues are
     * measured here against the three backgrounds a badge is actually drawn on.
     */
    @Test
    fun `both state hues clear AA on canvas, surface and surface raised`() {
        val backgrounds: List<Pair<String, Color>> = listOf(
            "canvas" to COLOURS.canvas,
            "surface" to COLOURS.surface,
            "surface raised" to COLOURS.surfaceRaised,
        )
        val hues: List<Pair<String, Color>> =
            listOf("accent" to COLOURS.accent, "positive" to COLOURS.positive)
        hues.forEach { (name, hue) ->
            backgrounds.forEach { (bgName, bg) ->
                val ratio: Double = contrastRatio(hue, bg)
                assertTrue(
                    name + " on " + bgName + " measures " + ratio + ":1",
                    ratio >= 4.5,
                )
            }
        }
    }

    /**
     * **The hue is never the signal, and this is why.** The two are a complementary pair at the same
     * lightness, which is what makes them look deliberate together and also what makes them very nearly
     * one swatch to a red-green colour-blind reader. So this asserts the *weakness*: it is here to stop
     * anyone concluding from the test above that the colours alone tell the two states apart. The words
     * do that, which is what [a location badge is spoken exactly as it is written] guards.
     */
    @Test
    fun `the two hues are nearly indistinguishable by luminance alone`() {
        val ratio: Double = contrastRatio(COLOURS.accent, COLOURS.positive)

        assertTrue(
            "accent and positive measure " + ratio + ":1 against each other, which is no longer the " +
                "pair this reasoning was written for - recheck that nothing relies on hue alone",
            ratio < 1.1,
        )
    }

    private companion object {
        val COLOURS = NeedlerDarkColors

        /** Every badge, with a representative percentage for the two that carry one. */
        val ALL_BADGES: List<NeedlerAlbumBadge> = listOf(
            NeedlerAlbumBadge.InLibrary,
            NeedlerAlbumBadge.OnDevice,
            NeedlerAlbumBadge.Pulling(percent = 62),
            NeedlerAlbumBadge.Pulling(),
            NeedlerAlbumBadge.PullingToDevice(percent = 62),
            NeedlerAlbumBadge.PullingToDevice(),
            NeedlerAlbumBadge.Waiting,
            NeedlerAlbumBadge.WaitingForWifi,
            NeedlerAlbumBadge.Searching,
            NeedlerAlbumBadge.NeedsAttention,
            NeedlerAlbumBadge.Ready,
            NeedlerAlbumBadge.NoSource,
            NeedlerAlbumBadge.Failed,
            NeedlerAlbumBadge.PartlyDelivered,
            NeedlerAlbumBadge.Cancelled,
        )

        /**
         * The tint rule from `NeedlerStateBadge`, restated.
         *
         * The composable's own `when` cannot be reached without rendering, and what matters about these
         * assignments is the rule rather than the pixels, so the rule is written twice and the two
         * copies are kept honest by sitting in one file each other's reasoning cites. If they diverge,
         * the badge is the one that is right and this is the one to correct.
         */
        fun badgeTint(badge: NeedlerAlbumBadge): Color = when (badge) {
            NeedlerAlbumBadge.OnDevice,
            is NeedlerAlbumBadge.PullingToDevice,
            NeedlerAlbumBadge.Ready -> COLOURS.positive

            NeedlerAlbumBadge.InLibrary,
            is NeedlerAlbumBadge.Pulling -> COLOURS.accent

            NeedlerAlbumBadge.Waiting,
            NeedlerAlbumBadge.WaitingForWifi,
            NeedlerAlbumBadge.Searching,
            NeedlerAlbumBadge.NeedsAttention,
            NeedlerAlbumBadge.Failed,
            NeedlerAlbumBadge.PartlyDelivered,
            NeedlerAlbumBadge.Cancelled -> COLOURS.textSecondary

            NeedlerAlbumBadge.NoSource -> COLOURS.textMuted
        }

        /** WCAG 2.1 relative luminance, on the sRGB channels Compose holds as 0f..1f. */
        fun luminance(colour: Color): Double {
            fun channel(value: Float): Double {
                val c: Double = value.toDouble()
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(colour.red) +
                0.7152 * channel(colour.green) +
                0.0722 * channel(colour.blue)
        }

        /** WCAG 2.1 contrast ratio, order-independent. */
        fun contrastRatio(a: Color, b: Color): Double {
            val first: Double = luminance(a)
            val second: Double = luminance(b)
            val lighter: Double = maxOf(first, second)
            val darker: Double = minOf(first, second)
            return (lighter + 0.05) / (darker + 0.05)
        }
    }
}

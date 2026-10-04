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

    // ---------------------------------------------------------------- the width of the words

    /**
     * The defect this section exists for, in the two numbers that describe it.
     *
     * `NeedlerAlbumBadge.NeedsAttention` drew "Needs attention on the server": 29 characters of
     * 13sp/600 beside a 16dp glyph, which is 196dp by [drawnWidthDp] and 192dp measured off
     * `screenshots/pulls-all-awaiting-review-phone.png`. A phone row has [ROW_SHARED_DP] to divide
     * between the text column and the trailing one, and the trailing one is unweighted - it takes
     * what it asks for and the title gets what is left, which was 74dp.
     *
     * The assertion is on the slot rather than on the string, for the reason
     * `PullsRowCompositionTest` gives about the subtitle it had to fix twice: a test that pins the
     * one sentence someone complained about is passed by the next sentence.
     */
    @Test
    fun `the parked state's badge is a label and not a sentence`() {
        assertEquals("Needs attention", NeedlerAlbumBadge.NeedsAttention.label())

        val drawn: Float = drawnWidthDp(NeedlerAlbumBadge.NeedsAttention)
        assertTrue(
            "the badge is " + drawn.toInt() + "dp and leaves " +
                (ROW_SHARED_DP - drawn).toInt() + "dp for the title",
            drawn <= HALF_ROW_DP,
        )
        // And the repair stated as a size rather than as a word, which is the figure anyone asking
        // "was it worth shortening" actually wants.
        val retired: Float = widthDp("Needs attention on the server", META_STRONG_DP) + GLYPH_DP
        assertEquals(
            "the title column got back fourteen characters of `meta` and eleven of `rowTitle`",
            84f,
            retired - drawn,
            0.01f,
        )
    }

    /**
     * And nothing is lost by it, because the one reading with no column to run out of keeps it all.
     *
     * "on the server" is the part that says a human has to act in DroppedNeedle's web interface and
     * that there is nothing on this phone to tap. A listener is told; a reader is told by the
     * screen's banner and by the screen's name.
     */
    @Test
    fun `the parked state still says where, spoken`() {
        assertEquals(
            "Needs attention on the server",
            NeedlerAlbumBadge.NeedsAttention.accessibleLabel(),
        )
        assertTrue(
            "the drawn label is the spoken one's opening, so the two are one vocabulary",
            NeedlerAlbumBadge.NeedsAttention.accessibleLabel()
                .startsWith(NeedlerAlbumBadge.NeedsAttention.label()),
        )
    }

    /**
     * Every badge against the same slot, not just the one that was reported.
     *
     * A badge names the row it sits beside, so it must not take more of the row than it leaves. The
     * widest after the repair are the two destination phrases at 124dp, and both are the vocabulary
     * doing its job rather than prose: REQUIREMENTS.md "Vocabulary" fixes **Pull to device** as the
     * action, and "The Wi-Fi-only setting is mislabelled" is why the hold names Wi-Fi.
     */
    @Test
    fun `no badge label takes more of a phone row than it leaves`() {
        ALL_BADGES.forEach { badge ->
            val label: Float = labelWidthDp(badge)
            assertTrue(
                badge.label() + " is " + label.toInt() + "dp of a " + ROW_SHARED_DP.toInt() +
                    "dp row, leaving " + (ROW_SHARED_DP - label).toInt() + "dp for the title",
                label <= HALF_ROW_DP,
            )
        }
    }

    /**
     * A ceiling the vocabulary sets rather than a number someone picked.
     *
     * The longest label a badge may draw is the longest the three states and the one verb can
     * produce, and that is "Pulling to device". Anything longer is prose arriving in a label column
     * again, which is what this whole section is about.
     */
    @Test
    fun `nothing is wider than the longest phrase the vocabulary forces`() {
        val widest: NeedlerAlbumBadge = ALL_BADGES.maxBy { labelWidthDp(it) }

        assertEquals(
            widthDp("Pulling to device", META_STRONG_DP) + GLYPH_DP,
            labelWidthDp(widest),
            0.01f,
        )
    }

    /**
     * The one thing this component draws that is still past half a row, measured and left alone.
     *
     * [NeedlerAlbumBadge.PullingToDevice] draws its percentage as a second run beside the label, so
     * a pin downloading at 62% is 148dp - 15dp past half - and `ArtistScreen` puts exactly that in
     * the trailing column of a 390dp discography row. It is not shortened here because the only way
     * to shorten it is to break "Pull to device" apart, and the badge's cost is bounded instead: a
     * percentage may add the 6dp `Arrangement.spacedBy` and three digits, and no more.
     *
     * Recorded as a measurement so the next person reads a figure rather than rediscovering it on a
     * device, which is how this file's other numbers were found.
     */
    @Test
    fun `a percentage costs the title no more than its own digits`() {
        val bare: Float = drawnWidthDp(NeedlerAlbumBadge.PullingToDevice())
        val counting: Float = drawnWidthDp(NeedlerAlbumBadge.PullingToDevice(percent = 62))

        assertEquals(24f, counting - bare, 0.01f)
        assertTrue(
            "the widest thing a badge draws is " + counting.toInt() + "dp",
            counting <= 150f,
        )
    }

    /**
     * What the row got back, in the units the user reads: characters of the album title.
     *
     * `Death's Dateless Night` is the title the device drew as `Death's Dateles...` on all 35 of
     * its rows. `rowTitle` is 16sp/600 and averages 7.4dp a character over the committed renders -
     * `Black Classical Music` is 21 characters in 157dp and `Back Street Crawler` 19 in 141dp in
     * `screenshots/pulls-every-state-phone.png` - and the row gives the title two lines. So the
     * assertion is that the first line now holds the wrap point, which is the difference between a
     * title read and a title recognised.
     */
    @Test
    fun `the recovered column fits the title the device could not show`() {
        val slot: Float = ROW_SHARED_DP - drawnWidthDp(NeedlerAlbumBadge.NeedsAttention)

        assertTrue("the title column is " + slot.toInt() + "dp", slot >= 150f)
        assertTrue(
            "the first line holds " + (slot / ROW_TITLE_DP).toInt() + " characters",
            widthDp("Death's Dateless", ROW_TITLE_DP) <= slot,
        )
    }

    private companion object {
        val COLOURS = NeedlerDarkColors

        // ------------------------------------------------------------ the width model
        //
        // Arithmetic over the design tokens, because the alternative is rendering and what is being
        // asserted is a size rather than a pixel. The model is `PullsRowCompositionTest`'s, which
        // calibrated it against `screenshots/pulls-every-state-phone.png` at 2px to the dp and lands
        // within 4dp of every row in that image: `Waiting` is seven characters in 65dp there and 64dp
        // here, `Partly delivered` sixteen in 92dp and 96dp, and the sentence this change removed 192dp
        // and 196dp. 4dp is accuracy enough for a defect that was out by a factor of three.
        //
        // It is restated here rather than shared because `:core:design` cannot see `:feature:pulls`,
        // and because the row this file is measuring against is `NeedlerAlbumRow`, which lives here.

        /**
         * What a 390dp phone row has left for the text column and the trailing one together.
         *
         * 390 phone, less `spacing.phoneGutter` at 20dp on both sides, less `sizes.artworkRow` at
         * 56dp, less two 14dp gaps from `NeedlerAlbumRow`'s own `Arrangement.spacedBy`.
         */
        const val ROW_SHARED_DP: Float = 266f

        /** A badge that takes more than this takes more of the row than it leaves. */
        const val HALF_ROW_DP: Float = ROW_SHARED_DP / 2f

        /** 13sp `metaStrong`, averaged over the committed renders. */
        const val META_STRONG_DP: Float = 6.0f

        /** 16sp/600 `rowTitle`, averaged over the same renders. */
        const val ROW_TITLE_DP: Float = 7.4f

        /** `NeedlerStateBadge`'s own `Arrangement.spacedBy(6.dp)`, between every run it draws. */
        const val ICON_GAP_DP: Float = 6f

        /** A 16dp glyph and that gap, on the badges that have one. */
        const val GLYPH_DP: Float = 16f + ICON_GAP_DP

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

        /** How wide the badge's **word** draws, glyph and its gap included. */
        fun labelWidthDp(badge: NeedlerAlbumBadge): Float =
            glyphWidthDp(badge) + widthDp(badge.label(), META_STRONG_DP)

        /**
         * How wide the badge draws in full, which for two of them is the word and then a figure.
         *
         * `NeedlerStateBadge` puts the percentage in a second `Text` with the same 6dp
         * `Arrangement.spacedBy` the glyph uses, so it costs the gap as well as its digits.
         */
        fun drawnWidthDp(badge: NeedlerAlbumBadge): Float {
            val percent: Int? = when (badge) {
                is NeedlerAlbumBadge.Pulling -> badge.percent
                is NeedlerAlbumBadge.PullingToDevice -> badge.percent
                else -> null
            }
            val figure: Float = percent
                ?.let { ICON_GAP_DP + widthDp(it.toString() + "%", META_STRONG_DP) }
                ?: 0f
            return labelWidthDp(badge) + figure
        }

        /**
         * The glyph rule from `NeedlerStateBadge`, restated for the same reason [badgeTint] is.
         *
         * The four end states draw no icon: nothing in the icon set says "stopped" without also
         * saying "error", and the label is the whole signal there.
         */
        fun glyphWidthDp(badge: NeedlerAlbumBadge): Float = when (badge) {
            NeedlerAlbumBadge.NoSource,
            NeedlerAlbumBadge.Failed,
            NeedlerAlbumBadge.PartlyDelivered,
            NeedlerAlbumBadge.Cancelled -> 0f
            else -> GLYPH_DP
        }

        fun widthDp(text: String, perCharacter: Float): Float = text.length * perCharacter

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

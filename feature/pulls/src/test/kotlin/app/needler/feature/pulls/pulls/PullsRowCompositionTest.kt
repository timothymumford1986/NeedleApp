@file:OptIn(ExperimentalTime::class)

package app.needler.feature.pulls.pulls

import app.needler.core.design.component.NeedlerAlbumBadge
import app.needler.core.design.component.label
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.model.PullProgress
import app.needler.core.domain.model.PullState
import app.needler.core.domain.model.PullStatus
import app.needler.feature.pulls.common.PullsFormat
import app.needler.feature.pulls.SamplePulls
import kotlin.time.ExperimentalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a row *composes*, as opposed to what `PullsFormat` *returns*.
 *
 * `PullsFormatTest` pins every string the formatter produces and this file deliberately asserts on
 * none of them. The device defects it covers were all in the composition instead — the screen
 * putting a correct string in the wrong place, three times now, or drawing a correct component for
 * a state that has nothing for it to show. A formatter test cannot catch any of those, which is why
 * they reached a device.
 *
 * The third of those is why this file also measures. Two different sentences were poured into the
 * same one-line slot, one after the other, and each fix was tested by asserting the *string* that
 * had been complained about was gone — which the next string passed. So the subtitle is now
 * asserted against the width of the column it is drawn in; see [slotWidthDp].
 */
class PullsRowCompositionTest {

    private val now = SamplePulls.renderedAt

    // ---- the row does not say the same thing twice --------------------------

    /**
     * The row a device showed 35 of.
     *
     * The badge read "Needs attention on the server" and the subtitle read "Paul Kelly · a source
     * needs picking on th…" — the same sentence, wrapped and cut, under a title where the artist
     * belongs.
     */
    @Test
    fun `a pull parked for a source pick says so once, in the badge`() {
        val pull: Pull = SamplePulls.awaitingSourceReview
        val subtitle: String = rowSubtitle(pull, now)

        assertEquals("Kate Bush", subtitle)
        assertTrue(
            "the state is still named, in the column that has room for it",
            badgeFor(pull).label().contains("attention"),
        )
    }

    /**
     * And every other state keeps its explanation, because the badge does not contain it.
     *
     * This is the half of the fix that is easy to get wrong in the other direction. "Put only the
     * artist in the subtitle" would fix one row by emptying nine: `Pulling` does not say "12 of 19
     * files", `Searching` does not say which source is being asked, `Waiting` does not say it is an
     * administrator being waited on, and `Failed` does not say no source was found.
     */
    @Test
    fun `every other state keeps the explanation its badge does not carry`() {
        val kept: Map<Pull, String> = mapOf(
            SamplePulls.downloading to "12 of 19 files",
            SamplePulls.searching to "asking slskd",
            SamplePulls.pendingApproval to "waiting for an administrator",
            SamplePulls.failed to "no source found",
            SamplePulls.partial to "7 of 10 files",
        )
        kept.forEach { (pull, detail) ->
            val subtitle: String = rowSubtitle(pull, now)
            assertTrue(
                "the row lost '" + detail + "': " + subtitle,
                subtitle.contains(detail),
            )
        }
    }

    /**
     * Asserted over every state rather than over the one that was broken.
     *
     * The contract is [badgeSaysTheDetail]'s and it runs both ways: where the badge already carries
     * the explanation the line must not repeat it, and everywhere else the line must still have it.
     * Checking only the first half would be satisfied by a screen that printed nothing anywhere.
     *
     * Deliberately not "the line never contains the badge's words". That reads as a stronger test
     * and is a wrong one: `Waiting` over "waiting for an administrator" shares a word while saying
     * strictly more, and suppressing it would cost the user the only part that matters - *who* is
     * being waited on.
     */
    @Test
    fun `each state puts its explanation in exactly one of the badge and the line`() {
        val states: List<Pull> = SamplePulls.everyState + queued + processing
        assertEquals(
            "a fixture per state, or this proves nothing",
            PullState.entries.toSet(),
            states.map { it.state }.toSet(),
        )

        states.forEach { pull ->
            val detail: String? = PullsFormat.stateDetail(pull)
            if (detail == null) return@forEach

            val subtitle: String = rowSubtitle(pull, now)
            if (badgeSaysTheDetail(pull)) {
                assertFalse(
                    "the badge already says this, so the line must not: " + subtitle,
                    subtitle.contains(detail),
                )
            } else {
                assertTrue(
                    "the badge does not say this, so the line must: " + subtitle,
                    subtitle.contains(detail),
                )
            }
        }
    }

    /**
     * And only one state answers true, so the fix stayed narrow.
     *
     * A regression guard on the scope rather than on the behaviour. If a later change makes a second
     * state suppress its explanation, that is a product decision someone should have to make
     * deliberately, not one that arrives as a side effect of rewording a badge.
     */
    @Test
    fun `only the source-review state has its explanation taken by the badge`() {
        val suppressed: List<PullState> = (SamplePulls.everyState + queued + processing)
            .filter { badgeSaysTheDetail(it) }
            .map { it.state }

        assertEquals(listOf(PullState.AWAITING_SOURCE_REVIEW), suppressed)
    }

    /** A row with no artist and a suppressed state still has to be a line, not an empty string. */
    @Test
    fun `a parked pull with no artist still reads as something`() {
        val anonymous: Pull = SamplePulls.awaitingSourceReview.copy(artistName = "")
        // Nothing is left but the state, so the state comes back rather than the row drawing a
        // blank line under its title. Suppressing the only thing a line has to say is not a fix.
        assertEquals("a source needs picking on the server", rowSubtitle(anonymous, now))
    }

    // ---- the line fits the slot it is drawn in ------------------------------

    /**
     * The row the device drew the second time, with the state suppressed and prose in its place.
     *
     * `Paul Kelly · Try MP3 320-plus kbps, the...` — a different sentence in the same slot, which is
     * why the rule is now about the slot. The line is the artist; the sentence is on the album
     * screen, in the request sheet and in this row's own spoken description, all three of which have
     * room to finish it.
     */
    @Test
    fun `a server's quality advice is not poured into the artist's line`() {
        val pull: Pull = SamplePulls.awaitingWithQualityAdvice

        assertEquals("Paul Kelly", rowSubtitle(pull, now))
        assertTrue(
            "nothing is lost: the full sentence is still what a screen reader hears",
            PullsFormat.spokenRow(pull, now).contains(SamplePulls.QUALITY_ADVICE),
        )
    }

    /** And it is the slot, not the state: the same sentence goes on a finished row too. */
    @Test
    fun `the advice is left off the wider row as well`() {
        assertEquals("Mk.gee · today", rowSubtitle(SamplePulls.landedWithQualityAdvice, now))
    }

    /**
     * A label is not prose, and the pack writes labels.
     *
     * Screen 06 draws `FLAC` and `MP3 320` on its finished rows and this field is where they arrive,
     * so a rule that took the whole field off the row would have deleted the pack's own content to
     * fix a string the pack never wrote.
     */
    @Test
    fun `a quality label short enough to read stays on the row`() {
        assertEquals("Mk.gee · FLAC · today", rowSubtitle(SamplePulls.landedToday, now))
        assertEquals("Cleo Sol · MP3 320 · yesterday", rowSubtitle(SamplePulls.landedYesterday, now))
    }

    /**
     * Nothing the row adds to the artist is wider than the column it is drawn in.
     *
     * The property both device defects broke, asserted over every state rather than over the two
     * strings that happened to break it. A part wider than the whole column cannot be read at any
     * length: it wraps into the next line, pushes the rest out and ends in an ellipsis mid-word.
     *
     * The artist itself is exempt, and deliberately. It is what the slot is *for*, it is bounded by
     * the data rather than by this file, and the one fixture that overflows on its own —
     * `Floating Points, Pharoah Sanders & The London Symphony Orchestra` — is visible doing exactly
     * that in `screenshots/pulls-every-state-phone.png`. Nothing this function composes can shorten
     * a name, and dropping the state explanation to make room would cost the row the only thing it
     * says. See [slotWidthDp] for where the figures come from.
     */
    @Test
    fun `nothing the row adds to the artist is wider than the slot`() {
        val states: List<Pull> = SamplePulls.everyState + queued + processing +
            SamplePulls.awaitingWithQualityAdvice + SamplePulls.landedWithQualityAdvice

        states.forEach { pull ->
            val slot: Float = slotWidthDp(pull)
            addedParts(pull, rowSubtitle(pull, now)).forEach { part ->
                assertTrue(
                    "'" + part + "' is " + widthDp(part).toInt() + "dp in a " +
                        slot.toInt() + "dp column: " + rowSubtitle(pull, now),
                    widthDp(part) <= slot,
                )
            }
        }
    }

    /** And on the device's own row the whole line fits, artist and all. */
    @Test
    fun `the parked row's line fits the narrowest slot on the screen`() {
        val pull: Pull = SamplePulls.awaitingWithQualityAdvice
        val slot: Float = slotWidthDp(pull)

        // This read `slot < 80f` and was a characterisation of the defect, not a requirement: the
        // badge said "Needs attention on the server" and left the text column 74dp, so every parked
        // title ellipsised and at 200% text the column collapsed to nothing. Shortening the drawn
        // label to "Needs attention" - the words the pull widget had used all along - returned 81dp.
        // Asserting a floor rather than the old ceiling keeps the guard pointing at the fault: a
        // badge that grows back into a sentence fails here again.
        assertTrue("the badge leaves only " + slot.toInt() + "dp for the title", slot > 140f)
        assertTrue(
            "the line is " + widthDp(rowSubtitle(pull, now)).toInt() + "dp in " + slot.toInt() + "dp",
            widthDp(rowSubtitle(pull, now)) <= slot,
        )
    }

    // ---- unknown is not zero, on the artwork too ---------------------------

    /**
     * The grey ring a device drew over 35 covers that were not downloading.
     *
     * `PullsFormat`'s first rule is "Unknown is not zero"; the bar and the percentage already obeyed
     * it and the artwork did not, because the row passed `fraction ?: 0f`. Asserted through the
     * progress model rather than by rendering, so the rule is pinned at the one place the row reads
     * it from.
     */
    @Test
    fun `a pull the server has reported no progress for has no ring to draw`() {
        listOf(
            SamplePulls.awaitingSourceReview,
            SamplePulls.searching,
            SamplePulls.pendingApproval,
        ).forEach { pull ->
            assertNull(
                "a ring at zero is a progress indicator for a state with no progress",
                pull.progress.fraction,
            )
        }

        // And the one that does report progress still has a figure to draw.
        assertEquals(0.62f, SamplePulls.downloading.progress.fraction!!, 0.001f)
    }

    // ---- an inert control is worse than an absent one ----------------------

    /**
     * **Clear done** with nothing done.
     *
     * It is not drawn at all, which is stronger than drawn-and-disabled: `PullsUiState.canClearDone`
     * already records the argument — "a control that is always present and usually inert teaches
     * the user to ignore it, and this one has a real effect worth noticing". A device with 35 active
     * pulls and none finished should therefore see no such control rather than a dead one.
     */
    @Test
    fun `clear done is absent rather than inert when nothing has finished`() {
        val allActive = PullsUiState(loading = false, pulls = SamplePulls.manyAwaitingReview)
        assertTrue("the fixture has nothing finished", allActive.completed.isEmpty())
        assertFalse(allActive.canClearDone)
        assertFalse("so the header draws nothing there", allActive.showClearDone)

        val withFinished = allActive.copy(pulls = allActive.pulls + SamplePulls.landedToday)
        assertTrue(withFinished.showClearDone)
    }

    /** And it is hidden on the other two lanes, where there is no endpoint that could clear one. */
    @Test
    fun `clear done belongs to the queue alone`() {
        val finished = PullsUiState(loading = false, pulls = SamplePulls.pack)
        assertTrue(finished.showClearDone)
        assertFalse(finished.copy(lane = PullsLane.HISTORY).showClearDone)
        assertFalse(finished.copy(lane = PullsLane.WANTED).showClearDone)
    }

    /**
     * What the composed line adds beyond the artist, split as the row joins it.
     *
     * The leading element is dropped only when it is the artist, so a row the mirror could not name
     * an artist for is measured on everything it draws.
     */
    private fun addedParts(pull: Pull, subtitle: String): List<String> {
        val parts: List<String> = subtitle.split(" · ")
        return if (parts.firstOrNull() == pull.artistName) parts.drop(1) else parts
    }

    /**
     * How wide the text column is on a phone, for the row this pull draws.
     *
     * Arithmetic, because the alternative is rendering — and the figures are not invented. They are
     * the design tokens the two row shapes compose with, checked against the committed renders in
     * `screenshots/`:
     *
     * ```
     * 390dp  phone width, as every phone render in this module is taken
     * -40dp  NeedlerTheme.spacing.phoneGutter, both sides
     * -56dp  NeedlerTheme.sizes.artworkRow
     * -28dp  Arrangement.spacedBy(14.dp), twice, in NeedlerAlbumRow and ActivePullRow alike
     * ```
     *
     * which leaves 266dp to divide between the text column and the trailing one. The trailing one
     * takes what it needs, because it is unweighted and the text column carries the `weight(1f)`.
     *
     * `screenshots/pulls-every-state-phone.png` is where the per-character figures were measured, at
     * 2px to the dp: `Cleo Sol · MP3 320 · yesterday` is 30 characters in 175dp and
     * `Floating Points, Pharoah Sanders` is 32 in 190dp, so 13sp `meta` averages about 5.8dp a
     * character; the `Partly delivered` badge is 16 characters in 92dp and `Waiting` seven in 65dp,
     * so 13sp `metaStrong` is about 6.0dp with a 22dp glyph where the badge has one. The pills are
     * all within 68dp. The model lands within 4dp of every row in that image, which is the accuracy
     * this needs: the defects it exists to catch were out by a factor of three.
     */
    private fun slotWidthDp(pull: Pull): Float {
        val percent: Int? = PullsFormat.percent(pull.progress.fraction)
        // ActivePullRow draws the percentage *instead of* the badge when there is one to draw;
        // FinishedPullRow always draws the badge.
        val stated: Float = if (pull.bucket == PullBucket.ACTIVE && percent != null) {
            widthDp(percent.toString() + "%", META_STRONG_DP)
        } else {
            badgeWidthDp(pull)
        }
        val pill: Float = if (pull.canCancel || pull.canRetry || pull.state == PullState.COMPLETED) {
            PILL_DP
        } else {
            0f
        }
        return ROW_TEXT_AND_TRAILING_DP - maxOf(stated, pill)
    }

    private fun badgeWidthDp(pull: Pull): Float {
        val badge: NeedlerAlbumBadge = badgeFor(pull)
        val glyph: Float = when (badge) {
            NeedlerAlbumBadge.Waiting,
            NeedlerAlbumBadge.Searching,
            NeedlerAlbumBadge.NeedsAttention,
            NeedlerAlbumBadge.Ready,
            is NeedlerAlbumBadge.Pulling -> GLYPH_DP
            else -> 0f
        }
        return glyph + widthDp(badge.label(), META_STRONG_DP)
    }

    private fun widthDp(text: String, perCharacter: Float = META_DP): Float =
        text.length * perCharacter

    private val queued: Pull = SamplePulls.downloading.copy(
        status = PullStatus.QUEUED,
        progress = PullProgress.Unknown,
    )

    private val processing: Pull = SamplePulls.downloading.copy(
        status = PullStatus.PROCESSING,
    )

    private companion object {

        /** 390 - 2x20 gutter - 56 artwork - 2x14 gap. See [slotWidthDp]. */
        const val ROW_TEXT_AND_TRAILING_DP: Float = 266f

        /** 13sp `meta`, averaged over the committed renders. */
        const val META_DP: Float = 5.8f

        /** 13sp `metaStrong`, the badges and the percentage. */
        const val META_STRONG_DP: Float = 6.0f

        /** A 16dp glyph and the 6dp `Arrangement.spacedBy` beside it, on the badges that have one. */
        const val GLYPH_DP: Float = 22f

        /** Cancel, Play and Retry, the widest of the three. */
        const val PILL_DP: Float = 68f
    }
}

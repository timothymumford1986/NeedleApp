@file:OptIn(ExperimentalTime::class)

package app.needler.feature.pulls.pulls

import app.needler.core.design.component.label
import app.needler.core.domain.model.Pull
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
 * none of them. The three device defects it covers were all in the composition instead — the screen
 * putting a correct string in the wrong place, twice, or drawing a correct component for a state
 * that has nothing for it to show. A formatter test cannot catch any of those, which is why they
 * reached a device.
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

    private val queued: Pull = SamplePulls.downloading.copy(
        status = PullStatus.QUEUED,
        progress = PullProgress.Unknown,
    )

    private val processing: Pull = SamplePulls.downloading.copy(
        status = PullStatus.PROCESSING,
    )
}

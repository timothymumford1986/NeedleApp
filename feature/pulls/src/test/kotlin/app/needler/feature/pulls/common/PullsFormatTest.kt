@file:OptIn(ExperimentalTime::class)

package app.needler.feature.pulls.common

import app.needler.core.domain.model.PullFailureReason
import app.needler.core.domain.model.PullProgress
import app.needler.core.domain.model.PullStatus
import app.needler.core.domain.model.RequestHistoryEntry
import app.needler.core.domain.model.RequestOutcome
import app.needler.core.domain.model.RequestTarget
import app.needler.core.domain.model.WantedGap
import app.needler.core.domain.model.WantedRetry
import app.needler.core.domain.model.WantedWatch
import app.needler.core.domain.model.WantedWatchState
import app.needler.feature.pulls.SamplePulls
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The formatter, against the strings screen 06 actually draws.
 *
 * Every expectation here is either a line from `design/html/06-Pulls.html` or a
 * rule REQUIREMENTS.md states. The ones worth having are the negative cases:
 * unknown must not become zero, and a percentage the server never sent must not
 * be invented.
 */
class PullsFormatTest {

    private val now = SamplePulls.renderedAt

    // ---- the pack's own rows ------------------------------------------------

    @Test
    fun `the downloading row reads as the pack draws it`() {
        assertEquals(
            "Yussef Dayes · 12 of 19 files · FLAC",
            PullsFormat.subtitle(SamplePulls.downloading, now),
        )
        assertEquals(62, PullsFormat.percent(SamplePulls.downloading.progress.fraction))
    }

    @Test
    fun `the searching row names the source being asked`() {
        assertEquals("Kisum · asking slskd", PullsFormat.subtitle(SamplePulls.searching, now))
    }

    @Test
    fun `a landed pull reads as quality and when`() {
        assertEquals(
            "Mk.gee · FLAC · today",
            PullsFormat.subtitle(SamplePulls.landedToday, now),
        )
        assertEquals(
            "Cleo Sol · MP3 320 · yesterday",
            PullsFormat.subtitle(SamplePulls.landedYesterday, now),
        )
    }

    @Test
    fun `a failed pull reads as its reason, not as a quality policy`() {
        assertEquals(
            "Paul Kossoff · no source found · 3 days ago",
            PullsFormat.subtitle(SamplePulls.failed, now),
        )
    }

    // ---- unknown is not zero ------------------------------------------------

    @Test
    fun `a pull the server has reported nothing about has no percentage and no detail`() {
        val silent = SamplePulls.searching.copy(progress = PullProgress.Unknown)
        assertNull(PullsFormat.percent(silent.progress.fraction))
        assertNull(PullsFormat.progressDetail(silent))
    }

    @Test
    fun `byte counters are used when the server counts bytes but not files`() {
        val byBytes = SamplePulls.downloading.copy(
            progress = PullProgress(
                downloadedBytes = 401_604_608L,
                totalSizeBytes = 647_749_632L,
            ),
        )
        assertEquals("383 MB of 618 MB", PullsFormat.progressDetail(byBytes))
    }

    @Test
    fun `a total of zero bytes is not a progress figure`() {
        val empty = SamplePulls.downloading.copy(
            progress = PullProgress(downloadedBytes = 0L, totalSizeBytes = 0L),
        )
        assertNull(PullsFormat.progressDetail(empty))
    }

    // ---- individual rules ---------------------------------------------------

    @Test
    fun `the header counts only what is in progress`() {
        assertEquals("2 in progress", PullsFormat.headerLine(activeCount = 2, totalCount = 5))
        assertEquals("1 in progress", PullsFormat.headerLine(activeCount = 1, totalCount = 1))
        assertEquals("Nothing in progress", PullsFormat.headerLine(activeCount = 0, totalCount = 4))
        assertEquals("", PullsFormat.headerLine(activeCount = 0, totalCount = 0))
    }

    @Test
    fun `relative days are coarse and never negative`() {
        assertEquals("today", PullsFormat.relativeDay(now, now))
        assertEquals("today", PullsFormat.relativeDay(now + Duration.parse("2h"), now))
        assertEquals("today", PullsFormat.relativeDay(now - Duration.parse("23h"), now))
        assertEquals("yesterday", PullsFormat.relativeDay(now - Duration.parse("25h"), now))
        assertEquals("2 days ago", PullsFormat.relativeDay(now - Duration.parse("49h"), now))
        assertEquals("3 days ago", PullsFormat.relativeDay(now - Duration.parse("80h"), now))
        assertEquals("over a month ago", PullsFormat.relativeDay(now - Duration.parse("900h"), now))
        assertNull(PullsFormat.relativeDay(null, now))
    }

    /**
     * Punch-list item 31: the column used to read "today", "yesterday", "3d
     * ago" down three rows, mixing whole words with an abbreviation. Every
     * answer either way along the scale is now words.
     */
    @Test
    fun `dates and countdowns are written in one register`() {
        assertEquals("due now", PullsFormat.countdown(now, now))
        assertEquals("due now", PullsFormat.countdown(now - Duration.parse("5m"), now))
        assertEquals("in 1 minute", PullsFormat.countdown(now + Duration.parse("40s"), now))
        assertEquals("in 25 minutes", PullsFormat.countdown(now + Duration.parse("25m"), now))
        assertEquals("in 1 hour", PullsFormat.countdown(now + Duration.parse("90m"), now))
        assertEquals("in 4 hours", PullsFormat.countdown(now + Duration.parse("4h"), now))
        assertEquals("in 1 day", PullsFormat.countdown(now + Duration.parse("30h"), now))
        assertEquals("in over a month", PullsFormat.countdown(now + Duration.parse("900h"), now))
        assertNull(PullsFormat.countdown(null, now))

        // The two formatters answer the same question in opposite directions, so
        // a reader seeing both in one column sees one kind of phrase.
        assertFalse(
            PullsFormat.relativeDay(now - Duration.parse("80h"), now)!!.contains("d ago"),
        )
        assertFalse(PullsFormat.countdown(now + Duration.parse("4h"), now)!!.endsWith("h"))
    }

    @Test
    fun `a failure with no reason falls back to whatever the server said`() {
        val vague = SamplePulls.failed.copy(
            failureReason = PullFailureReason.UNKNOWN,
            error = "slskd returned 0 candidates after 3 attempts",
        )
        assertEquals("slskd returned 0 candidates after 3 attempts", PullsFormat.failureReason(vague))

        val silent = SamplePulls.failed.copy(failureReason = null, error = null)
        assertEquals("this pull failed", PullsFormat.failureReason(silent))
    }

    @Test
    fun `processing says what it is doing rather than sitting at a hundred percent`() {
        val importing = SamplePulls.downloading.copy(status = PullStatus.PROCESSING)
        assertEquals("importing", PullsFormat.stateDetail(importing))
    }

    // ---- what TalkBack hears ------------------------------------------------

    @Test
    fun `the spoken row rejoins what the layout split apart`() {
        assertEquals(
            "Black Classical Music, Yussef Dayes, pulling, 62 percent, 12 of 19 files, FLAC",
            PullsFormat.spokenRow(SamplePulls.downloading, now),
        )
    }

    @Test
    fun `a cancelled pull is not said twice`() {
        val cancelled = SamplePulls.failed.copy(
            status = PullStatus.CANCELLED,
            failureReason = null,
        )
        // Drawn: the `Cancelled` badge in the trailing column is the word, so
        // the line carries only the artist and the date. Spoken: the state is
        // read out in its own place and the detail parts add nothing.
        assertNull(PullsFormat.stateDetail(cancelled))
        assertEquals("Paul Kossoff · 3 days ago", PullsFormat.subtitle(cancelled, now))
        assertEquals(
            "Back Street Crawler, Paul Kossoff, cancelled, 3 days ago",
            PullsFormat.spokenRow(cancelled, now),
        )
    }

    /**
     * The failed bucket's three outcomes are three different badges.
     *
     * Punch-list item 30: the failed row carried a **Retry** pill and no status
     * label while every other row named its state in the same column. The badge
     * names the state and the subtitle keeps the explanation, which is why
     * neither of these rows repeats itself.
     */
    @Test
    fun `a part-delivered pull says how far it got, not that it is incomplete`() {
        // `Partly delivered` is the badge beside this; "some tracks did not
        // arrive" here as well would be the same sentence twice.
        assertEquals("7 of 10 files", PullsFormat.stateDetail(SamplePulls.partial))
        assertEquals(
            "Radiohead · 7 of 10 files · 4 days ago",
            PullsFormat.subtitle(SamplePulls.partial, now),
        )
    }

    @Test
    fun `a part-delivered pull with no counters still says something`() {
        val uncounted = SamplePulls.partial.copy(progress = PullProgress.Unknown)
        assertEquals("some tracks did not arrive", PullsFormat.stateDetail(uncounted))
    }

    @Test
    fun `the spoken row says a failure as a state and a reason`() {
        assertEquals(
            "Back Street Crawler, Paul Kossoff, failed, no source found, 3 days ago",
            PullsFormat.spokenRow(SamplePulls.failed, now),
        )
    }

    // ---- a pull the mirror cannot name --------------------------------------
    //
    // The case that made this screen unusable on a real device: 34 of 35 rows
    // had an empty `albumTitle`, so the queue drew 34 blank title lines and 34
    // Cancel buttons whose content description was "Cancel the pull of " with
    // nothing after it. Every fixture supplied a title, so nothing here failed.

    @Test
    fun `an empty title becomes a name rather than a hole`() {
        assertEquals("Untitled album", PullsFormat.albumTitle(SamplePulls.untitled))
        assertEquals("Fever", PullsFormat.albumTitle(SamplePulls.searching))
    }

    @Test
    fun `a title of nothing but spaces is treated as no title`() {
        // `isEmpty` would let this through and draw an invisible title. The guard
        // is on `isBlank` for exactly this row.
        assertEquals("Untitled album", PullsFormat.albumTitle(SamplePulls.titleAllSpaces))
    }

    @Test
    fun `an action label never trails off after its preposition`() {
        // "Cancel the pull of " + title was the bug, verbatim.
        assertEquals("this album", PullsFormat.albumPhrase(SamplePulls.untitled))
        assertEquals("this album", PullsFormat.albumPhrase(SamplePulls.titleAllSpaces))
        assertEquals("Fever", PullsFormat.albumPhrase(SamplePulls.searching))

        val label: String = "Cancel the pull of " + PullsFormat.albumPhrase(SamplePulls.untitled)
        assertEquals("Cancel the pull of this album", label)
    }

    @Test
    fun `every label the screen builds names something, for every fixture`() {
        // The sweep the screen never had. A label that ends in "of", "of " or the
        // word "Play" alone names no target, which REQUIREMENTS.md
        // "Accessibility" does not accept from a control's content description.
        val everyPull = SamplePulls.everyState + SamplePulls.withMissingTitles
        for (pull in everyPull) {
            val phrase: String = PullsFormat.albumPhrase(pull)
            val drawn: String = PullsFormat.albumTitle(pull)
            assertTrue("blank phrase for " + pull.releaseGroupMbid.value, phrase.isNotBlank())
            assertTrue("blank title for " + pull.releaseGroupMbid.value, drawn.isNotBlank())
            for (label in listOf(
                "Cancel the pull of " + phrase,
                "Retry the pull of " + phrase,
                "Play " + phrase,
            )) {
                assertEquals("untrimmed label: '" + label + "'", label.trim(), label)
                assertFalse("dangling label: '" + label + "'", label.endsWith("of"))
            }
        }
    }

    @Test
    fun `the spoken row still starts with a name when there is no title`() {
        assertEquals(
            "Untitled album, Kelly Lee Owens, pulling, 35 percent, 4 of 11 files",
            PullsFormat.spokenRow(SamplePulls.untitled, now),
        )
        // A blank first element used to leave the phrase starting ", Kelly...".
        assertFalse(PullsFormat.spokenRow(SamplePulls.untitled, now).startsWith(","))
    }

    @Test
    fun `a pull with neither a title nor an artist still reads as a sentence`() {
        assertEquals("no source found · 2 days ago", PullsFormat.subtitle(SamplePulls.anonymous, now))
        assertEquals(
            "Untitled album, failed, no source found, 2 days ago",
            PullsFormat.spokenRow(SamplePulls.anonymous, now),
        )
    }

    @Test
    fun `a missing title does not change the subtitle, which is the other half of the row`() {
        // The split `stateDetail` makes — the badge says the state, the subtitle
        // explains it — was never the problem, and this pins that: the subtitle
        // of an unnamed pull is exactly the subtitle of a named one.
        assertEquals(
            "Kelly Lee Owens · 4 of 11 files",
            PullsFormat.subtitle(SamplePulls.untitled, now),
        )
        assertEquals(
            "Actress · FLAC · today",
            PullsFormat.subtitle(SamplePulls.titleAllSpaces, now),
        )
    }

    // ---- the same guard on the two lanes that are not a Pull ----------------
    //
    // `GET /api/v1/requests/history` and `GET /api/v1/requests/wanted` carry
    // `album_title` with the same nullability and the same habit of being blank,
    // and their formatters were added after the ones above — so they had none of
    // the coverage that proved the queue's guard works. These are the three
    // shapes the device report produced: absent, whitespace-only, and a row with
    // neither a title nor an artist.

    @Test
    fun `a history row with no title still names something`() {
        assertEquals("Untitled album", PullsFormat.historyTitle(historyEntry(albumTitle = "")))
        assertEquals("Untitled album", PullsFormat.historyTitle(historyEntry(albumTitle = "   ")))
        assertEquals("this album", PullsFormat.historyPhrase(historyEntry(albumTitle = "")))
        assertEquals("this album", PullsFormat.historyPhrase(historyEntry(albumTitle = "  ")))
        assertEquals("Spiderland", PullsFormat.historyTitle(historyEntry(albumTitle = "Spiderland")))
    }

    @Test
    fun `a history row with neither a title nor an artist still reads as a sentence`() {
        val entry = historyEntry(albumTitle = "", artistName = "")

        assertEquals("arrived · today", PullsFormat.historySubtitle(entry, now))
        assertEquals("Untitled album, arrived, today", PullsFormat.spokenHistoryRow(entry, now))
    }

    @Test
    fun `a track request with a blank album does not say 'from' and stop`() {
        // The dangling-label shape, one lane over: the subtitle's "from …" is
        // built by concatenation exactly as "Cancel the pull of …" was.
        val entry = historyEntry(
            albumTitle = "   ",
            trackTitle = "Nightswimming",
            target = RequestTarget.TRACK,
        )

        assertEquals("Nightswimming", PullsFormat.historyTitle(entry))
        assertFalse(PullsFormat.historySubtitle(entry, now).contains("from"))
    }

    @Test
    fun `a wanted row and a retrying row both name something`() {
        assertEquals(
            "Untitled album, not found yet, checks again in 4 hours",
            PullsFormat.spokenWantedRow(wantedWatch(albumTitle = ""), now),
        )
        assertEquals(
            "Untitled album, being retried",
            PullsFormat.spokenWantedRetryRow(wantedRetry(albumTitle = "  "), now),
        )
    }

    // ---- fixtures for the two read-through lanes ----------------------------

    private fun historyEntry(
        albumTitle: String,
        artistName: String = "Slint",
        trackTitle: String? = null,
        target: RequestTarget = RequestTarget.ALBUM,
    ): RequestHistoryEntry = RequestHistoryEntry(
        releaseGroupMbid = SamplePulls.mbid("history"),
        albumTitle = albumTitle,
        artistName = artistName,
        status = RequestOutcome.COMPLETED,
        statusToken = "completed",
        target = target,
        trackTitle = trackTitle,
        requestedAt = now - Duration.parse("2h"),
        completedAt = now - Duration.parse("1h"),
    )

    /** A live watch: the gap refines the state, and the next check is a countdown rather than an age. */
    private fun wantedWatch(albumTitle: String): WantedWatch = WantedWatch(
        releaseGroupMbid = SamplePulls.mbid("wanted"),
        albumTitle = albumTitle,
        artistName = "",
        gap = WantedGap.MISSING,
        state = WantedWatchState.WATCHING,
        stateToken = "watching",
        checkCount = 3,
        nextCheckAt = now + Duration.parse("4h"),
    )

    private fun wantedRetry(albumTitle: String): WantedRetry = WantedRetry(
        releaseGroupMbid = SamplePulls.mbid("retrying"),
        albumTitle = albumTitle,
        artistName = "",
    )
}

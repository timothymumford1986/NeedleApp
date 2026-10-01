@file:OptIn(ExperimentalTime::class)

package app.needler.feature.pulls

import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullActivitySummary
import app.needler.core.domain.model.PullFailureReason
import app.needler.core.domain.model.PullProgress
import app.needler.core.domain.model.PullStatus
import app.needler.core.domain.model.PullTaskId
import app.needler.core.domain.model.ReleaseGroupMbid
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The design pack's own queue, typed in.
 *
 * Screen 06 draws five pulls — two in `NOW`, three in `EARLIER` — and using
 * those exact titles, artists, figures and states is what makes a rendered PNG
 * directly comparable with the pack. A screenshot of invented data can only be
 * checked for "does it look plausible", which is not a test.
 *
 * Two deliberate differences from the HTML, both forced by the domain model:
 *
 *  * **The format labels are `quality_snapshot_summary`.** The pack writes
 *    "FLAC" and "MP3 320" on the finished rows. [Pull] carries no audio format —
 *    it is a *task*, and the format belongs to the album the task produces — so
 *    the fixture puts those strings where the model has room for them, which is
 *    the quality policy the server recorded against the request. See
 *    `PullsFormat.detailParts`.
 *  * **Artwork is an `ArtworkRef` with no resolver behind it**, so every square
 *    renders as the placeholder tint. That is a consequence of artwork URLs not
 *    being resolvable in any module below `:app`, and it is the same in
 *    `:feature:library`'s renders.
 */
internal object SamplePulls {

    /** The instant every relative figure on these screens is measured from. */
    val renderedAt: Instant = Instant.parse("2026-09-21T18:00:00Z")

    fun mbid(slug: String): ReleaseGroupMbid = ReleaseGroupMbid("rg-$slug")

    // ---- NOW ----------------------------------------------------------------

    /**
     * `Black Classical Music`, 62%, 12 of 19 files.
     *
     * The pack's headline row: the ring, the bar and the percentage all present
     * because the server has reported `progress_percent`.
     */
    val downloading: Pull = Pull(
        releaseGroupMbid = mbid("black-classical-music"),
        albumTitle = "Black Classical Music",
        artistName = "Yussef Dayes",
        taskId = PullTaskId("dl-4711"),
        status = PullStatus.DOWNLOADING,
        searchJobId = "sj-4711",
        candidateIndex = 0,
        progress = PullProgress(
            percent = 62,
            filesCompleted = 12,
            filesTotal = 19,
            downloadedBytes = 401_604_608L,
            totalSizeBytes = 647_749_632L,
        ),
        source = "slskd",
        qualityPolicySummary = "FLAC",
        createdAt = renderedAt - Duration.parse("9m"),
        updatedAt = renderedAt - Duration.parse("4s"),
    )

    /**
     * `Fever`, still being searched for.
     *
     * `queued` with **no** `search_job_id`, which is the client-side derivation
     * REQUIREMENTS.md spells out: the server is looking for sources, so the row
     * shows an empty ring and a `Searching` badge rather than a fake 0%.
     */
    val searching: Pull = Pull(
        releaseGroupMbid = mbid("fever"),
        albumTitle = "Fever",
        artistName = "Kisum",
        taskId = PullTaskId("dl-4712"),
        status = PullStatus.QUEUED,
        searchJobId = null,
        candidateIndex = null,
        source = "slskd",
        createdAt = renderedAt - Duration.parse("2m"),
        updatedAt = renderedAt - Duration.parse("2m"),
    )

    /**
     * A role-`user` request an administrator has not approved.
     *
     * Not drawn on screen 06, but REQUIREMENTS.md requires it — "a pull may sit
     * waiting with no visible progress … render the server's returned status;
     * show Waiting" — so it is here to be rendered and asserted on.
     */
    val pendingApproval: Pull = Pull(
        releaseGroupMbid = mbid("promises"),
        albumTitle = "Promises",
        artistName = "Floating Points, Pharoah Sanders & The London Symphony Orchestra",
        taskId = null,
        status = PullStatus.QUEUED,
        awaitingApproval = true,
        createdAt = renderedAt - Duration.parse("21m"),
        updatedAt = renderedAt - Duration.parse("21m"),
    )

    /**
     * A pull parked for a manual source pick.
     *
     * `queued` with a `search_job_id` but no `candidate_index`. Resolving it
     * needs the web UI in v1, which is why the row reports rather than offers.
     */
    val awaitingSourceReview: Pull = Pull(
        releaseGroupMbid = mbid("hounds-of-love"),
        albumTitle = "Hounds of Love",
        artistName = "Kate Bush",
        taskId = PullTaskId("dl-4708"),
        status = PullStatus.QUEUED,
        searchJobId = "sj-4708",
        candidateIndex = null,
        source = "nzbget",
        createdAt = renderedAt - Duration.parse("40m"),
        updatedAt = renderedAt - Duration.parse("35m"),
    )

    // ---- EARLIER ------------------------------------------------------------

    /** `Two Star & The Dream Police`, landed today. */
    val landedToday: Pull = Pull(
        releaseGroupMbid = mbid("two-star"),
        albumTitle = "Two Star & The Dream Police",
        artistName = "Mk.gee",
        taskId = PullTaskId("dl-4699"),
        status = PullStatus.COMPLETED,
        searchJobId = "sj-4699",
        candidateIndex = 0,
        progress = PullProgress(percent = 100, filesCompleted = 12, filesTotal = 12),
        source = "slskd",
        qualityPolicySummary = "FLAC",
        createdAt = renderedAt - Duration.parse("4h"),
        updatedAt = renderedAt - Duration.parse("3h"),
    )

    /** `Heaven`, landed yesterday, as MP3 320. */
    val landedYesterday: Pull = Pull(
        releaseGroupMbid = mbid("heaven"),
        albumTitle = "Heaven",
        artistName = "Cleo Sol",
        taskId = PullTaskId("dl-4688"),
        status = PullStatus.COMPLETED,
        searchJobId = "sj-4688",
        candidateIndex = 1,
        progress = PullProgress(percent = 100, filesCompleted = 10, filesTotal = 10),
        source = "slskd",
        qualityPolicySummary = "MP3 320",
        createdAt = renderedAt - Duration.parse("32h"),
        updatedAt = renderedAt - Duration.parse("30h"),
    )

    /** `Back Street Crawler`: nothing usable on any configured source. */
    val failed: Pull = Pull(
        releaseGroupMbid = mbid("back-street-crawler"),
        albumTitle = "Back Street Crawler",
        artistName = "Paul Kossoff",
        taskId = PullTaskId("dl-4602"),
        status = PullStatus.FAILED,
        searchJobId = "sj-4602",
        candidateIndex = 0,
        failureReason = PullFailureReason.NO_SOURCE_FOUND,
        source = "slskd",
        createdAt = renderedAt - Duration.parse("80h"),
        updatedAt = renderedAt - Duration.parse("78h"),
    )

    /**
     * A part-delivered pull.
     *
     * REQUIREMENTS.md, "Partial content is a normal state": some tracks arrived
     * and some did not, the album is in the library and plays, and the row
     * offers a retry.
     */
    val partial: Pull = Pull(
        releaseGroupMbid = mbid("in-rainbows"),
        albumTitle = "In Rainbows",
        artistName = "Radiohead",
        taskId = PullTaskId("dl-4590"),
        status = PullStatus.PARTIAL,
        searchJobId = "sj-4590",
        candidateIndex = 0,
        progress = PullProgress(filesCompleted = 7, filesTotal = 10),
        source = "slskd",
        createdAt = renderedAt - Duration.parse("100h"),
        updatedAt = renderedAt - Duration.parse("96h"),
    )

    /**
     * A pull somebody stopped.
     *
     * The third of the failed bucket's three outcomes, and the one that had no
     * fixture at all — which is why the row drew "cancelled" in its subtitle and
     * nothing in the trailing column for as long as it did. `failureReason` is
     * left null on purpose: the server sends none when the stop came from a
     * client, so the row has the state and nothing else to say.
     */
    val cancelled: Pull = Pull(
        releaseGroupMbid = mbid("sound-ancestors"),
        albumTitle = "Sound Ancestors",
        artistName = "Madlib",
        taskId = PullTaskId("dl-4575"),
        status = PullStatus.CANCELLED,
        searchJobId = "sj-4575",
        candidateIndex = 0,
        source = "slskd",
        createdAt = renderedAt - Duration.parse("130h"),
        updatedAt = renderedAt - Duration.parse("128h"),
    )

    // ---- pulls the server could not name ------------------------------------

    /**
     * A pull with **no album title at all**: the case that made this screen
     * unusable on a real device.
     *
     * Not an invented edge. 34 of 35 pulls on a live install had an empty
     * `albumTitle`, because `GET /api/v1/downloads` often omits `album_title` and
     * the `album` mirror had been filled with a blank placeholder rather than the
     * title the response actually carried. Every fixture above supplies a title,
     * which is exactly why the screen passed review while being unusable — so
     * this one, and [titleAllSpaces] below, are the fixtures that make the empty
     * case testable rather than theoretical.
     *
     * Active and cancellable on purpose: the **Cancel** pill is where the bug
     * was provable, because its content description is built by concatenation and
     * read as "Cancel the pull of " with nothing after it.
     */
    val untitled: Pull = Pull(
        releaseGroupMbid = mbid("untitled"),
        albumTitle = "",
        artistName = "Kelly Lee Owens",
        taskId = PullTaskId("dl-4720"),
        status = PullStatus.DOWNLOADING,
        searchJobId = "sj-4720",
        candidateIndex = 0,
        progress = PullProgress(percent = 35, filesCompleted = 4, filesTotal = 11),
        source = "slskd",
        createdAt = renderedAt - Duration.parse("6m"),
        updatedAt = renderedAt - Duration.parse("10s"),
    )

    /**
     * A title that is whitespace and nothing else.
     *
     * A separate fixture from [untitled] because `isEmpty` and `isBlank` are
     * different questions and a guard written against the first one lets this
     * through — it renders as a row with an invisible title, which is the same
     * defect with none of the evidence. Finished rather than active, so the
     * fallback is exercised on the other row shape and on its **Play** label too.
     */
    val titleAllSpaces: Pull = Pull(
        releaseGroupMbid = mbid("all-spaces"),
        albumTitle = "   ",
        artistName = "Actress",
        taskId = PullTaskId("dl-4715"),
        status = PullStatus.COMPLETED,
        searchJobId = "sj-4715",
        candidateIndex = 0,
        progress = PullProgress(percent = 100, filesCompleted = 9, filesTotal = 9),
        source = "slskd",
        qualityPolicySummary = "FLAC",
        createdAt = renderedAt - Duration.parse("6h"),
        updatedAt = renderedAt - Duration.parse("5h"),
    )

    /**
     * Neither a title nor an artist, and a **Retry** to label.
     *
     * The floor of the screen: nothing the mirror knows, nothing the failed row
     * can say but its reason. If the subtitle and the spoken phrase are still
     * sentences here, they are sentences everywhere.
     */
    val anonymous: Pull = Pull(
        releaseGroupMbid = mbid("anonymous"),
        albumTitle = "",
        artistName = "",
        taskId = PullTaskId("dl-4601"),
        status = PullStatus.FAILED,
        searchJobId = "sj-4601",
        candidateIndex = 0,
        failureReason = PullFailureReason.NO_SOURCE_FOUND,
        source = "slskd",
        createdAt = renderedAt - Duration.parse("50h"),
        updatedAt = renderedAt - Duration.parse("49h"),
    )

    // ---- assemblies ---------------------------------------------------------

    /** Screen 06 exactly: two active, two landed, one failed, newest first. */
    val pack: List<Pull> = listOf(
        downloading,
        searching,
        landedToday,
        landedYesterday,
        failed,
    )

    /** The pack plus the four states it does not happen to draw. */
    val everyState: List<Pull> = listOf(
        downloading,
        searching,
        pendingApproval,
        awaitingSourceReview,
        landedToday,
        landedYesterday,
        failed,
        partial,
        cancelled,
    )

    /**
     * The queue as a real device showed it: mostly pulls with no name.
     *
     * One named row is kept deliberately. The device report's own proof was that
     * "Cancel the pull of Songs for Every Condition" read correctly once while
     * "Cancel the pull of " read seven times, so a fixture with a single good row
     * beside the broken ones is what a render can be judged against — all-blank
     * data would look consistent and prove nothing.
     */
    val withMissingTitles: List<Pull> = listOf(
        untitled,
        downloading,
        titleAllSpaces,
        anonymous,
    )

    /** Two in flight, one held for review, one failure the user has not seen. */
    val summary: PullActivitySummary = PullActivitySummary(
        revision = 8_421L,
        activeCount = 2,
        heldCount = 1,
        failedCount = 1,
    )
}

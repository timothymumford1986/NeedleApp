@file:OptIn(ExperimentalTime::class)

package app.needler.feature.pulls

import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullActivitySummary
import app.needler.core.domain.model.PullFailureReason
import app.needler.core.domain.model.PullProgress
import app.needler.core.domain.model.PullStatus
import app.needler.core.domain.model.PullTaskId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestHistoryEntry
import app.needler.core.domain.model.RequestHistoryPage
import app.needler.core.domain.model.RequestOutcome
import app.needler.core.domain.model.RequestTarget
import app.needler.core.domain.model.WantedGap
import app.needler.core.domain.model.WantedList
import app.needler.core.domain.model.WantedRetry
import app.needler.core.domain.model.WantedWatch
import app.needler.core.domain.model.WantedWatchState
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

    // ---- the quality policy, as prose ---------------------------------------

    /**
     * `quality_snapshot_summary` as a server writes it when it writes advice rather than a label.
     *
     * Every fixture above carries the pack's own `FLAC` and `MP3 320`, which are four and seven
     * characters and fit anywhere. Nothing in the API says they have to be: the field is free text
     * from the server's quality policy. The device report caught the first twenty-two characters of
     * one, `Try MP3 320-plus kbps, the` — the row cut it there — and the rest of this string is
     * written to that shape rather than copied, since the device never showed the rest. What is
     * load-bearing is the length, and the length is the server's to choose.
     */
    const val QUALITY_ADVICE: String = "Try MP3 320-plus kbps, the server will keep looking for FLAC"

    /**
     * The row the device drew: parked for a source pick, with [QUALITY_ADVICE] attached.
     *
     * Its album and artist are the ones in the device report, so what a test asserts and what was
     * reported are the same row. [awaitingSourceReview] is deliberately left without the
     * summary - it is the pack's own parked row, and a fixture set where every row carries a server
     * sentence would stop proving that a short label still draws.
     */
    val awaitingWithQualityAdvice: Pull = awaitingSourceReview.copy(
        releaseGroupMbid = mbid("dateless-night"),
        albumTitle = "Death's Dateless Night",
        artistName = "Paul Kelly",
        qualityPolicySummary = QUALITY_ADVICE,
    )

    /** The same sentence on a row the badge leaves room on: the slot is wider, the string is not. */
    val landedWithQualityAdvice: Pull = landedToday.copy(
        releaseGroupMbid = mbid("two-star-advice"),
        qualityPolicySummary = QUALITY_ADVICE,
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

    // Declared before the fixture that reads them: a Kotlin object initialises its properties
    // in source order, so a list built from a `val` declared below it is built from nulls.
    private val AWAITING_TITLES: List<String> = listOf(
        "Death's Dateless Night",
        "Kei to the City",
        "THE FIRST TIME",
        "Folie à Deux",
        "No Stress",
        "This Is a Coping Mechanism",
    )

    private val AWAITING_ARTISTS: List<String> = listOf(
        "Paul Kelly",
        "Kei",
        "The Nerves",
        "Fall Out Boy",
        "Laurent Wolf",
        "Hot Mulligan",
    )

    /**
     * The queue a device actually had: a screenful of pulls, every one parked for a manual source
     * pick and none of them downloading.
     *
     * The fixture that was missing when two defects shipped. [pack] and [everyState] both mix
     * states, so a fault that only shows when *every* row is the same state — 35 identical progress
     * rings at zero, 35 subtitles repeating the badge beside them — looked like one odd row in a
     * varied list rather than like a screen. Twelve is enough to overflow a 390x844 phone, which is
     * the other thing this is for: it is the only fixture that puts a row at the bottom edge of the
     * viewport, where the chrome is.
     */
    val manyAwaitingReview: List<Pull> = List(12) { index ->
        awaitingSourceReview.copy(
            releaseGroupMbid = mbid("awaiting-" + index),
            albumTitle = AWAITING_TITLES[index % AWAITING_TITLES.size],
            artistName = AWAITING_ARTISTS[index % AWAITING_ARTISTS.size],
            taskId = PullTaskId("dl-48" + (10 + index)),
            searchJobId = "sj-48" + (10 + index),
            createdAt = renderedAt - Duration.parse((index + 1).toString() + "h"),
            updatedAt = renderedAt - Duration.parse((index + 1).toString() + "h"),
        )
    }

    /** Two in flight, one held for review, one failure the user has not seen. */
    val summary: PullActivitySummary = PullActivitySummary(
        revision = 8_421L,
        activeCount = 2,
        heldCount = 1,
        failedCount = 1,
    )

    // ---- the history lane ---------------------------------------------------
    //
    // The design pack draws no history list, so there is nothing to type in from it. These are
    // built to cover `RequestOutcome` exhaustively instead, which is the property that matters:
    // `historyBadge` has a branch per outcome and two of them deliberately draw no badge, and a
    // fixture set that happened to miss `REJECTED` would let that hole through unseen.

    fun historyEntry(
        slug: String,
        albumTitle: String,
        artistName: String,
        status: RequestOutcome,
        statusToken: String = status.serverToken ?: slug,
        target: RequestTarget = RequestTarget.ALBUM,
        trackTitle: String? = null,
        requestedAgo: String = "26h",
        completedAgo: String? = "24h",
        inLibrary: Boolean = status == RequestOutcome.COMPLETED,
        reviewedByName: String? = null,
    ): RequestHistoryEntry = RequestHistoryEntry(
        releaseGroupMbid = mbid(slug),
        albumTitle = albumTitle,
        artistName = artistName,
        status = status,
        statusToken = statusToken,
        target = target,
        trackTitle = trackTitle,
        requestedAt = renderedAt - Duration.parse(requestedAgo),
        completedAt = completedAgo?.let { renderedAt - Duration.parse(it) },
        inLibrary = inLibrary,
        reviewedByName = reviewedByName,
        reviewedAt = reviewedByName?.let { renderedAt - Duration.parse("20h") },
    )

    /** `arrived`, and in the library — the common case and the one with nothing to explain. */
    val historyArrived: RequestHistoryEntry = historyEntry(
        slug = "spiderland",
        albumTitle = "Spiderland",
        artistName = "Slint",
        status = RequestOutcome.COMPLETED,
    )

    /** An administrator said no, and is named, which is the point of the row. */
    val historyDeclined: RequestHistoryEntry = historyEntry(
        slug = "hounds-history",
        albumTitle = "Hounds of Love",
        artistName = "Kate Bush",
        status = RequestOutcome.REJECTED,
        completedAgo = null,
        reviewedByName = "Ada",
    )

    val historyFailed: RequestHistoryEntry = historyEntry(
        slug = "crawler-history",
        albumTitle = "Back Street Crawler",
        artistName = "Paul Kossoff",
        status = RequestOutcome.FAILED,
    )

    val historyCancelled: RequestHistoryEntry = historyEntry(
        slug = "ancestors-history",
        albumTitle = "Sound Ancestors",
        artistName = "Madlib",
        status = RequestOutcome.CANCELLED,
    )

    val historyWaitingForAdmin: RequestHistoryEntry = historyEntry(
        slug = "promises-history",
        albumTitle = "Promises",
        artistName = "Floating Points",
        status = RequestOutcome.AWAITING_APPROVAL,
        completedAgo = null,
        requestedAgo = "3h",
    )

    val historyPending: RequestHistoryEntry = historyEntry(
        slug = "fever-history",
        albumTitle = "Fever",
        artistName = "Kisum",
        status = RequestOutcome.PENDING,
        completedAgo = null,
        requestedAgo = "40m",
    )

    val historyInProgress: RequestHistoryEntry = historyEntry(
        slug = "bcm-history",
        albumTitle = "Black Classical Music",
        artistName = "Yussef Dayes",
        status = RequestOutcome.IN_PROGRESS,
        completedAgo = null,
        requestedAgo = "9m",
    )

    /**
     * A track request, which names the *track* and puts the album in the line as "from …".
     *
     * `inLibrary` is true on a *failed* request on purpose: that is the one combination where
     * `PullsFormat.historyDetailParts` adds "in your library", because on an arrived request it
     * would say what "arrived" already said.
     */
    val historyTrack: RequestHistoryEntry = historyEntry(
        slug = "automatic-history",
        albumTitle = "Automatic for the People",
        artistName = "R.E.M.",
        status = RequestOutcome.FAILED,
        target = RequestTarget.TRACK,
        trackTitle = "Nightswimming",
        inLibrary = true,
    )

    /**
     * A status token this client does not model.
     *
     * REQUIREMENTS.md "Placing a request" requires the server's own status to be rendered rather
     * than inferred, so this row prints `requires review` and draws no badge — picking the nearest
     * badge for an unknown token is the inference that rule forbids.
     */
    val historyUnknownState: RequestHistoryEntry = historyEntry(
        slug = "unknown-history",
        albumTitle = "Sun Ra Arkestra Live",
        artistName = "Sun Ra",
        status = RequestOutcome.OTHER,
        statusToken = "requires_review",
        completedAgo = null,
    )

    /** A history row the server could not name, so the lane's own fallback is exercised. */
    val historyUntitled: RequestHistoryEntry = historyEntry(
        slug = "untitled-history",
        albumTitle = "",
        artistName = "Kelly Lee Owens",
        status = RequestOutcome.COMPLETED,
    )

    /** Every outcome `RequestOutcome` has, in the order a newest-first server would send them. */
    val historyEveryOutcome: List<RequestHistoryEntry> = listOf(
        historyInProgress,
        historyPending,
        historyWaitingForAdmin,
        historyArrived,
        historyUntitled,
        historyDeclined,
        historyUnknownState,
        historyFailed,
        historyTrack,
        historyCancelled,
    )

    /**
     * One page of history, with the totals this endpoint really does report.
     *
     * `total` and `totalPages` are both set, because this is the one request list that sends them —
     * which is what makes "Load page 2 of 7" implementable here and not on `GET /api/v1/downloads`.
     */
    fun historyPage(
        entries: List<RequestHistoryEntry>,
        page: Int = 1,
        totalPages: Int = 7,
        total: Int = 46,
        pageSize: Int = 20,
    ): RequestHistoryPage = RequestHistoryPage(
        entries = entries,
        page = page,
        pageSize = pageSize,
        total = total,
        totalPages = totalPages,
    )

    // ---- the wanted lane ----------------------------------------------------

    fun wantedWatch(
        slug: String,
        albumTitle: String,
        artistName: String,
        gap: WantedGap,
        state: WantedWatchState,
        stateToken: String = state.name.lowercase(),
        checkCount: Int = 4,
        newCandidateCount: Int = 0,
        nextCheckIn: String? = "4h",
        lastCheckedAgo: String? = "20h",
    ): WantedWatch = WantedWatch(
        releaseGroupMbid = mbid(slug),
        albumTitle = albumTitle,
        artistName = artistName,
        gap = gap,
        state = state,
        stateToken = stateToken,
        checkCount = checkCount,
        newCandidateCount = newCandidateCount,
        lastCheckedAt = lastCheckedAgo?.let { renderedAt - Duration.parse(it) },
        nextCheckAt = nextCheckIn?.let { renderedAt + Duration.parse(it) },
        createdAt = renderedAt - Duration.parse("300h"),
    )

    /** `WATCHING` with `MISSING`: "not found yet", and the next check is a countdown. */
    val watchNotFoundYet: WantedWatch = wantedWatch(
        slug = "watch-missing",
        albumTitle = "The Dreaming",
        artistName = "Kate Bush",
        gap = WantedGap.MISSING,
        state = WantedWatchState.WATCHING,
        newCandidateCount = 2,
    )

    /** `WATCHING` with `PARTIAL`: "only part of it found". */
    val watchPartial: WantedWatch = wantedWatch(
        slug = "watch-partial",
        albumTitle = "In Rainbows",
        artistName = "Radiohead",
        gap = WantedGap.PARTIAL,
        state = WantedWatchState.WATCHING,
        nextCheckIn = "35m",
    )

    /** `WATCHING` with a `kind` this client does not model: "still being looked for". */
    val watchOtherGap: WantedWatch = wantedWatch(
        slug = "watch-other-gap",
        albumTitle = "Bitches Brew",
        artistName = "Miles Davis",
        gap = WantedGap.OTHER,
        state = WantedWatchState.WATCHING,
        nextCheckIn = "3d",
    )

    /** `DORMANT`: still recorded, checked rarely. A countdown is still honest here. */
    val watchDormant: WantedWatch = wantedWatch(
        slug = "watch-dormant",
        albumTitle = "Rock Bottom",
        artistName = "Robert Wyatt",
        gap = WantedGap.MISSING,
        state = WantedWatchState.DORMANT,
        checkCount = 31,
        nextCheckIn = "26d",
    )

    /** `STOPPED`: no countdown, because no check is coming. The effort is reported instead. */
    val watchStopped: WantedWatch = wantedWatch(
        slug = "watch-stopped",
        albumTitle = "Spring Heel Jack",
        artistName = "Spring Heel Jack",
        gap = WantedGap.MISSING,
        state = WantedWatchState.STOPPED,
        checkCount = 48,
        nextCheckIn = null,
    )

    /** `FULFILLED`: it arrived, and the watch is kept as a record. */
    val watchFulfilled: WantedWatch = wantedWatch(
        slug = "watch-fulfilled",
        albumTitle = "Heaven",
        artistName = "Cleo Sol",
        gap = WantedGap.MISSING,
        state = WantedWatchState.FULFILLED,
        checkCount = 12,
        nextCheckIn = null,
    )

    /** A `state` token this client does not model, printed tidied rather than guessed at. */
    val watchUnknownState: WantedWatch = wantedWatch(
        slug = "watch-other",
        albumTitle = "Discreet Music",
        artistName = "Brian Eno",
        gap = WantedGap.MISSING,
        state = WantedWatchState.OTHER,
        stateToken = "deferred_by_admin",
        nextCheckIn = null,
    )

    /** A watch the server could not name, so the fallback is exercised on this lane too. */
    val watchUntitled: WantedWatch = wantedWatch(
        slug = "watch-untitled",
        albumTitle = "   ",
        artistName = "",
        gap = WantedGap.MISSING,
        state = WantedWatchState.WATCHING,
        nextCheckIn = "90m",
    )

    /** Every `WantedWatchState`, plus both refinements of the live one and both name fallbacks. */
    val everyWatchState: List<WantedWatch> = listOf(
        watchNotFoundYet,
        watchPartial,
        watchOtherGap,
        watchUntitled,
        watchDormant,
        watchUnknownState,
        watchFulfilled,
        watchStopped,
    )

    val retryingWithBudget: WantedRetry = WantedRetry(
        releaseGroupMbid = mbid("retry-budget"),
        albumTitle = "Two Star & The Dream Police",
        artistName = "Mk.gee",
        retryCount = 3,
        maxAttempts = 5,
        nextRetryAt = renderedAt + Duration.parse("12m"),
    )

    /** No budget and no schedule: the floor of `PullsFormat.wantedRetryDetailParts`. */
    val retryingUnbudgeted: WantedRetry = WantedRetry(
        releaseGroupMbid = mbid("retry-plain"),
        albumTitle = "Sienna",
        artistName = "Oscar Jerome",
    )

    val retrying: List<WantedRetry> = listOf(retryingWithBudget, retryingUnbudgeted)

    /** The whole of one `GET /api/v1/requests/wanted` answer. There is no second page. */
    val wantedList: WantedList = WantedList(watches = everyWatchState, retrying = retrying)
}

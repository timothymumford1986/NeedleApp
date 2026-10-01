// kotlinx.datetime.Instant is a typealias for kotlin.time.Instant from
// kotlinx-datetime 0.7.0 onwards, and `Pull` declares its timestamps with the
// kotlinx name. Writing kotlin.time.Instant here is therefore the same type,
// spelled the way the rest of the app spells it; the opt-in marker is what the
// underlying class still carries.
@file:OptIn(ExperimentalTime::class)

package app.needler.feature.pulls.common

import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.model.PullFailureReason
import app.needler.core.domain.model.PullState
import app.needler.core.domain.model.RequestHistoryEntry
import app.needler.core.domain.model.RequestOutcome
import app.needler.core.domain.model.RequestTarget
import app.needler.core.domain.model.WantedGap
import app.needler.core.domain.model.WantedRetry
import app.needler.core.domain.model.WantedWatch
import app.needler.core.domain.model.WantedWatchState
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Every string the Pulls screen renders from a number, a state or an instant.
 *
 * These are pure functions for the same reason `LibraryFormat` is: formatting is
 * where a screen quietly lies, and a pure function is the part of a screen that
 * can be tested without rendering anything. The rules it follows are the ones
 * that module already established, because two screens that round bytes
 * differently would be a bug the user sees.
 *
 *  * **Unknown is not zero.** A pull with no byte counters, no file counters and
 *    no `progress_percent` draws no bar and no percentage rather than a
 *    convincing `0%`. `GET /api/v1/downloads` reports all three
 *    inconsistently — REQUIREMENTS.md, "Queue screen requirements", asks for all
 *    three precisely because they do not always agree — so "no figure" is a real
 *    and common answer.
 *  * **Spoken text is separate from drawn text.** `62%` is right on screen and
 *    reads badly aloud, and "no source found" as a bare subtitle fragment is not
 *    a sentence. [spokenRow] builds the phrase a screen reader should hear.
 *
 * ## Why this duplicates a little of `LibraryFormat`
 *
 * `LibraryFormat` is `internal` to `:feature:library`, and a feature module
 * cannot see another feature module. The byte formatter below is therefore a
 * deliberate second copy, kept to the same rules so the two screens agree. The
 * fix is a shared formatting home — a `:core:ui` or an addition to
 * `:core:design` — and it is in the handover notes rather than done here,
 * because `:core:design` is being worked on by someone else.
 */
internal object PullsFormat {

    // ---- names --------------------------------------------------------------

    /**
     * The album's name as the row **draws** it, never blank.
     *
     * [Pull.albumTitle] is blank whenever the `album` mirror has no name for the
     * release group, and on a real device that was 34 of 35 rows: a queue of
     * empty title lines with only the subtitle to tell them apart. The screen
     * cannot fix the missing data — that is
     * `DefaultPullRepository.refreshPulls`' job, and it now takes the title from
     * whichever lane supplied it — but it must not draw a hole while the data is
     * absent, because a row with no name is a row the user cannot act on.
     *
     * "Untitled album" rather than a dash or a blank: it occupies the title slot
     * as a name, which is what the layout, the ellipsis and TalkBack all expect
     * to find there. The same guard already exists one layer down for
     * notifications — `NotificationComposer.text` uses
     * `ifBlank { "Your pull has arrived" }` — and this is the Pulls screen's
     * version of it. The alternative, promoting the artist's name into the title
     * slot, was rejected: the subtitle already starts with the artist, so the row
     * would say one name twice and still not name the album.
     */
    fun albumTitle(pull: Pull): String = albumTitle(pull.albumTitle)

    /**
     * The same guard, for the lanes that are not a [Pull].
     *
     * `GET /api/v1/requests/history` and `GET /api/v1/requests/wanted` carry `album_title` with
     * exactly the same nullability and exactly the same habit of being blank — the DTOs say so in
     * their own KDoc — so they need exactly the same guard. Taking a `String` rather than adding a
     * third and fourth overload per type is what makes "route every render site through here"
     * something a reviewer can check by grepping for the constant, instead of a rule that holds for
     * one type and quietly does not for the next two.
     */
    fun albumTitle(title: String): String = title.ifBlank { UNTITLED_ALBUM }

    /**
     * How an action label **names** the album inside a sentence: `Cancel the pull
     * of Fever`, or `Cancel the pull of this album` when there is no name.
     *
     * Separate from [albumTitle] because a title slot and a sentence want
     * different words for the same absence, and this module already splits drawn
     * text from spoken text everywhere else. A label built by concatenation is
     * where the original bug became visible: the cancel description was
     * `"Cancel the pull of "` plus the title, and with no title it named no
     * target at all. REQUIREMENTS.md "Accessibility" — "every control carries a
     * content description" — is not satisfied by one that trails off.
     *
     * "this album" keeps the sentence whole and honest. "Untitled album" would
     * also parse, but "Cancel the pull of Untitled album" claims the album is
     * called that, and it is not; it has no name here yet.
     */
    fun albumPhrase(pull: Pull): String = albumPhrase(pull.albumTitle)

    /** The sentence-shaped guard for the history and wanted lanes. See [albumTitle]. */
    fun albumPhrase(title: String): String = title.ifBlank { THIS_ALBUM }

    // ---- the header ---------------------------------------------------------

    /**
     * The line under the screen title: `2 in progress`, as screen 06 draws it.
     *
     * When nothing is in flight but the list is not empty the line says so
     * rather than disappearing, because a screen whose subtitle vanishes reads
     * as though it failed to load. An empty screen has its own empty state and
     * needs no subtitle at all.
     */
    fun headerLine(activeCount: Int, totalCount: Int): String = when {
        // The pack's own wording, bare number and all: "2 in progress". It sits
        // directly under a title that already says what is being counted.
        activeCount > 0 -> activeCount.toString() + " in progress"
        totalCount > 0 -> "Nothing in progress"
        else -> ""
    }

    // ---- progress -----------------------------------------------------------

    /** `62` from `0.62f`. Null when the server has reported nothing to round. */
    fun percent(fraction: Float?): Int? {
        val value: Float = fraction ?: return null
        return (value.coerceIn(0f, 1f) * 100f).roundToInt()
    }

    /**
     * `12 of 19 files`, or `380 MB of 610 MB` when the server counts bytes but
     * not files.
     *
     * REQUIREMENTS.md asks the queue screen to show progress "from
     * `progress_percent`, `downloaded_bytes` against `total_size_bytes`, and
     * `files_completed` of `files_total`". All three are consulted — the bar and
     * the percentage come from [app.needler.core.domain.model.PullProgress.fraction],
     * which already prefers the reported percentage and falls back to bytes and
     * then to files — but only the most legible of them is *drawn* as text. A
     * 56dp row on a 390dp phone cannot carry three counters and an artist name,
     * and a row that wrapped to four lines would push the next pull off screen.
     */
    fun progressDetail(pull: Pull): String? {
        val progress = pull.progress
        if (progress.filesTotal > 0) {
            return progress.filesCompleted.toString() + " of " +
                plural(progress.filesTotal.toLong(), "file")
        }
        val done: String? = bytes(progress.downloadedBytes)
        val total: String? = bytes(progress.totalSizeBytes?.takeIf { it > 0L })
        if (done != null && total != null) return done + " of " + total
        return null
    }

    /**
     * `42 GB`. Binary units under decimal names, which is what Android's own
     * storage screens use and therefore what a user comparing the two expects.
     */
    fun bytes(byteCount: Long?): String? {
        val value: Long = byteCount ?: return null
        if (value < 0L) return null
        if (value < UNIT) return value.toString() + " B"
        var scaled: Double = value.toDouble()
        var unitIndex = -1
        while (scaled >= UNIT && unitIndex < UNITS.lastIndex) {
            scaled /= UNIT
            unitIndex++
        }
        val unit: String = UNITS[unitIndex]
        return if (scaled < 10.0) {
            val tenths: Int = (scaled * 10.0).roundToInt()
            (tenths / 10).toString() + "." + (tenths % 10) + " " + unit
        } else {
            scaled.roundToInt().toString() + " " + unit
        }
    }

    // ---- time ---------------------------------------------------------------

    /**
     * `today`, `yesterday`, `3d ago` — the tail of the subtitle on a finished
     * pull, as screen 06 draws it.
     *
     * Measured in elapsed time, not in calendar days. A calendar-correct answer
     * needs a time zone, and the only zone available to a formatter is the
     * device's current one, which would make this function impure and its tests
     * dependent on where they run. The cost is that something finished at
     * 23:50 still reads "today" at 00:10; the benefit is a function that cannot
     * disagree with itself between two renders. If the distinction ever matters
     * the zone becomes a parameter, which is a one-line change.
     */
    fun relativeDay(then: Instant?, now: Instant): String? {
        val at: Instant = then ?: return null
        val seconds: Long = (now - at).inWholeSeconds
        return when {
            seconds < 0L -> "today"
            seconds < 86_400L -> "today"
            seconds < 172_800L -> "yesterday"
            seconds < 2_592_000L -> (seconds / 86_400L).toString() + "d ago"
            else -> "over a month ago"
        }
    }

    // ---- states -------------------------------------------------------------

    /**
     * What the middle of the subtitle says for this pull's state.
     *
     * Screen 06 puts the *explanation* here and keeps the badge for the state's
     * one-word name: "Kisum · asking slskd" beside a "Searching" badge,
     * "Paul Kossoff · no source found" beside no badge at all. That split is
     * what stops the row saying the same thing twice, and it is why a failed
     * pull's reason lives in this line rather than in the trailing column.
     */
    fun stateDetail(pull: Pull): String? = when (pull.state) {
        PullState.PENDING_APPROVAL -> "waiting for an administrator"

        // The pack's own wording, and the source is worth naming: "asking
        // slskd" tells a self-hoster which of their configured sources is being
        // tried, which is the difference between waiting and investigating.
        PullState.SEARCHING -> pull.source?.let { "asking " + it } ?: "looking for a source"

        // REQUIREMENTS.md: manual source selection is a web-UI job in v1, so
        // this says where to go rather than offering an action that does not
        // exist here.
        PullState.AWAITING_SOURCE_REVIEW -> "a source needs picking on the server"

        PullState.QUEUED -> "waiting for a download slot"

        PullState.DOWNLOADING -> progressDetail(pull) ?: pull.source

        // Not cancellable, and the row says why it is busy rather than looking
        // stuck at 100%.
        PullState.PROCESSING -> "importing"

        // Nothing: a finished pull's line is its quality and when it landed,
        // both of which [detailParts] adds. "Completed" as well would be a
        // third way of saying what the `Ready` badge beside it already says.
        PullState.COMPLETED -> null

        PullState.PARTIAL -> "some tracks did not arrive"

        PullState.FAILED -> failureReason(pull)

        PullState.CANCELLED -> "cancelled"
    }

    /**
     * Why a pull failed, in the words the pack uses where it has them.
     *
     * The server distinguishes fewer reasons than a user would like, so
     * [Pull.error] is preferred over a generic phrase when the server bothered
     * to send one — it is the only explanation the user is ever going to get.
     */
    fun failureReason(pull: Pull): String = when (pull.failureReason) {
        PullFailureReason.NO_SOURCE_FOUND -> "no source found"
        PullFailureReason.DOWNLOAD_FAILED -> "the download failed"
        PullFailureReason.IMPORT_FAILED -> "downloaded, but the import failed"
        PullFailureReason.REJECTED -> "an administrator rejected this"
        PullFailureReason.HELD_FOR_REVIEW -> "held for review on the server"
        PullFailureReason.CANCELLED -> "cancelled"
        PullFailureReason.UNKNOWN, null -> pull.error?.takeIf { it.isNotBlank() } ?: "this pull failed"
    }

    // ---- composed lines -----------------------------------------------------

    /**
     * Everything the row knows about this pull beyond its title and its artist,
     * in the order screen 06 puts it.
     *
     * Shared by [subtitle] and [spokenRow] so the two cannot drift: what a
     * sighted user reads and what a screen reader hears are the same facts, and
     * a formatter that assembled them twice would eventually disagree with
     * itself. Parts whose figure is unknown are dropped rather than rendered as
     * a placeholder, so a pull the server has told us nothing about still reads
     * as the artist's name and nothing more.
     */
    fun detailParts(pull: Pull, now: Instant): List<String> = buildList {
        stateDetail(pull)?.takeIf { it.isNotBlank() }?.let { add(it) }

        // The pack's `FLAC` and `MP3 320`. `Pull` carries no format of its own —
        // the album does, and until a pull lands there is no album row to ask —
        // so `quality_snapshot_summary`, the policy the server applied to this
        // request, is the nearest true thing. It is suppressed on a failed pull,
        // where the reason is what matters and a quality policy is noise.
        if (pull.bucket != PullBucket.FAILED) {
            pull.qualityPolicySummary?.takeIf { it.isNotBlank() }?.let { add(it) }
        }

        if (!pull.state.isActive) {
            relativeDay(pull.updatedAt ?: pull.createdAt, now)?.let { add(it) }
        }

        // The request itself has not reached the server yet, which is a
        // different thing from the server not having started it.
        if (pull.isPendingSubmission) add("queued on this device")
    }

    /**
     * The row's second line: `Yussef Dayes · 12 of 19 files · FLAC`, `Mk.gee ·
     * FLAC · today`, `Paul Kossoff · no source found`.
     */
    fun subtitle(pull: Pull, now: Instant): String {
        val parts: List<String> = buildList {
            if (pull.artistName.isNotBlank()) add(pull.artistName)
            addAll(detailParts(pull, now))
        }
        return parts.joinToString(separator = " · ")
    }

    /**
     * The whole row as one spoken phrase.
     *
     * A screen reader gets the album, the artist, the state as a word, and the
     * figure as "62 percent" rather than "62%", which TalkBack reads as a
     * symbol. The visible row splits the same facts between a subtitle, a bar
     * and a badge; there is no way to hear a layout, so they are rejoined here.
     */
    fun spokenRow(pull: Pull, now: Instant): String {
        val state: String = spokenState(pull)
        val parts: List<String> = buildList {
            // [albumTitle], not `pull.albumTitle`: a blank first element left the phrase starting
            // ", Yussef Dayes", and TalkBack reads a leading separator as a pause before nothing.
            add(albumTitle(pull))
            if (pull.artistName.isNotBlank()) add(pull.artistName)
            add(state)
            percent(pull.progress.fraction)?.let { add(it.toString() + " percent") }
            // A cancelled pull's state and its explanation are the same word,
            // and "cancelled, cancelled" is what a screen reader would say
            // without this. The visible row needs both - the state is a badge
            // there and the explanation is a line - but a spoken sentence does
            // not.
            addAll(detailParts(pull, now).filterNot { it.equals(state, ignoreCase = true) })
        }
        return parts.joinToString(separator = ", ")
    }

    /** The state's name, said aloud. */
    fun spokenState(pull: Pull): String = when (pull.state) {
        PullState.PENDING_APPROVAL -> "waiting for approval"
        PullState.SEARCHING -> "searching"
        PullState.AWAITING_SOURCE_REVIEW -> "needs attention on the server"
        PullState.QUEUED -> "queued"
        PullState.DOWNLOADING -> "pulling"
        PullState.PROCESSING -> "importing"
        PullState.COMPLETED -> "ready"
        PullState.PARTIAL -> "partly delivered"
        PullState.FAILED -> "failed"
        PullState.CANCELLED -> "cancelled"
    }

    // ---- what you have asked for -------------------------------------------
    //
    // `GET /api/v1/requests/history`. Kept beside the queue's formatters rather
    // than in a file of its own because the two are drawn on one screen and must
    // round, separate and abbreviate identically; a second formatting home is
    // how two lists on one screen start disagreeing about what "3d ago" means.

    /**
     * How long ago, counting **forwards**: `in 4h`, `due now`.
     *
     * [relativeDay] answers "how long since", and every figure on the queue is in
     * the past, so it was all that was needed. The wanted list is the first thing
     * on this screen with a timestamp in the *future* — `next_check_at` and
     * `next_retry_at` are when the server will look again — and feeding those to
     * [relativeDay] returns "today" for everything, which is true and useless.
     *
     * Rounds down and never below a minute, so a check due in forty seconds reads
     * "in 1m" rather than "in 0m". `due now` covers the overshoot: these are
     * server-side schedules seen through a client clock, and a countdown that went
     * negative would read as an error rather than as a check that is imminent.
     */
    fun countdown(then: Instant?, now: Instant): String? {
        val at: Instant = then ?: return null
        val seconds: Long = (at - now).inWholeSeconds
        return when {
            seconds <= 0L -> "due now"
            seconds < 3_600L -> "in " + (seconds / 60L).coerceAtLeast(1L) + "m"
            seconds < 86_400L -> "in " + (seconds / 3_600L) + "h"
            seconds < 2_592_000L -> "in " + (seconds / 86_400L) + "d"
            else -> "in over a month"
        }
    }

    /**
     * What the row **names**: the album, or the track when a track is what was asked for.
     *
     * `request_kind` is `album` or `track` on both request lanes, and a track request keyed on a
     * recording MBID reports the release group it belongs to separately. Naming the album in the
     * title slot of a track request would name something the user did not ask for, so the track wins
     * the slot and the album moves into the subtitle as "from …".
     *
     * Falls back through [albumTitle], so a track request with neither a track title nor an album
     * title still fills the slot rather than drawing a hole.
     */
    fun historyTitle(entry: RequestHistoryEntry): String = when (entry.target) {
        RequestTarget.TRACK -> entry.trackTitle?.takeIf { it.isNotBlank() }
            ?: albumTitle(entry.albumTitle)

        RequestTarget.ALBUM -> albumTitle(entry.albumTitle)
    }

    /** How an action label names this entry inside a sentence. See [albumPhrase]. */
    fun historyPhrase(entry: RequestHistoryEntry): String = when (entry.target) {
        RequestTarget.TRACK -> entry.trackTitle?.takeIf { it.isNotBlank() }
            ?: albumPhrase(entry.albumTitle)

        RequestTarget.ALBUM -> albumPhrase(entry.albumTitle)
    }

    /**
     * What became of the request, in words.
     *
     * The [RequestOutcome.OTHER] branch prints the server's own token, tidied of its underscores.
     * REQUIREMENTS.md, "Placing a request", requires the status the server returned to be rendered
     * rather than inferred, and a state this client has never heard of is exactly the case that rule
     * exists for: "requires review" read back to the user is worth more than a guess, and infinitely
     * more than the silence of an unmatched `when` branch.
     */
    fun historyOutcome(entry: RequestHistoryEntry): String = when (entry.status) {
        RequestOutcome.PENDING -> "waiting to start"
        RequestOutcome.AWAITING_APPROVAL -> "waiting for an administrator"
        RequestOutcome.IN_PROGRESS -> "being acquired"
        RequestOutcome.COMPLETED -> "arrived"

        // Naming the reviewer is the difference between a policy and a mystery: a user whose request
        // was declined can go and ask that person.
        RequestOutcome.REJECTED -> entry.reviewedByName
            ?.takeIf { it.isNotBlank() }
            ?.let { "declined by " + it }
            ?: "declined"

        RequestOutcome.FAILED -> "failed"
        RequestOutcome.CANCELLED -> "cancelled"
        RequestOutcome.OTHER -> tidyToken(entry.statusToken).ifBlank { "state unknown" }
    }

    /**
     * The row's second line: `Slint · arrived · yesterday`, `Kate Bush · declined by Ada · 3d ago`.
     *
     * Deliberately the same grammar as [subtitle] — artist, then what happened, then when — because
     * the two lists sit behind two tabs of one screen and a user moving between them should not have
     * to re-learn where to look.
     */
    fun historySubtitle(entry: RequestHistoryEntry, now: Instant): String {
        val parts: List<String> = buildList {
            if (entry.artistName.isNotBlank()) add(entry.artistName)
            addAll(historyDetailParts(entry, now))
        }
        return parts.joinToString(separator = " · ")
    }

    /** Everything after the artist, shared by [historySubtitle] and [spokenHistoryRow]. */
    fun historyDetailParts(entry: RequestHistoryEntry, now: Instant): List<String> = buildList {
        // Only on a track request, and only when the album has a name worth printing: "from " and
        // nothing else is the dangling label the accessibility work was about.
        if (entry.target == RequestTarget.TRACK && entry.albumTitle.isNotBlank()) {
            add("from " + entry.albumTitle)
        }

        add(historyOutcome(entry))

        // Interesting only when the two disagree. On an arrived request "in your library" says what
        // "arrived" already said; on a failed or declined one it is news - the album got there by
        // some other route, and the user does not need to ask again.
        if (entry.inLibrary && entry.status != RequestOutcome.COMPLETED) add("in your library")

        relativeDay(entry.happenedAt, now)?.let { add(it) }
    }

    /**
     * The whole history row as one spoken phrase.
     *
     * Built from the same parts as the visible row for the reason [spokenRow] gives: a screen reader
     * cannot hear a layout, so the badge, the title and the line are rejoined into a sentence, and
     * assembling them twice is how the two would eventually disagree.
     */
    fun spokenHistoryRow(entry: RequestHistoryEntry, now: Instant): String {
        val parts: List<String> = buildList {
            add(historyTitle(entry))
            if (entry.artistName.isNotBlank()) add(entry.artistName)
            addAll(historyDetailParts(entry, now))
        }
        return parts.joinToString(separator = ", ")
    }

    /**
     * The count under the title on the history tab: `46 requests`.
     *
     * The figure is the server's `total`, which this lane really does report — unlike
     * `GET /api/v1/downloads`, where REQUIREMENTS.md rules out a count because there is neither
     * `total` nor `total_pages` to build one from. [loaded] is the fallback for a server that omits
     * the total anyway, so the line says something true rather than nothing.
     */
    fun historyHeaderLine(total: Int, loaded: Int): String = when {
        total > 0 -> plural(total.toLong(), "request")
        loaded > 0 -> plural(loaded.toLong(), "request")
        else -> ""
    }

    // ---- the wanted list ---------------------------------------------------

    /**
     * Why the server is still looking, and how hard.
     *
     * The gap and the watch state are two fields and one sentence: `missing` plus `watching` is "not
     * found yet", and `missing` plus `stopped` is "no longer being looked for". Printing both
     * separately would give a row like "not found yet · no longer being looked for", which
     * contradicts itself. So the state decides the wording and the gap only refines the live case.
     */
    fun wantedState(watch: WantedWatch): String = when (watch.state) {
        WantedWatchState.WATCHING -> when (watch.gap) {
            WantedGap.MISSING -> "not found yet"
            WantedGap.PARTIAL -> "only part of it found"
            WantedGap.OTHER -> "still being looked for"
        }

        WantedWatchState.DORMANT -> "checked only occasionally now"
        WantedWatchState.STOPPED -> "no longer being looked for"
        WantedWatchState.FULFILLED -> "found"
        WantedWatchState.OTHER -> tidyToken(watch.stateToken).ifBlank { "watched" }
    }

    /** `Kate Bush · not found yet · 2 new sources · checks again in 4h`. */
    fun wantedSubtitle(watch: WantedWatch, now: Instant): String {
        val parts: List<String> = buildList {
            if (watch.artistName.isNotBlank()) add(watch.artistName)
            addAll(wantedDetailParts(watch, now))
        }
        return parts.joinToString(separator = " · ")
    }

    /** Everything after the artist on a wanted row. */
    fun wantedDetailParts(watch: WantedWatch, now: Instant): List<String> = buildList {
        add(wantedState(watch))

        // The one number on this row a user can act on: sources have appeared that the server has
        // not tried yet, so the next check is worth waiting for rather than giving up on.
        if (watch.newCandidateCount > 0) {
            add(plural(watch.newCandidateCount.toLong(), "new source"))
        }

        // Only while the server will actually look again. A countdown on a stopped watch would
        // promise a check that is never coming.
        if (watch.state.isLive) {
            countdown(watch.nextCheckAt, now)?.let { add("checks again " + it) }
        } else if (watch.checkCount > 0) {
            // With no future check, how often it was tried is the only measure of effort left.
            add("checked " + plural(watch.checkCount.toLong(), "time"))
        }
    }

    /** The wanted row, said aloud. */
    fun spokenWantedRow(watch: WantedWatch, now: Instant): String {
        val parts: List<String> = buildList {
            add(albumTitle(watch.albumTitle))
            if (watch.artistName.isNotBlank()) add(watch.artistName)
            addAll(wantedDetailParts(watch, now))
        }
        return parts.joinToString(separator = ", ")
    }

    /**
     * `Radiohead · attempt 3 of 5 · retries in 12m`.
     *
     * Separate from [wantedSubtitle] because the server counts these separately - the response's
     * `count` covers the watches only - and because they mean something different: a watch is a
     * standing wish, a retrying item is a download about to be attempted again. Reading them in the
     * same words would hide that one of them is moving.
     */
    fun wantedRetrySubtitle(retry: WantedRetry, now: Instant): String {
        val parts: List<String> = buildList {
            if (retry.artistName.isNotBlank()) add(retry.artistName)
            addAll(wantedRetryDetailParts(retry, now))
        }
        return parts.joinToString(separator = " · ")
    }

    /** Everything after the artist on a retrying row. */
    fun wantedRetryDetailParts(retry: WantedRetry, now: Instant): List<String> = buildList {
        val attempt: Int = retry.retryCount.coerceAtLeast(0)
        when {
            retry.maxAttempts > 0 -> add("attempt " + attempt + " of " + retry.maxAttempts)
            attempt > 0 -> add("attempt " + attempt)
            else -> add("being retried")
        }
        countdown(retry.nextRetryAt, now)?.let { add("retries " + it) }
    }

    /** The retrying row, said aloud. */
    fun spokenWantedRetryRow(retry: WantedRetry, now: Instant): String {
        val parts: List<String> = buildList {
            add(albumTitle(retry.albumTitle))
            if (retry.artistName.isNotBlank()) add(retry.artistName)
            addAll(wantedRetryDetailParts(retry, now))
        }
        return parts.joinToString(separator = ", ")
    }

    /**
     * The count under the title on the wanted tab: `7 watched`, `7 watched · 2 retrying`.
     *
     * Both figures, because the endpoint's own `count` reports only the first and a user reading "7"
     * beside nine rows would be right to distrust the screen.
     */
    fun wantedHeaderLine(watches: Int, retrying: Int): String {
        val parts: List<String> = buildList {
            if (watches > 0) add(watches.toString() + " watched")
            if (retrying > 0) add(retrying.toString() + " retrying")
        }
        return parts.joinToString(separator = " · ")
    }

    /** `1 file`, `19 files`. */
    fun plural(count: Long, noun: String): String =
        count.toString() + " " + noun + (if (count == 1L) "" else "s")

    /**
     * A wire token made readable: `awaiting_source_review` becomes `awaiting source review`.
     *
     * Only ever applied to a token this client does not model, where the alternative is printing an
     * identifier at a user or printing nothing at all. It deliberately does not capitalise or
     * re-word: whatever the server called the state is what the user will find in the web UI, and
     * prettifying it would break that match.
     */
    fun tidyToken(token: String): String =
        token.trim().replace('_', ' ').replace('-', ' ')

    private const val UNIT: Long = 1024L

    private val UNITS: List<String> = listOf("KB", "MB", "GB", "TB")

    /** What fills the title slot when the mirror has no name. See [albumTitle]. */
    private const val UNTITLED_ALBUM: String = "Untitled album"

    /** What names the album inside an action label when there is no name. See [albumPhrase]. */
    private const val THIS_ALBUM: String = "this album"
}

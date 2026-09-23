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
            add(pull.albumTitle)
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

    /** `1 file`, `19 files`. */
    fun plural(count: Long, noun: String): String =
        count.toString() + " " + noun + (if (count == 1L) "" else "s")

    private const val UNIT: Long = 1024L

    private val UNITS: List<String> = listOf("KB", "MB", "GB", "TB")
}

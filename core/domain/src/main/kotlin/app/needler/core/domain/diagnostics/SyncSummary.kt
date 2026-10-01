package app.needler.core.domain.diagnostics

import app.needler.core.domain.model.FullSyncReason
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.SyncPhase
import app.needler.core.domain.model.SyncReport

/**
 * One line per sync pass, which is the "sync summaries" REQUIREMENTS.md "Observability" asks the
 * diagnostics log to contain.
 *
 * ## Why a summary and not the steps
 *
 * A delta already writes one `NET` line per request, so the request narrative is there. What was
 * missing is the verdict: three hundred `GET` lines do not tell a reader whether the mirror moved,
 * and the case that matters most produces almost no requests at all. REQUIREMENTS.md's performance
 * budget for a delta against an unchanged library is one request, and
 * `delta sync - library unchanged` is the line that proves the budget was met rather than that the
 * sync silently did nothing.
 *
 * It also answers the complaint that brings people to this log in the first place - "it is not showing
 * my new album" - which has exactly three shapes, each a different line here: the revision never
 * moved, the pass failed, or the pass ran and updated nothing.
 *
 * ## Counts, and why the zeroes are dropped
 *
 * A delta that changed one album reads `delta sync - 1 album, 12 tracks` rather than
 * `0 artists, 1 album, 12 tracks, 0 playlists, 0 stale tracks evicted`. The log is scanned, and a row
 * of zeroes is where an eye stops finding the one figure that is not zero. `library unchanged` is
 * stated as itself for the same reason, because it is a different fact from "all counts were zero":
 * the first means the server said nothing had moved, the second means the app looked and found
 * nothing.
 *
 * ## Secrets
 *
 * Nothing here formats a URL, and a failure line carries [NeedlerError.diagnostic], which is
 * documented as "a short, non-localised description for the diagnostics log". Both still pass through
 * the redaction that `SessionDiagnosticsLog.record` applies on ingest, which is the point of that
 * rule being on ingest: a `diagnostic` string assembled somewhere else from a failed request line
 * cannot leak the Subsonic app-password through this call site.
 */
public object SyncSummary {

    /**
     * The summary line for a pass that finished.
     *
     * @param report what the pass did.
     * @param elapsedMillis wall-clock duration, which is the figure the performance budget is written
     *   in and the only way a reader can tell a one-request delta from a full re-read.
     * @param reason named for a full sync only, because a full sync is expensive and REQUIREMENTS.md
     *   admits exactly three reasons for one - so "which of the three was this" is the first question
     *   anybody asks on seeing one in a log.
     */
    public fun line(
        report: SyncReport,
        elapsedMillis: Long,
        reason: FullSyncReason? = null,
    ): String {
        val head: String = label(report.phase, reason) + " - "
        val tail: String = " (" + DiagnosticsFormat.duration(elapsedMillis) + ")"
        if (report.libraryUnchanged) {
            return head + "library unchanged" + revisionSuffix(report.newRevision) + tail
        }
        val parts: MutableList<String> = ArrayList(5)
        if (report.artistsUpdated > 0) {
            parts.add(DiagnosticsFormat.plural(report.artistsUpdated, "artist"))
        }
        if (report.albumsUpdated > 0) parts.add(DiagnosticsFormat.plural(report.albumsUpdated, "album"))
        if (report.tracksUpdated > 0) parts.add(DiagnosticsFormat.plural(report.tracksUpdated, "track"))
        if (report.playlistsUpdated > 0) {
            parts.add(DiagnosticsFormat.plural(report.playlistsUpdated, "playlist"))
        }
        if (report.staleTracksEvicted.isNotEmpty()) {
            parts.add(
                DiagnosticsFormat.plural(report.staleTracksEvicted.size, "stale track") + " evicted",
            )
        }
        // "nothing changed" rather than an empty clause: a pass that ran against a moved revision and
        // found no work is a real and slightly surprising outcome, and a line that trailed off after
        // the dash would read as a truncated log rather than as an answer.
        val body: String = if (parts.isEmpty()) "nothing changed" else parts.joinToString(", ")
        return head + body + revisionSuffix(report.newRevision) + tail
    }

    /**
     * The line for a pass that failed.
     *
     * [DiagnosticsLevel.Warn] is what [levelOf] pairs with this, not `Error`: REQUIREMENTS.md treats
     * being offline as normal rather than alarming - the mirror is still the read path and the app is
     * still fully usable - so a failed sync is a fact to record, not an incident.
     */
    public fun failureLine(
        phase: SyncPhase,
        error: NeedlerError,
        elapsedMillis: Long,
        reason: FullSyncReason? = null,
    ): String = label(phase, reason) + " failed - " + error.diagnostic +
        " (" + DiagnosticsFormat.duration(elapsedMillis) + ")"

    /**
     * [DiagnosticsLevel.Debug] for a pass that found nothing, [DiagnosticsLevel.Info] for one that
     * changed something.
     *
     * The cheap case happens every fifteen minutes in the foreground and would otherwise be the most
     * common line in the buffer, pushing the five hundred lines that explain a failure out of the far
     * end. A pass that actually wrote to the mirror is the kind of thing `Info` is for.
     */
    public fun levelOf(report: SyncReport): DiagnosticsLevel =
        if (report.libraryUnchanged) DiagnosticsLevel.Debug else DiagnosticsLevel.Info

    /** `delta sync`, `full sync (FIRST_CONNECT)`. */
    private fun label(phase: SyncPhase, reason: FullSyncReason?): String = when (phase) {
        SyncPhase.DELTA -> "delta sync"
        SyncPhase.FULL -> if (reason == null) "full sync" else "full sync (" + reason.name + ")"
        SyncPhase.IDLE -> "sync"
    }

    /**
     * `, revision 1737052800000`, or nothing when the server never gave one.
     *
     * The revision is the single most useful figure in this log for the complaint that brings people
     * to it: two consecutive lines with the same revision mean the server is saying the library has
     * not moved, which is a server-side answer and not an app bug. It is a `lastModified` timestamp,
     * not a credential.
     */
    private fun revisionSuffix(revision: String?): String =
        if (revision == null) "" else ", revision " + revision
}

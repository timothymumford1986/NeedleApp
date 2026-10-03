package app.needler.settings

import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.RemovedDownload
import app.needler.core.domain.model.SyncReport

/**
 * The sentences a storage action reports when it has finished, or when it could not run.
 *
 * ## Why these moved out of [SettingsViewModel]
 *
 * Because there are two ViewModels in this package that remove a download now, not one.
 * [DownloadsViewModel] owns the per-album list on its own screen, and [SettingsViewModel] still owns
 * "Clear cached music" and "Remove all from device". Both have to say what a removal freed, and
 * REQUIREMENTS.md "Storage, and why there is no budget" is emphatic about why that sentence has to be
 * exact: "a 'remove' that leaves the usage figure unchanged is the one thing that would make this
 * whole screen untrustworthy". Two copies of that sentence is two sentences that will eventually
 * disagree about what the app just did, and the one the user believes would be whichever screen they
 * happened to be on.
 *
 * The alternative was leaving the copy private to [SettingsViewModel] and having [DownloadsViewModel]
 * write its own. Rejected for the reason above, and because the failure copy in particular is a
 * deliberate, documented subset - see [failure] - which a second author would reconstruct differently.
 */
internal object SettingsNotices {

    /**
     * What removing one album actually gave back.
     *
     * The album is named rather than referred to as "the album", because the user may have tapped
     * remove on a list of forty and a confirmation that does not name one is a confirmation they
     * cannot check. An album whose download never landed frees nothing, which is a success and is
     * reported as one rather than as a failure.
     */
    fun removed(album: DownloadedAlbum, removed: RemovedDownload): String =
        if (removed.removedTracks == 0) {
            "Removed " + album.title + ". None of it was on this device."
        } else {
            "Removed " + album.title + ": " +
                SettingsFormat.plural(removed.removedTracks.toLong(), "track") + ", " +
                SettingsFormat.bytes(removed.freedBytes) + " freed."
        }

    /**
     * What forgetting a certificate says afterwards.
     *
     * It names the consequence rather than confirming the act, because the act is already visible -
     * the row above it has changed to "None" by the time this is read - and the consequence is not.
     * The server is unreachable from this moment, and the user needs to know where to go to fix that
     * rather than discovering it the next time they press play.
     */
    const val FORGOT_CERTIFICATE: String =
        "Needler no longer trusts this certificate. This server will be unreachable until you " +
            "trust it again from the Connect screen."

    /**
     * What a finished **Sync now** says.
     *
     * ## What this fixes
     *
     * It said `Synced 0 albums and 0 artists.` A sentence whose two numbers are both zero reads as a
     * report of failure, and the device audit read it as one - it is the shape of an error message,
     * with a success's punctuation. Nothing had gone wrong: the server's library simply had no work in
     * it for this device.
     *
     * ## Why `libraryUnchanged` was not enough on its own
     *
     * [SyncReport.libraryUnchanged] is true only for the cheap path, where the revision had not moved
     * and the engine returned after one request. A pass that *did* run - a moved revision, a forced
     * sync, or a full rebuild - and then found nothing to write comes back with the flag false and
     * every count at zero, which is the case the user actually hit. So the question asked here is "did
     * this write anything", not "did it decide to look".
     *
     * `tracksUpdated` and the evictions are folded in for honesty rather than for display: both are
     * only ever accumulated inside `LibrarySyncEngine`'s per-album loop, so a non-zero track count
     * with no albums cannot currently arise - but "already up to date" would be a lie the day it can,
     * and the counts that *are* drawn would not notice.
     *
     * The wording is `SyncSummary`'s own, which says "nothing changed" for exactly this outcome in the
     * diagnostics log. Two sentences about one event, in two places, that disagree about whether it
     * worked is how a user comes to distrust both.
     */
    fun synced(report: SyncReport): String = if (report.changedNothing) {
        "Already up to date."
    } else {
        "Synced " + SettingsFormat.plural(report.albumsUpdated.toLong(), "album") +
            " and " + SettingsFormat.plural(report.artistsUpdated.toLong(), "artist") + "."
    }

    /**
     * Whether a finished pass wrote anything at all to the mirror.
     *
     * An extension rather than a property on [SyncReport], because "did this change anything the user
     * would notice" is a question about copy on one screen and not part of the domain model's contract
     * - `SyncSummary` asks a near-identical question for the log and phrases its answer differently.
     */
    private val SyncReport.changedNothing: Boolean
        get() = libraryUnchanged ||
            (
                albumsUpdated == 0 &&
                    artistsUpdated == 0 &&
                    tracksUpdated == 0 &&
                    playlistsUpdated == 0 &&
                    staleTracksEvicted.isEmpty()
                )

    /**
     * One sentence a person can act on, for each failure a storage control can actually produce.
     *
     * `NeedlerError.diagnostic` is deliberately not used: its own documentation says it is for the
     * diagnostics log and is "never shown raw to the user". The `else` is not laziness either - most
     * of the modelled errors belong to onboarding, streaming or pulls and cannot reach any control on
     * these two screens, so spelling them all out here would be inventing copy for states that are
     * unreachable from them.
     */
    fun failure(error: NeedlerError): String = when (error) {
        is NeedlerError.Offline -> "There is no connection to the server right now."
        NeedlerError.SessionExpired ->
            "Your sign-in has expired. Sign in again to restore search and pulls."

        NeedlerError.SubsonicProtocolDisabled ->
            "The server has the Subsonic protocol switched off. An administrator has to enable it."

        NeedlerError.DownloadForbidden ->
            "This server does not allow downloads for your account."

        is NeedlerError.RateLimited -> "The server asked Needler to slow down. Try again shortly."
        is NeedlerError.ServerError -> "The server had a problem. Try again shortly."
        is NeedlerError.InsufficientStorage -> "This device has no room left."
        else -> "Something went wrong. Try again."
    }
}

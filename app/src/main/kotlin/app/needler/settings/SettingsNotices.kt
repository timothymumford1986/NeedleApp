package app.needler.settings

import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.RemovedDownload

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

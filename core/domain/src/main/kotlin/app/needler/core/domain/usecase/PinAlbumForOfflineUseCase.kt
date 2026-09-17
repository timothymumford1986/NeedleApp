package app.needler.core.domain.usecase

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PinSource
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.StorageBudget
import app.needler.core.domain.model.StoragePreferences
import app.needler.core.domain.model.StorageUsage
import app.needler.core.domain.model.flatMap
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.repository.SessionRepository
import kotlinx.coroutines.flow.first

/**
 * Pins an owned album so it plays with no network: the product's **Pull local** action.
 *
 * Three checks the caller must not have to remember:
 *
 * 1. Library download is admin-gated. When `GET /api/v1/download/access` said no, every pin affordance
 *    is hidden, and a pin attempted anyway fails with [NeedlerError.DownloadForbidden] rather than
 *    letting a `403` surface mid-download.
 * 2. Only owned albums can be pinned. A catalogue-only album has to be pulled to the server first, so
 *    pinning one is a programming error, not a user-facing state.
 * 3. Pinned content is exempt from the storage budget, so pinning can push usage over it. The
 *    requirement is to **warn rather than silently evict** what the user asked to keep, which is why
 *    this returns [PinAlbumResult.willExceedBudget] instead of quietly trimming the cache.
 */
public class PinAlbumForOfflineUseCase(
    private val libraryRepository: LibraryRepository,
    private val pinRepository: PinRepository,
    private val sessionRepository: SessionRepository,
) {

    public suspend operator fun invoke(
        releaseGroupMbid: ReleaseGroupMbid,
        source: PinSource = PinSource.MANUAL,
    ): Outcome<PinAlbumResult> {
        val capabilities: ServerCapabilities? = sessionRepository.currentCapabilities()
        if (capabilities != null && !capabilities.libraryDownloadAllowed) {
            return Outcome.Failure(NeedlerError.DownloadForbidden)
        }

        val album: Album = libraryRepository.getAlbum(releaseGroupMbid)
            ?: return Outcome.Failure(NeedlerError.NotFound("album " + releaseGroupMbid.value))

        if (!album.isOwned) {
            return Outcome.Failure(
                NeedlerError.Rejected(
                    message = "album " + releaseGroupMbid.value + " is not owned; pull it first",
                ),
            )
        }

        val usage: StorageUsage = pinRepository.observeStorageUsage().first()
        val preferences: StoragePreferences = pinRepository.observeStoragePreferences().first()
        val metered: Boolean = sessionRepository.currentConnectivity().isMetered

        return pinRepository.pinAlbum(releaseGroupMbid, source).flatMap {
            Outcome.Success(
                PinAlbumResult(
                    releaseGroupMbid = releaseGroupMbid,
                    willExceedBudget = willExceedBudget(usage, album.sizeBytes),
                    waitingForUnmeteredNetwork = preferences.downloadToDeviceOnWifiOnly && metered,
                ),
            )
        }
    }

    /** Unpins an album and deletes its bytes: "remove from device". */
    public suspend fun unpin(releaseGroupMbid: ReleaseGroupMbid): Outcome<Unit> =
        pinRepository.unpinAlbum(releaseGroupMbid)

    private fun willExceedBudget(usage: StorageUsage, albumSizeBytes: Long?): Boolean {
        val budget: StorageBudget = usage.budget
        if (budget !is StorageBudget.Limited) return false
        val incoming: Long = albumSizeBytes ?: 0L
        return usage.pinnedBytes + incoming > budget.bytes
    }
}

/**
 * The outcome of a pin.
 *
 * Both flags are advisory: the pin succeeded either way. They exist so the UI can be honest about what
 * happens next instead of leaving the user watching a download that will not start until Wi-Fi.
 */
public data class PinAlbumResult(
    val releaseGroupMbid: ReleaseGroupMbid,
    /**
     * True when pinned content now exceeds the storage budget. Pinned content is never evicted, so the
     * correct response is a warning, not a cleanup.
     */
    val willExceedBudget: Boolean,
    /**
     * True when "Download to device on Wi-Fi only" is on and the connection is metered, so the download
     * is deferred - see [app.needler.core.domain.model.OfflineDownloadState.WaitingForUnmeteredNetwork].
     */
    val waitingForUnmeteredNetwork: Boolean,
)

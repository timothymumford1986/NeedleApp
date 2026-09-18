package app.needler.core.domain.usecase

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PinSource
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RemovedDownload
import app.needler.core.domain.model.ServerCapabilities
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
 * 3. Downloads have no storage limit, so a pin is never refused for space and never triggers an
 *    eviction. It can still be the thing that leaves the device short of room, so this reports
 *    [PinAlbumResult.willLeaveDeviceLowOnSpace] and lets the UI say so - the user removes albums by
 *    hand, and the app never deletes what they asked to keep.
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
                    willLeaveDeviceLowOnSpace = willLeaveDeviceLowOnSpace(usage, album.sizeBytes),
                    waitingForUnmeteredNetwork = preferences.downloadToDeviceOnWifiOnly && metered,
                ),
            )
        }
    }

    /**
     * Unpins an album and deletes its bytes: "remove from device".
     *
     * Returns what the removal freed, because on a device with no storage limit this action is the
     * user's only way to recover room and the UI has to be able to confirm that it worked.
     */
    public suspend fun unpin(releaseGroupMbid: ReleaseGroupMbid): Outcome<RemovedDownload> =
        pinRepository.unpinAlbum(releaseGroupMbid)

    /**
     * True when downloading this album would leave the device under the free-space floor.
     *
     * Advisory only. A download is never refused or trimmed for space - it is exactly the content the
     * user asked to keep - so the honest thing is to let the pin succeed and tell them their device
     * is getting full, with the Storage screen's per-album removal a tap away.
     *
     * A server-reported size of null means "unknown", which cannot be warned about without guessing,
     * so it is treated as no warning rather than as zero bytes.
     *
     * The floor compared against is the one on the snapshot, not the global minimum: it is computed
     * for this device's volume, so a 1 TB phone is not told it is fine at 3 GB free while the
     * eviction policy already considers it short.
     */
    private fun willLeaveDeviceLowOnSpace(usage: StorageUsage, albumSizeBytes: Long?): Boolean {
        val incoming: Long = albumSizeBytes ?: return usage.deviceLowOnSpace
        return usage.deviceFreeBytes - incoming < usage.freeSpaceFloorBytes
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
     * True when this download is likely to leave the device under the free-space floor. Downloads are
     * never evicted, so the correct response is a warning and an offer to remove albums, not a
     * cleanup the app performs on its own.
     */
    val willLeaveDeviceLowOnSpace: Boolean,
    /**
     * True when "Download to device on Wi-Fi only" is on and the connection is metered, so the download
     * is deferred - see [app.needler.core.domain.model.OfflineDownloadState.WaitingForUnmeteredNetwork].
     */
    val waitingForUnmeteredNetwork: Boolean,
)

package app.needler.core.domain.usecase

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumRequest
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SessionRepository

/**
 * Asks the server to acquire an album: the product's **Pull** action.
 *
 * The orchestration this use case exists for:
 *
 * - it refuses requests that would be pointless (the album is already owned, or already being
 *   acquired) and answers idempotently instead of creating a duplicate task;
 * - it distinguishes "offline" from "session expired", because the first is journalled and replayed
 *   while the second needs the user to sign in again and cannot be queued;
 * - it returns the server's own status untouched. The role is *not* consulted to decide whether the
 *   result is "Waiting": an admin may have changed the role moments earlier, so only the server knows.
 */
public class RequestAlbumUseCase(
    private val libraryRepository: LibraryRepository,
    private val pullRepository: PullRepository,
    private val sessionRepository: SessionRepository,
) {

    /**
     * Requests [releaseGroupMbid].
     *
     * @param monitorArtist sets the request's `monitor_artist` flag, subscribing the user to the
     *   artist's future releases. This is the secondary toggle on the request sheet, and the only part
     *   of artist following that v1 ships.
     * @param known the album as the UI already has it, to save a mirror read and to supply the
     *   title, artist and year hints the request body carries.
     */
    public suspend operator fun invoke(
        releaseGroupMbid: ReleaseGroupMbid,
        monitorArtist: Boolean = false,
        known: Album? = null,
    ): Outcome<RequestReceipt> {
        val album: Album? = known ?: libraryRepository.getAlbum(releaseGroupMbid)

        // Already satisfied or already in flight: answer from the current state rather than
        // creating a second task for the same release group.
        val shortCircuit: RequestStatus? = shortCircuitStatus(album?.state)
        if (shortCircuit != null) {
            return Outcome.Success(
                RequestReceipt(
                    releaseGroupMbid = releaseGroupMbid,
                    status = shortCircuit,
                    qualityPolicySummary = album?.qualityPolicySummary,
                ),
            )
        }

        val online: Boolean = sessionRepository.currentConnectivity().isOnline
        if (online) {
            val session: SessionState = sessionRepository.currentSession()
            if (!session.canUseCatalogueLane) {
                return Outcome.Failure(sessionBlocker(session))
            }
        }
        // When offline, the repository journals the request in the write queue and reports
        // RequestStatus.QUEUED_OFFLINE. A pull for an album that arrives by other means before the
        // queue drains is discarded silently rather than replayed.

        val request = AlbumRequest(
            releaseGroupMbid = releaseGroupMbid,
            albumTitle = album?.title,
            artistName = album?.artistName,
            year = album?.year,
            monitorArtist = monitorArtist,
        )
        return pullRepository.requestAlbum(request)
    }

    /** Requests several albums at once. The server caps a batch at 500 items. */
    public suspend fun requestAll(
        albums: List<Album>,
        monitorArtist: Boolean = false,
    ): Outcome<Unit> {
        val requestable: List<AlbumRequest> = albums
            .filter { album -> shortCircuitStatus(album.state) == null }
            .map { album ->
                AlbumRequest(
                    releaseGroupMbid = album.releaseGroupMbid,
                    albumTitle = album.title,
                    artistName = album.artistName,
                    year = album.year,
                    monitorArtist = monitorArtist,
                )
            }
        if (requestable.isEmpty()) return Outcome.Ok

        val online: Boolean = sessionRepository.currentConnectivity().isOnline
        if (online) {
            val session: SessionState = sessionRepository.currentSession()
            if (!session.canUseCatalogueLane) {
                return Outcome.Failure(sessionBlocker(session))
            }
        }

        return when (val result: Outcome<*> = pullRepository.requestAlbums(requestable)) {
            is Outcome.Success -> Outcome.Ok
            is Outcome.Failure -> Outcome.Failure(result.error)
        }
    }

    /**
     * The status to answer with when a request would be a no-op, or null when the request should go
     * ahead.
     */
    private fun shortCircuitStatus(state: AlbumState?): RequestStatus? = when (state) {
        null -> null
        AlbumState.NotOwned -> null
        is AlbumState.Failed -> null
        AlbumState.Owned -> RequestStatus.ALREADY_PRESENT
        is AlbumState.Pinned -> RequestStatus.ALREADY_PRESENT
        is AlbumState.PendingApproval -> RequestStatus.PENDING_APPROVAL
        is AlbumState.Acquiring -> RequestStatus.ACCEPTED
    }

    /**
     * Why the `/api/v1` lane cannot be used while online.
     *
     * [SessionState.PlayerOnly] is the common case and maps to
     * [NeedlerError.SessionExpired], which the UI must show as a non-blocking prompt to sign in again -
     * playback and library browsing are unaffected.
     */
    private fun sessionBlocker(session: SessionState): NeedlerError = when (session) {
        is SessionState.PlayerOnly -> NeedlerError.SessionExpired
        is SessionState.ReonboardingRequired -> NeedlerError.AppPasswordRevoked()
        SessionState.NotConfigured -> NeedlerError.CapabilityUnavailable("no server configured")
        is SessionState.SubsonicDisabled -> NeedlerError.SubsonicProtocolDisabled
        is SessionState.Authenticated -> NeedlerError.Unexpected("catalogue lane refused while authenticated")
    }
}

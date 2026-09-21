package app.needler.feature.library.album

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey

/**
 * Everything the album screen renders — screens 04 (owned) and 05 (not owned),
 * and 11 on a tablet.
 *
 * **There is one album screen, not two.** REQUIREMENTS.md "Identity model":
 * "an album found by catalogue search and an album already in the library are
 * the same domain object at different states … The UI never has separate
 * 'search result' and 'library album' types, which removes a whole class of
 * duplicate-rendering bugs." So the difference between screens 04 and 05 is
 * entirely [Album.state]: the same header, the same track list, a different
 * action.
 */
data class AlbumUiState(

    /** True until the mirror has answered. */
    val loading: Boolean = true,

    val album: Album? = null,
    val tracks: List<AlbumTrack> = emptyList(),

    val nowPlayingTrackKey: TrackKey? = null,

    /** How far the download to this device has got, when the album is pinned. */
    val download: OfflineDownloadState? = null,

    /**
     * Whether the server lets this user download to the device at all.
     *
     * REQUIREMENTS.md: "Offline downloads are separately gated by an
     * administrator. Needler must call `GET /api/v1/download/access` and hide
     * every pin and download affordance when `allowed` is false, rather than
     * letting the action fail later."
     */
    val downloadAllowed: Boolean = true,

    val offline: Boolean = false,

    /** An action is in flight, so the buttons are disabled rather than tappable twice. */
    val busy: Boolean = false,

    /** The result of the last action, or an explanation the screen owes the user. */
    val notice: AlbumNotice? = null,
) {

    /** The mirror answered and had nothing. A pulled album that was later removed, usually. */
    val notFound: Boolean get() = !loading && album == null

    /** Tracks the server never delivered. REQUIREMENTS.md "Partial content is a normal state". */
    val missingTracks: List<AlbumTrack> get() = tracks.filter { !it.available }

    /** True when some of this album arrived and some did not. */
    val isPartiallyDelivered: Boolean
        get() = album?.isOwned == true && missingTracks.isNotEmpty() && missingTracks.size < tracks.size

    /** The index playback should start from for "Play". Skips a missing opening track. */
    val firstPlayableIndex: Int get() = tracks.indexOfFirst { it.available }.coerceAtLeast(0)

    /** True when there is at least one track that can actually be played. */
    val hasPlayableTracks: Boolean get() = tracks.any { it.available }

    /**
     * What the primary action is, derived from the one place that knows:
     * [AlbumState.offeredActions].
     */
    val primaryAction: AlbumPrimaryAction
        get() = when (val state: AlbumState? = album?.state) {
            null -> AlbumPrimaryAction.NONE
            AlbumState.NotOwned -> AlbumPrimaryAction.PULL
            is AlbumState.PendingApproval -> AlbumPrimaryAction.WAITING
            is AlbumState.Acquiring -> AlbumPrimaryAction.ACQUIRING
            is AlbumState.Failed -> AlbumPrimaryAction.RETRY
            AlbumState.Owned, is AlbumState.Pinned -> AlbumPrimaryAction.PLAY
        }
}

/**
 * One row of the track list.
 *
 * [available] is the whole reason this wraps [Track] rather than being one.
 * REQUIREMENTS.md: a part-delivered pull leaves an album that is *in library*
 * with tracks that exist nowhere — "not on the server and not on any device" —
 * so there is no fallback to offer and a list that quietly omitted them would
 * misrepresent what the user owns. They are listed in their right positions,
 * greyed, each with its own retry.
 */
data class AlbumTrack(
    /** Position in the list, 1-based. Used for the number the row draws. */
    val position: Int,
    val track: Track,
    val available: Boolean,
) {
    val key: TrackKey get() = track.key
}

/** The shape of the action area, which is the only thing that differs between screens 04 and 05. */
enum class AlbumPrimaryAction {
    /** Play, Shuffle and Pull local (screens 04, 11). */
    PLAY,

    /** Pull this album, with the explanatory line (screen 05). */
    PULL,

    /** Waiting for an administrator. No action; the request has already been made. */
    WAITING,

    /** Pulling, with progress and a Cancel. */
    ACQUIRING,

    /** Failed, with an explanation and a Retry. */
    RETRY,

    /** Nothing loaded. */
    NONE,
}

/**
 * Something the screen has to tell the user after an action, or instead of one.
 *
 * Each of these is a different outcome with a different next step, which is why
 * they are modelled rather than collapsed into one string: a queued-offline
 * pull will happen by itself, a pending approval needs an administrator, and a
 * download the server forbids cannot be retried at all.
 */
sealed interface AlbumNotice {

    val message: String

    /** True for the ones that are bad news, which the screen tints differently. */
    val isProblem: Boolean get() = false

    /** The pull was accepted and the server is acting on it. */
    data object PullAccepted : AlbumNotice {
        override val message: String
            get() = "Pulling. Track it on the Pulls tab; you will be told when it lands."
    }

    /**
     * The pull needs approval first.
     *
     * REQUIREMENTS.md: "Needler must render the status the server returned
     * rather than inferring it from the cached role, because the role may have
     * changed moments earlier."
     */
    data object PullPendingApproval : AlbumNotice {
        override val message: String
            get() = "Requested. An administrator has to approve this before it is acquired."
    }

    /** Placed with no connection; the write queue will replay it. */
    data object PullQueuedOffline : AlbumNotice {
        override val message: String
            get() = "No connection, so this pull is queued. It is sent as soon as you are back online."
    }

    /** The server already has it. */
    data object AlreadyInLibrary : AlbumNotice {
        override val message: String get() = "This album is already in your library."
    }

    /** Downloading to the device started. */
    data object DownloadStarted : AlbumNotice {
        override val message: String get() = "Downloading to this device."
    }

    /**
     * Downloading will wait for Wi-Fi.
     *
     * REQUIREMENTS.md "The Wi-Fi-only setting is mislabelled": the setting that
     * actually costs mobile data is downloading to the device, and it defaults
     * to on, so this is the common case on a phone away from home.
     */
    data object DownloadWaitingForWifi : AlbumNotice {
        override val message: String
            get() = "Queued. This album downloads when you are next on Wi-Fi — you can change " +
                "that under Settings, Storage."
    }

    /** The album fits, but only just. */
    data object DownloadWillFillDevice : AlbumNotice {
        override val isProblem: Boolean get() = true
        override val message: String
            get() = "Downloading this album leaves your device low on free space."
    }

    /** The download finished and the bytes are gone. */
    data class RemovedFromDevice(val freedBytes: Long?) : AlbumNotice {
        override val message: String
            get() = "Removed from this device."
    }

    /** Something went wrong, in whatever words the domain error justified. */
    data class Problem(override val message: String) : AlbumNotice {
        override val isProblem: Boolean get() = true
    }

    companion object {
        /** Maps a request receipt onto what to say about it. */
        fun forRequest(status: RequestStatus): AlbumNotice = when (status) {
            RequestStatus.ACCEPTED -> PullAccepted
            RequestStatus.PENDING_APPROVAL -> PullPendingApproval
            RequestStatus.QUEUED_OFFLINE -> PullQueuedOffline
            RequestStatus.ALREADY_PRESENT -> AlreadyInLibrary
            RequestStatus.REJECTED -> Problem("The server rejected this request.")
        }
    }
}

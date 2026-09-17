package app.needler.core.domain.model

import kotlinx.datetime.Instant

/**
 * One album, owned or not.
 *
 * There is deliberately **no separate search-result type**. Because the release-group MBID is the join
 * key on both server lanes, an album found by catalogue search and an album already in the library are
 * the same domain object at different [AlbumState]s. Keeping one type removes a whole class of
 * duplicate-rendering bugs and lets a single list show both (screens 03 and 10).
 *
 * Fields that only exist for owned content are nullable, since a catalogue-only album has no track
 * count, size or format until the server acquires it.
 */
public data class Album(
    val releaseGroupMbid: ReleaseGroupMbid,
    val title: String,
    val artistName: String,
    val artistMbid: ArtistMbid?,
    val state: AlbumState,
    val year: Int? = null,
    val trackCount: Int? = null,
    val discCount: Int? = null,
    val durationMs: Long? = null,
    /** Denormalised from the tracks so list rows can badge FLAC / MP3 320 without loading tracks. */
    val quality: AudioQuality = AudioQuality.Unknown,
    /** On-server size in bytes; feeds the "176 albums - 42 GB" header as an offline fallback. */
    val sizeBytes: Long? = null,
    val addedAt: Instant? = null,
    val artwork: ArtworkRef? = null,
    val genres: List<String> = emptyList(),
    val isFavourite: Boolean = false,
    /**
     * The quality policy the server applied to the request for this album
     * (`quality_snapshot_summary`). Read-only: the request body carries no quality field, so this is
     * shown, never edited, by a non-admin.
     */
    val qualityPolicySummary: String? = null,
) {
    /** True when the server holds the audio, i.e. it can be streamed or downloaded to the device. */
    public val isOwned: Boolean get() = state.isOwned

    /** True when every pinned track is on this device and the album plays with no network at all. */
    public val isFullyOnDevice: Boolean
        get() {
            val pinned: AlbumState.Pinned = state as? AlbumState.Pinned ?: return false
            return pinned.download == OfflineDownloadState.Complete
        }

    /** The actions the design pack offers for this album's state. */
    public val offeredActions: Set<AlbumAction> get() = state.offeredActions
}

/**
 * The album state machine from the requirements.
 *
 * ```
 * [*]             -> NotOwned        found in catalogue search
 * NotOwned        -> PendingApproval requested by role `user`
 * NotOwned        -> Acquiring       requested by `trusted` or `admin`
 * PendingApproval -> Acquiring       admin approves
 * PendingApproval -> NotOwned        admin rejects
 * Acquiring       -> Owned           import completes
 * Acquiring       -> Failed          no usable source
 * Failed          -> Acquiring       retry
 * Owned           -> Pinned          user pins for offline
 * Pinned          -> Owned           user unpins
 * ```
 *
 * A sealed interface rather than an enum because [Acquiring] must carry live progress and [Failed] must
 * carry a reason; a parallel "progress" field on [Album] would let the two disagree.
 */
public sealed interface AlbumState {

    /** True for states in which the server holds playable audio. */
    public val isOwned: Boolean get() = this is Owned || this is Pinned

    /** The actions the design pack offers in this state (the state/badge/action table). */
    public val offeredActions: Set<AlbumAction>

    /** Not in the library. Found only in the MusicBrainz catalogue. Offers **Pull**. */
    public data object NotOwned : AlbumState {
        override val offeredActions: Set<AlbumAction> get() = setOf(AlbumAction.PULL)
    }

    /**
     * Requested by a user whose role needs approval; badged "Waiting", no action offered.
     *
     * The presence of this state must come from the status the *server* returned on the request, not
     * from the cached [UserRole]: an admin may have changed the role moments earlier.
     */
    public data class PendingApproval(
        val requestedAt: Instant? = null,
    ) : AlbumState {
        override val offeredActions: Set<AlbumAction> get() = emptySet()
    }

    /** The server is searching for, downloading or importing this album. Badged "Pulling, N%". */
    public data class Acquiring(
        val progress: PullProgress,
        /** Derived client-side state, e.g. Searching or "needs attention on the server". */
        val stage: PullState = PullState.QUEUED,
    ) : AlbumState {
        override val offeredActions: Set<AlbumAction>
            get() = if (stage.isCancellable) setOf(AlbumAction.CANCEL) else emptySet()
    }

    /** In the library, streams on demand. Badged "In library"; offers Play and **Pull local**. */
    public data object Owned : AlbumState {
        override val offeredActions: Set<AlbumAction>
            get() = setOf(AlbumAction.PLAY, AlbumAction.PULL_LOCAL)
    }

    /**
     * Pinned for offline: kept on device and exempt from LRU eviction.
     *
     * Badged "On device" with a green check on the artwork once [download] is
     * [OfflineDownloadState.Complete].
     */
    public data class Pinned(
        val download: OfflineDownloadState,
        val pinnedAt: Instant? = null,
        val source: PinSource = PinSource.MANUAL,
    ) : AlbumState {
        override val offeredActions: Set<AlbumAction>
            get() = setOf(AlbumAction.PLAY, AlbumAction.REMOVE_FROM_DEVICE)
    }

    /** Acquisition finished without usable audio. Badged "no source found"; offers Retry. */
    public data class Failed(
        val reason: PullFailureReason,
        val message: String? = null,
        val failedAt: Instant? = null,
    ) : AlbumState {
        override val offeredActions: Set<AlbumAction> get() = setOf(AlbumAction.RETRY)
    }
}

/**
 * The actions an album row may offer, one per cell of the requirements' state/action table.
 *
 * Domain-level so the phone list, tablet grid, Android Auto browse tree and the widgets cannot drift
 * apart on which action a state allows.
 */
public enum class AlbumAction {
    /** Ask the server to acquire this album. */
    PULL,

    /** Download an owned album to this device (pin it). */
    PULL_LOCAL,

    /** Cancel an in-flight acquisition. Only legal while searching, queued or downloading. */
    CANCEL,

    /** Play from the library, streaming or from cache. */
    PLAY,

    /** Unpin and delete the on-device copy. */
    REMOVE_FROM_DEVICE,

    /** Retry a failed, cancelled or partial acquisition. */
    RETRY,
}

/**
 * The album lists the library screens show. Each maps onto a `getAlbumList2` type, but the
 * repositories serve them from the mirror so they work offline.
 */
public enum class AlbumListKind {
    /** `getAlbumList2?type=newest`, newest first. Feeds "Recently added" and its widget. */
    NEWEST,

    /** `getAlbumList2?type=frequent`, server-computed ordering. "Most played". */
    FREQUENT,

    /** `getAlbumList2?type=recent`, recently played. */
    RECENT,

    /** Alphabetical by album title. */
    ALPHABETICAL_BY_NAME,

    /** Alphabetical by album artist. */
    ALPHABETICAL_BY_ARTIST,

    /** Albums the user has starred, via `getStarred2`. */
    STARRED,
}

package app.needler.core.domain.model

import kotlinx.datetime.Instant

/**
 * A server-side acquisition task: the thing the product calls a **pull**.
 *
 * "Pull" means asking the server to acquire an album you do not own. Downloading an album you *do* own
 * onto the phone is "pull local", which is [Pin] plus [OfflineDownloadState], not this type.
 */
public data class Pull(
    val releaseGroupMbid: ReleaseGroupMbid,
    val albumTitle: String,
    val artistName: String,
    /** The `/api/v1/downloads` task, absent while a request is only an approval waiting in a queue. */
    val taskId: PullTaskId? = null,
    /** The raw status the server reported. Never infer this from the user's role. */
    val status: PullStatus,
    /**
     * Present once the server has started a source search. Its presence or absence is half of the
     * client-side state derivation; see [PullState.derive].
     */
    val searchJobId: String? = null,
    /** Set once a source candidate has been chosen. Absent with a search job means a parked pull. */
    val candidateIndex: Int? = null,
    val progress: PullProgress = PullProgress.Unknown,
    /** Where the bytes are coming from, e.g. Soulseek or Usenet. The server supports several. */
    val source: String? = null,
    val error: String? = null,
    val failureReason: PullFailureReason? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    /**
     * True while this pull is a request the server parked for admin approval
     * (`GET /api/v1/requests/active`). Role `user` requests land here.
     */
    val awaitingApproval: Boolean = false,
    /** `quality_snapshot_summary`: the server-side quality policy applied to this request. */
    val qualityPolicySummary: String? = null,
    /** True while the request itself is still only journalled locally, waiting for connectivity. */
    val isPendingSubmission: Boolean = false,
) {
    /** The state to render, including the two states derived client-side. */
    public val state: PullState
        get() = PullState.derive(
            status = status,
            searchJobId = searchJobId,
            candidateIndex = candidateIndex,
            awaitingApproval = awaitingApproval,
        )

    /**
     * Cancel is offered only while searching, queued or downloading. Cancelling during `processing`
     * is unsafe because files are being moved, and the server refuses it.
     */
    public val canCancel: Boolean get() = state.isCancellable

    /** Retry is offered on failed, cancelled and partial tasks. */
    public val canRetry: Boolean get() = state.isRetryable

    /** Which of the Queue screen's three buckets this task belongs to. */
    public val bucket: PullBucket get() = state.bucket
}

/**
 * The server's own download task statuses, exactly as `/api/v1/downloads` reports them.
 *
 * Kept separate from [PullState] so that the raw server value is never overwritten by a derived one -
 * the derivation needs both this and the search-job fields.
 */
public enum class PullStatus {
    QUEUED,
    DOWNLOADING,
    PROCESSING,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED,
    ;

    public companion object {
        /** Maps a server token onto a status, defaulting to [QUEUED] for anything unrecognised. */
        public fun fromServerToken(token: String?): PullStatus {
            return when (token?.trim()?.lowercase()) {
                "queued" -> QUEUED
                "downloading" -> DOWNLOADING
                "processing" -> PROCESSING
                "completed" -> COMPLETED
                "partial" -> PARTIAL
                "failed" -> FAILED
                "cancelled", "canceled" -> CANCELLED
                else -> QUEUED
            }
        }
    }
}

/**
 * What the UI shows for a pull: the server's statuses plus the two states derived client-side, exactly
 * as the DroppedNeedle web UI derives them.
 */
public enum class PullState {
    /** A role-`user` request parked for admin approval. Badge: "Waiting". */
    PENDING_APPROVAL,

    /** `queued` with no `search_job_id`: the server is still looking for sources. Badge: "Searching". */
    SEARCHING,

    /**
     * `queued` with a `search_job_id` but no `candidate_index`: a manual source pick is parked.
     * Badge: "Needs attention on the server". Manual source selection is a web-UI job in v1.
     */
    AWAITING_SOURCE_REVIEW,

    /** `queued` with a chosen candidate: waiting for a download slot. */
    QUEUED,

    DOWNLOADING,

    /** Files are being moved and imported. Not cancellable. */
    PROCESSING,

    /** Import finished; the album is playable. */
    COMPLETED,

    /** Some tracks landed, some did not. Retryable. */
    PARTIAL,

    FAILED,

    CANCELLED,
    ;

    /** Cancel is legal only while searching, queued or downloading. */
    public val isCancellable: Boolean
        get() = this == SEARCHING || this == AWAITING_SOURCE_REVIEW || this == QUEUED || this == DOWNLOADING

    /** Retry is legal on failed, cancelled and partial tasks. */
    public val isRetryable: Boolean
        get() = this == FAILED || this == CANCELLED || this == PARTIAL

    /** True while the server is still working on this pull. */
    public val isActive: Boolean
        get() = when (this) {
            PENDING_APPROVAL, SEARCHING, AWAITING_SOURCE_REVIEW, QUEUED, DOWNLOADING, PROCESSING -> true
            COMPLETED, PARTIAL, FAILED, CANCELLED -> false
        }

    /** Active / Completed / Failed, matching the server's own grouping on the Pulls screen. */
    public val bucket: PullBucket
        get() = when (this) {
            PENDING_APPROVAL, SEARCHING, AWAITING_SOURCE_REVIEW, QUEUED, DOWNLOADING, PROCESSING ->
                PullBucket.ACTIVE
            COMPLETED -> PullBucket.COMPLETED
            PARTIAL, FAILED, CANCELLED -> PullBucket.FAILED
        }

    public companion object {
        /**
         * The single place the client-side derivation lives.
         *
         * `queued` alone means different things depending on the search-job fields, and the server does
         * not collapse them for us, so every caller must go through here rather than reading
         * [PullStatus] directly.
         */
        public fun derive(
            status: PullStatus,
            searchJobId: String?,
            candidateIndex: Int?,
            awaitingApproval: Boolean,
        ): PullState {
            if (awaitingApproval) return PENDING_APPROVAL
            return when (status) {
                PullStatus.QUEUED -> when {
                    searchJobId == null -> SEARCHING
                    candidateIndex == null -> AWAITING_SOURCE_REVIEW
                    else -> QUEUED
                }
                PullStatus.DOWNLOADING -> DOWNLOADING
                PullStatus.PROCESSING -> PROCESSING
                PullStatus.COMPLETED -> COMPLETED
                PullStatus.PARTIAL -> PARTIAL
                PullStatus.FAILED -> FAILED
                PullStatus.CANCELLED -> CANCELLED
            }
        }
    }
}

/** The Pulls screen's three buckets, sorted newest first within each. */
public enum class PullBucket {
    ACTIVE,
    COMPLETED,
    FAILED,
}

/** Per-task progress, from `progress_percent`, byte counters and file counters. */
public data class PullProgress(
    val percent: Int? = null,
    val filesCompleted: Int = 0,
    val filesTotal: Int = 0,
    val downloadedBytes: Long? = null,
    val totalSizeBytes: Long? = null,
) {
    /** 0f..1f, preferring the server's percentage and falling back to bytes, then files. */
    public val fraction: Float?
        get() {
            val reported: Int? = percent
            if (reported != null) return (reported.coerceIn(0, 100)).toFloat() / 100f
            val done: Long? = downloadedBytes
            val total: Long? = totalSizeBytes
            if (done != null && total != null && total > 0L) return done.toFloat() / total.toFloat()
            if (filesTotal > 0) return filesCompleted.toFloat() / filesTotal.toFloat()
            return null
        }

    public companion object {
        public val Unknown: PullProgress = PullProgress()
    }
}

/** Why a pull failed, insofar as the server distinguishes it. */
public enum class PullFailureReason {
    /** Nothing usable found on any configured source. The design pack badges this "no source found". */
    NO_SOURCE_FOUND,

    /** Sources found but every download attempt failed. */
    DOWNLOAD_FAILED,

    /** Bytes arrived but import or tagging failed. */
    IMPORT_FAILED,

    /** An admin rejected the request. */
    REJECTED,

    /** Held or quarantined for review; resolving it needs the web UI in v1. */
    HELD_FOR_REVIEW,

    CANCELLED,

    UNKNOWN,
}

/**
 * A request to acquire an album.
 *
 * The body carries the MBIDs and title hints only. There is deliberately no quality field: quality is
 * a server-side policy under `/api/v1/download-clients/policy` that only an admin can change, so
 * presenting a per-album quality toggle to a user would be a lie.
 */
public data class AlbumRequest(
    val releaseGroupMbid: ReleaseGroupMbid,
    /** Title hint, sent to help the server's source search. */
    val albumTitle: String? = null,
    /** Artist hint. */
    val artistName: String? = null,
    val year: Int? = null,
    /**
     * `monitor_artist`: subscribes the user to this artist's future releases. Offered as a secondary
     * toggle on the request sheet; it is what makes the new-release notification possible without the
     * deferred following screens.
     */
    val monitorArtist: Boolean = false,
)

/** A single-track request, keyed on the recording MBID rather than the release group. */
public data class TrackRequest(
    val recordingMbid: RecordingMbid,
    val trackTitle: String? = null,
    val artistName: String? = null,
    val monitorArtist: Boolean = false,
)

/**
 * What the server said when a request was placed.
 *
 * [status] must be rendered as returned rather than inferred from the cached [UserRole]: an admin may
 * have changed the role moments earlier, and the server is the only authority on whether this request
 * needs approval.
 */
public data class RequestReceipt(
    val releaseGroupMbid: ReleaseGroupMbid?,
    val status: RequestStatus,
    val taskId: PullTaskId? = null,
    val qualityPolicySummary: String? = null,
    val message: String? = null,
)

/** The status a request comes back with. */
public enum class RequestStatus {
    /** Accepted and parked for admin approval. */
    PENDING_APPROVAL,

    /** Accepted and already executing: role `trusted` or `admin`. */
    ACCEPTED,

    /** The server already owns it, or an identical request already exists. */
    ALREADY_PRESENT,

    /** Journalled locally because the app is offline; it will submit on reconnect. */
    QUEUED_OFFLINE,

    /** Refused outright. */
    REJECTED,
    ;

    public companion object {
        /**
         * Maps the server's `status` field. Unknown values fall back to [PENDING_APPROVAL] rather than
         * [ACCEPTED], so the UI never promises progress the server did not.
         */
        public fun fromServerToken(token: String?): RequestStatus {
            return when (token?.trim()?.lowercase()) {
                "accepted", "approved", "executing", "queued", "searching", "started" -> ACCEPTED
                "already_present", "exists", "owned", "skipped" -> ALREADY_PRESENT
                "rejected", "denied" -> REJECTED
                else -> PENDING_APPROVAL
            }
        }
    }
}

/**
 * The result of `POST /api/v1/requests/batch`, capped at 500 items server-side.
 *
 * [overflow] is the server's own count of items beyond what it would accept.
 */
public data class BatchRequestReceipt(
    val requested: List<RequestReceipt>,
    val skipped: List<ReleaseGroupMbid>,
    val overflow: Int,
)

/**
 * `GET /api/v1/downloads/activity-summary`.
 *
 * The cheap poll used everywhere except the foregrounded Pulls screen. [revision] makes a no-change
 * poll nearly free: when it has not moved, every downstream refresh can be skipped.
 */
public data class PullActivitySummary(
    val revision: Long,
    val activeCount: Int,
    /** Items held or quarantined. Read-only in v1; resolving them needs the web UI. */
    val heldCount: Int,
    val failedCount: Int,
    /** Albums that finished since the last poll: the source of the "pull finished" notification. */
    val landedReleaseGroupMbids: List<ReleaseGroupMbid> = emptyList(),
)

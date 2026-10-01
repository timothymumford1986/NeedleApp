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
    /**
     * The album's name, **which may be blank**.
     *
     * Not nullable, because there is nothing a caller would do with `null` that it would not also do
     * with `""`, and two spellings of "no title" invite a guard that only covers one of them. It is
     * blank whenever the `album` mirror has no name for this release group: REQUIREMENTS.md "Identity
     * model" makes the release-group MBID the join key and the mirror the one home for the title, so a
     * pull for something never seen in the library or in catalogue search has nothing to join to.
     *
     * Every place this is drawn, spoken or concatenated into a label **must** guard on `isBlank`.
     * That is not defensiveness: 34 of 35 rows on a real device were blank, which made the Pulls
     * screen unusable and produced the accessibility label "Cancel the pull of " with nothing after
     * it. REQUIREMENTS.md "Accessibility" requires every control to carry a content description, and a
     * description ending in a preposition does not satisfy it. See `PullsFormat.albumTitle` and
     * `NotificationComposer.text` for the two existing guards.
     */
    val albumTitle: String,
    /** The artist's name, blank under the same conditions as [albumTitle] and guarded the same way. */
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

// ---------------------------------------------------------------------------
// The other two request lanes
// ---------------------------------------------------------------------------

/**
 * Whether a request named an album or a single track.
 *
 * Both lanes below carry `request_kind`, and both are keyed on the release group either way: a track
 * request reports `track_release_group_mbid` beside the recording it was placed against, so the join
 * key REQUIREMENTS.md "Identity model" mandates is available for both. The distinction survives into
 * the domain only so a row can say "the track *Nightswimming*" rather than naming an album the user
 * never asked for.
 */
public enum class RequestTarget {
    ALBUM,
    TRACK,
    ;

    public companion object {
        /** Maps `request_kind`, which is `album` or `track`. Anything else is an album. */
        public fun fromServerToken(token: String?): RequestTarget =
            if (token?.trim()?.equals("track", ignoreCase = true) == true) TRACK else ALBUM
    }
}

/**
 * What became of a request, as `GET /api/v1/requests/history` reports it.
 *
 * REQUIREMENTS.md, "Placing a request": Needler "must render the status the server returned rather
 * than inferring it from the cached role, because the role may have changed moments earlier". That
 * is why [RequestHistoryEntry] keeps the server's raw token beside this enum. The enum is what the
 * screen branches on; the token is what it prints when the server names a state this client has
 * never heard of. Folding an unknown token onto the nearest member was rejected: it would show the
 * user a state the server did not report, which is the exact failure that requirement names.
 *
 * [serverToken] doubles as the `status` filter on the same endpoint. That is deliberate rather than
 * convenient — `GET /api/v1/requests/history` is the **only** request list that takes parameters at
 * all, and putting the filter value on the state it filters for keeps the two from drifting.
 * `requests/active` and `requests/wanted` take none and return the whole list.
 */
public enum class RequestOutcome(public val serverToken: String?) {

    /** Accepted and waiting on the server's own scheduling. */
    PENDING("pending"),

    /** Accepted and parked for an administrator. A role-`user` request lands here. */
    AWAITING_APPROVAL("awaiting_approval"),

    /** Approved, and the acquisition is somewhere in the download lane. */
    IN_PROGRESS("approved"),

    /** The album or track arrived. */
    COMPLETED("completed"),

    /** An administrator declined it. */
    REJECTED("rejected"),

    FAILED("failed"),

    CANCELLED("cancelled"),

    /**
     * A token this client does not model.
     *
     * Not an error and not a filter: [serverToken] is null precisely so that asking the server to
     * filter by "whatever we could not read" is impossible to express.
     */
    OTHER(null),
    ;

    /**
     * Whether `POST /api/v1/requests/retry/{mbid}` is worth offering on this entry.
     *
     * [REJECTED] is deliberately excluded. Retrying it re-asks an administrator who has already said
     * no, which is not a decision a client should take on the user's behalf, and the endpoint
     * answers `200` with `success=false` for it anyway — a refusal the user would read as a bug in
     * Needler rather than as a policy on their server.
     */
    public val isRetryable: Boolean get() = this == FAILED || this == CANCELLED

    /** True while the server still has work to do for this request. */
    public val isSettled: Boolean
        get() = when (this) {
            COMPLETED, REJECTED, FAILED, CANCELLED -> true
            PENDING, AWAITING_APPROVAL, IN_PROGRESS, OTHER -> false
        }

    public companion object {
        /** Maps the server's `status`. Unknown and blank both become [OTHER]; see the class KDoc. */
        public fun fromServerToken(token: String?): RequestOutcome {
            return when (token?.trim()?.lowercase()) {
                "pending", "requested", "new" -> PENDING
                "awaiting_approval", "awaiting-approval", "pending_approval" -> AWAITING_APPROVAL
                "approved", "queued", "searching", "downloading", "processing", "in_progress" ->
                    IN_PROGRESS
                "completed", "complete", "imported", "done" -> COMPLETED
                "rejected", "denied", "declined" -> REJECTED
                "failed", "error" -> FAILED
                "cancelled", "canceled" -> CANCELLED
                else -> OTHER
            }
        }
    }
}

/**
 * One entry of `GET /api/v1/requests/history`: something asked for, and what came of it.
 *
 * Separate from [Pull] rather than modelled as one, because the two answer different questions and
 * are keyed differently in time. A [Pull] is a *task* the server is working on now — it has byte
 * counters, a cancel and a retry, and it stops existing when the server clears it. This is the
 * record that the asking happened, and it outlives the task: REQUIREMENTS.md "Local persistence"
 * gives the `pull` table no history sibling, so a finished request survives only here, on the
 * server. Collapsing them would have meant either giving [Pull] a dozen nullable columns that are
 * meaningless for a live task, or dropping the fields that make history worth reading at all.
 *
 * ## Timestamps are ISO-8601 on this lane
 *
 * [requestedAt] and [completedAt] arrive as ISO-8601 strings, which is what the whole `/requests`
 * half of `/api/v1` sends — and is **not** what its own `wanted` list sends, nor what `/downloads`
 * sends. See [WantedWatch] for the other half of that trap.
 */
public data class RequestHistoryEntry(
    val releaseGroupMbid: ReleaseGroupMbid,
    /**
     * The album's name, **which may be blank**, for exactly the reasons [Pull.albumTitle] may be:
     * the server records what it was told and a request placed from a thin search result may have
     * told it nothing. Every render site must go through `PullsFormat.albumTitle` or
     * `PullsFormat.albumPhrase`; a blank drawn raw is what made the Pulls screen unusable once.
     */
    val albumTitle: String,
    /** The artist's name, blank under the same conditions as [albumTitle] and guarded the same way. */
    val artistName: String,
    /** The status as modelled. Branch on this; print [statusToken] when it is [RequestOutcome.OTHER]. */
    val status: RequestOutcome,
    /** Exactly what the server's `status` field said, kept so an unmodelled state is still reportable. */
    val statusToken: String,
    val target: RequestTarget = RequestTarget.ALBUM,
    /** Set on a track request, where the album title names the release the track came from. */
    val trackTitle: String? = null,
    val requestedAt: Instant? = null,
    val completedAt: Instant? = null,
    /** True when the album is now in the library, which is not the same as the request completing. */
    val inLibrary: Boolean = false,
    /** Who approved or declined it, when the server records a name. */
    val reviewedByName: String? = null,
    val reviewedAt: Instant? = null,
    val year: Int? = null,
) {
    /** Whether to offer `POST /api/v1/requests/retry/{mbid}`; see [RequestOutcome.isRetryable]. */
    public val canRetry: Boolean get() = status.isRetryable

    /** When this entry last changed, for the relative day at the end of its line. */
    public val happenedAt: Instant? get() = completedAt ?: reviewedAt ?: requestedAt
}

/**
 * One page of `GET /api/v1/requests/history`.
 *
 * **This is the request lane's only paged endpoint, and the only one with real totals.** The
 * contrast is worth stating where the type lives: `GET /api/v1/downloads` has no `total` and no
 * `total_pages`, so REQUIREMENTS.md rules that "an infinitely scrolling list is fine; a 'page 3 of
 * 7' control is not implementable" there. Here both are reported, so a count *is* implementable and
 * [total] is the honest number to put beside the list. `requests/active` and `requests/wanted` have
 * no paging at all and are not modelled as pages for that reason.
 */
public data class RequestHistoryPage(
    val entries: List<RequestHistoryEntry>,
    /** One-based, as the server counts. */
    val page: Int,
    val pageSize: Int,
    /** Every entry the filter matches, not just this page. */
    val total: Int,
    val totalPages: Int,
) {
    /**
     * Whether asking for [page] + 1 could return anything.
     *
     * Three answers in preference order, because only the first is the server's own. The totals are
     * what this endpoint promises; the page-count arithmetic covers a server that sends `total` but
     * not `total_pages`; and the last branch is the blind test `/downloads` is stuck with, kept here
     * only so a server that reports neither still scrolls rather than stopping at one page.
     */
    public val hasMore: Boolean
        get() = when {
            totalPages > 0 -> page < totalPages
            total > 0 && pageSize > 0 -> page.toLong() * pageSize.toLong() < total.toLong()
            else -> pageSize > 0 && entries.size >= pageSize
        }

    public companion object {
        /** The answer for a lane that has never been asked. */
        public val Empty: RequestHistoryPage = RequestHistoryPage(
            entries = emptyList(),
            page = 1,
            pageSize = 0,
            total = 0,
            totalPages = 0,
        )
    }
}

/** Why the server is still watching for something: it has none of it, or only part of it. */
public enum class WantedGap {
    /** Nothing of this release group has been found. */
    MISSING,

    /** Some of it landed and the rest is still being looked for. */
    PARTIAL,

    /** A `kind` this client does not model. */
    OTHER,
    ;

    public companion object {
        public fun fromServerToken(token: String?): WantedGap = when (token?.trim()?.lowercase()) {
            "missing" -> MISSING
            "partial" -> PARTIAL
            else -> OTHER
        }
    }
}

/** How alive a standing watch is. The server's own four words, plus one for anything else. */
public enum class WantedWatchState {
    /** Being checked on a schedule. */
    WATCHING,

    /** Still recorded, checked rarely: the server has stopped expecting this one soon. */
    DORMANT,

    /** No longer checked. */
    STOPPED,

    /** It arrived. The watch is kept as a record. */
    FULFILLED,

    OTHER,
    ;

    /** True while the server will look again without being asked. */
    public val isLive: Boolean get() = this == WATCHING || this == DORMANT

    public companion object {
        public fun fromServerToken(token: String?): WantedWatchState =
            when (token?.trim()?.lowercase()) {
                "watching", "active" -> WATCHING
                "dormant" -> DORMANT
                "stopped", "paused" -> STOPPED
                "fulfilled", "found" -> FULFILLED
                else -> OTHER
            }
    }
}

/**
 * A standing watch: music the server could not find, and keeps looking for.
 *
 * ## The timestamps here are epoch seconds, not ISO-8601
 *
 * This is the trap in this lane and it is worth stating twice. `GET /api/v1/requests/wanted` sits in
 * the same `/api/v1/requests` namespace as the history list, and disagrees with it: every `*_at`
 * here is **epoch seconds as a float**, the `/downloads` convention, while `requests/history` and
 * `requests/active` send ISO-8601 strings. REQUIREMENTS.md only warns that the `/requests` and
 * `/downloads` lanes differ; the split runs one level deeper than that, inside `/requests` itself.
 * `WireTime` in `:core:data` has a reader for each, and neither can be assumed from the path.
 */
public data class WantedWatch(
    val releaseGroupMbid: ReleaseGroupMbid,
    /** May be blank; guard every render site, as [Pull.albumTitle] documents. */
    val albumTitle: String,
    /** May be blank; guarded the same way. */
    val artistName: String,
    val gap: WantedGap,
    val state: WantedWatchState,
    /** The server's raw `state`, printed when [state] is [WantedWatchState.OTHER]. */
    val stateToken: String,
    /** How many times the server has looked so far. */
    val checkCount: Int = 0,
    /** Sources that have appeared since the last check and not yet been tried. */
    val newCandidateCount: Int = 0,
    val lastCheckedAt: Instant? = null,
    /** When the server will look again. In the future, so it formats as a countdown, not an age. */
    val nextCheckAt: Instant? = null,
    /** What the last look turned up, in the server's own words. */
    val lastOutcome: String? = null,
    val createdAt: Instant? = null,
    val year: Int? = null,
)

/**
 * An album the server is actively re-attempting, with an attempt budget.
 *
 * Carried separately from [WantedWatch] because the server counts it separately: `count` on the
 * response counts the watches only. A retrying item is not a dormant wish, it is a download the
 * server is about to try again, so it reads as a countdown and an attempt number rather than as a
 * watch state.
 */
public data class WantedRetry(
    val releaseGroupMbid: ReleaseGroupMbid,
    /** May be blank; guard every render site. */
    val albumTitle: String,
    /** May be blank; guarded the same way. */
    val artistName: String,
    val retryCount: Int = 0,
    val maxAttempts: Int = 0,
    /** Epoch seconds on the wire, like everything else on this lane. */
    val nextRetryAt: Instant? = null,
    val year: Int? = null,
)

/**
 * The whole of `GET /api/v1/requests/wanted`.
 *
 * Not a page, and deliberately not shaped like one: this endpoint has **no paging at all** and
 * returns the entire list, exactly as `requests/active` does. Modelling it as a page would invite a
 * caller to ask for a second one, which the server has no way of answering.
 */
public data class WantedList(
    val watches: List<WantedWatch> = emptyList(),
    val retrying: List<WantedRetry> = emptyList(),
) {
    public val isEmpty: Boolean get() = watches.isEmpty() && retrying.isEmpty()

    /** Everything on the list. The response's own `count` covers [watches] only. */
    public val size: Int get() = watches.size + retrying.size

    public companion object {
        public val Empty: WantedList = WantedList()
    }
}

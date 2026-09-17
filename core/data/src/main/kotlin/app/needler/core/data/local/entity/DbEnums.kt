package app.needler.core.data.local.entity

/*
 * Storage-level enumerations.
 *
 * These are deliberately *not* the `:core:domain` types. Three reasons, all of them about the fact
 * that a column value is a persisted fact:
 *
 *  1. Every constant carries an explicit [dbValue] string that is frozen for the life of the
 *     database. Domain names may be refactored freely; column contents may not, because a rename
 *     would need a data migration over a mirror that can hold tens of thousands of rows.
 *  2. The domain models states that carry payloads (`AlbumState.Acquiring(progress)`,
 *     `OfflineDownloadState.Downloading(...)`). A column can only hold the discriminator, so the
 *     payload lives in sibling columns and the two are recombined by the entity -> domain mappers,
 *     which are owned by a later wave and are deliberately absent from this package.
 *  3. `fromDbValue` never throws. A row written by a newer build, or corrupted, degrades to a safe
 *     fallback instead of crashing every query that touches the table.
 */

/**
 * Discriminator for the album state machine (REQUIREMENTS.md "Album states").
 *
 * Payload columns that go with it: `pull.percent` / `pull.status` for [ACQUIRING],
 * `pin.*` for [PINNED], `pull.error` for [FAILED].
 */
public enum class AlbumStateDb(public val dbValue: String) {
    NOT_OWNED("not_owned"),
    PENDING_APPROVAL("pending_approval"),
    ACQUIRING("acquiring"),
    OWNED("owned"),
    PINNED("pinned"),
    FAILED("failed"),
    ;

    /** True for the two states in which the server holds playable audio. */
    public val isInLibrary: Boolean get() = this == OWNED || this == PINNED

    public companion object {
        public fun fromDbValue(value: String?): AlbumStateDb =
            entries.firstOrNull { it.dbValue == value } ?: NOT_OWNED

        /** The states that make an album part of the owned library, as SQL literals. */
        public val IN_LIBRARY_DB_VALUES: List<String> = listOf(OWNED.dbValue, PINNED.dbValue)
    }
}

/** Why an album is pinned. Mirrors `PinSource` in `:core:domain`. */
public enum class PinSourceDb(public val dbValue: String) {
    /** The user tapped "Pull local". */
    MANUAL("manual"),

    /** "Keep pulled albums on device" auto-pinned an album this device pulled. */
    AUTO_PULLED("auto_pulled"),
    ;

    public companion object {
        public fun fromDbValue(value: String?): PinSourceDb =
            entries.firstOrNull { it.dbValue == value } ?: MANUAL
    }
}

/**
 * Discriminator for `OfflineDownloadState`: how far a pinned album's audio has got onto the device.
 *
 * The counts and byte totals the domain's `Downloading`/`Partial` states carry live in
 * `pin.tracks_complete`, `pin.tracks_total`, `pin.downloaded_bytes` and `pin.total_bytes`.
 */
public enum class DownloadStateDb(public val dbValue: String) {
    /** Pinned, nothing fetched yet. */
    QUEUED("queued"),
    DOWNLOADING("downloading"),

    /** Held because "Download to device on Wi-Fi only" is on and the connection is metered. */
    WAITING_FOR_UNMETERED("waiting_for_unmetered"),

    /** Every track is on the device: this is what draws the green check on the artwork. */
    COMPLETE("complete"),

    /** Some tracks are on the device; the rest failed or were evicted as stale. */
    PARTIAL("partial"),
    FAILED("failed"),
    ;

    public companion object {
        public fun fromDbValue(value: String?): DownloadStateDb =
            entries.firstOrNull { it.dbValue == value } ?: QUEUED

        /** States in which the downloader still has work to do for this pin. */
        public val UNFINISHED_DB_VALUES: List<String> =
            listOf(QUEUED.dbValue, DOWNLOADING.dbValue, WAITING_FOR_UNMETERED.dbValue, PARTIAL.dbValue)
    }
}

/**
 * Server-side acquisition status, plus the two states the web UI derives client-side.
 *
 * The server's own task statuses are `queued`, `downloading`, `processing`, `completed`, `partial`,
 * `failed` and `cancelled`. [SEARCHING] is `queued` with no `search_job_id`; [NEEDS_ATTENTION] is
 * `queued` with a `search_job_id` but no `candidate_index`. Both derivations need
 * `pull.search_job_id` and `pull.candidate_index`, which is why those columns exist even though
 * REQUIREMENTS.md's table does not list them.
 */
public enum class PullStatusDb(public val dbValue: String) {
    /** Requested by a role that needs approval; the Pulls screen badges this "Waiting". */
    PENDING_APPROVAL("pending_approval"),

    /** Derived: `queued` with no `search_job_id`. */
    SEARCHING("searching"),

    /** Derived: a manual source pick is parked on the server. */
    NEEDS_ATTENTION("needs_attention"),
    QUEUED("queued"),
    DOWNLOADING("downloading"),
    PROCESSING("processing"),
    COMPLETED("completed"),
    PARTIAL("partial"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ;

    /**
     * Cancel is offered only while searching, queued or downloading. The server refuses cancellation
     * during `processing`, because files are being moved at that point.
     */
    public val isCancellable: Boolean
        get() = this == SEARCHING || this == NEEDS_ATTENTION || this == QUEUED || this == DOWNLOADING

    /** Retry is offered on failed, cancelled and partial tasks. */
    public val isRetryable: Boolean get() = this == FAILED || this == CANCELLED || this == PARTIAL

    public companion object {
        public fun fromDbValue(value: String?): PullStatusDb =
            entries.firstOrNull { it.dbValue == value } ?: QUEUED

        /** Everything the Pulls screen buckets as Active, as SQL literals. */
        public val ACTIVE_DB_VALUES: List<String> = listOf(
            PENDING_APPROVAL.dbValue,
            SEARCHING.dbValue,
            NEEDS_ATTENTION.dbValue,
            QUEUED.dbValue,
            DOWNLOADING.dbValue,
            PROCESSING.dbValue,
        )

        public val FAILED_DB_VALUES: List<String> =
            listOf(FAILED.dbValue, CANCELLED.dbValue, PARTIAL.dbValue)
    }
}

/** What a row in `favourite` points at. Needler has binary favourites only; `setRating` is a no-op. */
public enum class FavouriteTypeDb(public val dbValue: String) {
    ALBUM("album"),
    ARTIST("artist"),
    TRACK("track"),
    ;

    public companion object {
        public fun fromDbValue(value: String?): FavouriteTypeDb =
            entries.firstOrNull { it.dbValue == value } ?: ALBUM
    }
}

/**
 * The mutations journalled in the offline write queue and replayed in sequence order on reconnect
 * (REQUIREMENTS.md "Write queue").
 *
 * The operation's arguments travel as JSON in `write_queue.payload`; this enum only says which
 * replayer to hand the row to.
 */
public enum class WriteOperationTypeDb(public val dbValue: String) {
    /** `POST /api/v1/requests/new`. Discarded silently if the album arrived by other means. */
    PULL_REQUEST("pull_request"),
    PULL_CANCEL("pull_cancel"),
    PULL_RETRY("pull_retry"),
    PLAYLIST_CREATE("playlist_create"),
    PLAYLIST_RENAME("playlist_rename"),
    PLAYLIST_ADD_TRACKS("playlist_add_tracks"),
    PLAYLIST_REMOVE_TRACKS("playlist_remove_tracks"),
    PLAYLIST_REORDER("playlist_reorder"),
    PLAYLIST_DELETE("playlist_delete"),
    STAR("star"),
    UNSTAR("unstar"),

    /** Submitted with its original timestamp, never with the replay time. */
    SCROBBLE("scrobble"),
    ;

    public companion object {
        public fun fromDbValue(value: String?): WriteOperationTypeDb =
            entries.firstOrNull { it.dbValue == value } ?: PULL_REQUEST
    }
}

package app.needler.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The offline write journal: "Every mutation made offline is journalled and replayed in order on
 * reconnect: pulls, playlist changes, star and unstar, scrobbles."
 *
 * [seq] is an auto-generated INTEGER primary key, which in SQLite is the rowid and therefore
 * monotonically increasing - that is the "monotonic sequence" the requirements ask for, and replay
 * order is simply `ORDER BY seq ASC`. Rows are deleted on success and dropped after a permanent
 * rejection, with a notice to the user.
 *
 * Secrets never appear in [payload]. The payload carries MBIDs, playlist ids, track keys and
 * timestamps; credentials live in EncryptedSharedPreferences and are attached by the network layer
 * at replay time.
 */
@Entity(
    tableName = "write_queue",
    indices = [
        Index(value = ["operation_type"], name = "index_write_queue_operation_type"),
        // "A pull for an album that arrived by other means while offline is discarded silently"
        // needs to find queued entries by what they target, without parsing every payload.
        Index(value = ["entity_key"], name = "index_write_queue_entity_key"),
        Index(value = ["next_attempt_at"], name = "index_write_queue_next_attempt_at"),
    ],
)
public data class WriteQueueEntity(

    /** Monotonic sequence. Replay is strictly in this order. */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "seq")
    val seq: Long = 0L,

    @ColumnInfo(name = "operation_type")
    val operationType: WriteOperationTypeDb,

    /**
     * What this operation targets, for cheap deduplication and cancellation: a release-group MBID, a
     * playlist id, or a track's canonical key. Null for operations that target nothing in
     * particular.
     */
    @ColumnInfo(name = "entity_key")
    val entityKey: String?,

    /** JSON arguments for the replayer. Never contains a bearer, app-password or URL credential. */
    @ColumnInfo(name = "payload")
    val payload: String,

    /** Replay attempts so far. Backoff is computed from this, and it bounds the retry loop. */
    @ColumnInfo(name = "attempts", defaultValue = "0")
    val attempts: Int = 0,

    /** Last transport or protocol error, already redacted. */
    @ColumnInfo(name = "last_error")
    val lastError: String?,

    /**
     * Epoch milliseconds before which replay must not be attempted again (exponential backoff with
     * jitter, capped at five minutes per the failure-handling table). 0 means "as soon as possible".
     */
    @ColumnInfo(name = "next_attempt_at", defaultValue = "0")
    val nextAttemptAt: Long = 0L,

    /**
     * Epoch milliseconds the mutation was made. Scrobbles must be submitted with **this** timestamp,
     * not the replay time, or an offline listening session lands in the wrong hour.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

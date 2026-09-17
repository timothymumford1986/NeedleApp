package app.needler.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.needler.core.data.local.entity.WriteQueueEntity
import kotlinx.coroutines.flow.Flow

/**
 * The offline write journal.
 *
 * Insert-only plus delete: entries are never updated except to record a failed attempt. Replay is
 * strictly `ORDER BY seq ASC`, because the operations are not commutative - adding tracks to a
 * playlist that a later entry renames must happen in the order the user did it.
 */
@Dao
public interface WriteQueueDao {

    /**
     * Journals a mutation and returns its sequence number.
     *
     * `@Insert` with the default ABORT strategy, deliberately: a write queue that silently replaced
     * rows would drop a mutation, and every entry is meant to be distinct even when it targets the
     * same album twice.
     */
    @Insert
    public suspend fun enqueue(entry: WriteQueueEntity): Long

    @Insert
    public suspend fun enqueueAll(entries: List<WriteQueueEntity>): List<Long>

    /** The next batch to replay: due entries, in sequence order. */
    @Query(
        """
        SELECT * FROM write_queue
        WHERE next_attempt_at <= :now
        ORDER BY seq ASC
        LIMIT :limit
        """,
    )
    public suspend fun getDue(now: Long, limit: Int): List<WriteQueueEntity>

    @Query("SELECT * FROM write_queue ORDER BY seq ASC LIMIT :limit")
    public suspend fun peek(limit: Int): List<WriteQueueEntity>

    @Query("SELECT * FROM write_queue ORDER BY seq ASC")
    public fun observeQueue(): Flow<List<WriteQueueEntity>>

    /** Drives the "changes waiting to sync" affordance; zero means the device agrees with the server. */
    @Query("SELECT COUNT(*) FROM write_queue")
    public fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM write_queue WHERE operation_type = :operationType")
    public suspend fun countOfType(operationType: String): Int

    /**
     * Entries targeting one entity, for the discard rule: "A pull for an album that arrived by other
     * means while offline is discarded silently rather than replayed."
     */
    @Query(
        """
        SELECT * FROM write_queue
        WHERE entity_key = :entityKey AND operation_type = :operationType
        ORDER BY seq ASC
        """,
    )
    public suspend fun getByEntity(entityKey: String, operationType: String): List<WriteQueueEntity>

    /** Records a failed attempt and schedules the next one; backoff is computed by the caller. */
    @Query(
        """
        UPDATE write_queue
        SET attempts = attempts + 1, last_error = :error, next_attempt_at = :nextAttemptAt
        WHERE seq = :seq
        """,
    )
    public suspend fun recordFailure(seq: Long, error: String?, nextAttemptAt: Long)

    /** Removes a replayed entry. Success is the only ordinary reason an entry leaves the queue. */
    @Query("DELETE FROM write_queue WHERE seq = :seq")
    public suspend fun delete(seq: Long)

    @Query("DELETE FROM write_queue WHERE seq IN (:seqs)")
    public suspend fun deleteAll(seqs: List<Long>): Int

    /** Silent discard of entries a reconnect made pointless. */
    @Query("DELETE FROM write_queue WHERE entity_key = :entityKey AND operation_type = :operationType")
    public suspend fun deleteByEntity(entityKey: String, operationType: String): Int

    @Query("DELETE FROM write_queue")
    public suspend fun clear()
}

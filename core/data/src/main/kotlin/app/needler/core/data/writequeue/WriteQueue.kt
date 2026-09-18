package app.needler.core.data.writequeue

import app.needler.core.data.local.dao.WriteQueueDao
import app.needler.core.data.local.entity.WriteOperationTypeDb
import app.needler.core.data.local.entity.WriteQueueEntity
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.WriteOperation
import app.needler.core.domain.model.WriteQueueEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

/**
 * The journal of mutations made while offline.
 *
 * Every write the user makes with no connection - a pull, a playlist edit, a star, a scrobble - is
 * applied locally at once and recorded here, then replayed **in sequence order** on reconnect. The
 * ordering is the point: a create followed by an add-tracks that replayed the other way round would
 * add to a playlist the server has never heard of.
 *
 * Reading and writing the journal lives here; replaying it lives in [WriteQueueFlusher], because
 * replay needs every client and this needs none.
 */
public class WriteQueue(
    private val dao: WriteQueueDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * Journals one mutation and returns its sequence.
     *
     * [supersede] drops earlier pending writes about the same entity of the same kind. Starring an
     * album three times offline is one intent, not three, and replaying all three would triple the
     * request count for no change in outcome. It is deliberately *not* applied to scrobbles: two
     * plays of one track are two listens and both belong in the user's history.
     */
    public suspend fun enqueue(
        operation: WriteOperation,
        supersede: Boolean = true,
        entityKeyOverride: String? = null,
    ): Long {
        val encoded: EncodedWrite = WriteQueueCodec.encode(operation)
            .let { if (entityKeyOverride == null) it else it.copy(entityKey = entityKeyOverride) }
        if (supersede && encoded.entityKey != null && encoded.type.supersedable) {
            dao.deleteByEntity(encoded.entityKey, encoded.type.dbValue)
            // A star and an unstar of the same thing cancel each other in intent, so the opposite
            // pending write goes too - otherwise the replay order decides the outcome.
            encoded.type.opposite?.let { dao.deleteByEntity(encoded.entityKey, it.dbValue) }
        }
        return dao.enqueue(
            WriteQueueEntity(
                operationType = encoded.type,
                entityKey = encoded.entityKey,
                payload = encoded.payloadJson(),
                attempts = 0,
                lastError = null,
                nextAttemptAt = 0L,
                createdAt = nowMillis(),
            ),
        )
    }

    /** The queue in sequence order, for the diagnostics screen. */
    public fun observe(): Flow<List<WriteQueueEntry>> =
        dao.observeQueue().map { rows -> rows.mapNotNull(::toEntry) }

    public fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    public suspend fun due(limit: Int = DEFAULT_FLUSH_LIMIT): List<WriteQueueEntity> =
        dao.getDue(now = nowMillis(), limit = limit)

    public suspend fun delete(sequence: Long) {
        dao.delete(sequence)
    }

    /**
     * Records a retryable failure and backs the entry off.
     *
     * Exponential with a ceiling: a queue that retried a `5xx` every reconnect would hammer a server
     * that is already unwell, and one that gave up would lose the write.
     */
    public suspend fun recordFailure(sequence: Long, attempts: Int, error: NeedlerError) {
        dao.recordFailure(
            seq = sequence,
            error = error.diagnostic,
            nextAttemptAt = nowMillis() + backoffMillis(attempts + 1),
        )
    }

    public suspend fun clear() {
        dao.clear()
    }

    public fun toEntry(row: WriteQueueEntity): WriteQueueEntry? {
        val operation: WriteOperation = WriteQueueCodec.decode(row.operationType, row.payload)
            ?: return null
        return WriteQueueEntry(
            sequence = row.seq,
            operation = operation,
            attempts = row.attempts,
            lastError = row.lastError?.let { NeedlerError.Unexpected(it) },
            createdAt = Instant.fromEpochMilliseconds(row.createdAt),
        )
    }

    public companion object {
        public const val DEFAULT_FLUSH_LIMIT: Int = 200

        /** Five minutes, matching the `5xx` backoff ceiling in the failure table. */
        public const val MAX_BACKOFF_MILLIS: Long = 5L * 60L * 1_000L

        public const val BASE_BACKOFF_MILLIS: Long = 5_000L

        public fun backoffMillis(attempts: Int): Long {
            if (attempts <= 0) return 0L
            val shift: Int = (attempts - 1).coerceAtMost(MAX_SHIFT)
            val delay: Long = BASE_BACKOFF_MILLIS shl shift
            return delay.coerceAtMost(MAX_BACKOFF_MILLIS)
        }

        private const val MAX_SHIFT: Int = 10
    }
}

/**
 * True for operations where a later write about the same entity replaces an earlier one.
 *
 * Scrobbles never supersede: each one is a distinct listen with its own timestamp, and the server
 * wants all of them.
 */
public val WriteOperationTypeDb.supersedable: Boolean
    get() = this != WriteOperationTypeDb.SCROBBLE &&
        this != WriteOperationTypeDb.PLAYLIST_ADD_TRACKS &&
        this != WriteOperationTypeDb.PLAYLIST_REMOVE_TRACKS &&
        this != WriteOperationTypeDb.PLAYLIST_CREATE

/** The write whose intent this one reverses, so the two cancel rather than racing on replay. */
public val WriteOperationTypeDb.opposite: WriteOperationTypeDb?
    get() = when (this) {
        WriteOperationTypeDb.STAR -> WriteOperationTypeDb.UNSTAR
        WriteOperationTypeDb.UNSTAR -> WriteOperationTypeDb.STAR
        else -> null
    }

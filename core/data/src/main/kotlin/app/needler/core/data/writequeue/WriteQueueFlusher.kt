package app.needler.core.data.writequeue

import app.needler.core.data.local.dao.AlbumDao
import app.needler.core.data.local.dao.TrackDao
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.data.local.entity.WriteQueueEntity
import app.needler.core.data.mapper.EntityMappers
import app.needler.core.domain.model.DroppedWrite
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ScrobbleEvent
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.WriteOperation
import app.needler.core.domain.model.WriteQueueFlushReport

/**
 * Performs one journalled mutation against the server.
 *
 * Split from the flusher so that ordering, dropping and backoff - the parts that go wrong, and the
 * parts worth testing without a network - are decided in one place, and the HTTP is somewhere else.
 */
public interface WriteQueueExecutor {

    /**
     * @param entityKey the row's `entity_key`. Only one replay needs it: a playlist created offline
     *   carries its provisional local id here, and the executor re-keys the local row to the id the
     *   server assigns - an adopt-server-id step, not an insert.
     */
    public suspend fun execute(operation: WriteOperation, entityKey: String?): Outcome<Unit>
}

/**
 * Replays the offline write queue, in order, on reconnect.
 *
 * Three rules from the requirements live here, and each one is a bug if it is missing:
 *
 *  * **In sequence order, stopping at the first retryable failure.** Skipping ahead past a failed
 *    entry reorders the user's intent; an add-tracks that overtook its own create would be applied
 *    to a playlist that does not exist yet.
 *  * **A permanent rejection drops the entry** and is reported, so the user can be told what did not
 *    happen. A retryable one backs off and stays.
 *  * **A pull for an album that arrived by other means is discarded silently.** Re-requesting music
 *    the server already has is noise, and the user never asked for it twice.
 *
 * A fourth rule is subtler and is the reason a scrobble is not simply replayed: its Subsonic track
 * id is re-resolved from the mirror at flush time. `tr-<file id>` is a fetch handle, and a quality
 * upgrade between the play and the reconnect moves it, so replaying the stored one scrobbles a file
 * row the server has replaced.
 */
public class WriteQueueFlusher(
    private val queue: WriteQueue,
    private val executor: WriteQueueExecutor,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
) {

    public suspend fun flush(limit: Int = WriteQueue.DEFAULT_FLUSH_LIMIT): WriteQueueFlushReport {
        val due: List<WriteQueueEntity> = queue.due(limit)
        if (due.isEmpty()) return WriteQueueFlushReport(replayed = 0)

        var replayed = 0
        val dropped: MutableList<DroppedWrite> = ArrayList()
        var stoppedAt = -1

        for ((index, row) in due.withIndex()) {
            val operation: WriteOperation? = WriteQueueCodec.decode(row.operationType, row.payload)
            if (operation == null) {
                // Unreadable payload: a row this build cannot make sense of. Retrying cannot help,
                // and keeping it would block every later write for ever.
                queue.delete(row.seq)
                continue
            }

            if (shouldDiscardSilently(operation)) {
                queue.delete(row.seq)
                continue
            }

            val prepared: WriteOperation = prepare(operation)
            when (val result: Outcome<Unit> = executor.execute(prepared, row.entityKey)) {
                is Outcome.Success -> {
                    queue.delete(row.seq)
                    replayed++
                }
                is Outcome.Failure -> {
                    if (result.error.isRetryable) {
                        queue.recordFailure(row.seq, row.attempts, result.error)
                        // Everything after this entry stays queued: order is the contract.
                        stoppedAt = index
                    } else {
                        queue.delete(row.seq)
                        dropped.add(
                            DroppedWrite(
                                sequence = row.seq,
                                operation = prepared,
                                error = result.error,
                            ),
                        )
                    }
                }
            }
            if (stoppedAt >= 0) break
        }

        val remaining: Int = if (stoppedAt >= 0) due.size - stoppedAt else 0
        return WriteQueueFlushReport(
            replayed = replayed,
            dropped = dropped,
            remaining = remaining,
        )
    }

    /**
     * A queued pull for an album that is already in the library is not replayed.
     *
     * The album can have arrived while the phone was offline - another device requested it, or an
     * admin approved a queue that finished - and re-asking the server for music it holds is the kind
     * of noise an offline queue is supposed to avoid.
     */
    private suspend fun shouldDiscardSilently(operation: WriteOperation): Boolean {
        val request: WriteOperation.PlaceAlbumRequest =
            operation as? WriteOperation.PlaceAlbumRequest ?: return false
        val album: AlbumEntity? = albumDao.getAlbum(request.request.releaseGroupMbid.value)
        return album?.inLibrary == true
    }

    /**
     * Rewrites an operation with anything that must be read *now* rather than when it was queued.
     *
     * Only scrobbles need it, and they need it badly: the fetch handle recorded at play time may
     * name a file the server has since replaced.
     */
    private suspend fun prepare(operation: WriteOperation): WriteOperation {
        val scrobble: WriteOperation.SubmitScrobble =
            operation as? WriteOperation.SubmitScrobble ?: return operation
        val event: ScrobbleEvent = scrobble.scrobble
        val row: TrackEntity = trackDao.getTrack(
            releaseGroupMbid = event.trackKey.releaseGroupMbid.value,
            discNo = event.trackKey.discNumber,
            trackNo = event.trackKey.trackNumber,
        ) ?: return operation
        if (!EntityMappers.hasPlayableFile(row)) return operation
        val current: TrackFetchHandle = EntityMappers.fetchHandle(row)
        if (current.fileId == event.fetchHandle.fileId) return operation
        return WriteOperation.SubmitScrobble(event.copy(fetchHandle = current))
    }

    /** True when the queue should be flushed at all: nothing to do is the common case. */
    public suspend fun hasWork(): Boolean = queue.due(limit = 1).isNotEmpty()
}

/** A permanent rejection this layer raises itself, for a write nothing can ever land. */
internal fun unreplayable(detail: String): NeedlerError = NeedlerError.Rejected(message = detail)

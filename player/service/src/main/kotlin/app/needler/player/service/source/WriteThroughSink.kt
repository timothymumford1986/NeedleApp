package app.needler.player.service.source

import app.needler.core.domain.cache.AudioCacheWriteHandle
import app.needler.core.domain.model.Outcome
import kotlinx.coroutines.runBlocking

/**
 * Feeds the bytes a `DataSource` has just read into an open audio-store write, and decides at the end
 * whether they may be published.
 *
 * ## One fetch, not two
 *
 * REQUIREMENTS.md forbids the obvious implementation - stream the track, then download it again in the
 * background - and forbids Media3's own `CacheDataSource` for four separate reasons. What is left is this:
 * a thin wrapper that copies bytes on the way past. The track the user just heard is offline afterwards,
 * and the server was asked for it once.
 *
 * ## Never publish a partial file
 *
 * This is the rule the whole class exists for. A truncated file recorded as a complete cached track is the
 * worst failure the store has, because it is silent: months later a play finds a local file, plays it, and
 * stops two minutes into a four-minute song with nothing anywhere reporting an error. The user concludes
 * their music is corrupt.
 *
 * So [finish] commits only when the byte count matches what the server declared, and every other ending -
 * a cancelled load, a dropped connection, a seek away from the track, a process death - abandons. The
 * handle holds its bytes in a file nothing else can find until the commit, so an abandon leaves nothing
 * behind.
 *
 * ## A cache write may never fail playback
 *
 * Every call here swallows its own failures. The user is listening to music; a full disk, an unwritable
 * directory or a database that will not answer is not a reason to stop the song. A failed write simply
 * means the track is not kept.
 */
public class WriteThroughSink(
    private val handle: AudioCacheWriteHandle,
) {

    private var failed: Boolean = false

    /** Bytes accepted so far, as the store counts them. */
    public val bytesWritten: Long get() = handle.bytesWritten

    /** True once a write failed and the sink has given up. Playback is unaffected. */
    public val hasFailed: Boolean get() = failed

    /**
     * Appends what was just read.
     *
     * Blocking, because it is called from Media3's loading thread between `read` calls, which is the only
     * place the bytes exist. The store's own writes go to an I/O dispatcher inside the handle.
     */
    public fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (failed || length <= 0) return
        try {
            runBlocking { handle.write(buffer, offset, length) }
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            failed = true
        }
    }

    /**
     * Ends the write.
     *
     * ## What counts as complete
     *
     * Two kinds of evidence, and the stronger one is used whenever it exists:
     *
     *  * **The declared length was received in full.** Every byte the response said the file has is on
     *    disk. That is a measurement, and it settles the question on its own - a write that was
     *    abandoned mid-track cannot have reached the declared length, which is the case REQUIREMENTS.md
     *    "Offline and caching" requires to be discarded.
     *  * **The reader reached end-of-input**, used only when nothing declared a length. It is weaker: it
     *    says the body ended cleanly, not that the body was whole.
     *
     * End-of-input is deliberately **not** required on top of the byte count, and that is a correction
     * rather than a loosening. Media3 closes a `DataSource` when its load ends, and a load can end
     * having taken every byte without the reader asking once more and being told there are no
     * more - a track's last block landing exactly on the end of the body, a load cancelled a moment
     * after the final read. Demanding both discarded those writes, which is the same silent outcome as
     * never opening one: the track plays, nothing is kept, and no error is raised anywhere. The
     * download path has never had that requirement; it publishes on the byte count alone.
     *
     * A short body is still refused, for the reason the class exists.
     *
     * @param readToEnd whether the `DataSource` reported end-of-input rather than being closed early.
     * @return true when the bytes were published as a complete cached track.
     */
    public fun finish(readToEnd: Boolean): Boolean {
        if (failed) {
            abandon()
            return false
        }
        val expected: Long? = handle.expectedSizeBytes
        val complete: Boolean = if (expected != null) handle.bytesWritten == expected else readToEnd
        if (!complete) {
            // Short of the declared length, or an end nothing can vouch for. Either way these are
            // partial bytes, and committing them is the silent failure above, so they go in the bin.
            abandon()
            return false
        }
        return try {
            runBlocking { handle.commit() } is Outcome.Success
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            abandon()
            false
        }
    }

    /** Throws the partial bytes away. Idempotent, and safe after a commit. */
    public fun abandon() {
        try {
            runBlocking { handle.abandon() }
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
        }
        failed = true
    }
}

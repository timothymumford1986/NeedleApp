package app.needler.player.service.source

import app.needler.core.domain.cache.AudioCacheWriteHandle
import app.needler.core.domain.cache.AudioRetentionEvent
import app.needler.core.domain.diagnostics.DiagnosticsSink
import app.needler.core.domain.model.Outcome
import kotlin.math.min
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
 * So the bytes are published only when the byte count matches what the server declared, and every other
 * ending - a cancelled load, a dropped connection, a seek away from the track, a process death - abandons.
 * The handle holds its bytes in a file nothing else can find until the commit, so an abandon leaves
 * nothing behind.
 *
 * ## The store decides, and the store reports
 *
 * [finish] no longer pre-checks the byte count itself. That check used to live here *as well as* in
 * `AudioCacheStoreWriter.commit`, and the duplicate is what hid the defect this class was losing every
 * track to: the sink compared the counts, abandoned without a word, and the store - which is the one
 * place that writes a line per outcome - never heard that a write had ended at all. Ten instrumented
 * log points produced nothing, because the eleventh exit was this one. So every ending with a declared
 * length now goes to [AudioCacheWriteHandle.commit] and the store answers, publishes or refuses, and
 * says which. The one decision left here is the one the store cannot see: whether a response that
 * declared no length ended cleanly.
 *
 * ## A reader may stop before the end of the file
 *
 * [fillTail] exists because "the player read the whole track" and "the player read the whole file" are
 * not the same sentence, and the difference was discarding every stream on the device. See its KDoc.
 *
 * ## A cache write may never fail playback
 *
 * Every call here swallows its own failures. The user is listening to music; a full disk, an unwritable
 * directory or a database that will not answer is not a reason to stop the song. A failed write simply
 * means the track is not kept.
 */
public class WriteThroughSink(
    private val handle: AudioCacheWriteHandle,
    /**
     * Where the two endings the store cannot see are written down.
     *
     * Only two: a write that failed on the way in, and a body that declared no length and did not end
     * cleanly. Every other outcome is reported by the store from inside `commit`, which is the point of
     * handing the decision back to it - see the class comment.
     */
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.None,
) {

    private var failed: Boolean = false

    /** Bytes accepted so far, as the store counts them. */
    public val bytesWritten: Long get() = handle.bytesWritten

    /** The length this write is held to, or null when neither the response nor the mirror declared one. */
    public val expectedSizeBytes: Long? get() = handle.expectedSizeBytes

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
     * Takes the bytes the reader left behind, so the file on disk is the file the server served.
     *
     * ## The defect this exists for
     *
     * An extractor reads the *track*, and a file is not only its track. `Mp3Extractor` asks its seeker
     * where the audio data ends and reports end-of-stream there: with a Xing or VBRI header, that is the
     * byte count the encoder wrote into the header, and anything appended after it - an ID3v1 tag is 128
     * bytes, Lyrics3v2 and APEv2 are larger - is never requested. `DefaultExtractorInput` never reads
     * more from a `DataSource` than the extractor asked for, so the data source is closed a tag's worth
     * of bytes short of `Content-Length`, on a track that played perfectly from the first byte to the
     * last. The store then measured the write against the length the response declared, found it short,
     * and discarded it. Every play, every track, nothing retained - which is what a device reporting
     * `Cached while listening 0 B` beside `Downloaded 440 MB` looks like from the outside.
     *
     * ## Why taking the tail is honest
     *
     * These bytes are the real tail of the same HTTP response, read in order from the same open
     * connection: the file on disk afterwards is byte-for-byte what the server sent, and the store's
     * completeness check is unchanged and still decides. Nothing is inferred, padded or assumed. The
     * alternative considered and rejected was to relax the check to "close enough" - which is precisely
     * the rule that publishes a truncated file as a complete track, the one failure this path may never
     * have.
     *
     * ## Why it is bounded
     *
     * [MAX_TAIL_BYTES]. A reader also stops short when the *user* stops it - a skip, a seek, a
     * cancelled load - and draining that would fetch the rest of a track nobody is listening to, on a
     * connection the user may be paying for by the megabyte. A trailing metadata block is kilobytes; the
     * remainder of an abandoned track is megabytes, so a flat cap separates them without having to guess
     * at Media3's reason. A shortfall above the cap is left alone and [finish] lets the store refuse it,
     * which now writes the two byte counts to the log rather than saying nothing.
     *
     * @param read reads from the still-open response, with the same contract as `DataSource.read`: the
     *   number of bytes read, or a non-positive value at the end of the body.
     * @return how many tail bytes were taken, which is zero whenever there was nothing to take.
     */
    public fun fillTail(read: (ByteArray, Int, Int) -> Int): Long {
        if (failed) return 0L
        val expected: Long = handle.expectedSizeBytes ?: return 0L
        val shortfall: Long = expected - handle.bytesWritten
        if (shortfall <= 0L || shortfall > MAX_TAIL_BYTES) return 0L

        val buffer = ByteArray(min(shortfall, TAIL_BUFFER_BYTES.toLong()).toInt())
        var taken = 0L
        try {
            while (taken < shortfall) {
                val wanted: Int = min(shortfall - taken, buffer.size.toLong()).toInt()
                val got: Int = read(buffer, 0, wanted)
                if (got <= 0) break
                write(buffer, 0, got)
                if (failed) break
                taken += got
            }
        } catch (error: Throwable) {
            // A cancelled load closes the source on an interrupted thread, so a throw here is the
            // ordinary way this ends. The byte counts are left to speak for themselves: finish()
            // hands them to the store, which refuses the write and says by how much.
            if (error is InterruptedException) Thread.currentThread().interrupt()
        }

        report(
            AudioRetentionEvent.ReaderStoppedShort(
                key = handle.key,
                shortfallBytes = shortfall,
                tailBytes = taken,
                declaredBytes = expected,
            ),
        )
        return taken
    }

    /**
     * Ends the write.
     *
     * ## Who decides
     *
     * The store does, for every write that was held to a length: [AudioCacheWriteHandle.commit] compares
     * the bytes on disk with the length this write was opened against, publishes or refuses, and writes
     * the line that says which. Deciding it here as well is what made a refusal invisible - see the
     * class comment - so the only case left in this function is the one the store has no way to judge:
     * a response that declared no length at all. There, end-of-input is all the evidence there is, and
     * it is weak evidence: it says the body ended cleanly, not that the body was whole. An early close
     * on such a body is therefore refused, and now says so.
     *
     * End-of-input is deliberately **not** required on top of a matching byte count. Media3 closes a
     * `DataSource` when its load ends, and a load can end having taken every byte without the reader
     * asking once more and being told there are no more - a track's last block landing exactly on the
     * end of the body, a load cancelled a moment after the final read. Demanding both discarded those
     * writes, which is the same silent outcome as never opening one. The download path has never had
     * that requirement; it publishes on the byte count alone.
     *
     * @param readToEnd whether the `DataSource` reported end-of-input rather than being closed early.
     * @return true when the bytes were published as a complete cached track.
     */
    public fun finish(readToEnd: Boolean): Boolean {
        if (failed) {
            // The handle has already thrown its partial away, and commit() would answer with an error
            // the caller has nowhere to show. One line, because nothing else in the app mentions it.
            report(AudioRetentionEvent.WriteFailed(key = handle.key, writtenBytes = handle.bytesWritten))
            abandon()
            return false
        }
        if (handle.expectedSizeBytes == null && !readToEnd) {
            report(
                AudioRetentionEvent.IncompleteWrite(
                    key = handle.key,
                    writtenBytes = handle.bytesWritten,
                    declaredBytes = null,
                ),
            )
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

    private fun report(event: AudioRetentionEvent) {
        diagnostics.record(event.level, event.line)
    }

    internal companion object {

        /**
         * The largest shortfall [fillTail] will read, in bytes.
         *
         * 64 KiB, chosen to sit between the two things a short read means. Above it is a track the user
         * moved away from, where the remainder is the rest of the song and fetching it would spend the
         * user's data on audio nobody asked for. Below it is trailing metadata an encoder appended: ID3v1
         * is 128 bytes, a Lyrics3v2 or APEv2 block a few kilobytes, and one more read of a connection
         * that is about to be closed costs nothing measurable. A tag larger than this is left to the
         * store to refuse, which logs the exact byte counts - so the case becomes diagnosable rather
         * than silent, which is the property that was missing.
         */
        internal const val MAX_TAIL_BYTES: Long = 64L * 1024L

        /** One read's worth. Small, because the whole point is that there is very little left. */
        private const val TAIL_BUFFER_BYTES: Int = 8 * 1024
    }
}

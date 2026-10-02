package app.needler.core.domain.cache

import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlayableSource
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey

/**
 * Writes streamed audio into the on-device store as it is read, so a track the user just listened to
 * is already offline without a second fetch.
 *
 * ## Why Needler does not use Media3's cache
 *
 * Media3 ships `CacheDataSource` over `SimpleCache`, and the obvious thing to do is to point it at a
 * directory and be finished. Needler deliberately does not: **all retained audio lives in the one
 * `audio_cache` store**, and Media3's cache is switched off. Four reasons, each of which would be a
 * silent product bug rather than a missing feature:
 *
 *  1. **Its evictor takes a fixed byte cap and deletes oldest-first.** That is precisely the storage
 *     budget this product removed. The bound here is the device's free-space floor, which scales with
 *     the volume and needs no preference; a byte cap cannot express it.
 *  2. **It has no concept of a download that must never be evicted.** Downloaded albums are exempt
 *     from eviction at every level of disk pressure, and a `LeastRecentlyUsedCacheEvictor` cannot be
 *     told that some of its bytes are not candidates.
 *  3. **It caches whatever passes through it.** A track streamed as MP3 320 on mobile data would
 *     become that FLAC's permanent offline copy: the next play finds bytes, plays them, and the user
 *     who owns a lossless file never learns that one commute quietly downgraded their library.
 *     [PlayableSource.Stream.cacheWhileStreaming] exists to prevent exactly that, and this interface
 *     is where it is enforced.
 *  4. **It holds no per-row fingerprint.** DroppedNeedle replaces files in place on a quality
 *     upgrade, so every cached row carries the `file_id`, size, duration and format it was fetched
 *     with ([TrackFetchHandle]) and the staleness check compares against that. A content-keyed blob
 *     cache has nowhere to put it.
 *
 * ## Shape of a write
 *
 * One fetch, not a download after playback: the player service opens a write for the stream it is
 * about to read, feeds the bytes through as they arrive, and commits when the body ends.
 * `:player:service` owns the `DataSource` that does the reading; this module owns the store, and
 * `:core:data` implements it over the cache index and app-private internal storage.
 */
public interface AudioCacheWriter {

    /**
     * Opens a write for the track [source] is about to stream, or returns null when these bytes must
     * not be retained.
     *
     * Null is an ordinary answer, not a failure: the track streams and plays exactly as it would
     * have, it is simply not kept. There are four reasons for it, and the caller does not need to
     * tell them apart:
     *
     *  * **The bytes are transcoded.** [PlayableSource.Stream.cacheWhileStreaming] is false. The
     *    resolver sets that flag from the format it resolved and nothing downstream may override it.
     *  * **Nothing declared a length.** Neither the response nor the mirror gave one, so there is no
     *    number to check the free-space floor against before the write and none to check the finished
     *    write against afterwards. See "Why the length is a parameter" below.
     *  * **There is not enough free space.** Retaining them would take the device below its
     *    free-space floor even after the whole cached-while-listening tier had been given up. The
     *    incoming bytes are skipped rather than forced in, and nothing extra is evicted for them:
     *    evicting further would cost the user recently played music and still leave the write unable
     *    to fit.
     *  * **They are already on the device.** A complete, non-stale row exists for this key, so the
     *    only thing a second write could achieve is to replace a good file with an identical one.
     *
     * ## The caller cannot tell them apart; the log can
     *
     * That rule is about the *caller*, and it is not an argument for silence. A device played three
     * tracks to completion and showed `Cached while listening 0 B`, and because every refusal here was
     * silent, diagnosing it took a full source trace - a correct refusal and a broken one are the same
     * `null`. So an implementation writes one line per outcome to the diagnostics log REQUIREMENTS.md
     * "Observability" already requires, through a sink it is given: see [AudioRetentionEvent] for the
     * nine lines and why each one earns its place - including the endings that come *after* this
     * function has handed a handle out, which is the half the first round of instrumentation missed.
     *
     * Widening this return type to carry the reason was considered and rejected. A caller handed a
     * reason will eventually act on one, and then retention policy is being decided at the call site
     * rather than in the one store that owns it - which is the coupling this interface exists to
     * prevent.
     *
     * The expected [TrackFetchHandle] comes from [PlayableSource.Stream.fetchHandle] and is
     * snapshotted onto the row at commit time. It is what the staleness check compares against on
     * every later sync, which is why it must be the handle the bytes were actually fetched with and
     * never the mirror's current value.
     *
     * ## Why the length is a parameter and not read off the handle
     *
     * [TrackFetchHandle.sizeBytes] is the size **the metadata mirror recorded at the last sync**, and
     * that is a description of the file rather than a measurement of the response. The two can
     * legitimately disagree: DroppedNeedle replaces files in place on a quality upgrade
     * (REQUIREMENTS.md "Invalidating upgraded files"), so a play between the upgrade and the next
     * sync receives the new file's bytes while the mirror still describes the old one. A server that
     * reports no size at all disagrees in the other direction.
     *
     * That number decides two things - whether the write fits above the free-space floor, and whether
     * the finished write is complete - so getting it from the wrong side is not cosmetic. Checking a
     * complete body against a stale size makes the write look truncated, which discards it; and
     * because the file name is derived from the track key, the same disagreement recurs on every
     * play. The symptom is a device that streams perfectly and retains nothing, with no error
     * anywhere - REQUIREMENTS.md "Offline and caching" records that failure once already.
     *
     * The download path reached this conclusion first and states it plainly: "The transfer's own view
     * of the file's length wins over the mirror's." This parameter is the streaming path being held to
     * the same rule, rather than each half of one store trusting a different number.
     *
     * @param declaredLengthBytes the length the response declared for the **whole file**, from
     *   `Content-Length`, or null when the caller has no such number. It must describe the whole
     *   file: a write-through only ever begins at byte zero and never asks for a range, so a length
     *   measured against anything else does not belong here. Null falls back to
     *   [TrackFetchHandle.sizeBytes], which is all the caller had before the response arrived.
     */
    public suspend fun openWrite(
        source: PlayableSource.Stream,
        declaredLengthBytes: Long? = null,
    ): AudioCacheWriteHandle?
}

/**
 * One in-progress write of a track's bytes.
 *
 * ## Abandon-safe, which is the whole point
 *
 * A handle holds *incomplete* bytes until [commit] says otherwise, and until then nothing in the app
 * can find them: no row claims them, so no play attempt, eviction pass or usage figure sees them.
 * That is deliberate. A half-written file committed as a complete cached track is the worst failure
 * this store has, because it is **silent**: the next play finds a local file, plays it, and stops
 * two minutes into a four-minute song with no error anywhere. The user concludes the music is
 * corrupt, and no log line disagrees.
 *
 * So the rules are:
 *
 *  * A handle that is never committed leaves nothing behind. [abandon] is what a cancelled playback,
 *    a failed connection, a seek away from the track, or a killed process amounts to.
 *  * [commit] refuses unless the bytes are complete. When the server declared a size, the written
 *    count must equal it; a short body is a truncated fetch however cleanly the connection closed.
 *  * Both are idempotent and a commit after an abandon fails rather than resurrecting the file.
 *
 * ## Bytes arrive in order, from the start
 *
 * [write] appends. A stream that begins at a non-zero offset - the user seeking into a track that is
 * not on the device - cannot produce a complete file, so no handle should be opened for it, and one
 * already open must be abandoned rather than fed the later bytes. Retaining the tail of a track as
 * though it were the track is the same silent failure as retaining a truncated one.
 */
public interface AudioCacheWriteHandle {

    /** The track these bytes belong to. Identity is the stable key, never the `file_id`. */
    public val key: TrackKey

    /**
     * The length this write is held to, or null when nothing declared one. [commit] checks against it.
     *
     * It is the length the **response** declared where there was one, and the mirror's recorded size
     * only as a fallback - see [AudioCacheWriter.openWrite] for why the order matters. Null means
     * neither side said, and then only the reader reaching the end of the body can say the file is
     * whole.
     */
    public val expectedSizeBytes: Long?

    /** How many bytes have been accepted so far. */
    public val bytesWritten: Long

    /**
     * Appends bytes as they are read from the network.
     *
     * Failures here do not throw: the write is abandoned and later calls are ignored, because a
     * cache write must never be able to fail the playback it is riding along with. The user is
     * listening to music; a full disk is not a reason to stop the song.
     */
    public suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)

    /**
     * Publishes the bytes as a complete cached track and returns the row that now describes them.
     *
     * Fails with [NeedlerError.ProtocolViolation] when fewer bytes arrived than the server declared,
     * with [NeedlerError.Cancelled] when the handle was already abandoned, and with
     * [NeedlerError.Unexpected] when the file could not be published. Every failure abandons: there
     * is no state in which a handle has failed to commit and its partial bytes are still on disk.
     */
    public suspend fun commit(): Outcome<CachedAudio>

    /**
     * Discards everything written and releases the handle. Safe to call twice, and safe to call
     * after [commit] - where it does nothing, because the bytes are no longer this handle's.
     */
    public suspend fun abandon()
}

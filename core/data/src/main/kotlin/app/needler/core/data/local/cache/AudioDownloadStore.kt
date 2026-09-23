package app.needler.core.data.local.cache

import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import java.io.File

/**
 * The write path for "Pull local": bytes fetched deliberately rather than as a side effect of
 * playing something.
 *
 * It is a second entry point on the **same store**, implemented by [AudioCacheStoreWriter], not a
 * second store. That matters more than it looks: the free-space floor, the never-retain-transcodes
 * rule, the deterministic file names and the "a partial file is never published as a complete
 * track" rule all live in that class, and a downloader with its own copy of them would be a copy
 * that drifts. One store, one flag deciding eviction, one place that decides whether bytes may be
 * kept.
 *
 * Two things differ from the streaming path, and only two:
 *
 *  1. **The part file survives, and it is the download's own.** `AudioCacheWriter.openWrite` deletes
 *     its partial before it starts, because a stream always begins at byte zero. A download must do
 *     the opposite: the bytes already on disk are what a `Range` GET resumes from, and deleting them
 *     would make every interruption cost the whole track again. That is the entire reason this
 *     interface exists rather than the domain one being reused - and the reason the two paths write
 *     to different partial files, so that playing a track cannot delete the download of it.
 *  2. **The row lands pinned.** These bytes were asked for, so they belong to the downloaded tier
 *     and are exempt from eviction from the moment they are published.
 *
 * The transcode rule needs no restating here because it is satisfied by construction: downloads use
 * `download?id=`, which serves original bytes only. `stream?id=` with a format is the only way to
 * receive a transcode and this path never builds one.
 */
public interface AudioDownloadStore {

    /**
     * Opens, or re-opens, the download of one track.
     *
     * Re-opening is the normal case after an interruption: the same [key] yields the same part file,
     * so [AudioDownloadSlot.Open.partFile] may already hold bytes and
     * [AudioDownloadSlot.Open.bytesOnDisk] says how many.
     *
     * @param expectedSizeBytes the server's declared size, used to make room before any bytes are
     *   written. Null means the server did not say; room is then checked against what arrives, which
     *   is weaker but better than refusing to download the track at all.
     */
    public suspend fun openDownload(
        key: TrackKey,
        fetchHandle: TrackFetchHandle,
        expectedSizeBytes: Long? = fetchHandle.sizeBytes,
    ): AudioDownloadSlot

    /**
     * Unlinks part files that stand for nothing, and returns how many went.
     *
     * A partial is worth keeping for exactly as long as it is the resume point of a track that is
     * not yet on the device; beyond that it is bytes nothing will read and nothing but an uninstall
     * would reclaim. A partial for a track that is *not* published is never touched, however old it
     * looks - the next attempt continues from it, and age is not evidence that it is wrong.
     *
     * Called by the downloader at the end of a pass rather than on a timer. A sweep needs to know
     * which downloads are in flight to be safe, and the only place that knows is the job doing them.
     */
    public suspend fun sweepOrphanedParts(keys: Collection<TrackKey>): Int
}

/** What [AudioDownloadStore.openDownload] found. */
public sealed interface AudioDownloadSlot {

    /**
     * Complete, current bytes are already on the device.
     *
     * Not an error and not a no-op to paper over: the track counts as downloaded, and the album's
     * progress must include it. This is what makes pinning an album you have been streaming nearly
     * free.
     */
    public data object AlreadyOnDevice : AudioDownloadSlot

    /**
     * Keeping these bytes would take the device below its free-space floor even with the whole
     * cached-while-listening tier given up.
     *
     * The caller stops and records why. It must **not** evict downloads to make room: REQUIREMENTS.md
     * is explicit that a device below the floor produces a warning and an offer to remove albums,
     * never an automatic deletion of something the user chose to keep.
     */
    public data object NoRoom : AudioDownloadSlot

    /** The store could not be opened for writing. Transient; the job retries. */
    public data object Unwritable : AudioDownloadSlot

    /**
     * A writable slot.
     *
     * The caller appends to [partFile] - through `RangeDownloader`, which is what issues the
     * `Range` header - and then calls [commit] with what the transfer reported, or [discard] when
     * the partial bytes have been proven wrong (a `416`, which means the length on disk does not
     * match the server's file any more).
     */
    public interface Open : AudioDownloadSlot {

        public val key: TrackKey

        /** Where the bytes go. Deterministic, so the next attempt finds the same partial file. */
        public val partFile: File

        /** Bytes already on disk from an earlier attempt: the offset a resume starts at. */
        public val bytesOnDisk: Long

        /** The size the server declared, when it declared one. */
        public val expectedSizeBytes: Long?

        /**
         * Publishes the bytes as a complete, pinned track.
         *
         * Fails rather than publishing when the transfer was short or the file could not be moved
         * into place. A truncated file published as complete is the silent failure this whole store
         * is built to prevent: a later play finds a local file, plays it, and stops halfway through
         * with nothing reporting an error.
         *
         * **A short file keeps its bytes.** Refusing to publish and throwing the partial away are
         * different decisions, and only the first belongs to a short file: those bytes are a valid
         * resume point, and deleting them turns one finalise failure into a fresh download of the
         * whole track - which, on a failure that repeats, is a device that retains nothing however
         * long the pull runs.
         *
         * The partial is unlinked in three cases only: when it is proven wrong, meaning more bytes
         * on disk than the server says the whole file has; when the move into place fails, because
         * bytes that cannot be published and cannot be named are not worth keeping; and when the
         * caller says so through [discard].
         *
         * @param completeLengthBytes the whole file's length as **the transfer** reported it, from
         *   `Content-Range` or `Content-Length`. It takes precedence over [expectedSizeBytes],
         *   which comes from the mirror and can legitimately be out of date after a server-side
         *   quality upgrade: trusting the mirror there would fail a download whose bytes are
         *   perfectly complete.
         */
        public suspend fun commit(completeLengthBytes: Long? = null): Outcome<CachedAudio>

        /** Throws the partial bytes away: what a `416` or an abandoned album means. */
        public suspend fun discard()
    }
}

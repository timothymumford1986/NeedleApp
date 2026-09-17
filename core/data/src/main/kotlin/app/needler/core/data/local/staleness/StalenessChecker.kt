package app.needler.core.data.local.staleness

import app.needler.core.data.local.TrackKeyDb
import app.needler.core.data.local.projection.CachedAudioSignatureRow

/**
 * What the server currently says about one track's file.
 *
 * Built from fresh `getAlbum` metadata. The [key] is the stable track key; [fileId] is only a fetch
 * handle, which is exactly why it is one of the things compared rather than something relied upon.
 */
public data class ServerTrackMetadata(
    val key: TrackKeyDb,
    val fileId: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val format: String?,
    val bitrateKbps: Int? = null,
)

/**
 * What the cache recorded about a file when its bytes were downloaded.
 *
 * Deliberately a snapshot taken at download time, not a read of the mirror: sync overwrites the
 * mirror with the server's new values, so comparing the mirror against itself would always say
 * "unchanged" and the user would silently keep the worse copy.
 */
public data class CachedAudioSignature(
    val key: TrackKeyDb,
    val filePath: String,
    val pinned: Boolean,
    val complete: Boolean,
    val sizeOnDiskBytes: Long,
    val sourceFileId: String?,
    val sourceSizeBytes: Long?,
    val sourceDurationMs: Long?,
    val sourceFormat: String?,
) {
    public companion object {
        /** Adapts the DAO projection, so callers do not repeat the field-by-field copy. */
        public fun from(row: CachedAudioSignatureRow): CachedAudioSignature = CachedAudioSignature(
            key = row.key,
            filePath = row.filePath,
            pinned = row.pinned,
            complete = row.complete,
            sizeOnDiskBytes = row.sizeOnDiskBytes,
            sourceFileId = row.sourceFileId,
            sourceSizeBytes = row.sourceSizeBytes,
            sourceDurationMs = row.sourceDurationMs,
            sourceFormat = row.sourceFormat,
        )
    }
}

/** Which of the four signals differed - recorded so the diagnostics log can explain a re-download. */
public enum class StalenessReason {
    /** The server replaced the file: a new track-files row id sits behind the same track. */
    FILE_ID_CHANGED,
    SIZE_CHANGED,
    DURATION_CHANGED,
    FORMAT_CHANGED,

    /**
     * The track is no longer in the server's list for this album - a re-import dropped or renumbered
     * it. The bytes cannot be re-fetched under this key, so they are simply deleted.
     */
    REMOVED_FROM_SERVER,
}

/** One cached track whose bytes must be discarded, and why. */
public data class StaleCacheEntry(
    val cached: CachedAudioSignature,
    /** The server's current metadata, or null when the track has gone from the album. */
    val fresh: ServerTrackMetadata?,
    val reasons: Set<StalenessReason>,
) {
    public val key: TrackKeyDb get() = cached.key

    /**
     * True when the bytes must be fetched again straight away: "delete them, and re-download
     * immediately if the track is pinned".
     *
     * Unpinned stale bytes are only deleted - they were a side effect of playback and will come back
     * on the next play, which is the cheaper choice on a metered connection.
     */
    public val requiresRedownload: Boolean get() = cached.pinned && fresh != null
}

/** The outcome of one staleness pass. */
public data class StalenessReport(
    val stale: List<StaleCacheEntry>,
    /** Cached rows that matched the server exactly. Reported so a sync can be logged honestly. */
    val unchangedCount: Int,
) {
    public val isEmpty: Boolean get() = stale.isEmpty()

    /** Bytes that will be freed by discarding the stale copies. */
    public val staleBytes: Long get() = stale.sumOf { it.cached.sizeOnDiskBytes }

    /** Files to delete, in the order they were found. */
    public val filePaths: List<String> get() = stale.map { it.cached.filePath }

    /** Keys to fetch again immediately, because they are pinned. */
    public val redownloadKeys: List<TrackKeyDb>
        get() = stale.filter { it.requiresRedownload }.map { it.key }

    public companion object {
        public fun empty(unchangedCount: Int = 0): StalenessReport =
            StalenessReport(stale = emptyList(), unchangedCount = unchangedCount)
    }
}

/**
 * Detects cached audio that the server has replaced.
 *
 * ## Why this exists at all
 *
 * DroppedNeedle upgrades files **in place** when a better source appears. The Subsonic track id is
 * `tr-<file_id>` and that `file_id` is a row id in the server's track-files table, so an upgrade
 * changes the handle while the music stays at the same place on the same record. REQUIREMENTS.md:
 * "On every album sync, compare each track's `file_id`, size, duration and format against the
 * cached record. Any difference means the bytes are stale: delete them, and re-download immediately
 * if the track is pinned. ... Without this rule, users silently keep listening to the lower-quality
 * file they cached months ago."
 *
 * That last sentence is the whole design constraint: the failure mode has no error, no crash and no
 * symptom a user can report. Nothing else in the app will catch it.
 *
 * ## Comparison rule for unknown values
 *
 * A difference is only reported when **both** sides have a value. A null on either side means "not
 * known", never "changed". This matters: if a server version stops reporting durations for a
 * format, treating null as a difference would declare the entire cache stale and re-download a
 * multi-gigabyte library - possibly over a metered connection - because of a metadata regression.
 * The SQL variants in
 * [app.needler.core.data.local.dao.AudioCacheDao.isStale] and
 * [app.needler.core.data.local.dao.AudioCacheDao.findStaleAgainstMirror] use the same rule.
 *
 * Formats are compared case- and whitespace-insensitively, since `FLAC`, `flac` and `Flac` are the
 * same container and no server guarantees which one it sends.
 *
 * Pure Kotlin: no Room, no Android, fully unit-tested.
 */
public object StalenessChecker {

    /**
     * Compares every cached row against fresh server metadata.
     *
     * @param cached the cache's fingerprints, typically from
     *   `AudioCacheDao.getAlbumSignatures(mbid)`.
     * @param fresh the server's current tracks. Must be the **complete** list for the albums
     *   covered by [cached] whenever [treatMissingAsRemoved] is true.
     * @param treatMissingAsRemoved when true, a cached row with no matching fresh track is stale
     *   with [StalenessReason.REMOVED_FROM_SERVER]. Pass false when [fresh] is partial - for
     *   instance a single-track refresh - or a partial list will delete the rest of the album.
     */
    public fun detect(
        cached: List<CachedAudioSignature>,
        fresh: List<ServerTrackMetadata>,
        treatMissingAsRemoved: Boolean = true,
    ): StalenessReport {
        if (cached.isEmpty()) return StalenessReport.empty()

        val freshByKey: Map<TrackKeyDb, ServerTrackMetadata> = fresh.associateBy { it.key }
        val stale: MutableList<StaleCacheEntry> = ArrayList()
        var unchanged = 0

        for (row in cached) {
            val current: ServerTrackMetadata? = freshByKey[row.key]
            if (current == null) {
                if (treatMissingAsRemoved) {
                    stale.add(
                        StaleCacheEntry(
                            cached = row,
                            fresh = null,
                            reasons = setOf(StalenessReason.REMOVED_FROM_SERVER),
                        ),
                    )
                } else {
                    unchanged++
                }
                continue
            }
            val reasons: Set<StalenessReason> = reasons(cached = row, fresh = current)
            if (reasons.isEmpty()) {
                unchanged++
            } else {
                stale.add(StaleCacheEntry(cached = row, fresh = current, reasons = reasons))
            }
        }

        return StalenessReport(stale = stale, unchangedCount = unchanged)
    }

    /** Convenience for the DAO projection, so sync does not map rows by hand. */
    public fun detectFromRows(
        cached: List<CachedAudioSignatureRow>,
        fresh: List<ServerTrackMetadata>,
        treatMissingAsRemoved: Boolean = true,
    ): StalenessReport = detect(
        cached = cached.map(CachedAudioSignature::from),
        fresh = fresh,
        treatMissingAsRemoved = treatMissingAsRemoved,
    )

    /**
     * The four-signal comparison for one track. An empty result means the cached bytes are still
     * the file the server is serving.
     */
    public fun reasons(
        cached: CachedAudioSignature,
        fresh: ServerTrackMetadata,
    ): Set<StalenessReason> {
        val reasons: MutableSet<StalenessReason> = LinkedHashSet(4)
        if (differs(cached.sourceFileId?.trim(), fresh.fileId?.trim())) {
            reasons.add(StalenessReason.FILE_ID_CHANGED)
        }
        if (differs(cached.sourceSizeBytes, fresh.sizeBytes)) {
            reasons.add(StalenessReason.SIZE_CHANGED)
        }
        if (differs(cached.sourceDurationMs, fresh.durationMs)) {
            reasons.add(StalenessReason.DURATION_CHANGED)
        }
        if (differs(normaliseFormat(cached.sourceFormat), normaliseFormat(fresh.format))) {
            reasons.add(StalenessReason.FORMAT_CHANGED)
        }
        return reasons
    }

    /** True when both values are known and they are not equal. See the class comment. */
    private fun <T> differs(cached: T?, fresh: T?): Boolean =
        cached != null && fresh != null && cached != fresh

    private fun normaliseFormat(value: String?): String? {
        val trimmed: String = value?.trim()?.lowercase() ?: return null
        return trimmed.ifEmpty { null }
    }
}

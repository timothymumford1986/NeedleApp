package app.needler.core.data.local.staleness

import app.needler.core.data.local.TrackKeyDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Staleness detection: catching the silent quality upgrade.
 *
 * DroppedNeedle replaces audio files in place when a better source appears, so cached bytes can
 * quietly become the older, worse copy. These tests fix the four signals the requirements name -
 * `file_id`, size, duration and format - and the two decisions that follow: delete always,
 * re-download immediately only when the track is pinned.
 */
public class StalenessCheckerTest {

    private val key = TrackKeyDb(releaseGroupMbid = "rg-1", discNo = 1, trackNo = 3)

    // ---------------------------------------------------------------- the four signals

    @Test
    public fun `an unchanged file is not stale`() {
        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(cached()),
            fresh = listOf(fresh()),
        )

        assertTrue(report.isEmpty)
        assertEquals(1, report.unchangedCount)
        assertEquals(0L, report.staleBytes)
    }

    @Test
    public fun `a changed file id is stale - this is the quality upgrade case`() {
        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(cached(sourceFileId = "8801")),
            fresh = listOf(fresh(fileId = "9402")),
        )

        assertEquals(1, report.stale.size)
        assertEquals(setOf(StalenessReason.FILE_ID_CHANGED), report.stale.single().reasons)
    }

    @Test
    public fun `a changed size is stale`() {
        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(cached(sourceSizeBytes = 30_000_000)),
            fresh = listOf(fresh(sizeBytes = 42_000_000)),
        )

        assertEquals(setOf(StalenessReason.SIZE_CHANGED), report.stale.single().reasons)
    }

    @Test
    public fun `a changed duration is stale`() {
        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(cached(sourceDurationMs = 210_000)),
            fresh = listOf(fresh(durationMs = 214_000)),
        )

        assertEquals(setOf(StalenessReason.DURATION_CHANGED), report.stale.single().reasons)
    }

    @Test
    public fun `a changed format is stale`() {
        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(cached(sourceFormat = "mp3")),
            fresh = listOf(fresh(format = "flac")),
        )

        assertEquals(setOf(StalenessReason.FORMAT_CHANGED), report.stale.single().reasons)
    }

    @Test
    public fun `an mp3 upgraded to flac reports every signal that moved`() {
        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(
                cached(
                    sourceFileId = "8801",
                    sourceSizeBytes = 9_000_000,
                    sourceDurationMs = 210_000,
                    sourceFormat = "mp3",
                ),
            ),
            fresh = listOf(
                fresh(
                    fileId = "9402",
                    sizeBytes = 41_000_000,
                    durationMs = 210_400,
                    format = "flac",
                ),
            ),
        )

        assertEquals(
            setOf(
                StalenessReason.FILE_ID_CHANGED,
                StalenessReason.SIZE_CHANGED,
                StalenessReason.DURATION_CHANGED,
                StalenessReason.FORMAT_CHANGED,
            ),
            report.stale.single().reasons,
        )
    }

    @Test
    public fun `format comparison ignores case and surrounding space`() {
        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(cached(sourceFormat = "FLAC")),
            fresh = listOf(fresh(format = " flac ")),
        )

        assertTrue(report.isEmpty)
    }

    // ---------------------------------------------------------------- unknown values

    @Test
    public fun `a value the server stopped reporting is not treated as a change`() {
        // A metadata regression on the server must not declare a multi-gigabyte cache stale.
        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(cached()),
            fresh = listOf(fresh(sizeBytes = null, durationMs = null, format = null)),
        )

        assertTrue(report.isEmpty)
        assertEquals(1, report.unchangedCount)
    }

    @Test
    public fun `a value the cache never recorded is not treated as a change`() {
        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(cached(sourceSizeBytes = null, sourceDurationMs = null)),
            fresh = listOf(fresh()),
        )

        assertTrue(report.isEmpty)
    }

    @Test
    public fun `an unknown value on one side does not mask a real change on another`() {
        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(cached(sourceFileId = "8801", sourceSizeBytes = null)),
            fresh = listOf(fresh(fileId = "9402", sizeBytes = 41_000_000)),
        )

        assertEquals(setOf(StalenessReason.FILE_ID_CHANGED), report.stale.single().reasons)
    }

    // ---------------------------------------------------------------- removal

    @Test
    public fun `a cached track the server no longer lists is stale`() {
        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(cached()),
            fresh = emptyList(),
        )

        val entry: StaleCacheEntry = report.stale.single()
        assertEquals(setOf(StalenessReason.REMOVED_FROM_SERVER), entry.reasons)
        // Nothing to fetch again: the track is gone from the album under this key.
        assertFalse(entry.requiresRedownload)
    }

    @Test
    public fun `a partial fresh list does not delete the rest of the album`() {
        val other = TrackKeyDb(releaseGroupMbid = "rg-1", discNo = 1, trackNo = 4)

        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(cached(), cached(key = other)),
            fresh = listOf(fresh()),
            treatMissingAsRemoved = false,
        )

        assertTrue(report.isEmpty)
        assertEquals(2, report.unchangedCount)
    }

    // ---------------------------------------------------------------- what happens next

    @Test
    public fun `a stale pinned track is re-downloaded immediately`() {
        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(cached(pinned = true, sourceFileId = "8801")),
            fresh = listOf(fresh(fileId = "9402")),
        )

        assertTrue(report.stale.single().requiresRedownload)
        assertEquals(listOf(key), report.redownloadKeys)
    }

    @Test
    public fun `a stale unpinned track is only deleted`() {
        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(cached(pinned = false, sourceFileId = "8801")),
            fresh = listOf(fresh(fileId = "9402")),
        )

        assertFalse(report.stale.single().requiresRedownload)
        assertTrue(report.redownloadKeys.isEmpty())
    }

    @Test
    public fun `the report totals the bytes a staleness sweep frees and lists the files`() {
        val second = TrackKeyDb(releaseGroupMbid = "rg-1", discNo = 1, trackNo = 4)

        val report: StalenessReport = StalenessChecker.detect(
            cached = listOf(
                cached(sourceFileId = "1", sizeOnDiskBytes = 1_000),
                cached(key = second, sourceFileId = "2", sizeOnDiskBytes = 2_500),
            ),
            fresh = listOf(
                fresh(fileId = "11"),
                fresh(key = second, fileId = "22"),
            ),
        )

        assertEquals(2, report.stale.size)
        assertEquals(3_500L, report.staleBytes)
        assertEquals(
            listOf("/audio/rg-1-1-3.flac", "/audio/rg-1-1-4.flac"),
            report.filePaths,
        )
        assertEquals(0, report.unchangedCount)
    }

    @Test
    public fun `an empty cache is never stale`() {
        val report: StalenessReport = StalenessChecker.detect(
            cached = emptyList(),
            fresh = listOf(fresh()),
        )

        assertTrue(report.isEmpty)
        assertEquals(0, report.unchangedCount)
    }

    @Test
    public fun `the single-track comparison is the same one the sweep uses`() {
        val reasons: Set<StalenessReason> = StalenessChecker.reasons(
            cached = cached(sourceFileId = "8801"),
            fresh = fresh(fileId = "9402"),
        )

        assertEquals(setOf(StalenessReason.FILE_ID_CHANGED), reasons)
        assertTrue(StalenessChecker.reasons(cached = cached(), fresh = fresh()).isEmpty())
    }

    private fun cached(
        key: TrackKeyDb = this.key,
        pinned: Boolean = false,
        sizeOnDiskBytes: Long = 41_000_000,
        sourceFileId: String? = "8801",
        sourceSizeBytes: Long? = 41_000_000,
        sourceDurationMs: Long? = 210_000,
        sourceFormat: String? = "flac",
    ): CachedAudioSignature = CachedAudioSignature(
        key = key,
        filePath = "/audio/" + key.releaseGroupMbid + "-" + key.discNo + "-" + key.trackNo + ".flac",
        pinned = pinned,
        complete = true,
        sizeOnDiskBytes = sizeOnDiskBytes,
        sourceFileId = sourceFileId,
        sourceSizeBytes = sourceSizeBytes,
        sourceDurationMs = sourceDurationMs,
        sourceFormat = sourceFormat,
    )

    private fun fresh(
        key: TrackKeyDb = this.key,
        fileId: String? = "8801",
        sizeBytes: Long? = 41_000_000,
        durationMs: Long? = 210_000,
        format: String? = "flac",
    ): ServerTrackMetadata = ServerTrackMetadata(
        key = key,
        fileId = fileId,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        format = format,
    )
}

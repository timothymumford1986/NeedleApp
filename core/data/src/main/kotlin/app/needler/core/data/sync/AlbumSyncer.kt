package app.needler.core.data.sync

import app.needler.core.data.local.TrackKeyDb
import app.needler.core.data.local.dao.AlbumDao
import app.needler.core.data.local.dao.AudioCacheDao
import app.needler.core.data.local.dao.PinDao
import app.needler.core.data.local.dao.TrackDao
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.data.local.entity.PinEntity
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.data.local.projection.CachedAudioSignatureRow
import app.needler.core.data.local.staleness.CachedAudioSignature
import app.needler.core.data.local.staleness.ServerTrackMetadata
import app.needler.core.data.local.staleness.StaleCacheEntry
import app.needler.core.data.local.staleness.StalenessChecker
import app.needler.core.data.local.staleness.StalenessReport
import app.needler.core.data.mapper.EntityMappers
import app.needler.core.data.mapper.SubsonicIds
import app.needler.core.data.mapper.SubsonicMappers
import app.needler.core.data.mapper.TrackQualitySummary
import app.needler.core.data.mapper.networkCall
import app.needler.core.domain.model.AlbumSyncReport
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.flatMap
import app.needler.core.network.subsonic.SubsonicApi
import app.needler.core.network.subsonic.dto.AlbumId3Dto

/**
 * Syncing one album, and the staleness rule that goes with it.
 *
 * ## Why the comparison is against `audio_cache` and not against `track`
 *
 * DroppedNeedle replaces files in place when a better source appears, so a cached copy can silently
 * become the older, worse one. The check has to notice that, and the only way it can is by comparing
 * the server's fresh metadata against the fingerprint **snapshotted onto the `audio_cache` row when
 * those bytes were downloaded** - `source_file_id`, `source_size_bytes`, `source_duration_ms`,
 * `source_format`.
 *
 * Comparing against the `track` row cannot work, however tempting it looks. Sync overwrites `track`
 * with the server's new values in this very pass, so that comparison is the mirror against itself:
 * it reports "unchanged" every time, the user keeps the worse file for ever, and nothing ever says
 * so. `audio_cache` is never touched by sync, which is precisely what makes it the right witness.
 *
 * A field counts as changed **only when both sides carry a value**; a null on either side means
 * "unknown", never "changed". Without that, one server release that stopped reporting durations
 * would declare every cached track stale and re-download the user's whole offline library, possibly
 * over mobile data. `StalenessChecker` enforces it, which is why the comparison is not open-coded
 * here.
 */
public class AlbumSyncer(
    private val subsonic: SubsonicApi,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val audioCacheDao: AudioCacheDao,
    private val pinDao: PinDao,
    private val deleteFile: (String) -> Boolean = { path -> java.io.File(path).delete() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** Fetches one album and its songs, applies the staleness rule, and writes the mirror. */
    public suspend fun syncAlbum(mbid: ReleaseGroupMbid): Outcome<AlbumSyncReport> =
        networkCall { subsonic.album(SubsonicIds.albumId(mbid)) }
            .flatMap { dto -> Outcome.Success(apply(mbid, dto)) }

    /**
     * Writes one already-fetched album payload into the mirror.
     *
     * Separate from the fetch so a full sync, which already holds the payload, does not ask for it
     * twice.
     */
    public suspend fun apply(mbid: ReleaseGroupMbid, dto: AlbumId3Dto): AlbumSyncReport {
        val now: Long = nowMillis()

        // 1. Snapshot the cache fingerprints first. Ordering is belt and braces - sync never writes
        //    audio_cache - but reading before writing keeps the intent obvious to the next reader.
        val cached: List<CachedAudioSignature> = audioCacheDao.getAlbumSignatures(mbid.value)
            .map(CachedAudioSignature::from)

        // 2. The server's current view of each track.
        val freshRows: List<TrackEntity> = SubsonicMappers.trackEntities(mbid, dto.song, now)
        val fresh: List<ServerTrackMetadata> = freshRows.map(SubsonicMappers::serverTrackMetadata)

        // 3. Compare, before anything is written.
        val report: StalenessReport = StalenessChecker.detect(
            cached = cached,
            fresh = fresh,
            // A track the server no longer lists is not evidence that the bytes on disk are wrong:
            // `audio_cache` deliberately has no foreign key to `track`, because a re-import that
            // rewrites a row must not delete perfectly good audio for that position on that record.
            treatMissingAsRemoved = false,
        )

        // 4. Write the mirror.
        val existing: AlbumEntity? = albumDao.getAlbum(mbid.value)
        val quality: TrackQualitySummary = SubsonicMappers.qualitySummary(dto.song)
        val row: AlbumEntity? = SubsonicMappers.albumEntity(
            dto = dto,
            now = now,
            // The album's own state is not the server's to decide: a pinned album stays pinned, and
            // an album mid-pull keeps its acquiring state until the pull row says otherwise.
            state = ownedState(existing?.state),
            quality = quality,
        )
        if (row != null) {
            albumDao.upsert(
                row.copy(
                    qualityPolicySummary = existing?.qualityPolicySummary ?: row.qualityPolicySummary,
                ),
            )
        }
        // @Upsert, never @Insert(onConflict = REPLACE): INSERT OR REPLACE is a delete plus an insert,
        // and `album -> track ON DELETE CASCADE` would empty every album on every sync. The failure
        // is silent - the sync reports success and the tracks are simply gone.
        trackDao.replaceAlbumTracks(mbid.value, freshRows)

        // 5. Evict what the upgrade invalidated, and re-queue the pinned ones.
        val evicted: List<TrackKey> = evict(report)
        val redownload: List<TrackKey> = report.stale
            .filter { it.requiresRedownload }
            .map { EntityMappers.trackKey(it.key) }
        if (redownload.isNotEmpty()) requeuePinnedDownload(mbid, redownload.size)

        return AlbumSyncReport(
            releaseGroupMbid = mbid,
            trackCount = freshRows.size,
            staleTracksEvicted = evicted,
            tracksQueuedForRedownload = redownload,
        )
    }

    /**
     * Deletes the stale bytes and their index rows.
     *
     * Files go first and rows second: a row with no file is a cache miss and costs one re-fetch,
     * while a file with no row is a leak nothing will ever collect.
     */
    private suspend fun evict(report: StalenessReport): List<TrackKey> {
        if (report.isEmpty) return emptyList()
        val removed: MutableList<TrackKeyDb> = ArrayList(report.stale.size)
        for (entry: StaleCacheEntry in report.stale) {
            runCatching { deleteFile(entry.cached.filePath) }
            removed.add(entry.key)
        }
        audioCacheDao.deleteByCanonicalKeys(removed.map { it.canonical })
        return removed.map(EntityMappers::trackKey)
    }

    /**
     * Puts a pinned album back into the downloading state after an eviction.
     *
     * The bytes the user asked to keep are gone, so the album is no longer complete; leaving it
     * marked complete would hide a silent downgrade behind a green check.
     */
    private suspend fun requeuePinnedDownload(mbid: ReleaseGroupMbid, evictedTracks: Int) {
        val pin: PinEntity = pinDao.getPin(mbid.value) ?: return
        val onDevice: Int = audioCacheDao.getAlbumRows(mbid.value).count { it.complete }
        pinDao.setDownloadProgress(
            releaseGroupMbid = mbid.value,
            downloadState = if (onDevice > 0) DownloadStateDb.PARTIAL else DownloadStateDb.QUEUED,
            tracksComplete = onDevice,
            tracksTotal = maxOf(pin.tracksTotal, onDevice + evictedTracks),
            downloadedBytes = null,
            totalBytes = pin.totalBytes,
            error = null,
            updatedAt = nowMillis(),
        )
    }

    /**
     * The state an owned album keeps across a sync.
     *
     * A sync proves the server holds the audio; it says nothing about whether the user pinned it. So
     * a pinned row stays pinned and everything else becomes owned - including a row that was
     * acquiring, because the album's presence in the library is the completion of the pull.
     */
    private fun ownedState(current: AlbumStateDb?): AlbumStateDb =
        if (current == AlbumStateDb.PINNED) AlbumStateDb.PINNED else AlbumStateDb.OWNED

    /** For callers that want the raw signature rows, e.g. a diagnostics dump. */
    public suspend fun cachedSignatures(mbid: ReleaseGroupMbid): List<CachedAudioSignatureRow> =
        audioCacheDao.getAlbumSignatures(mbid.value)
}

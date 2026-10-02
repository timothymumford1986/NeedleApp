package app.needler.player.service.source

import app.needler.core.data.local.TrackKeyDb
import app.needler.core.data.local.dao.AudioCacheDao
import app.needler.core.data.local.entity.AudioCacheEntity
import app.needler.core.data.local.projection.AlbumCacheStateRow
import app.needler.core.data.local.projection.CacheUsageRow
import app.needler.core.data.local.projection.CachedAudioSignatureRow
import app.needler.core.data.local.projection.EvictionCandidateRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The `audio_cache` table as a map, for the one test in this module that needs the real store.
 *
 * `:core:data` has a fuller one in its own test source set, which a test source set cannot export, and
 * a second copy is cheaper than a `testFixtures` configuration for the handful of queries the retention
 * path touches. Everything else fails loudly rather than returning a plausible default: a store that
 * reached an unexpected query should break this test, not quietly get a zero.
 */
internal class FakeAudioCacheDao : AudioCacheDao {

    val rows: MutableMap<String, AudioCacheEntity> = LinkedHashMap()

    private fun id(mbid: String, discNo: Int, trackNo: Int): String =
        TrackKeyDb(mbid, discNo, trackNo).canonical

    private fun id(row: AudioCacheEntity): String = id(row.releaseGroupMbid, row.discNo, row.trackNo)

    private fun candidate(row: AudioCacheEntity): EvictionCandidateRow = EvictionCandidateRow(
        key = TrackKeyDb(row.releaseGroupMbid, row.discNo, row.trackNo),
        filePath = row.filePath,
        sizeBytes = row.sizeBytes,
        lastPlayedAt = row.lastPlayedAt,
        downloadedAt = row.downloadedAt,
    )

    override suspend fun upsert(row: AudioCacheEntity) {
        rows[id(row)] = row
    }

    override suspend fun upsertAll(rows: List<AudioCacheEntity>) {
        rows.forEach { this.rows[id(it)] = it }
    }

    override suspend fun get(releaseGroupMbid: String, discNo: Int, trackNo: Int): AudioCacheEntity? =
        rows[id(releaseGroupMbid, discNo, trackNo)]

    override suspend fun isOnDevice(releaseGroupMbid: String, discNo: Int, trackNo: Int): Boolean =
        get(releaseGroupMbid, discNo, trackNo)?.complete == true

    override suspend fun getUsage(): CacheUsageRow = CacheUsageRow(
        pinnedBytes = rows.values.filter { it.pinned }.sumOf { it.sizeBytes },
        unpinnedBytes = rows.values.filter { !it.pinned }.sumOf { it.sizeBytes },
        pinnedTracks = rows.values.count { it.pinned },
        unpinnedTracks = rows.values.count { !it.pinned },
    )

    override fun observeUsage(): Flow<CacheUsageRow> = flowOf(
        CacheUsageRow(
            pinnedBytes = rows.values.filter { it.pinned }.sumOf { it.sizeBytes },
            unpinnedBytes = rows.values.filter { !it.pinned }.sumOf { it.sizeBytes },
            pinnedTracks = rows.values.count { it.pinned },
            unpinnedTracks = rows.values.count { !it.pinned },
        ),
    )

    override suspend fun getEvictionCandidates(limit: Int): List<EvictionCandidateRow> =
        rows.values.filter { !it.pinned }.sortedBy { it.lastPlayedAt }.take(limit).map(::candidate)

    override suspend fun getAllUnpinnedCandidates(): List<EvictionCandidateRow> =
        rows.values.filter { !it.pinned }.sortedBy { it.lastPlayedAt }.map(::candidate)

    override suspend fun deleteByCanonicalKeys(canonicalKeys: List<String>): Int {
        val before: Int = rows.size
        canonicalKeys.forEach { rows.remove(it) }
        return before - rows.size
    }

    override suspend fun markPlayed(releaseGroupMbid: String, discNo: Int, trackNo: Int, playedAt: Long) {
        val key: String = id(releaseGroupMbid, discNo, trackNo)
        rows[key]?.let { rows[key] = it.copy(lastPlayedAt = playedAt, playCount = it.playCount + 1) }
    }

    override suspend fun delete(releaseGroupMbid: String, discNo: Int, trackNo: Int) {
        rows.remove(id(releaseGroupMbid, discNo, trackNo))
    }

    // ------------------------------------------------- not on the retention path

    override fun observeAlbumCacheState(releaseGroupMbid: String): Flow<List<AlbumCacheStateRow>> =
        notUsed("observeAlbumCacheState")

    override suspend fun getAlbumRows(releaseGroupMbid: String): List<AudioCacheEntity> =
        notUsed("getAlbumRows")

    override suspend fun getAllRowsForRemoval(): List<EvictionCandidateRow> =
        notUsed("getAllRowsForRemoval")

    override suspend fun getAlbumRowsForRemoval(releaseGroupMbid: String): List<EvictionCandidateRow> =
        notUsed("getAlbumRowsForRemoval")

    override suspend fun getAlbumSignatures(releaseGroupMbid: String): List<CachedAudioSignatureRow> =
        notUsed("getAlbumSignatures")

    override suspend fun getAllSignatures(): List<CachedAudioSignatureRow> = notUsed("getAllSignatures")

    override suspend fun isStale(
        releaseGroupMbid: String,
        discNo: Int,
        trackNo: Int,
        fileId: String?,
        sizeBytes: Long?,
        durationMs: Long?,
        format: String?,
    ): Boolean = notUsed("isStale")

    override suspend fun findStaleAgainstMirror(releaseGroupMbid: String): List<CachedAudioSignatureRow> =
        notUsed("findStaleAgainstMirror")

    override suspend fun setDownloadProgress(
        releaseGroupMbid: String,
        discNo: Int,
        trackNo: Int,
        sizeBytes: Long,
        complete: Boolean,
        downloadedAt: Long?,
    ): Unit = notUsed("setDownloadProgress")

    override suspend fun setAlbumPinned(releaseGroupMbid: String, pinned: Boolean): Unit =
        notUsed("setAlbumPinned")

    override suspend fun deleteAlbum(releaseGroupMbid: String): Unit = notUsed("deleteAlbum")

    override suspend fun clearUnpinned(): Unit = notUsed("clearUnpinned")

    override suspend fun clear(): Unit = notUsed("clear")

    private fun notUsed(name: String): Nothing =
        error("FakeAudioCacheDao." + name + " is not part of the retention path")
}

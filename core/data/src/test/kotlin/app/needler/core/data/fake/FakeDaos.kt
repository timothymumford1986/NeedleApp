package app.needler.core.data.fake

import app.needler.core.data.local.TrackKeyDb
import app.needler.core.data.local.dao.AlbumDao
import app.needler.core.data.local.dao.ArtistDao
import app.needler.core.data.local.dao.AudioCacheDao
import app.needler.core.data.local.dao.PinDao
import app.needler.core.data.local.dao.PullDao
import app.needler.core.data.local.dao.SyncStateDao
import app.needler.core.data.local.dao.TrackDao
import app.needler.core.data.local.dao.WriteQueueDao
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.ArtistEntity
import app.needler.core.data.local.entity.AudioCacheEntity
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.data.local.entity.FavouriteTypeDb
import app.needler.core.data.local.entity.PinEntity
import app.needler.core.data.local.entity.PullEntity
import app.needler.core.data.local.entity.PullStatusDb
import app.needler.core.data.local.entity.SyncStateEntity
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.data.local.entity.WriteQueueEntity
import app.needler.core.data.local.projection.AlbumCacheStateRow
import app.needler.core.data.local.projection.ArtistIndexRow
import app.needler.core.data.local.projection.CacheUsageRow
import app.needler.core.data.local.projection.CachedAudioSignatureRow
import app.needler.core.data.local.projection.DownloadedAlbumRow
import app.needler.core.data.local.projection.EvictionCandidateRow
import app.needler.core.data.local.projection.LibrarySongRow
import app.needler.core.data.local.projection.LibraryTotalsRow
import app.needler.core.data.local.projection.PinnedAlbumRow
import app.needler.core.data.local.projection.PlaylistTrackRow
import app.needler.core.data.local.projection.PullRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory stand-ins for the DAOs.
 *
 * Real fakes rather than mocks, because most of what is worth testing in this module is *stateful*:
 * a write queue's ordering, a sync's upsert-not-replace, the fingerprint on an `audio_cache` row
 * surviving a sync that rewrites `track`. A relaxed mock would happily answer every one of those
 * questions with whatever the test already believed.
 *
 * They are deliberately not a SQLite reimplementation. Ordering and filtering are done in Kotlin and
 * match the DAO's own `ORDER BY` where a test depends on it; anything no test reaches throws.
 */

private fun notUsed(name: String): Nothing =
    throw UnsupportedOperationException("FakeDao: $name is not used by any test")

// -------------------------------------------------------------------------- album

public class FakeAlbumDao : AlbumDao {

    public val rows: MutableMap<String, AlbumEntity> = LinkedHashMap()
    private val changes: MutableStateFlow<Int> = MutableStateFlow(0)

    private fun bump() {
        changes.value += 1
    }

    override suspend fun upsert(album: AlbumEntity) {
        rows[album.releaseGroupMbid] = album
        bump()
    }

    override suspend fun upsertAll(albums: List<AlbumEntity>) {
        albums.forEach { rows[it.releaseGroupMbid] = it }
        bump()
    }

    override fun observeAlbum(releaseGroupMbid: String): Flow<AlbumEntity?> =
        changes.map { rows[releaseGroupMbid] }

    override suspend fun getAlbum(releaseGroupMbid: String): AlbumEntity? = rows[releaseGroupMbid]

    override suspend fun getAlbums(releaseGroupMbids: List<String>): List<AlbumEntity> =
        releaseGroupMbids.mapNotNull { rows[it] }

    override fun observeLibraryByRecentlyAdded(limit: Int, offset: Int): Flow<List<AlbumEntity>> =
        changes.map {
            rows.values
                .filter { it.inLibrary }
                .sortedWith(compareByDescending<AlbumEntity> { it.addedAt ?: 0L }.thenBy { it.titleNormalised })
                .drop(offset)
                .take(limit)
        }

    override fun observeLibraryAlphabetical(limit: Int, offset: Int): Flow<List<AlbumEntity>> =
        changes.map {
            rows.values.filter { it.inLibrary }.sortedBy { it.titleNormalised }.drop(offset).take(limit)
        }

    override fun observeLibraryByArtist(limit: Int, offset: Int): Flow<List<AlbumEntity>> =
        changes.map {
            rows.values.filter { it.inLibrary }.sortedBy { it.artistNormalised }.drop(offset).take(limit)
        }

    override fun observeNewestAlbum(): Flow<AlbumEntity?> = changes.map {
        rows.values.filter { it.inLibrary }.maxByOrNull { it.addedAt ?: 0L }
    }

    override fun observeAlbumsByArtist(artistMbid: String): Flow<List<AlbumEntity>> = changes.map {
        rows.values
            .filter { it.artistMbid == artistMbid }
            .sortedWith(
                compareByDescending<AlbumEntity> { it.inLibrary }
                    .thenBy { it.year ?: Int.MAX_VALUE }
                    .thenBy { it.titleNormalised },
            )
    }

    override suspend fun getOwnedAlbumsByArtist(artistMbid: String): List<AlbumEntity> =
        rows.values.filter { it.artistMbid == artistMbid && it.inLibrary }

    override suspend fun searchAlbums(matchExpression: String, limit: Int): List<AlbumEntity> =
        matching(matchExpression).take(limit)

    override fun observeAlbumSearch(matchExpression: String, limit: Int): Flow<List<AlbumEntity>> =
        changes.map { matching(matchExpression).take(limit) }

    override suspend fun searchOwnedAlbums(matchExpression: String, limit: Int): List<AlbumEntity> =
        matching(matchExpression).filter { it.inLibrary }.take(limit)

    override fun observeLibraryTotals(): Flow<LibraryTotalsRow> = changes.map {
        val owned = rows.values.filter { it.inLibrary }
        LibraryTotalsRow(albumCount = owned.size, sizeBytes = owned.sumOf { it.sizeBytes ?: 0L })
    }

    override fun observeLibraryAlbumCount(): Flow<Int> =
        changes.map { rows.values.count { it.inLibrary } }

    override fun observeGenreColumns(): Flow<List<String>> =
        changes.map { rows.values.filter { it.inLibrary }.mapNotNull { it.genres } }

    override fun observeAlbumCountForGenre(genrePattern: String): Flow<Int> = changes.map {
        val term: String = genrePattern.trim('%')
        rows.values.count { it.inLibrary && it.genres.orEmpty().contains(term) }
    }

    override suspend fun setState(
        releaseGroupMbid: String,
        state: AlbumStateDb,
        inLibrary: Boolean,
        updatedAt: Long,
    ) {
        rows[releaseGroupMbid]?.let { row ->
            rows[releaseGroupMbid] = row.copy(state = state, inLibrary = inLibrary, updatedAt = updatedAt)
            bump()
        }
    }

    override suspend fun delete(releaseGroupMbid: String) {
        rows.remove(releaseGroupMbid)
        bump()
    }

    override suspend fun pruneCatalogueAlbums(olderThan: Long): Int {
        val victims: List<String> = rows.values
            .filter { !it.inLibrary && it.updatedAt < olderThan }
            .map { it.releaseGroupMbid }
        victims.forEach { rows.remove(it) }
        bump()
        return victims.size
    }

    override suspend fun clear() {
        rows.clear()
        bump()
    }

    /** A crude prefix match over the FTS expression, good enough to exercise the mapping. */
    private fun matching(matchExpression: String): List<AlbumEntity> {
        val term: String = matchExpression.replace("\"", "").replace("*", "").trim().lowercase()
        return rows.values
            .filter { it.titleNormalised.contains(term) || it.artistNormalised.contains(term) }
            .sortedWith(compareByDescending<AlbumEntity> { it.inLibrary }.thenBy { it.titleNormalised })
    }
}

// -------------------------------------------------------------------------- track

/**
 * Tracks in memory.
 *
 * [albums] and [favourites] are the other two fakes this one *joins to*. The Songs tab's four
 * queries are not track-only statements - each joins `album` for the `in_library` filter and for the
 * title and artist a song row draws, and the starred one joins `favourite` as well - so a fake that
 * could only see its own map would have to answer them with a guess. Both default to an empty fake,
 * so the seven tests that do not exercise a listing construct this exactly as they did before; the
 * one that does passes the same instances the repository under test was given.
 */
public class FakeTrackDao(
    private val albums: FakeAlbumDao = FakeAlbumDao(),
    private val favourites: FakeFavouriteDao = FakeFavouriteDao(),
) : TrackDao() {

    public val rows: MutableMap<String, TrackEntity> = LinkedHashMap()
    private val changes: MutableStateFlow<Int> = MutableStateFlow(0)

    private fun key(row: TrackEntity): String =
        TrackKeyDb(row.releaseGroupMbid, row.discNo, row.trackNo).canonical

    private fun bump() {
        changes.value += 1
    }

    override suspend fun upsert(track: TrackEntity) {
        rows[key(track)] = track
        bump()
    }

    override suspend fun upsertAll(tracks: List<TrackEntity>) {
        tracks.forEach { rows[key(it)] = it }
        bump()
    }

    override suspend fun deleteTracksNotIn(
        releaseGroupMbid: String,
        keepDiscAndTrack: List<String>,
    ): Int {
        val keep: Set<String> = keepDiscAndTrack.toSet()
        val victims: List<String> = rows.values
            .filter { it.releaseGroupMbid == releaseGroupMbid }
            .filter { !keep.contains(it.discNo.toString() + ":" + it.trackNo.toString()) }
            .map(::key)
        victims.forEach { rows.remove(it) }
        bump()
        return victims.size
    }

    override fun observeAlbumTracks(releaseGroupMbid: String): Flow<List<TrackEntity>> =
        changes.map { albumTracks(releaseGroupMbid) }

    override suspend fun getAlbumTracks(releaseGroupMbid: String): List<TrackEntity> =
        albumTracks(releaseGroupMbid)

    override suspend fun getTrack(releaseGroupMbid: String, discNo: Int, trackNo: Int): TrackEntity? =
        rows[TrackKeyDb(releaseGroupMbid, discNo, trackNo).canonical]

    override fun observeTrack(
        releaseGroupMbid: String,
        discNo: Int,
        trackNo: Int,
    ): Flow<TrackEntity?> =
        changes.map { rows[TrackKeyDb(releaseGroupMbid, discNo, trackNo).canonical] }

    override suspend fun searchTracks(matchExpression: String, limit: Int): List<TrackEntity> =
        matching(matchExpression).take(limit)

    override fun observeTrackSearch(matchExpression: String, limit: Int): Flow<List<TrackEntity>> =
        changes.map { matching(matchExpression).take(limit) }

    override fun observeTracksByGenre(
        genrePattern: String,
        limit: Int,
        offset: Int,
    ): Flow<List<TrackEntity>> = changes.map { emptyList() }

    // ---- the songs tab ------------------------------------------------------
    //
    // These four reproduce the DAO's own ORDER BY in Kotlin, because that ordering is the whole
    // point of the queries and a fake that returned insertion order would let the bug they were
    // written to fix pass every test. They are not a SQLite reimplementation: the comparators say
    // the same thing the SQL says, and where they cannot - SQLite's collation on non-ASCII text -
    // the fixtures stay inside what both agree on.

    override fun observeLibrarySongsByTitle(limit: Int, offset: Int): Flow<List<LibrarySongRow>> =
        changes.map {
            inLibrary()
                .sortedWith(
                    compareBy(
                        { it.track.titleNormalised },
                        { it.album.artistNormalised },
                        { it.album.titleNormalised },
                        { it.track.discNo },
                        { it.track.trackNo },
                    ),
                )
                .page(limit, offset)
        }

    override fun observeLibrarySongsByArtist(limit: Int, offset: Int): Flow<List<LibrarySongRow>> =
        changes.map {
            inLibrary()
                .sortedWith(
                    compareBy(
                        { it.album.artistNormalised },
                        { it.album.titleNormalised },
                        { it.track.discNo },
                        { it.track.trackNo },
                    ),
                )
                .page(limit, offset)
        }

    override fun observeLibrarySongsByRecentlyAdded(
        limit: Int,
        offset: Int,
    ): Flow<List<LibrarySongRow>> = changes.map {
        inLibrary()
            .sortedWith(
                // `added_at IS NULL` first, then the date descending: an album the server never
                // dated sorts last rather than first, which is what the SQL says.
                compareBy<Joined> { it.album.addedAt == null }
                    .thenByDescending { it.album.addedAt ?: 0L }
                    .thenBy { it.album.titleNormalised }
                    .thenBy { it.track.discNo }
                    .thenBy { it.track.trackNo },
            )
            .page(limit, offset)
    }

    override fun observeStarredLibrarySongs(limit: Int, offset: Int): Flow<List<LibrarySongRow>> =
        changes.map {
            val starred: Map<String, Long?> = favourites.rows.values
                .filter { it.entityType == FavouriteTypeDb.TRACK }
                .mapNotNull { row ->
                    val mbid: String = row.releaseGroupMbid ?: return@mapNotNull null
                    val disc: Int = row.discNo ?: return@mapNotNull null
                    val track: Int = row.trackNo ?: return@mapNotNull null
                    TrackKeyDb(mbid, disc, track).canonical to row.starredAt
                }
                .toMap()
            inLibrary()
                .filter { starred.containsKey(key(it.track)) }
                .sortedWith(
                    compareBy<Joined> { starred[key(it.track)] == null }
                        .thenByDescending { starred[key(it.track)] ?: 0L }
                        .thenBy { it.track.titleNormalised },
                )
                .page(limit, offset)
        }

    /** A track joined to its album, which is what every Songs tab query selects. */
    private data class Joined(val track: TrackEntity, val album: AlbumEntity)

    /** The inner join plus `album.in_library = 1`: a track whose album has gone is not a song. */
    private fun inLibrary(): List<Joined> = rows.values.mapNotNull { track ->
        albums.rows[track.releaseGroupMbid]
            ?.takeIf { it.inLibrary }
            ?.let { Joined(track, it) }
    }

    private fun List<Joined>.page(limit: Int, offset: Int): List<LibrarySongRow> =
        drop(offset).take(limit).map {
            LibrarySongRow(
                track = it.track,
                albumTitle = it.album.title,
                albumArtistName = it.album.artistName,
            )
        }

    override suspend fun getTracksByCanonicalKeys(canonicalKeys: List<String>): List<TrackEntity> =
        canonicalKeys.mapNotNull { rows[it] }

    override suspend fun findTracksWithChangedFileId(releaseGroupMbid: String): List<TrackEntity> =
        notUsed("findTracksWithChangedFileId")

    override fun observeTrackCount(): Flow<Int> = changes.map { rows.size }

    override suspend fun deleteAlbumTracks(releaseGroupMbid: String) {
        rows.values.filter { it.releaseGroupMbid == releaseGroupMbid }.map(::key)
            .forEach { rows.remove(it) }
        bump()
    }

    override suspend fun clear() {
        rows.clear()
        bump()
    }

    private fun albumTracks(releaseGroupMbid: String): List<TrackEntity> = rows.values
        .filter { it.releaseGroupMbid == releaseGroupMbid }
        .sortedWith(compareBy({ it.discNo }, { it.trackNo }))

    private fun matching(matchExpression: String): List<TrackEntity> {
        val term: String = matchExpression.replace("\"", "").replace("*", "").trim().lowercase()
        return rows.values.filter { it.titleNormalised.contains(term) }.sortedBy { it.titleNormalised }
    }
}

// ------------------------------------------------------------------- audio cache

public class FakeAudioCacheDao : AudioCacheDao {

    public val rows: MutableMap<String, AudioCacheEntity> = LinkedHashMap()
    private val changes: MutableStateFlow<Int> = MutableStateFlow(0)

    private fun key(row: AudioCacheEntity): String =
        TrackKeyDb(row.releaseGroupMbid, row.discNo, row.trackNo).canonical

    private fun bump() {
        changes.value += 1
    }

    override suspend fun upsert(row: AudioCacheEntity) {
        rows[key(row)] = row
        bump()
    }

    override suspend fun upsertAll(rows: List<AudioCacheEntity>) {
        rows.forEach { this.rows[key(it)] = it }
        bump()
    }

    override suspend fun get(releaseGroupMbid: String, discNo: Int, trackNo: Int): AudioCacheEntity? =
        rows[TrackKeyDb(releaseGroupMbid, discNo, trackNo).canonical]

    override suspend fun isOnDevice(releaseGroupMbid: String, discNo: Int, trackNo: Int): Boolean =
        get(releaseGroupMbid, discNo, trackNo)?.complete == true

    override fun observeAlbumCacheState(releaseGroupMbid: String): Flow<List<AlbumCacheStateRow>> =
        changes.map {
            album(releaseGroupMbid).map { row ->
                AlbumCacheStateRow(
                    key = TrackKeyDb(row.releaseGroupMbid, row.discNo, row.trackNo),
                    pinned = row.pinned,
                    complete = row.complete,
                    sizeBytes = row.sizeBytes,
                )
            }
        }

    override suspend fun getAlbumRows(releaseGroupMbid: String): List<AudioCacheEntity> =
        album(releaseGroupMbid)

    override fun observeUsage(): Flow<CacheUsageRow> = changes.map { usage() }

    override suspend fun getUsage(): CacheUsageRow = usage()

    override suspend fun getEvictionCandidates(limit: Int): List<EvictionCandidateRow> =
        rows.values.filter { !it.pinned }.sortedBy { it.lastPlayedAt }.take(limit).map(::candidate)

    override suspend fun getAllUnpinnedCandidates(): List<EvictionCandidateRow> =
        rows.values.filter { !it.pinned }.sortedBy { it.lastPlayedAt }.map(::candidate)

    override suspend fun getAllRowsForRemoval(): List<EvictionCandidateRow> =
        rows.values.map(::candidate)

    override suspend fun getAlbumRowsForRemoval(releaseGroupMbid: String): List<EvictionCandidateRow> =
        album(releaseGroupMbid).map(::candidate)

    override suspend fun getAlbumSignatures(releaseGroupMbid: String): List<CachedAudioSignatureRow> =
        album(releaseGroupMbid).map(::signature)

    override suspend fun getAllSignatures(): List<CachedAudioSignatureRow> =
        rows.values.map(::signature)

    /**
     * The both-sides-have-a-value rule, matching the SQL exactly.
     *
     * A null on either side means "unknown", never "changed": without that, a server release that
     * stopped reporting durations would condemn the user's whole offline library.
     */
    override suspend fun isStale(
        releaseGroupMbid: String,
        discNo: Int,
        trackNo: Int,
        fileId: String?,
        sizeBytes: Long?,
        durationMs: Long?,
        format: String?,
    ): Boolean {
        val row: AudioCacheEntity = get(releaseGroupMbid, discNo, trackNo) ?: return false
        fun <T> differs(cached: T?, fresh: T?): Boolean = cached != null && fresh != null && cached != fresh
        return differs(row.sourceFileId, fileId) ||
            differs(row.sourceSizeBytes, sizeBytes) ||
            differs(row.sourceDurationMs, durationMs) ||
            differs(row.sourceFormat?.trim()?.lowercase(), format?.trim()?.lowercase())
    }

    override suspend fun findStaleAgainstMirror(releaseGroupMbid: String): List<CachedAudioSignatureRow> =
        notUsed("findStaleAgainstMirror")

    override suspend fun markPlayed(
        releaseGroupMbid: String,
        discNo: Int,
        trackNo: Int,
        playedAt: Long,
    ) {
        val id: String = TrackKeyDb(releaseGroupMbid, discNo, trackNo).canonical
        rows[id]?.let { rows[id] = it.copy(lastPlayedAt = playedAt, playCount = it.playCount + 1) }
        bump()
    }

    override suspend fun setDownloadProgress(
        releaseGroupMbid: String,
        discNo: Int,
        trackNo: Int,
        sizeBytes: Long,
        complete: Boolean,
        downloadedAt: Long?,
    ) {
        val id: String = TrackKeyDb(releaseGroupMbid, discNo, trackNo).canonical
        rows[id]?.let {
            rows[id] = it.copy(sizeBytes = sizeBytes, complete = complete, downloadedAt = downloadedAt)
        }
        bump()
    }

    override suspend fun setAlbumPinned(releaseGroupMbid: String, pinned: Boolean) {
        album(releaseGroupMbid).forEach { rows[key(it)] = it.copy(pinned = pinned) }
        bump()
    }

    override suspend fun delete(releaseGroupMbid: String, discNo: Int, trackNo: Int) {
        rows.remove(TrackKeyDb(releaseGroupMbid, discNo, trackNo).canonical)
        bump()
    }

    override suspend fun deleteByCanonicalKeys(canonicalKeys: List<String>): Int {
        var removed = 0
        canonicalKeys.forEach { if (rows.remove(it) != null) removed++ }
        bump()
        return removed
    }

    override suspend fun deleteAlbum(releaseGroupMbid: String) {
        album(releaseGroupMbid).map(::key).forEach { rows.remove(it) }
        bump()
    }

    override suspend fun clearUnpinned() {
        rows.values.filter { !it.pinned }.map(::key).forEach { rows.remove(it) }
        bump()
    }

    override suspend fun clear() {
        rows.clear()
        bump()
    }

    private fun album(releaseGroupMbid: String): List<AudioCacheEntity> = rows.values
        .filter { it.releaseGroupMbid == releaseGroupMbid }
        .sortedWith(compareBy({ it.discNo }, { it.trackNo }))

    private fun usage(): CacheUsageRow = CacheUsageRow(
        pinnedBytes = rows.values.filter { it.pinned }.sumOf { it.sizeBytes },
        unpinnedBytes = rows.values.filter { !it.pinned }.sumOf { it.sizeBytes },
        pinnedTracks = rows.values.count { it.pinned },
        unpinnedTracks = rows.values.count { !it.pinned },
    )

    private fun candidate(row: AudioCacheEntity): EvictionCandidateRow = EvictionCandidateRow(
        key = TrackKeyDb(row.releaseGroupMbid, row.discNo, row.trackNo),
        filePath = row.filePath,
        sizeBytes = row.sizeBytes,
        lastPlayedAt = row.lastPlayedAt,
        downloadedAt = row.downloadedAt,
    )

    private fun signature(row: AudioCacheEntity): CachedAudioSignatureRow = CachedAudioSignatureRow(
        key = TrackKeyDb(row.releaseGroupMbid, row.discNo, row.trackNo),
        filePath = row.filePath,
        pinned = row.pinned,
        complete = row.complete,
        sizeOnDiskBytes = row.sizeBytes,
        sourceFileId = row.sourceFileId,
        sourceSizeBytes = row.sourceSizeBytes,
        sourceDurationMs = row.sourceDurationMs,
        sourceFormat = row.sourceFormat,
    )
}

// ---------------------------------------------------------------------------- pin

public class FakePinDao : PinDao {

    public val rows: MutableMap<String, PinEntity> = LinkedHashMap()
    private val changes: MutableStateFlow<Int> = MutableStateFlow(0)

    override suspend fun upsert(pin: PinEntity) {
        rows[pin.releaseGroupMbid] = pin
        changes.value += 1
    }

    override suspend fun upsertAll(pins: List<PinEntity>) {
        pins.forEach { rows[it.releaseGroupMbid] = it }
        changes.value += 1
    }

    override fun observePinnedAlbums(): Flow<List<PinnedAlbumRow>> = notUsed("observePinnedAlbums")

    override fun observeDownloadedAlbums(): Flow<List<DownloadedAlbumRow>> =
        notUsed("observeDownloadedAlbums")

    override suspend fun getDownloadedAlbums(): List<DownloadedAlbumRow> =
        notUsed("getDownloadedAlbums")

    override fun observePin(releaseGroupMbid: String): Flow<PinEntity?> =
        changes.map { rows[releaseGroupMbid] }

    override suspend fun getPin(releaseGroupMbid: String): PinEntity? = rows[releaseGroupMbid]

    override fun observeIsPinned(releaseGroupMbid: String): Flow<Boolean> =
        changes.map { rows.containsKey(releaseGroupMbid) }

    override suspend fun getPinsInState(downloadStates: List<String>): List<PinEntity> =
        rows.values.filter { downloadStates.contains(it.downloadState.dbValue) }

    override fun observePinsInState(downloadStates: List<String>): Flow<List<PinEntity>> =
        changes.map { getPinsInState(downloadStates) }

    override fun observePinCount(): Flow<Int> = changes.map { rows.size }

    override suspend fun setDownloadProgress(
        releaseGroupMbid: String,
        downloadState: DownloadStateDb,
        tracksComplete: Int,
        tracksTotal: Int,
        downloadedBytes: Long?,
        totalBytes: Long?,
        error: String?,
        updatedAt: Long,
    ) {
        rows[releaseGroupMbid]?.let { row ->
            rows[releaseGroupMbid] = row.copy(
                downloadState = downloadState,
                tracksComplete = tracksComplete,
                tracksTotal = tracksTotal,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                error = error,
                updatedAt = updatedAt,
            )
        }
        changes.value += 1
    }

    override suspend fun setDownloadState(
        releaseGroupMbid: String,
        downloadState: DownloadStateDb,
        updatedAt: Long,
    ) {
        rows[releaseGroupMbid]?.let { row ->
            rows[releaseGroupMbid] = row.copy(downloadState = downloadState, updatedAt = updatedAt)
        }
        changes.value += 1
    }

    override suspend fun delete(releaseGroupMbid: String) {
        rows.remove(releaseGroupMbid)
        changes.value += 1
    }

    override suspend fun clear() {
        rows.clear()
        changes.value += 1
    }
}

// --------------------------------------------------------------------------- pull

public class FakePullDao : PullDao {

    public val rows: MutableMap<String, PullEntity> = LinkedHashMap()
    private val changes: MutableStateFlow<Int> = MutableStateFlow(0)

    override suspend fun upsert(pull: PullEntity) {
        rows[pull.releaseGroupMbid] = pull
        changes.value += 1
    }

    override suspend fun upsertAll(pulls: List<PullEntity>) {
        pulls.forEach { rows[it.releaseGroupMbid] = it }
        changes.value += 1
    }

    override fun observePullsInStatus(statuses: List<String>): Flow<List<PullRow>> = changes.map {
        rows.values
            .filter { statuses.contains(it.status.dbValue) }
            .sortedByDescending { it.createdAt }
            .map(::projection)
    }

    override fun observeAllPulls(): Flow<List<PullRow>> =
        changes.map { rows.values.sortedByDescending { it.createdAt }.map(::projection) }

    override fun observeLeadingPull(statuses: List<String>): Flow<PullRow?> = changes.map {
        rows.values.filter { statuses.contains(it.status.dbValue) }
            .maxByOrNull { it.percent }
            ?.let(::projection)
    }

    override fun observePull(releaseGroupMbid: String): Flow<PullEntity?> =
        changes.map { rows[releaseGroupMbid] }

    override suspend fun getPull(releaseGroupMbid: String): PullEntity? = rows[releaseGroupMbid]

    override suspend fun getPullByTaskId(taskId: String): PullEntity? =
        rows.values.firstOrNull { it.taskId == taskId }

    override fun observePullCount(statuses: List<String>): Flow<Int> =
        changes.map { rows.values.count { statuses.contains(it.status.dbValue) } }

    override suspend fun updateProgress(
        releaseGroupMbid: String,
        status: PullStatusDb,
        percent: Int,
        filesDone: Int,
        filesTotal: Int,
        downloadedBytes: Long?,
        totalSizeBytes: Long?,
        error: String?,
        searchJobId: String?,
        candidateIndex: Int?,
        updatedAt: Long,
    ) {
        rows[releaseGroupMbid]?.let { row ->
            rows[releaseGroupMbid] = row.copy(
                status = status,
                percent = percent,
                filesDone = filesDone,
                filesTotal = filesTotal,
                downloadedBytes = downloadedBytes,
                totalSizeBytes = totalSizeBytes,
                error = error,
                searchJobId = searchJobId,
                candidateIndex = candidateIndex,
                updatedAt = updatedAt,
            )
        }
        changes.value += 1
    }

    override suspend fun delete(releaseGroupMbid: String) {
        rows.remove(releaseGroupMbid)
        changes.value += 1
    }

    override suspend fun deleteFinishedBefore(statuses: List<String>, olderThan: Long): Int {
        val victims: List<String> = rows.values
            .filter { statuses.contains(it.status.dbValue) && it.updatedAt < olderThan }
            .map { it.releaseGroupMbid }
        victims.forEach { rows.remove(it) }
        changes.value += 1
        return victims.size
    }

    override suspend fun clear() {
        rows.clear()
        changes.value += 1
    }

    private fun projection(row: PullEntity): PullRow = PullRow(
        releaseGroupMbid = row.releaseGroupMbid,
        taskId = row.taskId,
        status = row.status,
        percent = row.percent,
        filesDone = row.filesDone,
        filesTotal = row.filesTotal,
        downloadedBytes = row.downloadedBytes,
        totalSizeBytes = row.totalSizeBytes,
        source = row.source,
        error = row.error,
        searchJobId = row.searchJobId,
        candidateIndex = row.candidateIndex,
        createdAt = row.createdAt,
        albumTitle = null,
        albumArtistName = null,
        albumYear = null,
        albumCoverArtId = null,
    )
}

// ------------------------------------------------------------------------- artist

public class FakeArtistDao : ArtistDao {

    public val rows: MutableMap<String, ArtistEntity> = LinkedHashMap()
    private val changes: MutableStateFlow<Int> = MutableStateFlow(0)

    override suspend fun upsert(artist: ArtistEntity) {
        rows[artist.artistMbid] = artist
        changes.value += 1
    }

    override suspend fun upsertAll(artists: List<ArtistEntity>) {
        artists.forEach { rows[it.artistMbid] = it }
        changes.value += 1
    }

    override fun observeArtists(): Flow<List<ArtistIndexRow>> =
        changes.map { rows.values.sortedBy { it.sortNameNormalised }.map(::projection) }

    override fun observeArtistsPaged(limit: Int, offset: Int): Flow<List<ArtistIndexRow>> =
        changes.map {
            rows.values.sortedBy { it.sortNameNormalised }.drop(offset).take(limit).map(::projection)
        }

    override fun observeArtist(artistMbid: String): Flow<ArtistEntity?> =
        changes.map { rows[artistMbid] }

    override suspend fun getArtist(artistMbid: String): ArtistEntity? = rows[artistMbid]

    override suspend fun searchArtistsByPrefix(
        normalisedPrefix: String,
        limit: Int,
    ): List<ArtistIndexRow> = rows.values
        .filter { it.sortNameNormalised.startsWith(normalisedPrefix) }
        .sortedBy { it.sortNameNormalised }
        .take(limit)
        .map(::projection)

    override fun observeArtistCount(): Flow<Int> = changes.map { rows.size }

    override suspend fun setMonitored(artistMbid: String, monitored: Boolean) {
        rows[artistMbid]?.let { rows[artistMbid] = it.copy(monitored = monitored) }
        changes.value += 1
    }

    override suspend fun delete(artistMbid: String) {
        rows.remove(artistMbid)
        changes.value += 1
    }

    override suspend fun clear() {
        rows.clear()
        changes.value += 1
    }

    private fun projection(row: ArtistEntity): ArtistIndexRow = ArtistIndexRow(
        artistMbid = row.artistMbid,
        name = row.name,
        sortNameNormalised = row.sortNameNormalised,
        albumCount = row.albumCount,
        artUrl = row.artUrl,
    )
}

// --------------------------------------------------------------------- sync state

public class FakeSyncStateDao : SyncStateDao {

    public var row: SyncStateEntity? = SyncStateEntity(
        serverIdentity = null,
        libraryRevision = null,
        lastFullSyncAt = null,
        lastDeltaSyncAt = null,
        lastScanAt = null,
        downloadsRevision = null,
        serverAlbumCount = null,
        serverSizeBytes = null,
        updatedAt = 0L,
    )
    private val changes: MutableStateFlow<Int> = MutableStateFlow(0)

    override suspend fun upsert(state: SyncStateEntity) {
        row = state
        changes.value += 1
    }

    override fun observeSyncState(): Flow<SyncStateEntity?> = changes.map { row }

    override suspend fun getSyncState(): SyncStateEntity? = row

    override suspend fun getServerIdentity(): String? = row?.serverIdentity

    override suspend fun getLibraryRevision(): String? = row?.libraryRevision

    override suspend fun getDownloadsRevision(): String? = row?.downloadsRevision

    override suspend fun recordDeltaSync(libraryRevision: String?, syncedAt: Long) {
        row = row?.copy(
            libraryRevision = libraryRevision,
            lastDeltaSyncAt = syncedAt,
            updatedAt = syncedAt,
        )
        changes.value += 1
    }

    override suspend fun recordFullSync(libraryRevision: String?, syncedAt: Long) {
        row = row?.copy(
            libraryRevision = libraryRevision,
            lastFullSyncAt = syncedAt,
            lastDeltaSyncAt = syncedAt,
            updatedAt = syncedAt,
        )
        changes.value += 1
    }

    override suspend fun recordScanTime(lastScanAt: Long?, updatedAt: Long) {
        row = row?.copy(lastScanAt = lastScanAt, updatedAt = updatedAt)
        changes.value += 1
    }

    override suspend fun recordDownloadsRevision(downloadsRevision: String?, updatedAt: Long) {
        row = row?.copy(downloadsRevision = downloadsRevision, updatedAt = updatedAt)
        changes.value += 1
    }

    override suspend fun recordServerStats(albumCount: Int?, sizeBytes: Long?, updatedAt: Long) {
        row = row?.copy(
            serverAlbumCount = albumCount,
            serverSizeBytes = sizeBytes,
            updatedAt = updatedAt,
        )
        changes.value += 1
    }

    override suspend fun recordServerIdentity(serverIdentity: String?, updatedAt: Long) {
        row = row?.copy(serverIdentity = serverIdentity, updatedAt = updatedAt)
        changes.value += 1
    }

    override suspend fun clear() {
        row = null
        changes.value += 1
    }
}

// -------------------------------------------------------------------- write queue

/**
 * The write queue's journal, in memory.
 *
 * Sequences are assigned monotonically exactly as `autoGenerate` does, because replay order is the
 * contract this table exists to keep.
 */
public class FakeWriteQueueDao : WriteQueueDao {

    public val rows: MutableList<WriteQueueEntity> = ArrayList()
    private var nextSeq: Long = 1L
    private val changes: MutableStateFlow<Int> = MutableStateFlow(0)

    override suspend fun enqueue(entry: WriteQueueEntity): Long {
        val seq: Long = nextSeq++
        rows.add(entry.copy(seq = seq))
        changes.value += 1
        return seq
    }

    override suspend fun enqueueAll(entries: List<WriteQueueEntity>): List<Long> =
        entries.map { enqueue(it) }

    override suspend fun getDue(now: Long, limit: Int): List<WriteQueueEntity> =
        rows.filter { it.nextAttemptAt <= now }.sortedBy { it.seq }.take(limit)

    override suspend fun peek(limit: Int): List<WriteQueueEntity> =
        rows.sortedBy { it.seq }.take(limit)

    override fun observeQueue(): Flow<List<WriteQueueEntity>> =
        changes.map { rows.sortedBy { it.seq } }

    override fun observePendingCount(): Flow<Int> = changes.map { rows.size }

    override suspend fun countOfType(operationType: String): Int =
        rows.count { it.operationType.dbValue == operationType }

    override suspend fun getByEntity(
        entityKey: String,
        operationType: String,
    ): List<WriteQueueEntity> = rows
        .filter { it.entityKey == entityKey && it.operationType.dbValue == operationType }
        .sortedBy { it.seq }

    override suspend fun recordFailure(seq: Long, error: String?, nextAttemptAt: Long) {
        val index: Int = rows.indexOfFirst { it.seq == seq }
        if (index >= 0) {
            val row: WriteQueueEntity = rows[index]
            rows[index] = row.copy(
                attempts = row.attempts + 1,
                lastError = error,
                nextAttemptAt = nextAttemptAt,
            )
        }
        changes.value += 1
    }

    override suspend fun delete(seq: Long) {
        rows.removeAll { it.seq == seq }
        changes.value += 1
    }

    override suspend fun deleteAll(seqs: List<Long>): Int {
        val before: Int = rows.size
        rows.removeAll { seqs.contains(it.seq) }
        changes.value += 1
        return before - rows.size
    }

    override suspend fun deleteByEntity(entityKey: String, operationType: String): Int {
        val before: Int = rows.size
        rows.removeAll { it.entityKey == entityKey && it.operationType.dbValue == operationType }
        changes.value += 1
        return before - rows.size
    }

    override suspend fun clear() {
        rows.clear()
        changes.value += 1
    }
}

/** A `PlaylistTrackRow` builder, for the few tests that render playlist entries. */
public fun playlistTrackRow(
    position: Int,
    releaseGroupMbid: String,
    discNo: Int = 1,
    trackNo: Int = 1,
    title: String? = "Track",
    fileId: String? = "1",
): PlaylistTrackRow = PlaylistTrackRow(
    position = position,
    key = TrackKeyDb(releaseGroupMbid, discNo, trackNo),
    title = title,
    artistName = "Artist",
    durationMs = 180_000L,
    fileId = fileId,
    format = "flac",
    bitrateKbps = null,
    onDevice = false,
)

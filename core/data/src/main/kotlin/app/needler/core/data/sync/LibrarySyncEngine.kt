package app.needler.core.data.sync

import app.needler.core.data.local.dao.AlbumDao
import app.needler.core.data.local.dao.ArtistDao
import app.needler.core.data.local.dao.SyncStateDao
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.ArtistEntity
import app.needler.core.data.mapper.SubsonicIds
import app.needler.core.data.mapper.SubsonicMappers
import app.needler.core.data.mapper.networkCall
import app.needler.core.domain.model.AlbumSyncReport
import app.needler.core.domain.model.FullSyncReason
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.SyncPhase
import app.needler.core.domain.model.SyncReport
import app.needler.core.domain.model.TrackKey
import app.needler.core.network.subsonic.AlbumListType
import app.needler.core.network.subsonic.SubsonicApi
import app.needler.core.network.subsonic.dto.AlbumId3Dto
import app.needler.core.network.subsonic.dto.ArtistId3Dto
import app.needler.core.network.subsonic.dto.ArtistsDto
import app.needler.core.network.subsonic.dto.IndexesDto

/**
 * Keeping the metadata mirror current.
 *
 * ## Why the delta is built on `getIndexes` and not on `getArtists`
 *
 * `getIndexes` is the **only** endpoint on this server that honours `ifModifiedSince`. `getArtists`
 * accepts the parameter and ignores it, returning the whole artist list every time - so a delta
 * built on `getArtists` is a full sync wearing a delta's clothes, and the "one request, under 100 ms
 * on an unchanged library" budget could never be met. `getArtists` is still the right call for the
 * Artists browse screen, where the whole list is exactly what is wanted; it is only wrong here.
 *
 * A delta then runs in four steps, in this order:
 *
 *  1. `getIndexes(ifModifiedSince = stored revision)`. An unchanged library answers with an empty
 *     index list and the same revision, and nothing downstream runs at all.
 *  2. The artists the index names, each re-read with `getArtist` for its albums.
 *  3. `getAlbumList2?type=newest` for additions - an album can appear under an artist the index did
 *     not flag, because the artist row itself did not change.
 *  4. Each affected album, through [AlbumSyncer], which is where the staleness rule lives.
 */
public class LibrarySyncEngine(
    private val subsonic: SubsonicApi,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val syncStateDao: SyncStateDao,
    private val albumSyncer: AlbumSyncer,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * One delta pass.
     *
     * [force] skips the caller's staleness check, never the revision check: forcing a sync should
     * mean "ask the server now", not "re-download a library the server says has not moved".
     */
    public suspend fun deltaSync(force: Boolean = false): Outcome<SyncReport> {
        val storedRevision: String? = syncStateDao.getLibraryRevision()
        val ifModifiedSince: Long? = storedRevision?.toLongOrNull()

        val indexes: IndexesDto = when (val call = networkCall { subsonic.indexes(ifModifiedSince) }) {
            is Outcome.Failure -> return call
            is Outcome.Success -> call.value
        }

        val changedArtistIds: List<String> = indexes.index
            .flatMap { it.artist }
            .map { it.id }
            .filter { it.isNotBlank() }
            .distinct()

        val revisionMoved: Boolean = ifModifiedSince == null || indexes.lastModified > ifModifiedSince
        if (!revisionMoved && changedArtistIds.isEmpty() && !force) {
            // The cheap case, and the one the performance budget is written for: one request, and
            // every downstream refresh skipped.
            syncStateDao.recordDeltaSync(
                libraryRevision = revisionOf(indexes, storedRevision),
                syncedAt = nowMillis(),
            )
            return Outcome.Success(
                SyncReport(
                    phase = SyncPhase.DELTA,
                    libraryUnchanged = true,
                    newRevision = revisionOf(indexes, storedRevision),
                ),
            )
        }

        var artistsUpdated = 0
        var albumsUpdated = 0
        var tracksUpdated = 0
        val evicted: MutableList<TrackKey> = ArrayList()
        val affected: MutableSet<String> = LinkedHashSet()

        for (artistId in changedArtistIds) {
            val artist: ArtistId3Dto = when (val call = networkCall { subsonic.artist(artistId) }) {
                is Outcome.Failure -> return call
                is Outcome.Success -> call.value
            }
            if (writeArtist(artist)) artistsUpdated++
            affected.addAll(artist.album.mapNotNull { albumMbid(it)?.value })
        }

        // Additions. An album can land under an artist the index did not flag - the artist row was
        // already there and did not change - so the newest list is checked whatever the index said.
        val newest: List<AlbumId3Dto> = when (
            val call = networkCall { subsonic.albumList2(AlbumListType.Newest, size = NEWEST_PAGE_SIZE) }
        ) {
            is Outcome.Failure -> return call
            is Outcome.Success -> call.value
        }
        for (album in newest) {
            val mbid: ReleaseGroupMbid = albumMbid(album) ?: continue
            val known: AlbumEntity? = albumDao.getAlbum(mbid.value)
            if (known == null || !known.inLibrary) affected.add(mbid.value)
        }

        for (mbid in affected) {
            val report: AlbumSyncReport =
                when (val call = albumSyncer.syncAlbum(ReleaseGroupMbid(mbid))) {
                    is Outcome.Failure -> return call
                    is Outcome.Success -> call.value
                }
            albumsUpdated++
            tracksUpdated += report.trackCount
            evicted.addAll(report.staleTracksEvicted)
        }

        val newRevision: String? = revisionOf(indexes, storedRevision)
        syncStateDao.recordDeltaSync(libraryRevision = newRevision, syncedAt = nowMillis())

        return Outcome.Success(
            SyncReport(
                phase = SyncPhase.DELTA,
                artistsUpdated = artistsUpdated,
                albumsUpdated = albumsUpdated,
                tracksUpdated = tracksUpdated,
                libraryUnchanged = false,
                staleTracksEvicted = evicted,
                newRevision = newRevision,
            ),
        )
    }

    /**
     * A full rebuild of the mirror.
     *
     * Expensive - one request per artist plus one per album - and only three reasons justify it:
     * first connect, a changed server identity, or an explicit rebuild. Here `getArtists` *is* the
     * right call, because the whole list is what is wanted.
     */
    public suspend fun fullSync(reason: FullSyncReason): Outcome<SyncReport> {
        val artists: ArtistsDto = when (val call = networkCall { subsonic.artists() }) {
            is Outcome.Failure -> return call
            is Outcome.Success -> call.value
        }

        var artistsUpdated = 0
        var albumsUpdated = 0
        var tracksUpdated = 0
        val evicted: MutableList<TrackKey> = ArrayList()

        for (index in artists.index) {
            for (summary in index.artist) {
                val detail: ArtistId3Dto =
                    when (val call = networkCall { subsonic.artist(summary.id) }) {
                        is Outcome.Failure -> return call
                        is Outcome.Success -> call.value
                    }
                if (writeArtist(detail)) artistsUpdated++
                for (album in detail.album) {
                    val mbid: ReleaseGroupMbid = albumMbid(album) ?: continue
                    val report: AlbumSyncReport = when (val call = albumSyncer.syncAlbum(mbid)) {
                        is Outcome.Failure -> return call
                        is Outcome.Success -> call.value
                    }
                    albumsUpdated++
                    tracksUpdated += report.trackCount
                    evicted.addAll(report.staleTracksEvicted)
                }
            }
        }

        // The revision to hand back on the next delta. Reading it from `getIndexes` rather than
        // guessing means the first delta after a full sync is the cheap one.
        val revision: String? = when (val call = networkCall { subsonic.indexes() }) {
            is Outcome.Failure -> null
            is Outcome.Success -> call.value.lastModified.takeIf { it > 0L }?.toString()
        }
        syncStateDao.recordFullSync(libraryRevision = revision, syncedAt = nowMillis())

        return Outcome.Success(
            SyncReport(
                phase = SyncPhase.FULL,
                artistsUpdated = artistsUpdated,
                albumsUpdated = albumsUpdated,
                tracksUpdated = tracksUpdated,
                libraryUnchanged = false,
                staleTracksEvicted = evicted,
                newRevision = revision,
            ),
        )
    }

    /** `getScanStatus`, for the "last scan 47m ago" line. */
    public suspend fun refreshScanStatus(): Outcome<Unit> =
        when (val call = networkCall { subsonic.scanStatus() }) {
            is Outcome.Failure -> call
            is Outcome.Success -> {
                // The shim reports whether a scan is running and how many items it has seen, not
                // when the last one finished, so "last scan" is recorded as the time Needler last
                // saw a settled scan rather than invented from a field that does not exist.
                if (!call.value.scanning) {
                    syncStateDao.recordScanTime(lastScanAt = nowMillis(), updatedAt = nowMillis())
                }
                Outcome.Ok
            }
        }

    private suspend fun writeArtist(dto: ArtistId3Dto): Boolean {
        val existing: ArtistEntity? = SubsonicIds.artistMbid(dto.id, dto.musicBrainzId)
            ?.let { artistDao.getArtist(it.value) }
        val row: ArtistEntity = SubsonicMappers.artistEntity(
            dto = dto,
            now = nowMillis(),
            // `monitored` is the user's own flag, set by a request's `monitor_artist`. The library
            // lane knows nothing about it, so a sync must carry it across rather than clear it.
            monitored = existing?.monitored ?: false,
        ) ?: return false
        artistDao.upsert(row)
        return true
    }

    private fun albumMbid(dto: AlbumId3Dto): ReleaseGroupMbid? =
        SubsonicIds.releaseGroupMbid(dto.id, dto.musicBrainzId)

    private fun revisionOf(indexes: IndexesDto, stored: String?): String? =
        indexes.lastModified.takeIf { it > 0L }?.toString() ?: stored

    public companion object {
        /** `getAlbumList2` is capped at 500 server-side; a delta only needs the recent tail. */
        public const val NEWEST_PAGE_SIZE: Int = 100
    }
}

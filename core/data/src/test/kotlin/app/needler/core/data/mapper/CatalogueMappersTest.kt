package app.needler.core.data.mapper

import app.needler.core.data.fake.RG
import app.needler.core.data.local.entity.PullEntity
import app.needler.core.data.local.entity.PullStatusDb
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.PullState
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.StatsSource
import app.needler.core.network.v1.dto.DownloadTaskDto
import app.needler.core.network.v1.dto.LibraryStatsDto
import app.needler.core.network.v1.dto.ReleaseItemDto
import app.needler.core.network.v1.dto.RequestAcceptedDto
import app.needler.core.network.v1.dto.SearchResultDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping the catalogue lane.
 *
 * Two things here are load-bearing rather than mechanical: a catalogue hit becomes the **same**
 * `Album` type as a library album at a different state, and a download task's stored status folds in
 * the two client-derived states the Pulls screen filters on.
 */
public class CatalogueMappersTest {

    private val now: Long = 1_700_000_000_000L

    // ------------------------------------------------------------------- search

    @Test
    public fun `an album hit becomes an Album, not a search-result type`() {
        val album: Album = CatalogueMappers.album(
            SearchResultDto(type = "album", title = "Spiderland", musicbrainzId = RG, artist = "Slint"),
        )!!

        assertEquals(RG, album.releaseGroupMbid.value)
        assertEquals(AlbumState.NotOwned, album.state)
        // Catalogue artwork comes from a different endpoint, and the domain records which applies
        // rather than letting each surface guess.
        assertTrue(album.artwork is ArtworkRef.Catalogue)
    }

    @Test
    public fun `the server's own in_library flag is trusted for the state`() {
        val album: Album = CatalogueMappers.album(
            SearchResultDto(type = "album", title = "x", musicbrainzId = RG, inLibrary = true),
        )!!

        assertEquals(AlbumState.Owned, album.state)
    }

    @Test
    public fun `an artist hit is not mistaken for an album`() {
        val dto = SearchResultDto(type = "artist", title = "Slint", musicbrainzId = "artist-mbid")

        assertNull(CatalogueMappers.album(dto))
        assertNotNull(CatalogueMappers.artist(dto))
    }

    @Test
    public fun `a hit with no MBID is dropped, because the join key is the whole architecture`() {
        assertNull(CatalogueMappers.album(SearchResultDto(type = "album", title = "x")))
    }

    @Test
    public fun `service_status surfaces only when something is actually degraded`() {
        assertNull(CatalogueMappers.serviceStatus(mapOf("musicbrainz" to "ok")))
        assertNull(CatalogueMappers.serviceStatus(emptyMap()))

        val degraded = CatalogueMappers.serviceStatus(mapOf("musicbrainz" to "degraded"))!!
        assertTrue(degraded.isDegraded)
        assertTrue(degraded.message.orEmpty().contains("musicbrainz"))
    }

    // ------------------------------------------------------------ discographies

    @Test
    public fun `a discography entry maps with the artist carried in from the caller`() {
        val album: Album = CatalogueMappers.album(
            ReleaseItemDto(id = RG, title = "Tweez", firstReleaseDate = "1989-01-01"),
            artistName = "Slint",
            artistMbid = null,
        )!!

        assertEquals("Slint", album.artistName)
        assertEquals(1989, album.year)
    }

    @Test
    public fun `a catalogue album stored in the mirror is un-owned and therefore prunable`() {
        val album: Album = CatalogueMappers.album(
            SearchResultDto(type = "album", title = "x", musicbrainzId = RG),
        )!!

        val row = CatalogueMappers.albumEntity(album, now)

        assertEquals(false, row.inLibrary)
        assertEquals(now, row.updatedAt)
    }

    // -------------------------------------------------------------------- pulls

    @Test
    public fun `queued with no search job is Searching`() {
        val row: PullEntity = CatalogueMappers.pullEntity(
            task(status = "queued", searchJobId = null, candidateIndex = null),
            now,
        )!!

        assertEquals(PullStatusDb.SEARCHING, row.status)
        assertEquals(PullState.SEARCHING, EntityMappers.pullState(row))
    }

    @Test
    public fun `queued with a search job but no candidate needs attention on the server`() {
        val row: PullEntity = CatalogueMappers.pullEntity(
            task(status = "queued", searchJobId = "job-1", candidateIndex = null),
            now,
        )!!

        assertEquals(PullStatusDb.NEEDS_ATTENTION, row.status)
        assertEquals(PullState.AWAITING_SOURCE_REVIEW, EntityMappers.pullState(row))
    }

    @Test
    public fun `queued with a chosen candidate is simply queued`() {
        val row: PullEntity = CatalogueMappers.pullEntity(
            task(status = "queued", searchJobId = "job-1", candidateIndex = 0),
            now,
        )!!

        assertEquals(PullStatusDb.QUEUED, row.status)
        assertEquals(PullState.QUEUED, EntityMappers.pullState(row))
    }

    @Test
    public fun `the search job fields are stored, or both derived states are impossible`() {
        val row: PullEntity = CatalogueMappers.pullEntity(
            task(status = "queued", searchJobId = "job-7", candidateIndex = 3),
            now,
        )!!

        assertEquals("job-7", row.searchJobId)
        assertEquals(3, row.candidateIndex)
    }

    @Test
    public fun `epoch seconds as a float become millisecond timestamps`() {
        val row: PullEntity = CatalogueMappers.pullEntity(
            task(status = "downloading").copy(createdAt = 1_700_000_000.5),
            now,
        )!!

        assertEquals(1_700_000_000_500L, row.createdAt)
    }

    @Test
    public fun `a partial pull is in the library, because the tracks that arrived are real`() {
        assertEquals(
            app.needler.core.data.local.entity.AlbumStateDb.OWNED,
            EntityMappers.albumStateFor(PullStatusDb.PARTIAL),
        )
    }

    @Test
    public fun `a held task carries a read-only notice rather than an error`() {
        val row: PullEntity = CatalogueMappers.pullEntity(
            task(status = "processing").copy(heldForReview = true),
            now,
        )!!

        assertTrue(row.error.orEmpty().contains("Held for review"))
    }

    // ----------------------------------------------------------------- receipts

    @Test
    public fun `a receipt renders the server's status and never infers it`() {
        val receipt = CatalogueMappers.receipt(
            RequestAcceptedDto(
                success = true,
                message = "queued",
                musicbrainzId = RG,
                status = "awaiting_approval",
            ),
        )

        assertEquals(RequestStatus.PENDING_APPROVAL, receipt.status)
        assertEquals(RG, receipt.releaseGroupMbid?.value)
    }

    @Test
    public fun `an unsuccessful receipt is rejected however friendly its status looks`() {
        val receipt = CatalogueMappers.receipt(
            RequestAcceptedDto(success = false, message = "no", musicbrainzId = RG, status = "queued"),
        )

        assertEquals(RequestStatus.REJECTED, receipt.status)
    }

    // -------------------------------------------------------------------- stats

    @Test
    public fun `library stats use the live field names, including the epoch-float scan time`() {
        val stats = CatalogueMappers.libraryStats(
            LibraryStatsDto(
                totalAlbums = 176,
                totalArtists = 40,
                totalTracks = 2_000,
                totalSizeBytes = 42_000_000_000L,
                lastScanAt = 1_700_000_000.0,
            ),
        )

        assertEquals(176, stats.albumCount)
        assertEquals(42_000_000_000L, stats.totalSizeBytes)
        assertEquals(StatsSource.SERVER, stats.source)
        assertEquals(1_700_000_000_000L, stats.lastScanAt?.toEpochMilliseconds())
    }

    private fun task(
        status: String,
        searchJobId: String? = null,
        candidateIndex: Int? = null,
    ): DownloadTaskDto = DownloadTaskDto(
        id = "task-1",
        releaseGroupMbid = RG,
        artistName = "Slint",
        albumTitle = "Spiderland",
        status = status,
        progressPercent = 40,
        searchJobId = searchJobId,
        candidateIndex = candidateIndex,
        createdAt = 1_700_000_000.0,
        updatedAt = 1_700_000_100.0,
    )
}

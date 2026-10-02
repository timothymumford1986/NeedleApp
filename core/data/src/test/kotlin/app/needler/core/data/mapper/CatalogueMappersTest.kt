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
import org.junit.Assert.assertFalse
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

    // ------------------------------------------- the kind, and the id it implies
    //
    // REQUIREMENTS.md "Placing a request": cancel and retry of a request take `request_kind`, and a
    // track request is "keyed on recording MBID, not release group". The `pull` row is keyed on the
    // release group either way - REQUIREMENTS.md "Identity model" - so both have to be stored or the
    // cancel is sent for the wrong thing under the wrong kind.

    @Test
    public fun `a track download task stores its kind and its recording MBID`() {
        val row: PullEntity = CatalogueMappers.pullEntity(
            task(status = "downloading").copy(downloadType = "track", recordingMbid = RECORDING),
            now,
        )!!

        assertTrue(row.isTrackRequest)
        assertEquals(RECORDING, row.recordingMbid)
        // Still keyed on the release group, which is what the screen and the album join look up.
        assertEquals(RG, row.releaseGroupMbid)
    }

    @Test
    public fun `an album download task stores no recording MBID at all`() {
        val row: PullEntity = CatalogueMappers.pullEntity(task(status = "downloading"), now)!!

        assertFalse(row.isTrackRequest)
        assertNull(row.recordingMbid)
    }

    /**
     * On `requests/active`, `musicbrainz_id` is the *recording* for a track row.
     *
     * The release group arrives separately as `track_release_group_mbid`, which is why the row is
     * keyed on that; this is the one place both ids are in hand, so it is where the recording is
     * captured for a later cancel.
     */
    @Test
    public fun `a parked track request keeps the recording MBID and keys on the release group`() {
        val row: PullEntity = CatalogueMappers.pendingApprovalEntity(
            app.needler.core.network.v1.dto.ActiveRequestItemDto(
                musicbrainzId = RECORDING,
                trackReleaseGroupMbid = RG,
                requestKind = "track",
                status = "awaiting_approval",
            ),
            now,
        )!!

        assertEquals(RG, row.releaseGroupMbid)
        assertEquals(RECORDING, row.recordingMbid)
        assertTrue(row.isTrackRequest)
    }

    @Test
    public fun `a parked album request records no recording MBID`() {
        val row: PullEntity = CatalogueMappers.pendingApprovalEntity(
            app.needler.core.network.v1.dto.ActiveRequestItemDto(
                musicbrainzId = RG,
                status = "awaiting_approval",
            ),
            now,
        )!!

        assertFalse(row.isTrackRequest)
        assertNull(row.recordingMbid)
    }

    @Test
    public fun `an unrecognised download type is an album, which is the safe direction`() {
        val row: PullEntity = CatalogueMappers.pullEntity(
            task(status = "downloading").copy(downloadType = "boxset"),
            now,
        )!!

        assertFalse(row.isTrackRequest)
    }

    // --------------------------------------------------------- the quality badge

    /**
     * REQUIREMENTS.md "Design pack discrepancies": `quality_snapshot_summary` is the honest thing to
     * show, because quality is a server-side policy a user cannot override - and it rides on the
     * `album` row, which the Pulls projection joins for the title anyway.
     */
    @Test
    public fun `a placeholder album row can carry the quality the server applied`() {
        val row = CatalogueMappers.placeholderAlbumEntity(
            releaseGroupMbid = RG,
            title = "Spiderland",
            artistName = "Slint",
            artistMbid = null,
            year = 1991,
            now = now,
            qualityPolicySummary = "FLAC",
        )

        assertEquals("FLAC", row.qualityPolicySummary)
    }

    @Test
    public fun `a placeholder album row with no quality answer stores none`() {
        val row = CatalogueMappers.placeholderAlbumEntity(
            releaseGroupMbid = RG,
            title = "Spiderland",
            artistName = "Slint",
            artistMbid = null,
            year = 1991,
            now = now,
        )

        assertNull(row.qualityPolicySummary)
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

    /**
     * The 202 that used to be read as a rejection.
     *
     * REQUIREMENTS.md "Placing a request", item 1: the accepted status is 202, and "a client that
     * treats anything other than 200 as failure reports every successful pull as an error". A
     * non-null `success` defaulting to `false` was that same false negative reached through the
     * body: the server's structs emit every field including nulls, the decoder coerced the null onto
     * the default, and an accepted request became `REJECTED` - a `pull` row stored `FAILED` and the
     * album marked failed in the mirror. Only an explicit `false` is a refusal.
     */
    @Test
    public fun `a receipt with no success flag is not a rejection`() {
        val absent = CatalogueMappers.receipt(
            RequestAcceptedDto(musicbrainzId = RG, status = "awaiting_approval"),
        )
        val explicitNull = CatalogueMappers.receipt(
            RequestAcceptedDto(success = null, musicbrainzId = RG, status = "queued"),
        )

        assertEquals(RequestStatus.PENDING_APPROVAL, absent.status)
        assertEquals(RequestStatus.ACCEPTED, explicitNull.status)
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

    private companion object {
        /** A recording MBID, which is the id a `request_kind=track` cancel is keyed on. */
        const val RECORDING: String = "9d9f2a1b-0c3d-4e5f-8a7b-6c5d4e3f2a1b"
    }
}

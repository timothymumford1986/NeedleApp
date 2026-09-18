package app.needler.core.data.mapper

import app.needler.core.data.fake.RG
import app.needler.core.data.fake.albumDto
import app.needler.core.data.fake.artistDto
import app.needler.core.data.fake.songDto
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.ArtistEntity
import app.needler.core.data.local.entity.PlaylistTrackEntity
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.network.subsonic.dto.ChildDto
import app.needler.core.network.subsonic.dto.PlaylistDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping the owned-library lane into mirror rows.
 *
 * The parts worth fixing in a test are the ones that are silent when wrong: a track key built from
 * the wrong field, a duplicate (disc, track) pair losing a row on the primary key, and the quality
 * badge promising more than the album delivers.
 */
public class SubsonicMappersTest {

    private val now: Long = 1_700_000_000_000L

    // ------------------------------------------------------------------- albums

    @Test
    public fun `an album maps onto the release-group MBID, stripped of its prefix`() {
        val row: AlbumEntity = SubsonicMappers.albumEntity(albumDto(), now)!!

        assertEquals(RG, row.releaseGroupMbid)
        assertEquals("Spiderland", row.title)
        assertEquals("Slint", row.artistName)
        assertEquals(1991, row.year)
        assertTrue(row.inLibrary)
        assertEquals(AlbumStateDb.OWNED, row.state)
    }

    @Test
    public fun `an album with no usable id is dropped rather than stored under a blank key`() {
        assertNull(SubsonicMappers.albumEntity(albumDto().copy(id = "", musicBrainzId = null), now))
    }

    @Test
    public fun `duration arrives in seconds and is stored in milliseconds`() {
        val row: AlbumEntity = SubsonicMappers.albumEntity(albumDto(), now)!!

        assertEquals(2_400_000L, row.durationMs)
    }

    @Test
    public fun `the state is passed in, because the server payload knows nothing about pins`() {
        val row: AlbumEntity =
            SubsonicMappers.albumEntity(albumDto(), now, state = AlbumStateDb.PINNED)!!

        assertEquals(AlbumStateDb.PINNED, row.state)
        assertTrue(row.inLibrary)
    }

    // ------------------------------------------------------------------- tracks

    @Test
    public fun `a track key is release group, disc and track number - never the file id`() {
        val rows: List<TrackEntity> = SubsonicMappers.trackEntities(
            releaseGroupMbid = ReleaseGroupMbid(RG),
            songs = listOf(songDto(fileId = "8801", disc = 2, track = 7)),
            now = now,
        )

        val row: TrackEntity = rows.single()
        assertEquals(RG, row.releaseGroupMbid)
        assertEquals(2, row.discNo)
        assertEquals(7, row.trackNo)
        // The file id is stored, but only as a fetch handle and a staleness signal.
        assertEquals("8801", row.fileId)
    }

    @Test
    public fun `a song with no track number falls back to its ordinal within its disc`() {
        val rows: List<TrackEntity> = SubsonicMappers.trackEntities(
            releaseGroupMbid = ReleaseGroupMbid(RG),
            songs = listOf(
                songDto(fileId = "1", track = null, title = "One"),
                songDto(fileId = "2", track = null, title = "Two"),
            ),
            now = now,
        )

        assertEquals(listOf(1, 2), rows.map { it.trackNo })
        assertEquals(listOf("One", "Two"), rows.map { it.title })
    }

    @Test
    public fun `a duplicate disc and track pair keeps both rows instead of colliding`() {
        // Sloppily tagged rips do this. The primary key is (release group, disc, track), so a naive
        // mapping would silently lose the second song on upsert.
        val rows: List<TrackEntity> = SubsonicMappers.trackEntities(
            releaseGroupMbid = ReleaseGroupMbid(RG),
            songs = listOf(
                songDto(fileId = "1", track = 1, title = "One"),
                songDto(fileId = "2", track = 1, title = "Also one"),
            ),
            now = now,
        )

        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.discNo to it.trackNo }.toSet().size)
    }

    @Test
    public fun `tracks come back in disc then track order`() {
        val rows: List<TrackEntity> = SubsonicMappers.trackEntities(
            releaseGroupMbid = ReleaseGroupMbid(RG),
            songs = listOf(
                songDto(fileId = "3", disc = 2, track = 1),
                songDto(fileId = "1", disc = 1, track = 1),
                songDto(fileId = "2", disc = 1, track = 2),
            ),
            now = now,
        )

        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), rows.map { it.discNo to it.trackNo })
    }

    @Test
    public fun `directories in a song list are skipped`() {
        val rows: List<TrackEntity> = SubsonicMappers.trackEntities(
            releaseGroupMbid = ReleaseGroupMbid(RG),
            songs = listOf(songDto().copy(isDir = true), songDto(fileId = "9", track = 2)),
            now = now,
        )

        assertEquals(1, rows.size)
        assertEquals("9", rows.single().fileId)
    }

    // ------------------------------------------------------------------ quality

    @Test
    public fun `an all-lossless album badges FLAC with no bitrate`() {
        val summary: TrackQualitySummary = SubsonicMappers.qualitySummary(
            listOf(songDto(suffix = "flac"), songDto(fileId = "2", suffix = "flac")),
        )

        assertEquals("flac", summary.format)
        assertNull(summary.bitrateKbps)
    }

    @Test
    public fun `a mixed album reports its worst track, because the badge is a promise`() {
        val summary: TrackQualitySummary = SubsonicMappers.qualitySummary(
            listOf(
                songDto(fileId = "1", suffix = "flac"),
                songDto(fileId = "2", suffix = "mp3", bitRate = 320),
                songDto(fileId = "3", suffix = "mp3", bitRate = 256),
            ),
        )

        assertEquals("mp3", summary.format)
        assertEquals(256, summary.bitrateKbps)
    }

    @Test
    public fun `sizes are summed onto the album so the header has an offline fallback`() {
        val summary: TrackQualitySummary = SubsonicMappers.qualitySummary(
            listOf(songDto(fileId = "1", size = 100L), songDto(fileId = "2", size = 250L)),
        )

        assertEquals(350L, summary.totalSizeBytes)
    }

    // ------------------------------------------------------------------ artists

    @Test
    public fun `an artist maps onto its MBID with a sort name for the index jump`() {
        val row: ArtistEntity = SubsonicMappers.artistEntity(
            artistDto().copy(sortName = "Slint"),
            now,
        )!!

        assertEquals("slint", row.sortNameNormalised)
        assertEquals(1, row.albumCount)
    }

    @Test
    public fun `the monitored flag is carried in, because the library lane knows nothing about it`() {
        val row: ArtistEntity = SubsonicMappers.artistEntity(artistDto(), now, monitored = true)!!

        assertTrue(row.monitored)
    }

    // ---------------------------------------------------------------- playlists

    @Test
    public fun `playlist entries point at stable track keys, never at file ids`() {
        val entries: List<PlaylistTrackEntity> = SubsonicMappers.playlistTrackEntities(
            playlistId = "42",
            entries = listOf(
                songDto(fileId = "8801", disc = 1, track = 3),
                songDto(fileId = "8802", disc = 1, track = 4),
            ),
        )

        assertEquals(listOf(0, 1), entries.map { it.position })
        assertEquals(listOf(3, 4), entries.map { it.trackNo })
        assertEquals(listOf(RG, RG), entries.map { it.releaseGroupMbid })
        // The file id is kept only as a hint about which bytes the entry was built from.
        assertEquals(listOf("8801", "8802"), entries.map { it.sourceFileId })
    }

    @Test
    public fun `a playlist entry with no resolvable album is dropped, not stored unjoinable`() {
        val entries: List<PlaylistTrackEntity> = SubsonicMappers.playlistTrackEntities(
            playlistId = "42",
            entries = listOf(
                songDto(fileId = "1").copy(albumId = null, parent = null),
                songDto(fileId = "2", track = 2),
            ),
        )

        assertEquals(1, entries.size)
        assertEquals(0, entries.single().position)
    }

    @Test
    public fun `a playlist maps its counts and timestamps`() {
        val dto = PlaylistDto(
            id = "pl-42",
            name = "Late night",
            songCount = 2,
            duration = 600,
            created = "2024-01-02T03:04:05Z",
            entry = listOf<ChildDto>(songDto(), songDto(fileId = "2", track = 2)),
        )

        val row = SubsonicMappers.playlistEntity(dto, now)!!

        assertEquals("42", row.playlistId)
        assertEquals(2, row.trackCount)
        assertEquals(600_000L, row.durationMs)
        assertEquals("late night", row.nameNormalised)
    }
}

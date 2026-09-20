package app.needler.core.data.repository

import app.needler.core.data.fake.FakeNetworkMonitor
import app.needler.core.data.fake.FakePlaylistDao
import app.needler.core.data.fake.FakeSubsonicApi
import app.needler.core.data.fake.FakeTrackDao
import app.needler.core.data.fake.FakeWriteQueueDao
import app.needler.core.data.fake.RG
import app.needler.core.data.fake.trackRow
import app.needler.core.data.local.entity.WriteOperationTypeDb
import app.needler.core.data.writequeue.DefaultWriteQueueExecutor
import app.needler.core.data.fake.FakeAlbumDao
import app.needler.core.data.fake.FakeFavouriteDao
import app.needler.core.data.writequeue.WriteQueue
import app.needler.core.data.writequeue.WriteQueueFlusher
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Playlist
import app.needler.core.domain.model.PlaylistEdit
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.TrackKey
import app.needler.core.network.subsonic.dto.PlaylistDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playlists: local first, then the server or the journal.
 *
 * The case worth the most care is a playlist created with no connection. It is a real row under a
 * locally minted id with `local_only` set, and replay **re-keys** that row to the id the server
 * assigns. Inserting a second row instead would show the user their playlist twice; losing the flag
 * would make a failed replay leave a permanently invisible row.
 */
public class PlaylistRepositoryTest {

    private val playlistDao = FakePlaylistDao()
    private val trackDao = FakeTrackDao()
    private val albumDao = FakeAlbumDao()
    private val favouriteDao = FakeFavouriteDao()
    private val writeQueueDao = FakeWriteQueueDao()
    private val writeQueue = WriteQueue(writeQueueDao) { NOW }
    private val network = FakeNetworkMonitor()
    private val subsonic = FakeSubsonicApi()

    private val repository = DefaultPlaylistRepository(
        playlistDao = playlistDao,
        trackDao = trackDao,
        writeQueueDao = writeQueueDao,
        writeQueue = writeQueue,
        networkMonitor = network,
        subsonic = subsonic,
        nowMillis = { NOW },
        newLocalId = { "local-fixed" },
    )

    private val key = TrackKey(ReleaseGroupMbid(RG), discNumber = 1, trackNumber = 1)

    // --------------------------------------------------------------- offline create

    @Test
    public fun `a playlist created offline is a real row under a provisional id`(): Unit = runTest {
        network.goOffline()

        val id: PlaylistId =
            (repository.createPlaylist("Late night", listOf(key)) as Outcome.Success).value

        assertEquals("local-fixed", id.value)
        assertTrue(playlistDao.playlists["local-fixed"]?.localOnly == true)
        assertEquals(1, playlistDao.entries.size)
        assertTrue(subsonic.calls.isEmpty())
    }

    @Test
    public fun `a local-only playlist reads as having pending edits`(): Unit = runTest {
        network.goOffline()
        repository.createPlaylist("Late night", emptyList())

        val playlist: Playlist = repository.observePlaylist(PlaylistId("local-fixed")).first()!!

        assertTrue(playlist.hasPendingLocalEdits)
    }

    @Test
    public fun `the queued create carries the provisional id, so replay can adopt`(): Unit = runTest {
        network.goOffline()
        repository.createPlaylist("Late night", emptyList())

        val row = writeQueueDao.rows.single()
        assertEquals(WriteOperationTypeDb.PLAYLIST_CREATE, row.operationType)
        assertEquals("local-fixed", row.entityKey)
    }

    @Test
    public fun `replay re-keys the local row onto the server id instead of inserting`(): Unit =
        runTest {
            network.goOffline()
            repository.createPlaylist("Late night", listOf(key))
            network.state.value = app.needler.core.domain.model.ConnectivityState.Unmetered
            subsonic.createPlaylistResponse = { name, _, _ ->
                PlaylistDto(id = "pl-77", name = name.orEmpty())
            }

            flusher().flush()

            assertNull(playlistDao.playlists["local-fixed"])
            assertNotNull(playlistDao.playlists["77"])
            assertFalse(playlistDao.playlists["77"]!!.localOnly)
            // The entries moved with it, rather than being orphaned under the old id.
            assertEquals(listOf("77"), playlistDao.entries.map { it.playlistId })
        }

    // ---------------------------------------------------------------- online create

    @Test
    public fun `an online create adopts the server's id straight away`(): Unit = runTest {
        subsonic.createPlaylistResponse = { name, _, _ -> PlaylistDto(id = "pl-12", name = name.orEmpty()) }

        val id: PlaylistId = (repository.createPlaylist("Late night") as Outcome.Success).value

        assertEquals("12", id.value)
        assertTrue(playlistDao.playlists.containsKey("12"))
        assertFalse(playlistDao.playlists["12"]!!.localOnly)
    }

    @Test
    public fun `creating online resolves track keys to the ids the server wants`(): Unit = runTest {
        trackDao.rows["$RG/1/1"] = trackRow(fileId = "8801")
        subsonic.createPlaylistResponse = { _, _, _ -> PlaylistDto(id = "pl-12", name = "x") }

        repository.createPlaylist("Late night", listOf(key))

        assertTrue(subsonic.calls.any { it.contains("tr-8801") })
    }

    @Test
    public fun `a track with no server file is skipped rather than sent as a broken id`(): Unit =
        runTest {
            trackDao.rows["$RG/1/1"] = trackRow(fileId = null)
            subsonic.createPlaylistResponse = { _, _, _ -> PlaylistDto(id = "pl-12", name = "x") }

            repository.createPlaylist("Late night", listOf(key))

            assertTrue(subsonic.calls.any { it.startsWith("createPlaylist(Late night,,") })
        }

    // ----------------------------------------------------------------------- edits

    @Test
    public fun `an edit applies locally before it is sent`(): Unit = runTest {
        playlistDao.playlists["7"] = playlist("7", "Old name")

        repository.applyEdit(PlaylistEdit.Rename(PlaylistId("7"), "New name"))

        assertEquals("New name", playlistDao.playlists["7"]?.name)
        assertTrue(subsonic.calls.any { it.startsWith("updatePlaylist(pl-7") })
    }

    @Test
    public fun `an offline edit applies locally and waits in the journal`(): Unit = runTest {
        playlistDao.playlists["7"] = playlist("7", "Old name")
        network.goOffline()

        repository.applyEdit(PlaylistEdit.Rename(PlaylistId("7"), "New name"))

        assertEquals("New name", playlistDao.playlists["7"]?.name)
        assertEquals(WriteOperationTypeDb.PLAYLIST_RENAME, writeQueueDao.rows.single().operationType)
        assertTrue(subsonic.calls.isEmpty())
    }

    @Test
    public fun `removal is by index and renumbers what is left`(): Unit = runTest {
        playlistDao.playlists["7"] = playlist("7", "Mix")
        playlistDao.upsertTracks(
            listOf(
                entry("7", 0, 1),
                entry("7", 1, 2),
                entry("7", 2, 3),
            ),
        )

        repository.removeTracks(PlaylistId("7"), listOf(1))

        val remaining = playlistDao.getTracksOf("7")
        assertEquals(listOf(0, 1), remaining.map { it.position })
        assertEquals(listOf(1, 3), remaining.map { it.trackNo })
    }

    @Test
    public fun `adding tracks appends after what is already there`(): Unit = runTest {
        playlistDao.playlists["7"] = playlist("7", "Mix")
        playlistDao.upsertTracks(listOf(entry("7", 0, 1)))

        repository.addTracks(PlaylistId("7"), listOf(key.copy(trackNumber = 5)))

        assertEquals(listOf(0, 1), playlistDao.getTracksOf("7").map { it.position })
        assertEquals(2, playlistDao.playlists["7"]?.trackCount)
    }

    @Test
    public fun `a reorder is sent as a whole-playlist replace, which is the protocol's own quirk`(): Unit =
        runTest {
            playlistDao.playlists["7"] = playlist("7", "Mix")
            trackDao.rows["$RG/1/1"] = trackRow(fileId = "1")
            trackDao.rows["$RG/1/2"] = trackRow(track = 2, fileId = "2")

            repository.applyEdit(
                PlaylistEdit.Reorder(
                    PlaylistId("7"),
                    listOf(key.copy(trackNumber = 2), key),
                ),
            )

            assertTrue(subsonic.calls.any { it.startsWith("createPlaylist(null,tr-2|tr-1,pl-7)") })
        }

    @Test
    public fun `an edit to a local-only playlist queues behind its create`(): Unit = runTest {
        network.goOffline()
        repository.createPlaylist("Late night", emptyList())
        repository.applyEdit(PlaylistEdit.Rename(PlaylistId("local-fixed"), "Later"))

        assertEquals(2, writeQueueDao.rows.size)
        assertEquals(WriteOperationTypeDb.PLAYLIST_CREATE, writeQueueDao.rows[0].operationType)
        assertEquals(WriteOperationTypeDb.PLAYLIST_RENAME, writeQueueDao.rows[1].operationType)
    }

    @Test
    public fun `deleting removes the row and its entries`(): Unit = runTest {
        playlistDao.playlists["7"] = playlist("7", "Mix")
        playlistDao.upsertTracks(listOf(entry("7", 0, 1)))

        repository.deletePlaylist(PlaylistId("7"))

        assertNull(playlistDao.playlists["7"])
        assertTrue(playlistDao.getTracksOf("7").isEmpty())
    }

    // -------------------------------------------------------------------- refresh

    @Test
    public fun `refreshing a local-only playlist is a success, not a not-found`(): Unit = runTest {
        network.goOffline()
        repository.createPlaylist("Late night", emptyList())
        subsonic.calls.clear()

        val result = repository.refreshPlaylist(PlaylistId("local-fixed"))

        assertTrue(result is Outcome.Success)
        assertTrue(subsonic.calls.isEmpty())
    }

    @Test
    public fun `refreshing writes the server's entries as stable track keys`(): Unit = runTest {
        subsonic.playlistResponse = {
            PlaylistDto(
                id = "pl-7",
                name = "Mix",
                songCount = 1,
                entry = listOf(app.needler.core.data.fake.songDto(fileId = "8801", track = 4)),
            )
        }

        repository.refreshPlaylist(PlaylistId("7"))

        val row = playlistDao.getTracksOf("7").single()
        assertEquals(RG, row.releaseGroupMbid)
        assertEquals(4, row.trackNo)
    }

    private fun flusher(): WriteQueueFlusher = WriteQueueFlusher(
        queue = writeQueue,
        executor = DefaultWriteQueueExecutor(
            v1 = app.needler.core.data.fake.FakeV1Api(),
            subsonic = subsonic,
            trackDao = trackDao,
            playlistDao = playlistDao,
            favouriteDao = favouriteDao,
            nowMillis = { NOW },
        ),
        albumDao = albumDao,
        trackDao = trackDao,
    )

    private fun playlist(id: String, name: String) = app.needler.core.data.local.entity.PlaylistEntity(
        playlistId = id,
        name = name,
        nameNormalised = name.lowercase(),
        trackCount = 0,
        durationMs = null,
        owner = null,
        isPublic = false,
        comment = null,
        coverArtId = null,
        createdAt = NOW,
        changedAt = NOW,
        localOnly = false,
        updatedAt = NOW,
    )

    private fun entry(playlistId: String, position: Int, trackNo: Int) =
        app.needler.core.data.local.entity.PlaylistTrackEntity(
            playlistId = playlistId,
            position = position,
            releaseGroupMbid = RG,
            discNo = 1,
            trackNo = trackNo,
            sourceFileId = null,
        )

    private companion object {
        const val NOW: Long = 1_000L
    }
}

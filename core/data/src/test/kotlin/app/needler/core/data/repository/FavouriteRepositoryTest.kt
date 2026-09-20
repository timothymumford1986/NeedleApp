package app.needler.core.data.repository

import app.needler.core.data.fake.FakeFavouriteDao
import app.needler.core.data.fake.FakeNetworkMonitor
import app.needler.core.data.fake.FakeSubsonicApi
import app.needler.core.data.fake.FakeTrackDao
import app.needler.core.data.fake.FakeWriteQueueDao
import app.needler.core.data.fake.RG
import app.needler.core.data.fake.songDto
import app.needler.core.data.fake.trackRow
import app.needler.core.data.local.entity.FavouriteEntity
import app.needler.core.data.local.entity.FavouriteTypeDb
import app.needler.core.data.local.entity.WriteOperationTypeDb
import app.needler.core.data.writequeue.WriteQueue
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.FavouriteTarget
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.TrackKey
import app.needler.core.network.subsonic.dto.AlbumId3Dto
import app.needler.core.network.subsonic.dto.Starred2Dto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Binary favourites.
 *
 * There is no rating path to test because there is no rating concept: `setRating` on this server
 * validates its input and returns success without persisting anything, so offering it would silently
 * do nothing.
 *
 * What is worth testing is the local-first write. The heart fills under the user's finger whatever
 * the network is doing, `pending_sync` records that the server has not agreed yet, and a refresh must
 * not undo a star the queue has not replayed.
 */
public class FavouriteRepositoryTest {

    private val favouriteDao = FakeFavouriteDao()
    private val trackDao = FakeTrackDao()
    private val writeQueueDao = FakeWriteQueueDao()
    private val writeQueue = WriteQueue(writeQueueDao) { NOW }
    private val network = FakeNetworkMonitor()
    private val subsonic = FakeSubsonicApi()

    private val repository = DefaultFavouriteRepository(
        favouriteDao = favouriteDao,
        trackDao = trackDao,
        writeQueue = writeQueue,
        networkMonitor = network,
        subsonic = subsonic,
        nowMillis = { NOW },
    )

    private val albumTarget = FavouriteTarget.OfAlbum(ReleaseGroupMbid(RG))
    private val trackTarget =
        FavouriteTarget.OfTrack(TrackKey(ReleaseGroupMbid(RG), discNumber = 1, trackNumber = 1))

    // ---------------------------------------------------------------- local first

    @Test
    public fun `starring writes the mirror before the server is asked`(): Unit = runTest {
        repository.setFavourite(albumTarget, starred = true)

        assertNotNull(favouriteDao.rows["album/$RG"])
        assertTrue(repository.observeIsFavourite(albumTarget).first())
    }

    @Test
    public fun `a successful star clears pending_sync`(): Unit = runTest {
        repository.setFavourite(albumTarget, starred = true)

        assertFalse(favouriteDao.rows["album/$RG"]!!.pendingSync)
        assertTrue(subsonic.calls.any { it.startsWith("star(al-") })
    }

    @Test
    public fun `an offline star stays pending and is journalled`(): Unit = runTest {
        network.goOffline()

        repository.setFavourite(albumTarget, starred = true)

        assertTrue(favouriteDao.rows["album/$RG"]!!.pendingSync)
        assertEquals(WriteOperationTypeDb.STAR, writeQueueDao.rows.single().operationType)
        assertTrue(subsonic.calls.isEmpty())
    }

    @Test
    public fun `unstarring removes the row at once`(): Unit = runTest {
        favouriteDao.rows["album/$RG"] = FavouriteEntity(
            entityType = FavouriteTypeDb.ALBUM,
            entityId = RG,
            starredAt = NOW,
            releaseGroupMbid = RG,
            discNo = null,
            trackNo = null,
        )

        repository.setFavourite(albumTarget, starred = false)

        assertNull(favouriteDao.rows["album/$RG"])
        assertTrue(subsonic.calls.any { it.startsWith("unstar(al-") })
    }

    @Test
    public fun `ids route by type, so an artist star does not go out as a song id`(): Unit = runTest {
        repository.setFavourite(FavouriteTarget.OfArtist(ArtistMbid("artist-1")), starred = true)

        assertTrue(subsonic.calls.any { it == "star(ar-artist-1)" })
    }

    @Test
    public fun `a starred track is addressed by its current file id, resolved now`(): Unit = runTest {
        trackDao.rows["$RG/1/1"] = trackRow(fileId = "9402")

        repository.setFavourite(trackTarget, starred = true)

        assertTrue(subsonic.calls.any { it == "star(tr-9402)" })
    }

    @Test
    public fun `a starred track keyed on its stable tuple, never on the file id`(): Unit = runTest {
        trackDao.rows["$RG/1/1"] = trackRow(fileId = "9402")

        repository.setFavourite(trackTarget, starred = true)

        val row: FavouriteEntity = favouriteDao.rows.values.single()
        assertEquals(RG, row.releaseGroupMbid)
        assertEquals(1, row.discNo)
        assertEquals(1, row.trackNo)
        assertEquals("$RG/1/1", row.entityId)
    }

    @Test
    public fun `a track the mirror cannot resolve keeps the local star and queues the write`(): Unit =
        runTest {
            repository.setFavourite(trackTarget, starred = true)

            assertNotNull(favouriteDao.rows.values.singleOrNull())
            assertEquals(1, writeQueueDao.rows.size)
        }

    @Test
    public fun `a retryable failure keeps the star and queues it rather than reporting an error`(): Unit =
        runTest {
            subsonic.failWith = {
                app.needler.core.network.NetworkError.Offline(java.io.IOException("x"))
            }

            val result = repository.setFavourite(albumTarget, starred = true)

            assertTrue(result is app.needler.core.domain.model.Outcome.Success)
            assertEquals(1, writeQueueDao.rows.size)
            assertTrue(favouriteDao.rows["album/$RG"]!!.pendingSync)
        }

    // -------------------------------------------------------------------- refresh

    @Test
    public fun `a refresh replaces the synced rows from getStarred2`(): Unit = runTest {
        subsonic.starred2Response = {
            Starred2Dto(
                album = listOf(
                    AlbumId3Dto(id = "al-$RG", name = "Spiderland", starred = "2024-01-01T00:00:00Z"),
                ),
                song = listOf(songDto(fileId = "8801", track = 3)),
            )
        }

        repository.refreshFavourites()

        assertNotNull(favouriteDao.rows["album/$RG"])
        assertNotNull(favouriteDao.rows["track/$RG/1/3"])
        assertFalse(favouriteDao.rows["album/$RG"]!!.pendingSync)
    }

    @Test
    public fun `a refresh does not undo a star the queue has not replayed yet`(): Unit = runTest {
        network.goOffline()
        repository.setFavourite(albumTarget, starred = true)
        network.state.value = app.needler.core.domain.model.ConnectivityState.Unmetered
        subsonic.starred2Response = { Starred2Dto() }

        repository.refreshFavourites()

        // The server does not know about it yet. Dropping it here would silently undo the tap.
        assertNotNull(favouriteDao.rows["album/$RG"])
        assertTrue(favouriteDao.rows["album/$RG"]!!.pendingSync)
    }

    @Test
    public fun `a starred song with no track number is skipped rather than keyed wrongly`(): Unit =
        runTest {
            subsonic.starred2Response = {
                Starred2Dto(song = listOf(songDto(fileId = "8801", track = null)))
            }

            repository.refreshFavourites()

            assertTrue(favouriteDao.rows.isEmpty())
        }

    private companion object {
        const val NOW: Long = 1_000L
    }
}

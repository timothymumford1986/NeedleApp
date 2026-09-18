package app.needler.core.data.writequeue

import app.needler.core.data.fake.FakeWriteQueueDao
import app.needler.core.data.fake.RG
import app.needler.core.data.local.entity.WriteOperationTypeDb
import app.needler.core.data.local.entity.WriteQueueEntity
import app.needler.core.domain.model.AlbumRequest
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.FavouriteTarget
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.PlaylistEdit
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.ScrobbleEvent
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.WriteOperation
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Journalling offline mutations.
 *
 * Two properties matter and both are easy to lose: the sequence is monotonic, because replay order is
 * the contract, and a later write about the same thing supersedes an earlier one - except a scrobble,
 * where two plays are two listens and the server wants both.
 */
public class WriteQueueTest {

    private val dao = FakeWriteQueueDao()
    private var now: Long = 1_000L
    private val queue = WriteQueue(dao) { now }

    private val trackKey = TrackKey(ReleaseGroupMbid(RG), discNumber = 1, trackNumber = 3)

    // ---------------------------------------------------------------- sequencing

    @Test
    public fun `sequences are monotonic, because replay order is the contract`(): Unit = runTest {
        val first: Long = queue.enqueue(WriteOperation.CancelRequest(ReleaseGroupMbid(RG)))
        val second: Long = queue.enqueue(
            WriteOperation.EditPlaylist(PlaylistEdit.Rename(PlaylistId("7"), "New name")),
        )

        assertTrue(second > first)
        assertEquals(listOf(first, second), dao.rows.map { it.seq })
    }

    @Test
    public fun `entries come back due in sequence order`(): Unit = runTest {
        queue.enqueue(WriteOperation.CancelRequest(ReleaseGroupMbid(RG)))
        queue.enqueue(WriteOperation.RetryRequest(ReleaseGroupMbid(RG)))

        val due: List<WriteQueueEntity> = queue.due()

        assertEquals(listOf(1L, 2L), due.map { it.seq })
    }

    // --------------------------------------------------------------- superseding

    @Test
    public fun `three stars of one album replay once, because it is one intent`(): Unit = runTest {
        val target = FavouriteTarget.OfAlbum(ReleaseGroupMbid(RG))

        repeat(3) { queue.enqueue(WriteOperation.SetFavourite(target, starred = true)) }

        assertEquals(1, dao.rows.size)
    }

    @Test
    public fun `an unstar cancels a pending star rather than racing it`(): Unit = runTest {
        val target = FavouriteTarget.OfArtist(ArtistMbid("artist-1"))

        queue.enqueue(WriteOperation.SetFavourite(target, starred = true))
        queue.enqueue(WriteOperation.SetFavourite(target, starred = false))

        assertEquals(1, dao.rows.size)
        assertEquals(WriteOperationTypeDb.UNSTAR, dao.rows.single().operationType)
    }

    @Test
    public fun `two plays of one track are two scrobbles`(): Unit = runTest {
        val event = scrobble(at = 1_000L)

        queue.enqueue(WriteOperation.SubmitScrobble(event))
        queue.enqueue(WriteOperation.SubmitScrobble(event.copy(playedAt = Instant.fromEpochMilliseconds(2_000L))))

        assertEquals(2, dao.rows.size)
    }

    @Test
    public fun `adding tracks twice queues both, because they are different tracks`(): Unit = runTest {
        val id = PlaylistId("7")

        queue.enqueue(
            WriteOperation.EditPlaylist(PlaylistEdit.AddTracks(id, listOf(trackKey))),
            supersede = false,
        )
        queue.enqueue(
            WriteOperation.EditPlaylist(PlaylistEdit.AddTracks(id, listOf(trackKey.copy(trackNumber = 4)))),
            supersede = false,
        )

        assertEquals(2, dao.rows.size)
    }

    // ------------------------------------------------------------------- backoff

    @Test
    public fun `a failure backs the entry off rather than retrying it immediately`(): Unit = runTest {
        val seq: Long = queue.enqueue(WriteOperation.CancelRequest(ReleaseGroupMbid(RG)))

        queue.recordFailure(seq, attempts = 0, error = NeedlerError.Offline())

        assertEquals(1, dao.rows.single().attempts)
        assertTrue(dao.rows.single().nextAttemptAt > now)
        assertTrue(queue.due().isEmpty())
    }

    @Test
    public fun `backoff grows and is capped at five minutes`(): Unit {
        assertEquals(WriteQueue.BASE_BACKOFF_MILLIS, WriteQueue.backoffMillis(1))
        assertTrue(WriteQueue.backoffMillis(3) > WriteQueue.backoffMillis(2))
        assertEquals(WriteQueue.MAX_BACKOFF_MILLIS, WriteQueue.backoffMillis(20))
    }

    // ------------------------------------------------------------------- codec

    @Test
    public fun `an album request survives a round trip through the payload`(): Unit = runTest {
        val request = AlbumRequest(
            releaseGroupMbid = ReleaseGroupMbid(RG),
            albumTitle = "Spiderland",
            artistName = "Slint",
            year = 1991,
            monitorArtist = true,
        )

        queue.enqueue(WriteOperation.PlaceAlbumRequest(request))
        val decoded = queue.toEntry(dao.rows.single())!!.operation as WriteOperation.PlaceAlbumRequest

        assertEquals(request, decoded.request)
    }

    @Test
    public fun `a scrobble keeps its original timestamp, or a commute lands in one minute`(): Unit =
        runTest {
            val event: ScrobbleEvent = scrobble(at = 1_699_999_000_000L)

            queue.enqueue(WriteOperation.SubmitScrobble(event), supersede = false)
            val decoded = queue.toEntry(dao.rows.single())!!.operation as WriteOperation.SubmitScrobble

            assertEquals(event.playedAt, decoded.scrobble.playedAt)
            assertEquals(event.trackKey, decoded.scrobble.trackKey)
            assertEquals(true, decoded.scrobble.submission)
        }

    @Test
    public fun `a playlist reorder keeps its track order`(): Unit = runTest {
        val keys: List<TrackKey> = listOf(
            trackKey.copy(trackNumber = 3),
            trackKey.copy(trackNumber = 1),
            trackKey.copy(trackNumber = 2),
        )

        queue.enqueue(
            WriteOperation.EditPlaylist(PlaylistEdit.Reorder(PlaylistId("7"), keys)),
            supersede = false,
        )
        val decoded = queue.toEntry(dao.rows.single())!!.operation as WriteOperation.EditPlaylist

        assertEquals(keys, (decoded.edit as PlaylistEdit.Reorder).trackKeys)
    }

    @Test
    public fun `a create carries its provisional local id as the entity key`(): Unit = runTest {
        queue.enqueue(
            operation = WriteOperation.EditPlaylist(PlaylistEdit.Create("Late night", emptyList())),
            supersede = false,
            entityKeyOverride = "local-abc",
        )

        assertEquals("local-abc", dao.rows.single().entityKey)
    }

    @Test
    public fun `an unreadable payload decodes to nothing rather than throwing`(): Unit = runTest {
        dao.enqueue(
            WriteQueueEntity(
                operationType = WriteOperationTypeDb.PULL_REQUEST,
                entityKey = null,
                payload = "not json at all",
                lastError = null,
                createdAt = now,
            ),
        )

        assertEquals(null, queue.toEntry(dao.rows.single()))
    }

    @Test
    public fun `a favourite target round-trips for all three kinds`(): Unit = runTest {
        val targets: List<FavouriteTarget> = listOf(
            FavouriteTarget.OfAlbum(ReleaseGroupMbid(RG)),
            FavouriteTarget.OfArtist(ArtistMbid("artist-1")),
            FavouriteTarget.OfTrack(trackKey),
        )

        targets.forEach { queue.enqueue(WriteOperation.SetFavourite(it, starred = true)) }

        val decoded: List<FavouriteTarget> = dao.rows
            .mapNotNull { queue.toEntry(it)?.operation as? WriteOperation.SetFavourite }
            .map { it.target }
        assertEquals(targets, decoded)
    }

    @Test
    public fun `an entry records when it was created`(): Unit = runTest {
        now = 1_700_000_000_000L
        queue.enqueue(WriteOperation.CancelRequest(ReleaseGroupMbid(RG)))

        val entry = queue.toEntry(dao.rows.single())
        assertNotNull(entry)
        assertEquals(now, entry!!.createdAt.toEpochMilliseconds())
    }

    private fun scrobble(at: Long): ScrobbleEvent = ScrobbleEvent(
        trackKey = trackKey,
        fetchHandle = TrackFetchHandle(
            fileId = FileId("8801"),
            sizeBytes = 40_000_000L,
            durationMs = 353_000L,
            format = null,
            bitrateKbps = null,
        ),
        playedAt = Instant.fromEpochMilliseconds(at),
        submission = true,
    )
}

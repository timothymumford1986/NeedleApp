package app.needler.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.needler.core.data.background.DownloadResumeCoordinator
import app.needler.core.data.fake.FakeAlbumDao
import app.needler.core.data.fake.FakeAudioCacheDao
import app.needler.core.data.fake.FakePinDao
import app.needler.core.data.fake.FakeTrackDao
import app.needler.core.data.fake.RG
import app.needler.core.data.fake.RecordingWorkScheduler
import app.needler.core.data.fake.albumRow
import app.needler.core.data.fake.cacheRow
import app.needler.core.data.fake.pinRow
import app.needler.core.data.fake.trackRow
import app.needler.core.data.local.NeedlerDatabase
import app.needler.core.data.local.cache.CacheIndex
import app.needler.core.data.local.cache.DeviceFreeSpace
import app.needler.core.data.local.cache.RemovedAudio
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.AudioCacheEntity
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.data.local.entity.PinEntity
import app.needler.core.data.mapper.EntityMappers
import app.needler.core.data.platform.ArtworkCacheSize
import app.needler.core.data.settings.NeedlerSettingsStore
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RemovedDownload
import app.needler.core.domain.model.StoppedDownload
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Provider

/**
 * Stopping a download, which is **not** removing one.
 *
 * A device audit watched an album download under a badge reading "Pulling to device, 31 percent,
 * playing now" and found the only action offered beside it was "Device. Remove ... from this device",
 * which deletes. There was no way to stop a download and keep what had arrived, because there was no
 * domain operation to stop one: `BackgroundWorkScheduler.cancelAlbumDownload` was reachable only from
 * [DefaultPinRepository.unpinAlbum] and from eviction, both of which delete the bytes on the way past.
 *
 * REQUIREMENTS.md "The download in flight is a badge, not a banner" sets what the two actions must
 * do: "stopping leaves what has landed as a part-downloaded pin, which plays, while removing deletes
 * the bytes and reports what it freed". So these tests are written in pairs - what the stop leaves
 * and what the removal takes - because each is only meaningful against the other.
 *
 * ## The one test that is really about the start-up sweep
 *
 * `a stopped download is not a row the start-up sweep re-enqueues` is the test this whole task turns
 * on, and it asserts the *contrast*: the same [DownloadResumeCoordinator] over the same row
 * re-enqueues the download before the stop and does not after it. Cancelling the job alone would
 * leave the row at `downloading`, which is in [DownloadStateDb.RESUMABLE_DB_VALUES], so the next cold
 * start would silently restart the download the user had just stopped - the un-exitable download
 * REQUIREMENTS.md records, re-entered through the control that exists to escape it.
 *
 * [NeedlerDatabase] is a mock here and nothing else is. Only the removal path touches it, and only
 * for the one transaction Room cannot run off a device; everything the stop does is the real code
 * over the real fakes, because the question is what it writes to the pin row and what it leaves on
 * the disk.
 */
public class DownloadStopTest {

    private val pinDao = FakePinDao()
    private val audioCacheDao = FakeAudioCacheDao()
    private val albumDao = FakeAlbumDao()
    private val trackDao = FakeTrackDao()
    private val scheduler = RecordingWorkScheduler()
    private val database: NeedlerDatabase = mockk(relaxed = true)

    /** Paths the repository asked to unlink. Empty is the whole point of a stop. */
    private val deleted: MutableList<String> = ArrayList()

    private val deviceFreeSpace = DeviceFreeSpace.of(freeBytes = 100_000_000_000L, floorBytes = 1_000L)

    private val repository = DefaultPinRepository(
        database = database,
        pinDao = pinDao,
        audioCacheDao = audioCacheDao,
        albumDao = albumDao,
        trackDao = trackDao,
        cacheIndex = CacheIndex(audioCacheDao = audioCacheDao, deviceFreeSpace = deviceFreeSpace),
        deviceFreeSpace = deviceFreeSpace,
        settingsStore = NeedlerSettingsStore(NoDiskPreferences()),
        artworkCacheSize = ArtworkCacheSize.None,
        deleteFile = { path ->
            deleted.add(path)
            true
        },
        nowMillis = { NOW },
        workScheduler = scheduler,
    )

    private val sweep = DownloadResumeCoordinator(
        pinDao = pinDao,
        scheduler = Provider { scheduler },
    )

    /**
     * An album mid-download: [landed] of [total] tracks written, the next one part-written.
     *
     * The part-written row matters. `complete = false` is what the store leaves while a track is in
     * flight, and the count a stop reports has to come from the index rather than from the pin row's
     * own progress figure - they differ by exactly this track.
     */
    private suspend fun downloading(landed: Int = 4, total: Int = 12) {
        albumDao.upsert(albumRow(state = AlbumStateDb.PINNED))
        repeat(total) { index ->
            trackDao.upsert(trackRow(track = index + 1, fileId = "880" + index))
        }
        pinDao.upsert(
            pinRow(
                state = DownloadStateDb.DOWNLOADING,
                tracksComplete = landed + 1,
                tracksTotal = total,
            ),
        )
        repeat(landed) { index ->
            audioCacheDao.upsert(
                cacheRow(
                    track = index + 1,
                    path = "/audio/" + RG + "/" + (index + 1) + ".flac",
                    sizeBytes = TRACK_BYTES,
                    complete = true,
                    pinned = true,
                ),
            )
        }
        audioCacheDao.upsert(
            cacheRow(
                track = landed + 1,
                path = "/audio/" + RG + "/" + (landed + 1) + ".flac.part",
                sizeBytes = 1_000L,
                complete = false,
                pinned = true,
            ),
        )
    }

    /**
     * What `NeedlerDatabase.removeDownloadedAlbum` does, without the transaction Room needs a device
     * for: read the rows before they go, drop them, drop the pin.
     */
    private fun stubRemoval() {
        coEvery { database.removeDownloadedAlbum(any()) } coAnswers {
            val mbid: String = firstArg()
            val removed: RemovedAudio = RemovedAudio.of(audioCacheDao.getAlbumRowsForRemoval(mbid))
            audioCacheDao.deleteAlbum(mbid)
            pinDao.delete(mbid)
            removed
        }
    }

    private fun pin(): PinEntity = requireNotNull(pinDao.rows[RG])

    private fun completeRows(): List<AudioCacheEntity> =
        audioCacheDao.rows.values.filter { it.releaseGroupMbid == RG && it.complete }

    // ------------------------------------------------------- what the stop leaves behind

    @Test
    public fun `stopping an in-flight download leaves every track that landed playable`(): Unit =
        runTest {
            downloading(landed = 4, total = 12)

            val outcome: Outcome<StoppedDownload> = repository.stopPinnedDownload(ALBUM)

            assertTrue(outcome is Outcome.Success)
            val stopped: StoppedDownload = (outcome as Outcome.Success).value
            assertEquals("counted from the index, not from the row's progress", 4, stopped.tracksOnDevice)
            assertEquals(12, stopped.tracksTotal)
            assertTrue(stopped.keptAnything)
            // The whole claim REQUIREMENTS.md makes for stopping: the bytes stay, and they stay in
            // the tier nothing evicts.
            assertEquals(4, completeRows().size)
            assertTrue("a stopped download is still a pin", completeRows().all { it.pinned })
            assertEquals("stopping unlinks nothing", emptyList<String>(), deleted)
            assertTrue("the album is still pinned", pinDao.rows.containsKey(RG))
        }

    @Test
    public fun `stopping cancels the job rather than waiting for it to notice`(): Unit = runTest {
        downloading()

        repository.stopPinnedDownload(ALBUM)

        assertEquals(listOf(RG), scheduler.cancellations)
        assertEquals("nothing is re-enqueued by a stop", emptyList<String>(), scheduler.downloads)
    }

    @Test
    public fun `the row rests at partial, with no error on it`(): Unit = runTest {
        downloading(landed = 4, total = 12)

        repository.stopPinnedDownload(ALBUM)

        assertEquals(DownloadStateDb.PARTIAL, pin().downloadState)
        assertEquals(4, pin().tracksComplete)
        assertEquals(12, pin().tracksTotal)
        // A stop is not a failure. A reason left on the row would be drawn as one.
        assertEquals(null, pin().error)
    }

    @Test
    public fun `a stopped download is no longer in flight, so Stop stops being offered`(): Unit =
        runTest {
            downloading()

            repository.stopPinnedDownload(ALBUM)

            val state: OfflineDownloadState = EntityMappers.offlineDownloadState(pin())
            assertTrue(state is OfflineDownloadState.Partial)
            // `AlbumState.Pinned.offeredActions` offers CANCEL on exactly this predicate, so the
            // control withdraws itself the moment the row changes - there is no second fact to clear.
            assertFalse("a stopped download must not read as progress", state.isInFlight)
        }

    // -------------------------------------------------- the start-up sweep, which is the point

    @Test
    public fun `a stopped download is not a row the start-up sweep re-enqueues`(): Unit = runTest {
        downloading()

        // First, with teeth: the sweep does re-enqueue this row while it says `downloading`, which
        // is what would silently undo a stop that only cancelled the job.
        assertEquals(listOf(RG), sweep.resumeInterruptedDownloads())
        scheduler.downloads.clear()

        repository.stopPinnedDownload(ALBUM)

        assertEquals(
            "partial is excluded from RESUMABLE_DB_VALUES, and that is what makes a stop stick",
            emptyList<String>(),
            sweep.resumeInterruptedDownloads(),
        )
        assertEquals(emptyList<String>(), scheduler.downloads)
        assertTrue(pinDao.getPinsInState(DownloadStateDb.RESUMABLE_DB_VALUES).isEmpty())
    }

    @Test
    public fun `a stop with nothing landed still sticks, and still keeps the pin`(): Unit = runTest {
        albumDao.upsert(albumRow(state = AlbumStateDb.PINNED))
        pinDao.upsert(pinRow(state = DownloadStateDb.QUEUED, tracksComplete = 0, tracksTotal = 12))

        val outcome: Outcome<StoppedDownload> = repository.stopPinnedDownload(ALBUM)

        assertEquals(0, (outcome as Outcome.Success).value.tracksOnDevice)
        assertFalse(outcome.value.keptAnything)
        // `queued` is in the resumable set and `partial` is not, so this is the case where the choice
        // of state does all the work: nothing is on the device, and nothing is coming either.
        assertEquals(DownloadStateDb.PARTIAL, pin().downloadState)
        assertEquals(emptyList<String>(), sweep.resumeInterruptedDownloads())
        assertTrue(pinDao.rows.containsKey(RG))
    }

    @Test
    public fun `stopping an album that is not pinned fails rather than reporting a stop`(): Unit =
        runTest {
            albumDao.upsert(albumRow())

            val outcome: Outcome<StoppedDownload> = repository.stopPinnedDownload(ALBUM)

            assertTrue(outcome is Outcome.Failure)
            assertTrue((outcome as Outcome.Failure).error is NeedlerError.NotFound)
            // Reporting a successful stop of nothing would tell the screen a tap worked on a row
            // that has gone.
            assertEquals(emptyList<String>(), scheduler.cancellations)
        }

    // ---------------------------------------------------- and the other half of the pair

    @Test
    public fun `removing still deletes the bytes and still reports what it freed`(): Unit = runTest {
        downloading(landed = 4, total = 12)
        stubRemoval()

        val outcome: Outcome<RemovedDownload> = repository.unpinAlbum(ALBUM)

        val removed: RemovedDownload = (outcome as Outcome.Success).value
        // Four complete tracks and the part-written fifth: every row of the album goes, whichever
        // tier it was in, because to the user an album is on the device or it is not.
        assertEquals(5, removed.removedTracks)
        assertEquals(4 * TRACK_BYTES + 1_000L, removed.freedBytes)
        assertEquals(5, deleted.size)
        assertTrue(audioCacheDao.rows.isEmpty())
        assertFalse("the pin goes with the bytes", pinDao.rows.containsKey(RG))
        assertEquals(AlbumStateDb.OWNED, requireNotNull(albumDao.rows[RG]).state)
    }

    @Test
    public fun `a stop followed by a remove frees exactly what the stop kept`(): Unit = runTest {
        downloading(landed = 4, total = 12)
        stubRemoval()

        val stopped: StoppedDownload = (repository.stopPinnedDownload(ALBUM) as Outcome.Success).value
        val removed: RemovedDownload = (repository.unpinAlbum(ALBUM) as Outcome.Success).value

        // The sequence a user actually performs: stop the download, decide later that the part of
        // the record on the device is not worth the room. The second action has to free what the
        // first one kept, or the Storage figure is a fiction.
        assertEquals(4, stopped.tracksOnDevice)
        assertEquals(4 * TRACK_BYTES + 1_000L, removed.freedBytes)
        assertTrue(deleted.isNotEmpty())
    }

    /**
     * Preferences with no disk under them.
     *
     * Nested rather than a file-level class, because `PlaybackSettingsRepositoryTest` already has one
     * of these in this package and two private top-level classes of the same name collide on the JVM.
     */
    private class NoDiskPreferences : DataStore<Preferences> {

        private val state: MutableStateFlow<Preferences> = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> get() = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val updated: Preferences = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private companion object {
        private val ALBUM = ReleaseGroupMbid(RG)
        private const val TRACK_BYTES: Long = 10_485_760L
        private const val NOW: Long = 1_700_000_000_000L
    }
}

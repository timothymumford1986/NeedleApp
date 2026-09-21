package app.needler.core.data.background

import app.needler.core.data.fake.FakeAlbumDao
import app.needler.core.data.fake.FakeBackgroundStateStore
import app.needler.core.data.fake.FakeNeedlerNotifier
import app.needler.core.data.fake.FakeV1Api
import app.needler.core.data.fake.RG
import app.needler.core.data.fake.RecordingWorkScheduler
import app.needler.core.data.fake.albumRow
import app.needler.core.data.settings.NeedlerSettings
import app.needler.core.data.settings.NotificationSettings
import app.needler.core.data.settings.StorageSettings
import app.needler.core.domain.model.AlbumSyncReport
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullActivitySummary
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.model.PullStatus
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.SyncPhase
import app.needler.core.domain.model.SyncReport
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SyncRepository
import app.needler.core.network.v1.dto.DownloadActivitySummaryDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One background poll, end to end over fakes.
 *
 * The two behaviours worth this much wiring are the ones that only show up in the whole: that an
 * unchanged `revision` really does stop the run before the task-list refresh, and that a refused
 * notification permission changes nothing except whether a notification appears. The second is the
 * requirement REQUIREMENTS.md states as "the Pulls tab badge is the reliable channel" - which is
 * only true if the poll that feeds it keeps running when notifications cannot.
 */
public class PullPollerTest {

    private val pullRepository: PullRepository = mockk(relaxed = true)
    private val syncRepository: SyncRepository = mockk(relaxed = true)
    private val pinRepository: PinRepository = mockk(relaxed = true)
    private val albumDao = FakeAlbumDao()
    private val notifier = FakeNeedlerNotifier()
    private val backgroundState = FakeBackgroundStateStore()
    private val scheduler = RecordingWorkScheduler()
    private val v1 = FakeV1Api()

    private var settings = NeedlerSettings()

    @Before
    public fun setUp() {
        every { pullRepository.observePulls(any<PullBucket>()) } returns flowOf(emptyList())
        every { pullRepository.observePulls() } returns flowOf(emptyList())
        coEvery { pullRepository.refreshPulls() } returns Outcome.Ok
        coEvery { syncRepository.syncIfStale(any()) } returns
            Outcome.Success(SyncReport(phase = SyncPhase.IDLE, libraryUnchanged = true))
        coEvery { syncRepository.syncAlbum(any()) } returns
            Outcome.Success(AlbumSyncReport(ReleaseGroupMbid(RG), trackCount = 0))
        coEvery { pinRepository.pinAlbum(any(), any()) } returns Outcome.Ok
        v1.activitySummaryResponse = { DownloadActivitySummaryDto() }
    }

    private fun poller() = PullPoller(
        pullRepository = pullRepository,
        syncRepository = syncRepository,
        pinRepository = pinRepository,
        settings = { settings },
        backgroundState = backgroundState,
        notifier = notifier,
        albumDao = albumDao,
        v1 = v1,
        scheduler = scheduler,
    )

    private fun summary(
        revision: Long,
        landed: List<String> = emptyList(),
        failed: Int = 0,
        active: Int = 0,
    ) = PullActivitySummary(
        revision = revision,
        activeCount = active,
        heldCount = 0,
        failedCount = failed,
        landedReleaseGroupMbids = landed.map(::ReleaseGroupMbid),
    )

    private fun givenSummary(value: PullActivitySummary) {
        coEvery { pullRepository.refreshActivitySummary() } returns Outcome.Success(value)
    }

    private fun activePull() = Pull(
        releaseGroupMbid = ReleaseGroupMbid(RG),
        albumTitle = "Spiderland",
        artistName = "Slint",
        status = PullStatus.DOWNLOADING,
    )

    // ------------------------------------------------------------------ the cheap path

    @Test
    public fun `an unchanged revision stops before the task list is refreshed`(): Unit = runTest {
        backgroundState.memory = PollMemory(lastRevision = 9L, announcedLandedMbids = setOf(RG))
        givenSummary(summary(revision = 9L, landed = listOf(RG), failed = 4))

        val result = poller().poll(PollCadence.ACTIVE_PULLS)

        assertTrue(result.revisionUnchanged)
        coVerify(exactly = 0) { pullRepository.refreshPulls() }
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    public fun `nothing active and every notification off skips the network entirely`(): Unit = runTest {
        settings = NeedlerSettings(
            notifications = NotificationSettings(
                pullFinished = false,
                pullFailed = false,
                newReleaseFromFollowedArtist = false,
            ),
        )

        val result = poller().poll(PollCadence.IDLE)

        assertFalse(result.polled)
        coVerify(exactly = 0) { pullRepository.refreshActivitySummary() }
        assertTrue("the six-hourly wake still earns its keep by syncing", result.syncRan)
    }

    @Test
    public fun `an active pull is polled even with every notification off`(): Unit = runTest {
        settings = NeedlerSettings(
            notifications = NotificationSettings(
                pullFinished = false,
                pullFailed = false,
                newReleaseFromFollowedArtist = false,
            ),
        )
        every { pullRepository.observePulls(any<PullBucket>()) } returns flowOf(listOf(activePull()))
        givenSummary(summary(revision = 2L, active = 1))

        val result = poller().poll(PollCadence.ACTIVE_PULLS)

        assertTrue(result.polled)
        coVerify { pullRepository.refreshPulls() }
    }

    // --------------------------------------------------------------- the moved-on path

    @Test
    public fun `a landed album produces a notification naming it`(): Unit = runTest {
        albumDao.upsert(albumRow(title = "Spiderland", artist = "Slint"))
        backgroundState.memory = PollMemory(lastRevision = 1L)
        givenSummary(summary(revision = 2L, landed = listOf(RG)))

        poller().poll(PollCadence.ACTIVE_PULLS)

        val posted = notifier.posted.single() as NeedlerNotification.PullFinished
        assertEquals("Spiderland", posted.albumTitle)
        assertEquals(NotificationDestination.Album(RG), posted.destination)
        assertEquals(setOf(RG), backgroundState.memory.announcedLandedMbids)
    }

    @Test
    public fun `a refused permission suppresses the notification and nothing else`(): Unit = runTest {
        notifier.permission = NotificationPermissionState.DENIED
        albumDao.upsert(albumRow())
        backgroundState.memory = PollMemory(lastRevision = 1L)
        givenSummary(summary(revision = 2L, landed = listOf(RG)))

        val result = poller().poll(PollCadence.ACTIVE_PULLS)

        assertTrue("nothing can be shown", notifier.posted.isEmpty())
        // ... but the mirrored task list, which is what the Pulls badge counts, is still refreshed.
        coVerify { pullRepository.refreshPulls() }
        assertFalse(result.revisionUnchanged)
        assertEquals(
            "and the revision is still recorded, so the next poll is still cheap",
            2L,
            backgroundState.memory.lastRevision,
        )
    }

    @Test
    public fun `keep pulled albums on device syncs the album before pinning it`(): Unit = runTest {
        settings = NeedlerSettings(storage = StorageSettings(keepPulledAlbumsOnDevice = true))
        backgroundState.memory = PollMemory(lastRevision = 1L)
        givenSummary(summary(revision = 2L, landed = listOf(RG)))

        poller().poll(PollCadence.ACTIVE_PULLS)

        // A pull that has only just landed has no track rows yet, and a downloader with no tracks
        // to fetch would mark an empty album complete.
        coVerify { syncRepository.syncAlbum(ReleaseGroupMbid(RG)) }
        coVerify { pinRepository.pinAlbum(ReleaseGroupMbid(RG), any()) }
    }

    @Test
    public fun `the setting being off leaves landed albums alone`(): Unit = runTest {
        backgroundState.memory = PollMemory(lastRevision = 1L)
        givenSummary(summary(revision = 2L, landed = listOf(RG)))

        poller().poll(PollCadence.ACTIVE_PULLS)

        coVerify(exactly = 0) { pinRepository.pinAlbum(any(), any()) }
    }

    // ------------------------------------------------------------------- the cadence

    @Test
    public fun `the cadence follows what the server just said`(): Unit = runTest {
        givenSummary(summary(revision = 2L, active = 3))
        poller().poll(PollCadence.IDLE)
        assertEquals(PollCadence.ACTIVE_PULLS, scheduler.cadences.last())

        backgroundState.memory = PollMemory(lastRevision = 2L)
        givenSummary(summary(revision = 3L, active = 0))
        poller().poll(PollCadence.ACTIVE_PULLS)
        assertEquals(PollCadence.IDLE, scheduler.cadences.last())
    }

    @Test
    public fun `only the six-hourly cadence drives the sync`(): Unit = runTest {
        givenSummary(summary(revision = 2L))

        assertTrue(poller().poll(PollCadence.IDLE).syncRan)

        backgroundState.memory = PollMemory(lastRevision = 2L)
        givenSummary(summary(revision = 3L))
        assertFalse(poller().poll(PollCadence.ACTIVE_PULLS).syncRan)
    }

    // -------------------------------------------------------------------- new releases

    @Test
    public fun `the unseen count is only asked for when that notification is on`(): Unit = runTest {
        settings = NeedlerSettings(
            notifications = NotificationSettings(newReleaseFromFollowedArtist = false),
        )
        givenSummary(summary(revision = 2L))

        poller().poll(PollCadence.ACTIVE_PULLS)

        assertFalse(
            "a second request on every poll, for a notification the user turned off",
            v1.calls.contains("unseenNewReleaseCount"),
        )
    }

    @Test
    public fun `a transport failure is reported as retryable rather than swallowed`(): Unit = runTest {
        coEvery { pullRepository.refreshActivitySummary() } returns
            Outcome.Failure(NeedlerError.Offline(OfflineCause.NO_NETWORK))

        val result = poller().poll(PollCadence.ACTIVE_PULLS)

        assertTrue(result.failed)
        assertTrue(result.retryable)
        assertTrue(notifier.posted.isEmpty())
    }
}

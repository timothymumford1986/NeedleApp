package app.needler.core.data.background

import app.needler.core.data.local.dao.AlbumDao
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.mapper.networkCall
import app.needler.core.data.settings.NeedlerSettings
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PinSource
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullActivitySummary
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SyncRepository
import app.needler.core.network.v1.V1Api
import app.needler.core.network.v1.dto.NewReleaseDto
import app.needler.core.network.v1.dto.NewReleasesDto
import app.needler.core.network.v1.dto.UnseenCountDto
import kotlinx.coroutines.flow.first

/**
 * One background poll: ask the server what moved, and do only what moved requires.
 *
 * DroppedNeedle has no push mechanism, so everything the app can tell a user about comes from this.
 * It is also the thing most likely to eat a battery, which is why the cheap paths are the ones the
 * code takes first:
 *
 *  1. **Do not poll at all** when there are no active pulls and every notification is switched off.
 *     There is nothing a poll could report; the six-hourly wake exists for the metadata sync and
 *     does that alone.
 *  2. **Stop at the revision.** `GET /api/v1/downloads/activity-summary` returns a `revision` that
 *     the server advances only on real state changes, never on progress-only writes. An unchanged
 *     revision ends the run: no task-list page walk, no database writes, no notification work. That
 *     one comparison is what makes a fifteen-minute poller acceptable.
 *  3. **One extra request, only when asked for.** The unseen new-release count is a second endpoint
 *     and is only called when that notification is switched on.
 *
 * The decisions are in [PollDigest] and [PollSchedule], which are pure; this class is the wiring
 * that carries them out.
 */
public class PullPoller(
    private val pullRepository: PullRepository,
    private val syncRepository: SyncRepository,
    private val pinRepository: PinRepository,
    /**
     * One snapshot of the settings per run.
     *
     * A supplier rather than the store itself, so a poll can be tested without a `DataStore` and
     * therefore without a device. It is read once at the top of a run: a user flipping a switch
     * mid-poll should change the next poll, not half of this one.
     */
    private val settings: suspend () -> NeedlerSettings,
    private val backgroundState: BackgroundStateStore,
    private val notifier: NeedlerNotifier,
    private val albumDao: AlbumDao,
    private val v1: V1Api,
    private val scheduler: BackgroundWorkScheduler,
) {

    /**
     * Runs one poll at [cadence].
     *
     * Returns what happened so the caller can decide whether to ask for another go: a transport
     * failure is a retry, an unchanged revision is a success that did nothing.
     */
    public suspend fun poll(cadence: PollCadence): PollRunResult {
        val settings: NeedlerSettings = settings()
        val activeBefore: List<Pull> = pullRepository.observePulls(PullBucket.ACTIVE).first()
        val hasActivePulls: Boolean = activeBefore.isNotEmpty()

        if (!PollSchedule.shouldPollActivity(hasActivePulls, settings.notifications)) {
            // The battery rule, stated plainly: nothing in flight and nothing switched on, so the
            // only reason this process woke up is the mirror.
            val synced: Boolean = syncIfDue(cadence)
            return PollRunResult(
                polled = false,
                revisionUnchanged = true,
                activeCount = 0,
                notificationsPosted = 0,
                syncRan = synced,
            )
        }

        val summaryCall: Outcome<PullActivitySummary> = pullRepository.refreshActivitySummary()
        val summary: PullActivitySummary = when (summaryCall) {
            is Outcome.Failure -> return PollRunResult(
                polled = true,
                revisionUnchanged = false,
                activeCount = activeBefore.size,
                notificationsPosted = 0,
                syncRan = false,
                failed = true,
                retryable = summaryCall.error.isRetryable,
            )

            is Outcome.Success -> summaryCall.value
        }

        var memory: PollMemory = backgroundState.pollMemory()

        val headlines: Map<String, AlbumEntity> = if (summary.landedReleaseGroupMbids.isEmpty()) {
            emptyMap()
        } else {
            albumDao.getAlbums(summary.landedReleaseGroupMbids.map { it.value })
                .associateBy { it.releaseGroupMbid }
        }

        val digest: PollDigestResult = PollDigest.digest(
            previous = memory,
            summary = summary,
            settings = settings.notifications,
            albumLookup = { mbid ->
                headlines[mbid]?.let { AlbumHeadline(it.title, it.artistName) }
            },
        )
        memory = digest.memory

        if (!digest.revisionUnchanged) {
            // The revision moved, so the mirrored task list is out of date - and the Pulls badge is
            // read from it. The badge is the reliable channel, so it is refreshed whether or not a
            // single notification is going to be posted.
            pullRepository.refreshPulls()
            digest.notifications.forEach(notifier::post)
            if (settings.storage.keepPulledAlbumsOnDevice) {
                keepLandedAlbumsOnDevice(digest.landedMbids)
            }
        }

        memory = pollNewReleases(memory, settings)
        backgroundState.savePollMemory(memory)

        val syncRan: Boolean = syncIfDue(cadence)

        // The cadence is re-evaluated from what the server just said rather than from what was
        // scheduled: an album that finished is the moment to drop from fifteen minutes back to six
        // hours, and one that started is the moment to do the reverse.
        scheduler.schedulePeriodicPoll(PollSchedule.cadenceFor(summary.activeCount > 0))

        return PollRunResult(
            polled = true,
            revisionUnchanged = digest.revisionUnchanged,
            activeCount = summary.activeCount,
            notificationsPosted = digest.notifications.size,
            syncRan = syncRan,
        )
    }

    /**
     * The new-release half.
     *
     * Two endpoints exist for this - a count and a list - and only the count is cheap. The list is
     * read only when the count is exactly one, because that is the only case where there is a
     * single artist for the notification to open; with several unseen the notification says how
     * many and lands on the library.
     */
    private suspend fun pollNewReleases(memory: PollMemory, settings: NeedlerSettings): PollMemory {
        if (!settings.notifications.newReleaseFromFollowedArtist) return memory

        val countCall: Outcome<UnseenCountDto> = networkCall { v1.unseenNewReleaseCount() }
        val count: Int = when (countCall) {
            is Outcome.Failure -> return memory
            is Outcome.Success -> countCall.value.count
        }

        var artistMbid: String? = null
        var artistName: String? = null
        var albumTitle: String? = null
        if (count == 1 && count > memory.lastUnseenNewReleaseCount) {
            val listCall: Outcome<NewReleasesDto> = networkCall { v1.newReleases(limit = 1) }
            if (listCall is Outcome.Success) {
                val item: NewReleaseDto? = listCall.value.items.firstOrNull()
                artistMbid = item?.artistMbid?.takeIf { it.isNotBlank() }
                artistName = item?.artistName?.takeIf { it.isNotBlank() }
                albumTitle = item?.title?.takeIf { it.isNotBlank() }
            }
        }

        val (notification, updated) = PollDigest.newReleaseNotification(
            previous = memory,
            unseenCount = count,
            settings = settings.notifications,
            artistMbid = artistMbid,
            artistName = artistName,
            albumTitle = albumTitle,
        )
        notification?.let(notifier::post)
        return updated
    }

    /**
     * "Keep pulled albums on device": auto-pin whatever this device just pulled.
     *
     * The album is synced first, deliberately. A pull that has only just landed has no `track` rows
     * in the mirror yet, and a downloader with no tracks to fetch would record an empty album as
     * complete - which is the same silent lie as a truncated file, one level up.
     */
    private suspend fun keepLandedAlbumsOnDevice(landedMbids: List<String>) {
        landedMbids.forEach { raw ->
            val mbid = ReleaseGroupMbid(raw)
            syncRepository.syncAlbum(mbid)
            // A failure here is ordinary: the album may not be in the library yet, in which case
            // the next poll picks it up. Pinning is idempotent, so a repeat costs nothing.
            pinRepository.pinAlbum(mbid, PinSource.AUTO_PULLED)
        }
    }

    /**
     * The metadata sync, which the six-hourly wake exists to drive.
     *
     * The fifteen-minute cadence deliberately does not sync: four `getIndexes` calls an hour for as
     * long as a pull is in flight would be a poor trade for a mirror that is fifteen minutes fresher
     * than it needs to be. [SyncRepository.syncIfStale] then makes the call a no-op when something
     * else - the Library screen, a foreground trigger - has already synced recently.
     */
    private suspend fun syncIfDue(cadence: PollCadence): Boolean {
        if (!PollSchedule.shouldSync(cadence)) return false
        syncRepository.syncIfStale()
        return true
    }
}

/** What one poll did, so the worker can decide between success, retry and doing nothing. */
public data class PollRunResult(
    /** False when the battery rule skipped the network entirely. */
    val polled: Boolean,
    /** True when the server's `revision` had not moved, so nothing downstream ran. */
    val revisionUnchanged: Boolean,
    val activeCount: Int,
    val notificationsPosted: Int,
    val syncRan: Boolean,
    val failed: Boolean = false,
    val retryable: Boolean = false,
)

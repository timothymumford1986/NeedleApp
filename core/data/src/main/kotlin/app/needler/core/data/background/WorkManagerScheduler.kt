package app.needler.core.data.background

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.needler.core.data.settings.NeedlerSettingsStore
import app.needler.core.data.settings.StorageSettings
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [BackgroundWorkScheduler] over `WorkManager`.
 *
 * Every job in the app is enqueued from here, with a unique name, so the failure modes that come
 * from scheduling in several places cannot happen: two pollers running at different periods, three
 * copies of the same album downloading at once, or a sync racing another sync.
 *
 * Constraints are the other reason this is one file. "Download to device on Wi-Fi only" is a
 * `WorkManager` constraint rather than a check inside the job, because a job that starts and then
 * discovers it is on mobile data has already opened a socket - and because `WorkManager` will then
 * start it again by itself the moment Wi-Fi comes back, which is exactly the behaviour the setting
 * describes. Data Saver is honoured by the same mechanism: a constrained job does not run while the
 * system says background data is restricted.
 */
public class WorkManagerScheduler(
    private val workManager: WorkManager,
    private val settingsStore: NeedlerSettingsStore,
) : BackgroundWorkScheduler {

    override suspend fun scheduleAlbumDownload(releaseGroupMbid: String) {
        if (releaseGroupMbid.isBlank()) return
        val storage: StorageSettings = settingsStore.storage.first()

        workManager.enqueueUniqueWork(
            AlbumDownloadWorker.workName(releaseGroupMbid),
            // KEEP, not REPLACE: a second tap on an album already downloading must join the job in
            // flight, not restart it and lose the bytes it has fetched.
            ExistingWorkPolicy.KEEP,
            albumDownloadRequest(releaseGroupMbid, storage.downloadToDeviceOnWifiOnly),
        )
    }

    override suspend fun cancelAlbumDownload(releaseGroupMbid: String) {
        if (releaseGroupMbid.isBlank()) return
        workManager.cancelUniqueWork(AlbumDownloadWorker.workName(releaseGroupMbid))
    }

    override suspend fun schedulePollAfterPull() {
        workManager.enqueueUniqueWork(
            ExpeditedPollWorker.WORK_NAME,
            // REPLACE: a newly placed pull restarts the ladder, because the thing the user is now
            // waiting for is newer than whatever the running check was watching.
            ExistingWorkPolicy.REPLACE,
            pollAfterPullRequest(),
        )
    }

    override suspend fun schedulePeriodicPoll(cadence: PollCadence) {
        workManager.enqueueUniquePeriodicWork(
            PullPollWorker.WORK_NAME,
            // UPDATE rather than REPLACE, so changing the cadence does not reset the next run to a
            // full period away: a poller that restarted its clock every time an album finished
            // would poll less often than either cadence asks for.
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicPollRequest(cadence),
        )
    }

    override suspend fun scheduleSync(trigger: SyncTrigger) {
        if (trigger == SyncTrigger.NONE) return
        workManager.enqueueUniqueWork(
            LibrarySyncWorker.WORK_NAME,
            // A full sync supersedes a queued delta; a delta queued behind anything else is
            // redundant, because whatever is running will leave the mirror at least as fresh.
            if (trigger.isFull) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            syncRequest(trigger),
        )
    }
}

/**
 * The four `WorkRequest`s, built away from the `WorkManager` that enqueues them.
 *
 * ## Why these are not inline in [WorkManagerScheduler]
 *
 * Because `WorkRequest.Builder.build()` is a **validator**, and nothing in the suite was running it.
 * `schedulePollAfterPull` set an initial delay *and* `setExpedited`, which `build()` rejects with
 * `IllegalArgumentException: Expedited jobs cannot be delayed` - so placing a pull from the UI
 * crashed the app, every time, on an OPPO Find X9 on 2026-10-08.
 *
 * 2422 tests passed through it. Every one reached [BackgroundWorkScheduler] through
 * `RecordingWorkScheduler`, whose `schedulePollAfterPull` is `expeditedPolls += 1`, so the only
 * class that touches `WorkManager`'s API had no test at all. Building a request needs no `Context`
 * and no `WorkManager`; enqueueing one needs both. Splitting them at that line is what lets
 * `WorkManagerSchedulerTest` assert the half that was wrong, as a plain JVM test.
 */
internal fun albumDownloadRequest(
    releaseGroupMbid: String,
    wifiOnly: Boolean,
): OneTimeWorkRequest = OneTimeWorkRequestBuilder<AlbumDownloadWorker>()
    .setInputData(workDataOf(AlbumDownloadWorker.KEY_RELEASE_GROUP_MBID to releaseGroupMbid))
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(
                // The setting is about mobile data, and UNMETERED is how the platform says that.
                // Note it is deliberately not CONNECTED-plus-a-check: the constraint is also what
                // re-runs the job when Wi-Fi returns.
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build(),
    )
    // Expedited and *not* delayed, which is the combination `build()` allows. An album download is
    // long, so being refused a quota slot must postpone it rather than fail it.
    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    .setBackoffCriteria(
        BackoffPolicy.EXPONENTIAL,
        DOWNLOAD_BACKOFF.inWholeMilliseconds,
        TimeUnit.MILLISECONDS,
    )
    .addTag(TAG_DOWNLOAD)
    .build()

internal fun pollAfterPullRequest(): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<ExpeditedPollWorker>()
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        // Delayed, and therefore **not** expedited. `WorkRequest.Builder.build()` throws
        // `IllegalArgumentException: Expedited jobs cannot be delayed` when both are set, and this
        // request set both: it crashed the app on every pull placed from the UI, in
        // `DefaultPullRepository` immediately after the request reached the server. Observed on an
        // OPPO Find X9 on 2026-10-08, twice in thirty seconds.
        //
        // REQUIREMENTS.md's table reads "1 min, then backing off to 15 min | Expedited
        // `WorkManager`", which is two things `WorkManager` will not do at once. The delay is the
        // half that is the behaviour: `PollSchedule.nextDelay` returns `EXPEDITED_FIRST_DELAY` for
        // attempt zero, and polling the instant the server was asked would return the same state it
        // just reported. "Expedited" there names the urgency of the ladder, not the API, so the API
        // call is the half that goes.
        .setInitialDelay(
            PollSchedule.EXPEDITED_FIRST_DELAY.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
        )
        // The back-off ladder REQUIREMENTS.md specifies: one minute, doubling, and the worker stops
        // asking once it reaches fifteen - which is where the periodic poller takes over.
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            PollSchedule.EXPEDITED_FIRST_DELAY.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
        )
        .addTag(TAG_POLL)
        .build()

internal fun periodicPollRequest(cadence: PollCadence): PeriodicWorkRequest =
    PeriodicWorkRequestBuilder<PullPollWorker>(
        cadence.interval.inWholeMilliseconds,
        TimeUnit.MILLISECONDS,
    )
        .setInputData(workDataOf(PullPollWorker.KEY_CADENCE to cadence.name))
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            POLL_BACKOFF.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
        )
        .addTag(TAG_POLL)
        .build()

internal fun syncRequest(trigger: SyncTrigger): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<LibrarySyncWorker>()
        .setInputData(workDataOf(LibrarySyncWorker.KEY_TRIGGER to trigger.name))
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            SYNC_BACKOFF.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
        )
        .addTag(TAG_SYNC)
        .build()

internal const val TAG_DOWNLOAD: String = "needler-download"
internal const val TAG_POLL: String = "needler-poll"
internal const val TAG_SYNC: String = "needler-sync"

private val DOWNLOAD_BACKOFF = 30.seconds
private val POLL_BACKOFF = 5.minutes
private val SYNC_BACKOFF = 1.minutes

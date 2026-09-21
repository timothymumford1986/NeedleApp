package app.needler.core.data.background

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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

        val request = OneTimeWorkRequestBuilder<AlbumDownloadWorker>()
            .setInputData(
                workDataOf(AlbumDownloadWorker.KEY_RELEASE_GROUP_MBID to releaseGroupMbid),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        // The setting is about mobile data, and UNMETERED is how the platform says
                        // that. Note it is deliberately not CONNECTED-plus-a-check: the constraint
                        // is also what re-runs the job when Wi-Fi returns.
                        if (storage.downloadToDeviceOnWifiOnly) {
                            NetworkType.UNMETERED
                        } else {
                            NetworkType.CONNECTED
                        },
                    )
                    .build(),
            )
            // An expedited job, as REQUIREMENTS.md asks, falling back to ordinary work when the
            // app's expedited quota is spent. The fallback matters: an album download is long, and
            // being refused a quota slot must postpone it rather than fail it.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                DOWNLOAD_BACKOFF.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            )
            .addTag(TAG_DOWNLOAD)
            .build()

        workManager.enqueueUniqueWork(
            AlbumDownloadWorker.workName(releaseGroupMbid),
            // KEEP, not REPLACE: a second tap on an album already downloading must join the job in
            // flight, not restart it and lose the bytes it has fetched.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override suspend fun cancelAlbumDownload(releaseGroupMbid: String) {
        if (releaseGroupMbid.isBlank()) return
        workManager.cancelUniqueWork(AlbumDownloadWorker.workName(releaseGroupMbid))
    }

    override suspend fun schedulePollAfterPull() {
        val request = OneTimeWorkRequestBuilder<ExpeditedPollWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setInitialDelay(
                PollSchedule.EXPEDITED_FIRST_DELAY.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            // The back-off ladder REQUIREMENTS.md specifies: one minute, doubling, and the worker
            // stops asking once it reaches fifteen - which is where the periodic poller takes over.
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                PollSchedule.EXPEDITED_FIRST_DELAY.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            )
            .addTag(TAG_POLL)
            .build()

        workManager.enqueueUniqueWork(
            ExpeditedPollWorker.WORK_NAME,
            // REPLACE: a newly placed pull restarts the ladder, because the thing the user is now
            // waiting for is newer than whatever the running check was watching.
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override suspend fun schedulePeriodicPoll(cadence: PollCadence) {
        val request = PeriodicWorkRequestBuilder<PullPollWorker>(
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

        workManager.enqueueUniquePeriodicWork(
            PullPollWorker.WORK_NAME,
            // UPDATE rather than REPLACE, so changing the cadence does not reset the next run to a
            // full period away: a poller that restarted its clock every time an album finished
            // would poll less often than either cadence asks for.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override suspend fun scheduleSync(trigger: SyncTrigger) {
        if (trigger == SyncTrigger.NONE) return
        val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
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

        workManager.enqueueUniqueWork(
            LibrarySyncWorker.WORK_NAME,
            // A full sync supersedes a queued delta; a delta queued behind anything else is
            // redundant, because whatever is running will leave the mirror at least as fresh.
            if (trigger.isFull) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val TAG_DOWNLOAD = "needler-download"
        const val TAG_POLL = "needler-poll"
        const val TAG_SYNC = "needler-sync"

        val DOWNLOAD_BACKOFF = 30.seconds
        val POLL_BACKOFF = 5.minutes
        val SYNC_BACKOFF = 1.minutes
    }
}

package app.needler.core.data.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The periodic poller: fifteen minutes with pulls in flight, six hours without.
 *
 * Both cadences are the same class, distinguished by an input, because the only difference between
 * them is whether the run also drives the metadata sync. One worker means one place where the
 * revision comparison, the notification rules and the battery rule live; two would mean two, and
 * the six-hourly one would be the one that quietly stopped matching.
 *
 * Fifteen minutes is `WorkManager`'s floor for periodic work. A pull that finishes a minute after a
 * poll therefore waits a quarter of an hour to surface here - which is the reason
 * [ExpeditedPollWorker] exists, and the reason the Pulls badge rather than a notification is the
 * channel the product relies on.
 */
@HiltWorker
public class PullPollWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val poller: PullPoller,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val cadence: PollCadence = PollCadence.entries
            .firstOrNull { it.name == inputData.getString(KEY_CADENCE) }
            ?: PollCadence.IDLE

        val result: PollRunResult = poller.poll(cadence)
        return when {
            !result.failed -> Result.success()
            // A poll that could not reach the server is not a failure worth surfacing: the app is
            // simply offline, which is a first-class state. Retrying with backoff is the whole of
            // the correct response.
            result.retryable -> Result.retry()
            else -> Result.success()
        }
    }

    public companion object {
        public const val KEY_CADENCE: String = "cadence"

        /** One periodic poller, re-enqueued with a new period when the cadence changes. */
        public const val WORK_NAME: String = "needler-poll"
    }
}

/**
 * The expedited check that runs just after a pull is placed.
 *
 * REQUIREMENTS.md schedules this at one minute, backing off to fifteen. It exists for one case and
 * is worth keeping to it: a user has just asked for an album, is waiting for it, and the periodic
 * poller cannot run sooner than a quarter of an hour. It re-arms itself through `Result.retry` with
 * an explicit backoff, and it stops as soon as there is nothing active left to wait for - or after
 * [PollSchedule.EXPEDITED_MAX_ATTEMPTS], because at that point the periodic poller is the right
 * owner and two pollers is one too many.
 */
@HiltWorker
public class ExpeditedPollWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val poller: PullPoller,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val result: PollRunResult = poller.poll(PollCadence.ACTIVE_PULLS)
        if (result.failed) {
            return if (result.retryable) Result.retry() else Result.success()
        }
        val stillActive: Boolean = result.activeCount > 0
        return if (PollSchedule.shouldKeepExpediting(runAttemptCount, stillActive)) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    public companion object {
        /** Unique, so placing three pulls in a row arms one check rather than three. */
        public const val WORK_NAME: String = "needler-poll-after-pull"
    }
}

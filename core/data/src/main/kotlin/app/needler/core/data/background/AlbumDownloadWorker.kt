package app.needler.core.data.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Downloads one pinned album onto the device.
 *
 * This is the job `DefaultPinRepository` deliberately does not do. Pinning records intent in a
 * transaction and returns; the per-track `Range` GETs have to outlive the screen that started them
 * and the process they started in, which is precisely what `WorkManager` is for. Without this class
 * "Pull local" was a button with nothing behind it.
 *
 * The work itself is [AlbumDownloader], which is a plain class with no Android types in it, so the
 * resume, `416` and permanent-failure behaviour are unit-tested. What is left here is the three
 * things only a `Worker` can do: map an outcome onto a [Result], publish progress, and show the
 * progress notification.
 *
 * ## Why a retry rather than a failure
 *
 * [Result.retry] is `WorkManager`'s exponential backoff, which is what a transport failure deserves
 * and what REQUIREMENTS.md asks for. [Result.failure] is reserved for the two permanent cases -
 * library download disabled by the administrator, and no room on the device - both of which are
 * recorded on the pin row with a reason first, so the album screen can say what happened instead of
 * spinning for ever.
 */
@HiltWorker
public class AlbumDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val downloader: AlbumDownloader,
    private val notifier: NeedlerNotifier,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val mbid: String = inputData.getString(KEY_RELEASE_GROUP_MBID)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()

        val headline: AlbumHeadline? = runCatching { downloader.headline(mbid) }.getOrNull()
        val title: String = headline?.title.orEmpty()
        val tag: String = progressTag(mbid)

        return try {
            val outcome: AlbumDownloadOutcome = downloader.download(mbid) { progress ->
                setProgress(
                    workDataOf(
                        KEY_RELEASE_GROUP_MBID to progress.releaseGroupMbid,
                        KEY_TRACKS_COMPLETE to progress.tracksComplete,
                        KEY_TRACKS_TOTAL to progress.tracksTotal,
                        KEY_DOWNLOADED_BYTES to progress.downloadedBytes,
                    ),
                )
                notifier.showDownloadProgress(
                    tag = tag,
                    albumTitle = title,
                    tracksComplete = progress.tracksComplete,
                    tracksTotal = progress.tracksTotal,
                )
            }
            when (outcome) {
                is AlbumDownloadOutcome.Success, AlbumDownloadOutcome.NothingToDo -> Result.success()
                is AlbumDownloadOutcome.Retry -> Result.retry()
                is AlbumDownloadOutcome.Failed -> Result.failure()
            }
        } finally {
            // The progress notification goes whatever happened. A stuck "downloading" in the shade
            // after the job has stopped is worse than no notification at all, because it is the one
            // piece of state the user cannot dismiss by opening the app.
            notifier.cancel(tag)
        }
    }

    /**
     * Only ever asked for on Android 11 and below, where `WorkManager` runs an expedited job inside
     * its own foreground service and needs a notification for it.
     *
     * On Android 12 and above expedited work is a JobScheduler expedited job and this is not called
     * at all, which is why the progress notification is also posted normally from [doWork] - the
     * requirement is a progress notification, not a foreground service, and this app has exactly
     * one of those and it belongs to the Media3 session.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        FOREGROUND_NOTIFICATION_ID,
        notifier.downloadProgress(albumTitle = "", tracksComplete = 0, tracksTotal = 0),
    )

    public companion object {

        public const val KEY_RELEASE_GROUP_MBID: String = "releaseGroupMbid"
        public const val KEY_TRACKS_COMPLETE: String = "tracksComplete"
        public const val KEY_TRACKS_TOTAL: String = "tracksTotal"
        public const val KEY_DOWNLOADED_BYTES: String = "downloadedBytes"

        /** Unique work name, one per album, so a second tap joins the job rather than doubling it. */
        public fun workName(releaseGroupMbid: String): String = "needler-download-" + releaseGroupMbid

        /** Notification tag for an album's progress, distinct from the news notifications. */
        public fun progressTag(releaseGroupMbid: String): String =
            "download-progress:" + releaseGroupMbid

        private const val FOREGROUND_NOTIFICATION_ID: Int = 42
    }
}

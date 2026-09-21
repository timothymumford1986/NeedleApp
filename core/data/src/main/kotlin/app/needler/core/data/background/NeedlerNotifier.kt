package app.needler.core.data.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.needler.core.data.R

/**
 * Posting a notification, and knowing whether one can be posted at all.
 *
 * Separate from [NotificationComposer] because these are the parts that need a `Context`: channels,
 * pending intents and the runtime permission. The wording, the channel each notification belongs to
 * and the screen each one opens are decided in pure code and tested there.
 *
 * An interface because the workers are unit-tested with no device, and because a test that wants to
 * assert "nothing was posted when the permission was refused" needs to be able to watch.
 */
public interface NeedlerNotifier {

    /**
     * Creates the notification channels.
     *
     * Mandatory from Android 8: a notification posted to a channel that does not exist is dropped
     * silently, which is the worst possible failure mode - no error, no notification, nothing to
     * debug. Creating a channel that already exists is a no-op, so this is safe to call on every
     * process start and must be called before anything posts.
     */
    public fun ensureChannels()

    /** Whether `POST_NOTIFICATIONS` has been granted, does not apply, or was refused. */
    public fun permissionState(): NotificationPermissionState

    /**
     * Posts one notification, or does nothing when the permission was refused.
     *
     * Refusal is not an error here and must not be treated as one: the caller carries on, because
     * the Pulls badge - which needs no permission - is the channel the product actually relies on.
     */
    public fun post(notification: NeedlerNotification)

    /** Removes a posted notification, e.g. once the user has seen the thing it was about. */
    public fun cancel(tag: String)

    /**
     * The progress notification a download job shows while it runs.
     *
     * Built rather than posted, because `WorkManager` needs the object itself for the
     * `ForegroundInfo` it requires from an expedited job on Android 11 and below.
     */
    public fun downloadProgress(
        albumTitle: String,
        tracksComplete: Int,
        tracksTotal: Int,
    ): Notification

    /**
     * Shows or updates the download job's progress notification.
     *
     * REQUIREMENTS.md asks for "expedited `WorkManager` jobs with a progress notification, not a
     * second foreground service", and that is exactly what this is: an ordinary low-importance
     * notification the job posts and clears itself. Nothing here starts a service - the one
     * foreground service in this app belongs to the Media3 session.
     */
    public fun showDownloadProgress(
        tag: String,
        albumTitle: String,
        tracksComplete: Int,
        tracksTotal: Int,
    )
}

/**
 * [NeedlerNotifier] over the platform notification manager.
 *
 * The tap target is built from the app's own launch intent rather than from an `Activity` class
 * reference, because this module must not know that `:app` exists, let alone what its activity is
 * called. The destination travels as two extras which `:app` decodes - see
 * [NotificationDestination].
 */
public class AndroidNeedlerNotifier(
    private val context: Context,
) : NeedlerNotifier {

    private val manager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    override fun ensureChannels() {
        val notificationManager: NotificationManager = manager ?: return
        NotificationChannelId.entries.forEach { channel ->
            val importance: Int = when (channel) {
                // The progress notification is furniture, not news: low importance so it never
                // makes a sound or pushes itself in front of anything.
                NotificationChannelId.DOWNLOADS -> NotificationManager.IMPORTANCE_LOW
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            val created = NotificationChannel(channel.id, channel.channelName, importance).apply {
                description = channel.channelDescription
                setShowBadge(channel != NotificationChannelId.DOWNLOADS)
            }
            runCatching { notificationManager.createNotificationChannel(created) }
        }
    }

    override fun permissionState(): NotificationPermissionState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Below Android 13 there is no permission to grant. That is not the same as "granted":
            // a rationale shown on such a device would refer to a dialog that cannot appear.
            return NotificationPermissionState.NOT_REQUIRED
        }
        val granted: Boolean = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return NotificationPermissionState.GRANTED
        // Whether this is "never asked" or "refused" is a question only the Activity can answer,
        // via shouldShowRequestPermissionRationale. From here the two are indistinguishable, so the
        // optimistic answer is right: the policy that acts on it also consults the persisted
        // "already asked" flag, which is what stops a second dialog.
        return NotificationPermissionState.NOT_YET_ASKED
    }

    override fun post(notification: NeedlerNotification) {
        val notificationManager: NotificationManager = manager ?: return
        if (!NotificationPermissionPolicy.canPost(permissionState())) return
        if (!notificationManager.areNotificationsEnabled()) return

        val text: NotificationText = NotificationComposer.text(notification)
        val built: Notification = Notification.Builder(context, notification.channel.id)
            .setSmallIcon(R.drawable.ic_stat_needler)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntentFor(notification))
            .build()

        runCatching { notificationManager.notify(notification.tag, ID, built) }
    }

    override fun cancel(tag: String) {
        runCatching { manager?.cancel(tag, ID) }
    }

    override fun downloadProgress(
        albumTitle: String,
        tracksComplete: Int,
        tracksTotal: Int,
    ): Notification {
        val text: NotificationText =
            NotificationComposer.downloadProgressText(albumTitle, tracksComplete, tracksTotal)
        return Notification.Builder(context, NotificationChannelId.DOWNLOADS.id)
            .setSmallIcon(R.drawable.ic_stat_needler)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(tracksTotal.coerceAtLeast(0), tracksComplete.coerceAtLeast(0), tracksTotal <= 0)
            .build()
    }

    override fun showDownloadProgress(
        tag: String,
        albumTitle: String,
        tracksComplete: Int,
        tracksTotal: Int,
    ) {
        val notificationManager: NotificationManager = manager ?: return
        if (!NotificationPermissionPolicy.canPost(permissionState())) return
        if (!notificationManager.areNotificationsEnabled()) return
        runCatching {
            notificationManager.notify(
                tag,
                ID,
                downloadProgress(albumTitle, tracksComplete, tracksTotal),
            )
        }
    }

    /**
     * The launch intent, carrying the destination as extras.
     *
     * `FLAG_IMMUTABLE` is mandatory from Android 12 and correct everywhere: nothing outside this
     * app has any business rewriting the intent, and a mutable pending intent handed to the system
     * is one of the older ways an app leaks an authority it did not mean to.
     */
    private fun pendingIntentFor(notification: NeedlerNotification): PendingIntent? {
        val launch: Intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?: return null
        val (kind: String, id: String?) = NotificationDestination.encode(notification.destination)
        launch
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(NotificationDestination.EXTRA_KIND, kind)
            .putExtra(NotificationDestination.EXTRA_ID, id)
        return PendingIntent.getActivity(
            context,
            // A request code per notification, so two pending intents for different albums are not
            // treated as the same one and collapsed onto whichever was built first.
            notification.tag.hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        /**
         * One numeric id for everything, with the string tag doing the distinguishing.
         *
         * The tags are derived from content, so a repeat of the same news replaces its predecessor
         * rather than stacking, and there is no counter to lose when the process dies.
         */
        const val ID: Int = 1
    }
}

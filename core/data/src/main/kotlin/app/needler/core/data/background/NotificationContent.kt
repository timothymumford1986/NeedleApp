package app.needler.core.data.background

/**
 * The three switchable notifications, as content rather than as platform objects.
 *
 * REQUIREMENTS.md "Background work and notifications" specifies exactly three, each independently
 * switchable on screen 12, each with a source and a destination:
 *
 * | Notification | Source | Tapping it opens |
 * | --- | --- | --- |
 * | Pull finished | `landed_release_group_mbids` on the activity summary | The album, ready to play |
 * | Pull failed | `failed_count` rising | Pulls, on that item |
 * | New release from a followed artist | the unseen-count endpoint | The artist |
 *
 * Content lives here, separate from posting, for one reason: the mapping from a poll result to
 * words on a lock screen is the part that can be wrong, and it is the part that can be tested on
 * the JVM. Anything that needs a `Context` is in [NeedlerNotifier].
 */
public sealed interface NeedlerNotification {

    /** Which channel this belongs on, and therefore which switch silences it. */
    public val channel: NotificationChannelId

    /** Where a tap goes. */
    public val destination: NotificationDestination

    /**
     * Stable per-notification id, so a second "pull finished" for the same album replaces the first
     * rather than stacking. Ids are derived from content, never from a counter, because a counter
     * would not survive process death and the duplicate would.
     */
    public val tag: String

    /** An album the server finished acquiring. */
    public data class PullFinished(
        val releaseGroupMbid: String,
        val albumTitle: String,
        val artistName: String,
    ) : NeedlerNotification {
        override val channel: NotificationChannelId get() = NotificationChannelId.PULL_FINISHED
        override val destination: NotificationDestination
            get() = NotificationDestination.Album(releaseGroupMbid)
        override val tag: String get() = "pull-finished:" + releaseGroupMbid
    }

    /**
     * One or more pulls failed.
     *
     * The summary carries a count and no identities, so this is deliberately a single collapsed
     * notification rather than one per task: the summary cannot name them, and a second request to
     * find out would undo the point of the cheap poll. The task list has the detail, which is where
     * the tap goes.
     */
    public data class PullFailed(
        val failedCount: Int,
    ) : NeedlerNotification {
        override val channel: NotificationChannelId get() = NotificationChannelId.PULL_FAILED
        override val destination: NotificationDestination get() = NotificationDestination.Pulls
        override val tag: String get() = "pull-failed"
    }

    /**
     * A followed artist released something.
     *
     * [artistMbid] is present only when exactly one release was unseen, because only then is there
     * a single artist to open. With several unseen the tap falls back to the library, which is why
     * the destination is computed rather than fixed.
     */
    public data class NewRelease(
        val unseenCount: Int,
        val artistMbid: String? = null,
        val artistName: String? = null,
        val albumTitle: String? = null,
    ) : NeedlerNotification {
        override val channel: NotificationChannelId get() = NotificationChannelId.NEW_RELEASE
        override val destination: NotificationDestination
            get() = artistMbid?.let(NotificationDestination::Artist) ?: NotificationDestination.Library
        override val tag: String get() = "new-release"
    }
}

/** Where a notification tap lands. Resolved to a route by `:app`, which owns navigation. */
public sealed interface NotificationDestination {

    /** The album screen, ready to play. */
    public data class Album(val releaseGroupMbid: String) : NotificationDestination

    /** The artist screen. */
    public data class Artist(val artistMbid: String) : NotificationDestination

    /** The Pulls tab. */
    public data object Pulls : NotificationDestination

    /** The Library tab: the fallback when there is no single thing to open. */
    public data object Library : NotificationDestination

    public companion object {

        /** Extra key carrying the destination kind on the launch intent. */
        public const val EXTRA_KIND: String = "app.needler.notification.KIND"

        /** Extra key carrying the MBID a destination needs, when it needs one. */
        public const val EXTRA_ID: String = "app.needler.notification.ID"

        public const val KIND_ALBUM: String = "album"
        public const val KIND_ARTIST: String = "artist"
        public const val KIND_PULLS: String = "pulls"
        public const val KIND_LIBRARY: String = "library"

        /** The wire form of a destination, as two intent extras. */
        public fun encode(destination: NotificationDestination): Pair<String, String?> =
            when (destination) {
                is Album -> KIND_ALBUM to destination.releaseGroupMbid
                is Artist -> KIND_ARTIST to destination.artistMbid
                Pulls -> KIND_PULLS to null
                Library -> KIND_LIBRARY to null
            }

        /**
         * Reads a destination back off an intent's extras.
         *
         * Returns null for anything unrecognised rather than guessing, because a launch intent that
         * did not come from a notification carries neither extra and must open the app normally.
         */
        public fun decode(kind: String?, id: String?): NotificationDestination? = when (kind) {
            KIND_ALBUM -> id?.takeIf { it.isNotBlank() }?.let(::Album)
            KIND_ARTIST -> id?.takeIf { it.isNotBlank() }?.let(::Artist)
            KIND_PULLS -> Pulls
            KIND_LIBRARY -> Library
            else -> null
        }
    }
}

/**
 * The channels, which are mandatory from Android 8 and are also the user's second switch.
 *
 * Each of the three notifications gets its own channel rather than sharing one, because a channel
 * is where Android puts the per-notification controls the user actually reaches from the system
 * settings: importance, sound, badge. One shared channel would make screen 12's three switches the
 * only way to silence any of them, and would let a user who muted "pull failed" in the system UI
 * lose "pull finished" along with it.
 *
 * The fourth is not a notification the user chose: it is the progress notification a download job
 * shows while it runs, kept at low importance so it never makes a sound.
 */
public enum class NotificationChannelId(
    public val id: String,
    public val channelName: String,
    public val channelDescription: String,
) {
    PULL_FINISHED(
        id = "needler.pull.finished",
        channelName = "Pull finished",
        channelDescription = "An album you asked for has arrived and is ready to play.",
    ),
    PULL_FAILED(
        id = "needler.pull.failed",
        channelName = "Pull failed",
        channelDescription = "A pull could not be completed.",
    ),
    NEW_RELEASE(
        id = "needler.following.new-release",
        channelName = "New releases",
        channelDescription = "An artist you follow has released something new.",
    ),
    DOWNLOADS(
        id = "needler.downloads",
        channelName = "Downloading to device",
        channelDescription = "Progress while an album is being downloaded to this device.",
    ),
}

/** Title and body text for one notification. Kept pure so the wording is testable. */
public data class NotificationText(val title: String, val body: String)

/**
 * Turns a [NeedlerNotification] into the two lines a user reads.
 *
 * The wording follows the product's vocabulary from REQUIREMENTS.md: the app says **pull** for a
 * server-side acquisition and reserves "download" for getting bytes onto this device, which is why
 * only [NotificationChannelId.DOWNLOADS] uses the second word.
 */
public object NotificationComposer {

    public fun text(notification: NeedlerNotification): NotificationText = when (notification) {
        is NeedlerNotification.PullFinished -> NotificationText(
            title = notification.albumTitle.ifBlank { "Your pull has arrived" },
            body = if (notification.artistName.isBlank()) {
                "Ready to play"
            } else {
                notification.artistName + " - ready to play"
            },
        )

        is NeedlerNotification.PullFailed -> NotificationText(
            title = if (notification.failedCount <= 1) "A pull failed" else "Pulls failed",
            body = if (notification.failedCount <= 1) {
                "Open Pulls to see why, and retry."
            } else {
                notification.failedCount.toString() + " pulls need attention."
            },
        )

        is NeedlerNotification.NewRelease -> NotificationText(
            title = if (notification.unseenCount <= 1) "New release" else "New releases",
            body = when {
                notification.unseenCount <= 1 && notification.artistName != null ->
                    notification.artistName +
                        (notification.albumTitle?.let { title -> " released " + title }
                            ?: " has something new")

                notification.unseenCount <= 1 -> "An artist you follow has something new."

                else -> notification.unseenCount.toString() +
                    " new releases from artists you follow."
            },
        )
    }

    /** Progress text for the download job's own notification. */
    public fun downloadProgressText(
        albumTitle: String,
        tracksComplete: Int,
        tracksTotal: Int,
    ): NotificationText = NotificationText(
        title = albumTitle.ifBlank { "Downloading to device" },
        body = if (tracksTotal > 0) {
            tracksComplete.toString() + " of " + tracksTotal.toString() + " tracks"
        } else {
            "Starting"
        },
    )
}

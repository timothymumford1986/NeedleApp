package app.needler.core.data.background

/**
 * The one place background work is enqueued.
 *
 * Repositories call this rather than touching `WorkManager` themselves, for two reasons. It keeps
 * `WorkManager` - which needs a `Context` and a database - out of classes that are unit-tested with
 * no device, and it keeps the names, the uniqueness policies and the constraints in one file, where
 * a duplicate-named job or a missing network constraint is visible.
 *
 * [None] is the default wherever a repository takes one, so existing tests construct repositories
 * unchanged and no test accidentally enqueues real work.
 */
public interface BackgroundWorkScheduler {

    /**
     * Starts, or resumes, the download of a pinned album: what "Pull local" actually does.
     *
     * Unique per album, so a second tap while one is running joins the existing job rather than
     * fetching the same bytes twice. Network constraints are read from the "Download to device on
     * Wi-Fi only" setting at enqueue time, which is why this suspends.
     */
    public suspend fun scheduleAlbumDownload(releaseGroupMbid: String)

    /** Stops a download: unpinning an album, or "Remove all from device". */
    public suspend fun cancelAlbumDownload(releaseGroupMbid: String)

    /**
     * The expedited check after a pull is placed.
     *
     * REQUIREMENTS.md's schedule has this at one minute, backing off to fifteen. It exists because
     * fifteen minutes is `WorkManager`'s floor for periodic work, so a small album that finishes
     * two minutes after a poll would otherwise sit unannounced for a quarter of an hour - exactly
     * the case where a user is watching.
     */
    public suspend fun schedulePollAfterPull()

    /**
     * Keeps the periodic poller running at the right cadence: fifteen minutes with pulls in flight,
     * six hours without.
     *
     * Safe and cheap to call on every app open, which is how the schedule survives an app update or
     * a force-stop.
     */
    public suspend fun schedulePeriodicPoll(cadence: PollCadence)

    /** A one-off sync, at the scope [trigger] calls for. */
    public suspend fun scheduleSync(trigger: SyncTrigger)

    public companion object {

        /** Enqueues nothing. The default in every repository, and what the unit tests get. */
        public val None: BackgroundWorkScheduler = object : BackgroundWorkScheduler {
            override suspend fun scheduleAlbumDownload(releaseGroupMbid: String) = Unit
            override suspend fun cancelAlbumDownload(releaseGroupMbid: String) = Unit
            override suspend fun schedulePollAfterPull() = Unit
            override suspend fun schedulePeriodicPoll(cadence: PollCadence) = Unit
            override suspend fun scheduleSync(trigger: SyncTrigger) = Unit
        }
    }
}

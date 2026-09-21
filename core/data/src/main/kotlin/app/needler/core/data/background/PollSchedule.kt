package app.needler.core.data.background

import app.needler.core.data.settings.NotificationSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * How often the background poller runs, and when it is allowed not to run at all.
 *
 * REQUIREMENTS.md "Polling schedule" fixes five rows. The two foreground rows - 2 s on the Pulls
 * screen, 20 s elsewhere - are plain coroutines owned by the screens and are none of this object's
 * business. The three background rows are:
 *
 * | Condition                  | Interval               | Mechanism               |
 * | -------------------------- | ---------------------- | ----------------------- |
 * | Pull placed, app in the background | 1 min, backing off to 15 min | Expedited one-time work |
 * | Active pulls, app backgrounded     | 15 min                       | Periodic work           |
 * | No active pulls                    | 6 h                          | Periodic work, also drives sync |
 *
 * **Fifteen minutes is `WorkManager`'s floor for periodic work and this object does not pretend to
 * beat it.** [PollCadence.ACTIVE_PULLS] is exactly that floor; a pull that finishes just after a
 * poll can therefore take a quarter of an hour to surface, which is why the first check after a
 * pull is expedited and why the Pulls badge - not a notification - is the reliable channel.
 *
 * Everything here is pure so the battery rules can be unit-tested without a device.
 */
public object PollSchedule {

    /** `WorkManager`'s own minimum period. Asking for less silently gets this. */
    public val MINIMUM_PERIODIC_INTERVAL: Duration = 15.minutes

    /** The first post-pull check, soon enough to catch a small album. */
    public val EXPEDITED_FIRST_DELAY: Duration = 1.minutes

    /** Where the post-pull back-off lands before the periodic poller takes over. */
    public val EXPEDITED_MAX_DELAY: Duration = 15.minutes

    /**
     * How many times the post-pull check re-arms itself before handing over.
     *
     * 1, 2, 4, 8 and 15 minutes: roughly half an hour of attention after a pull is placed, which
     * covers the case the expedited check exists for - a user waiting for something small - without
     * turning into a second poller.
     */
    public const val EXPEDITED_MAX_ATTEMPTS: Int = 5

    /**
     * The periodic interval for the current state of the world.
     *
     * Active pulls mean something is expected to change, so the poller runs at the floor. Nothing
     * active means the only reason to wake at all is the metadata sync, which is six-hourly.
     */
    public fun cadenceFor(hasActivePulls: Boolean): PollCadence =
        if (hasActivePulls) PollCadence.ACTIVE_PULLS else PollCadence.IDLE

    /**
     * Whether a periodic run should touch the network for the activity summary at all.
     *
     * This is the battery rule REQUIREMENTS.md states in so many words: with no active pulls and
     * every notification switched off there is nothing a poll could tell the user, so the six-hourly
     * wake does its metadata sync and nothing else. It deliberately does **not** consider whether
     * the notification permission was granted - see [NotificationPermissionPolicy] - because a
     * refused permission still leaves the Pulls badge to keep correct.
     */
    public fun shouldPollActivity(
        hasActivePulls: Boolean,
        notifications: NotificationSettings,
    ): Boolean = hasActivePulls || !notifications.allDisabled

    /**
     * Whether a periodic run should also drive the metadata delta sync.
     *
     * Only the six-hourly cadence does. A fifteen-minute poller that synced as well would turn
     * "there are pulls in flight" into four `getIndexes` calls an hour for as long as they run.
     */
    public fun shouldSync(cadence: PollCadence): Boolean = cadence == PollCadence.IDLE

    /**
     * Back-off for the post-pull expedited check: 1, 2, 4, 8 minutes, then clamped to
     * [EXPEDITED_MAX_DELAY].
     *
     * [attempt] is `WorkManager`'s `runAttemptCount`, which is zero on the first run.
     */
    public fun expeditedDelay(attempt: Int): Duration {
        if (attempt <= 0) return EXPEDITED_FIRST_DELAY
        val doublings: Int = attempt.coerceAtMost(MAX_DOUBLINGS)
        val scaled: Duration = EXPEDITED_FIRST_DELAY * (1 shl doublings)
        return if (scaled > EXPEDITED_MAX_DELAY) EXPEDITED_MAX_DELAY else scaled
    }

    /**
     * Whether the post-pull check should ask to run again.
     *
     * It stops as soon as nothing is active - there is nothing left to wait for - and in any case
     * once it has had [EXPEDITED_MAX_ATTEMPTS] goes, after which the periodic poller is the right
     * owner and a second one would only cost battery.
     */
    public fun shouldKeepExpediting(attempt: Int, stillActive: Boolean): Boolean =
        stillActive && attempt + 1 < EXPEDITED_MAX_ATTEMPTS

    /**
     * True when the activity summary's `revision` has not moved, so every downstream refresh can be
     * skipped.
     *
     * The server advances `revision` on insert, removal, owner change and durable state changes, and
     * never on progress-only writes. That is what makes an unchanged poll nearly free: one small
     * request, no task-list page walk, no notification work, no database writes.
     */
    public fun revisionUnchanged(previous: Long?, current: Long): Boolean =
        previous != null && previous == current

    /** 1 shl 4 is 16 minutes, already past the cap; doubling further would only overflow sooner. */
    private const val MAX_DOUBLINGS: Int = 4
}

/** The two background polling intervals REQUIREMENTS.md allows. */
public enum class PollCadence(public val interval: Duration) {
    /** Something is in flight on the server: poll at `WorkManager`'s floor. */
    ACTIVE_PULLS(15.minutes),

    /** Nothing is in flight: the wake exists mainly to keep the mirror current. */
    IDLE(6.hours),
}

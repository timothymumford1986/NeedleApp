package app.needler.core.data.background

import app.needler.core.data.settings.NotificationSettings
import app.needler.core.domain.model.PullActivitySummary

/**
 * What the poller remembers between runs.
 *
 * All of it exists to stop the same thing being announced twice. A notification that re-fires on
 * every poll is worse than no notification at all, and the process dies between polls by design, so
 * this has to be persisted rather than held in a field - see [BackgroundStateStore].
 */
public data class PollMemory(
    /**
     * `revision` from the last activity summary, or null before the first successful poll.
     *
     * This is the whole reason a background poll is cheap. The server advances it on insert,
     * removal, owner change and durable state changes and never on progress-only writes, so an
     * equal revision means nothing has moved and every downstream refresh can be skipped.
     */
    val lastRevision: Long? = null,

    /** Albums already announced as landed, so a landed list that repeats does not repeat the news. */
    val announcedLandedMbids: Set<String> = emptySet(),

    /**
     * `failed_count` as of the last poll.
     *
     * The notification fires on the count **rising**, not on it being non-zero: a queue with one
     * old failure in it must not produce a notification every six hours for ever.
     */
    val lastFailedCount: Int = 0,

    /** The unseen new-release count as of the last poll, for the same rising-edge rule. */
    val lastUnseenNewReleaseCount: Int = 0,
) {
    public companion object {
        /** A device that has never polled. Everything is a first sighting, nothing is news. */
        public val Empty: PollMemory = PollMemory()
    }
}

/**
 * The result of comparing one activity summary against what the poller last saw.
 *
 * [revisionUnchanged] is the cheap path and the caller must honour it: when it is true there is
 * nothing to fetch, nothing to write and nothing to post, and the run should end there.
 */
public data class PollDigestResult(
    val revisionUnchanged: Boolean,
    /** Notifications to post, already filtered by the user's three switches. */
    val notifications: List<NeedlerNotification>,
    /** Albums that landed since the last poll, whether or not they were announced. */
    val landedMbids: List<String>,
    /** What to persist for the next run. */
    val memory: PollMemory,
)

/**
 * Turns a poll into "what changed, and what is worth telling the user".
 *
 * Pure, because every rule in it is one that can be wrong in a way no device would make obvious:
 * announcing an album twice, announcing an old failure for ever, or doing a full task-list refresh
 * on a revision that never moved. All three are asserted in `PollDigestTest`.
 *
 * The first poll on a fresh install is a special case worth stating: it has no previous revision,
 * so it is not "unchanged", but it must also not announce the entire backlog. Landed albums seen on
 * the very first poll are recorded as announced without producing notifications - the badge already
 * carries them, and a user who has just signed in does not want twelve notifications about music
 * that arrived last week.
 */
public object PollDigest {

    public fun digest(
        previous: PollMemory,
        summary: PullActivitySummary,
        settings: NotificationSettings,
        /** Album titles and artists for the landed MBIDs, from the mirror. Missing entries degrade. */
        albumLookup: (String) -> AlbumHeadline? = { null },
    ): PollDigestResult {
        val landed: List<String> = summary.landedReleaseGroupMbids.map { it.value }

        if (PollSchedule.revisionUnchanged(previous.lastRevision, summary.revision)) {
            return PollDigestResult(
                revisionUnchanged = true,
                notifications = emptyList(),
                landedMbids = emptyList(),
                memory = previous,
            )
        }

        val firstEverPoll: Boolean = previous.lastRevision == null
        val unannounced: List<String> = landed.filterNot { previous.announcedLandedMbids.contains(it) }

        val notifications: MutableList<NeedlerNotification> = ArrayList()

        if (settings.pullFinished && !firstEverPoll) {
            unannounced.forEach { mbid ->
                val headline: AlbumHeadline? = albumLookup(mbid)
                notifications.add(
                    NeedlerNotification.PullFinished(
                        releaseGroupMbid = mbid,
                        albumTitle = headline?.title.orEmpty(),
                        artistName = headline?.artistName.orEmpty(),
                    ),
                )
            }
        }

        val failuresRose: Boolean = summary.failedCount > previous.lastFailedCount
        if (settings.pullFailed && failuresRose && !firstEverPoll) {
            notifications.add(NeedlerNotification.PullFailed(failedCount = summary.failedCount))
        }

        return PollDigestResult(
            revisionUnchanged = false,
            notifications = notifications,
            landedMbids = landed,
            memory = previous.copy(
                lastRevision = summary.revision,
                // Everything landed is recorded as announced, including on the first poll where
                // nothing was posted. Recording it is what makes the first poll quiet and the
                // second one honest.
                announcedLandedMbids = (previous.announcedLandedMbids + landed).takeLast(),
                lastFailedCount = summary.failedCount,
            ),
        )
    }

    /**
     * The new-release half, which is a separate endpoint and therefore a separate decision.
     *
     * The unseen count is only worth a request when the switch is on: it is a second call on every
     * poll otherwise, for a notification the user turned off. Fires on a rising count, so a badge
     * the user has not cleared does not re-announce itself for ever.
     */
    public fun newReleaseNotification(
        previous: PollMemory,
        unseenCount: Int,
        settings: NotificationSettings,
        artistMbid: String? = null,
        artistName: String? = null,
        albumTitle: String? = null,
    ): Pair<NeedlerNotification.NewRelease?, PollMemory> {
        val memory: PollMemory = previous.copy(lastUnseenNewReleaseCount = unseenCount)
        if (!settings.newReleaseFromFollowedArtist) return null to memory
        if (unseenCount <= previous.lastUnseenNewReleaseCount) return null to memory
        return NeedlerNotification.NewRelease(
            unseenCount = unseenCount,
            artistMbid = artistMbid?.takeIf { unseenCount == 1 },
            artistName = artistName?.takeIf { unseenCount == 1 },
            albumTitle = albumTitle?.takeIf { unseenCount == 1 },
        ) to memory
    }

    /**
     * Caps the announced set so it cannot grow without bound.
     *
     * A set of MBIDs is small, but it is written to disk on every poll for the life of the install,
     * and there is no event that would ever prune it. The cap is generous enough that an album
     * cannot fall out of it while the server is still reporting it as landed.
     */
    private fun Set<String>.takeLast(limit: Int = MAX_ANNOUNCED): Set<String> =
        if (size <= limit) this else toList().takeLast(limit).toSet()

    private const val MAX_ANNOUNCED: Int = 200
}

/** The two strings a "pull finished" notification needs, read from the mirror. */
public data class AlbumHeadline(val title: String, val artistName: String)

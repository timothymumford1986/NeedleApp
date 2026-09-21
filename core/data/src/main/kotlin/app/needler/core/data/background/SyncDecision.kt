package app.needler.core.data.background

import app.needler.core.domain.model.FullSyncReason

/** What a sync trigger asks for. */
public enum class SyncTrigger {

    /** Nothing to do: the mirror belongs to this server and is fresh enough. */
    NONE,

    /** First connect after onboarding: there is no mirror yet, so nothing can be a delta. */
    FULL_FIRST_CONNECT,

    /**
     * The saved server changed identity.
     *
     * The mirror, the audio cache and the playlist ids are dropped before the sync runs: MBIDs are
     * global but `file_id` values and playlist ids are not, so a mirror pointed at a different
     * server describes music that is not there.
     */
    FULL_IDENTITY_CHANGED,

    /** The mirror belongs to this server and has simply gone stale. */
    DELTA,
    ;

    public val isFull: Boolean
        get() = this == FULL_FIRST_CONNECT || this == FULL_IDENTITY_CHANGED

    /** The domain's reason for a full sync, for the three cases that have one. */
    public val fullSyncReason: FullSyncReason?
        get() = when (this) {
            FULL_FIRST_CONNECT -> FullSyncReason.FIRST_CONNECT
            FULL_IDENTITY_CHANGED -> FullSyncReason.SERVER_IDENTITY_CHANGED
            NONE, DELTA -> null
        }
}

/**
 * When a sync has to run without the user asking for one.
 *
 * Before this, the only thing that ever synced was the Library screen's own refresh, so a fresh
 * install showed an empty library until the user happened to open that screen - and a sign-in on a
 * different server left the old server's mirror in place until something noticed. Both are decided
 * here.
 *
 * REQUIREMENTS.md "Sync" fixes the table:
 *
 * | Trigger | Scope |
 * | --- | --- |
 * | App foreground, mirror older than 15 min | Delta |
 * | Manual Sync now | Delta, forced |
 * | Background worker | Delta |
 * | First connect, or server identity changed | Full |
 *
 * Pure, so the awkward cases are testable: a mirror that has a revision but has never completed a
 * full sync is **not** a valid delta base, and a null identity means "not connected" rather than
 * "new server", which would otherwise wipe a perfectly good mirror every time the credential store
 * was read a moment too early.
 */
public object SyncDecision {

    /**
     * @param storedIdentity `sync_state.server_identity`: the server this mirror was built from.
     * @param currentIdentity the saved server's identity now, or null when none is configured.
     * @param lastFullSyncAt when a full sync last completed, or null if none ever has.
     * @param lastDeltaSyncAt when a delta last completed.
     * @param staleAfterMillis the foreground staleness window, 15 minutes by requirement.
     */
    public fun decide(
        storedIdentity: String?,
        currentIdentity: String?,
        lastFullSyncAt: Long?,
        lastDeltaSyncAt: Long?,
        nowMillis: Long,
        staleAfterMillis: Long,
    ): SyncTrigger {
        // No server saved: the Connect screen owns the app and there is nothing to sync against.
        if (currentIdentity.isNullOrBlank()) return SyncTrigger.NONE

        // A mirror that has never completed a full sync cannot be the base of a delta, whatever
        // else it holds. `getIndexes` with `ifModifiedSince` would return "nothing changed" against
        // a library the app has never actually read.
        if (storedIdentity.isNullOrBlank() || lastFullSyncAt == null) {
            return SyncTrigger.FULL_FIRST_CONNECT
        }

        if (storedIdentity != currentIdentity) return SyncTrigger.FULL_IDENTITY_CHANGED

        val lastDelta: Long = lastDeltaSyncAt ?: return SyncTrigger.DELTA
        val age: Long = nowMillis - lastDelta
        // A clock that moved backwards - a manual time change, a reboot before NTP - produces a
        // negative age. Syncing then is cheap and harmless; trusting it would leave the mirror
        // frozen until the clock caught up.
        return if (age < 0L || age >= staleAfterMillis) SyncTrigger.DELTA else SyncTrigger.NONE
    }
}

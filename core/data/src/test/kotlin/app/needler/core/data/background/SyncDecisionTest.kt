package app.needler.core.data.background

import app.needler.core.domain.model.FullSyncReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * When a sync runs without a screen asking for one.
 *
 * The bug this exists to prevent is the one that shipped: the only sync trigger in the app was the
 * Library screen's own refresh, so a fresh install showed an empty library until the user happened
 * to open that screen. The second is quieter and worse - signing in to a different server while the
 * previous server's mirror is still in place, where every `file_id` and playlist id describes music
 * that is not there.
 */
public class SyncDecisionTest {

    private val staleAfter = 15.minutes.inWholeMilliseconds
    private val now = 1_000_000_000L

    @Test
    public fun `no server saved means nothing to sync`() {
        assertEquals(
            SyncTrigger.NONE,
            SyncDecision.decide(
                storedIdentity = null,
                currentIdentity = null,
                lastFullSyncAt = null,
                lastDeltaSyncAt = null,
                nowMillis = now,
                staleAfterMillis = staleAfter,
            ),
        )
    }

    @Test
    public fun `a fresh install gets a full sync rather than an empty library`() {
        val trigger = SyncDecision.decide(
            storedIdentity = null,
            currentIdentity = "https://music.example.net",
            lastFullSyncAt = null,
            lastDeltaSyncAt = null,
            nowMillis = now,
            staleAfterMillis = staleAfter,
        )

        assertEquals(SyncTrigger.FULL_FIRST_CONNECT, trigger)
        assertEquals(FullSyncReason.FIRST_CONNECT, trigger.fullSyncReason)
    }

    @Test
    public fun `a mirror that never completed a full sync is not a valid delta base`() {
        // getIndexes with ifModifiedSince against a library the app has never actually read comes
        // back with "nothing changed", and the library stays empty for ever.
        assertEquals(
            SyncTrigger.FULL_FIRST_CONNECT,
            SyncDecision.decide(
                storedIdentity = "https://music.example.net",
                currentIdentity = "https://music.example.net",
                lastFullSyncAt = null,
                lastDeltaSyncAt = now - 1_000L,
                nowMillis = now,
                staleAfterMillis = staleAfter,
            ),
        )
    }

    @Test
    public fun `a changed server identity wipes and rebuilds`() {
        val trigger = SyncDecision.decide(
            storedIdentity = "https://music.example.net",
            currentIdentity = "https://other.example.net",
            lastFullSyncAt = now - 1_000L,
            lastDeltaSyncAt = now - 1_000L,
            nowMillis = now,
            staleAfterMillis = staleAfter,
        )

        assertEquals(SyncTrigger.FULL_IDENTITY_CHANGED, trigger)
        assertEquals(FullSyncReason.SERVER_IDENTITY_CHANGED, trigger.fullSyncReason)
    }

    @Test
    public fun `a stale mirror on the same server is a delta`() {
        val trigger = SyncDecision.decide(
            storedIdentity = "https://music.example.net",
            currentIdentity = "https://music.example.net",
            lastFullSyncAt = now - 99_999_999L,
            lastDeltaSyncAt = now - staleAfter - 1L,
            nowMillis = now,
            staleAfterMillis = staleAfter,
        )

        assertEquals(SyncTrigger.DELTA, trigger)
        assertNull(trigger.fullSyncReason)
    }

    @Test
    public fun `a mirror synced two minutes ago is left alone`() {
        assertEquals(
            SyncTrigger.NONE,
            SyncDecision.decide(
                storedIdentity = "https://music.example.net",
                currentIdentity = "https://music.example.net",
                lastFullSyncAt = now - 99_999_999L,
                lastDeltaSyncAt = now - 2.minutes.inWholeMilliseconds,
                nowMillis = now,
                staleAfterMillis = staleAfter,
            ),
        )
    }

    @Test
    public fun `a clock that moved backwards syncs rather than freezing the mirror`() {
        assertEquals(
            SyncTrigger.DELTA,
            SyncDecision.decide(
                storedIdentity = "https://music.example.net",
                currentIdentity = "https://music.example.net",
                lastFullSyncAt = now - 99_999_999L,
                // A reboot before NTP, or a manual time change: the last sync is in the future.
                lastDeltaSyncAt = now + 86_400_000L,
                nowMillis = now,
                staleAfterMillis = staleAfter,
            ),
        )
    }
}

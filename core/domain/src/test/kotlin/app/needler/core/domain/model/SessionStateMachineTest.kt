package app.needler.core.domain.model

import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential state machine, and in particular the case the first draft got backwards.
 *
 * The product decision under test: **a revoked app-password with a live bearer is repaired
 * silently**. The minting endpoint needs only the bearer, so the app already holds everything
 * required, and the user is asked to sign in again in exactly one case - both credentials dead.
 * Getting this wrong is expensive in a way no test failure would otherwise catch: it throws away a
 * working session and a multi-gigabyte cache over one secret, usually one the user revoked from the
 * web UI without realising which app it belonged to.
 */
public class SessionStateMachineTest {

    private val server = ServerIdentity(baseUrl = "https://music.example.net")

    private val user = User(id = "u1", username = "yourname", role = UserRole.USER)

    private val capabilities = ServerCapabilities(
        subsonicEnabled = true,
        transcodingAvailable = false,
        libraryDownloadAllowed = true,
    )

    private val expiry: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val authenticated = SessionState.Authenticated(
        server = server,
        user = user,
        capabilities = capabilities,
        bearerExpiresAt = expiry,
    )

    private val repairing = SessionState.RepairingAppPassword(
        server = server,
        user = user,
        capabilities = capabilities,
        bearerExpiresAt = expiry,
    )

    // ---------------------------------------------------------------- the repairable case

    @Test
    public fun `a revoked app-password with a live bearer is repaired, not re-onboarded`() {
        val next: SessionState = SessionState.afterAppPasswordRejected(authenticated, bearerAlive = true)

        assertEquals(repairing, next)
    }

    @Test
    public fun `repairing keeps the catalogue lane and suspends the library lane`() {
        // The exact inverse of PlayerOnly, and the reason playback pauses for the length of the
        // repair: streaming needs the app-password, search does not.
        assertTrue(repairing.canUseCatalogueLane)
        assertFalse(repairing.canUseLibraryLane)
    }

    @Test
    public fun `a repaired session is authenticated again, expiry warning intact`() {
        val repaired: SessionState.Authenticated = repairing.repaired()

        assertEquals(authenticated, repaired)
        // The bearer's own 30-day clock is untouched by the repair, so the day-25 warning still
        // fires on time rather than being reset by an unrelated failure.
        assertTrue(repaired.shouldWarnAboutExpiry(now = expiry - 1.days))
        assertFalse(repaired.shouldWarnAboutExpiry(now = expiry - 20.days))
    }

    @Test
    public fun `a second rejection during a repair does not restart it`() {
        // Several Subsonic calls are usually in the air when the first one is refused. One repair
        // per failed request would burn through the server's cap of 25 app-passwords in a burst.
        val next: SessionState = SessionState.afterAppPasswordRejected(repairing, bearerAlive = true)

        assertSame(repairing, next)
    }

    @Test
    public fun `a retryable repair failure stays in repair and records the error`() {
        // Offline is not evidence that the bearer is dead. Escalating here would hand the user a
        // sign-in screen for a problem that fixes itself when the train leaves the tunnel.
        val next: SessionState = SessionState.afterAppPasswordRepairFailed(
            current = repairing,
            error = NeedlerError.Offline(),
        )

        assertEquals(repairing.copy(lastAttemptError = NeedlerError.Offline()), next)
    }

    @Test
    public fun `a permanent repair failure is the end of the silent path`() {
        val next: SessionState = SessionState.afterAppPasswordRepairFailed(
            current = repairing,
            error = NeedlerError.SessionExpired,
        )

        assertEquals(
            SessionState.ReonboardingRequired(server, ReonboardingReason.BOTH_CREDENTIALS_DEAD),
            next,
        )
    }

    // ---------------------------------------------------------------- the one case that re-onboards

    @Test
    public fun `a revoked app-password with a dead bearer is the only way to re-onboarding`() {
        val next: SessionState = SessionState.afterAppPasswordRejected(authenticated, bearerAlive = false)

        assertEquals(
            SessionState.ReonboardingRequired(server, ReonboardingReason.BOTH_CREDENTIALS_DEAD),
            next,
        )
    }

    @Test
    public fun `a rejection from player-only re-onboards, because the bearer is already gone`() {
        val playerOnly = SessionState.PlayerOnly(
            server = server,
            user = user,
            capabilities = capabilities,
            reason = PlayerOnlyReason.BEARER_EXPIRED,
        )

        val next: SessionState = SessionState.afterAppPasswordRejected(playerOnly, bearerAlive = false)

        assertEquals(
            SessionState.ReonboardingRequired(server, ReonboardingReason.BOTH_CREDENTIALS_DEAD),
            next,
        )
    }

    @Test
    public fun `losing the bearer mid-repair leaves nothing to mint with`() {
        val next: SessionState = SessionState.afterBearerRejected(
            current = repairing,
            reason = PlayerOnlyReason.BEARER_REJECTED,
        )

        assertEquals(
            SessionState.ReonboardingRequired(server, ReonboardingReason.BOTH_CREDENTIALS_DEAD),
            next,
        )
    }

    // ---------------------------------------------------------------- the unchanged half

    @Test
    public fun `an expired bearer still degrades to a pure music player`() {
        val next: SessionState = SessionState.afterBearerRejected(
            current = authenticated,
            reason = PlayerOnlyReason.BEARER_EXPIRED,
        )

        assertEquals(
            SessionState.PlayerOnly(server, user, capabilities, PlayerOnlyReason.BEARER_EXPIRED),
            next,
        )
        assertTrue(next.canUseLibraryLane)
        assertFalse(next.canUseCatalogueLane)
    }

    @Test
    public fun `states with no library lane to lose are unaffected by a rejection`() {
        val disabled = SessionState.SubsonicDisabled(server)

        assertSame(disabled, SessionState.afterAppPasswordRejected(disabled, bearerAlive = true))
        assertSame(
            SessionState.NotConfigured,
            SessionState.afterAppPasswordRejected(SessionState.NotConfigured, bearerAlive = true),
        )
    }

    @Test
    public fun `re-onboarding has no reason that names a single revoked secret`() {
        // A reason for "app-password revoked" would be an invitation to use it, and using it is the
        // bug: one revoked secret must never cost the user their session and their cache.
        val reasons: List<String> = ReonboardingReason.entries.map { it.name }

        assertEquals(
            listOf(
                "BOTH_CREDENTIALS_DEAD",
                "CREDENTIALS_UNREADABLE",
                "SERVER_IDENTITY_CHANGED",
                "SIGNED_OUT",
            ),
            reasons,
        )
    }
}

package app.needler.core.data.repository

import app.needler.core.data.fake.FakeNetworkMonitor
import app.needler.core.data.fake.FakeSubsonicApi
import app.needler.core.data.fake.FakeV1Api
import app.needler.core.data.security.CredentialStoreState
import app.needler.core.data.security.EmptySharedPreferences
import app.needler.core.data.security.FakeSharedPreferences
import app.needler.core.data.security.SecureCredentialStore
import app.needler.core.domain.diagnostics.DiagnosticsSink
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.network.capability.CapabilityProbe
import app.needler.core.network.tls.MutableCertificatePinStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Does signing in actually leave anything on the disk?
 *
 * The test that was missing, and the one the device fault of 2026-10-02 needed. The symptom was a
 * user signed in, working, with the library loaded, who came back on a blank Connect screen after an
 * app update replaced the process - twice. Two explanations fitted every observation equally well:
 * the credentials were never written, so the session lived in memory and died with it; or they were
 * written, survived, and something above the data layer lost them. Nothing in the suite could tell
 * those apart, because every existing test of the store writes through the store and reads back
 * through the same object's in-memory cache, which is exactly the part that does not survive.
 *
 * So these tests assert against the [android.content.SharedPreferences] underneath, and the second
 * one rebuilds the repository over the same preferences and asks what state the app would start in.
 * That is the process replacement, modelled: nothing is carried over in memory, which is all an app
 * update does to a credential file.
 *
 * REQUIREMENTS.md "Authentication" is the contract being checked. Point 3 - the app-password secret
 * "is returned exactly once and is never re-fetchable, so a failed write must abort and revoke" - is
 * why the third test exists: a sign-in that reports success while persisting nothing is the worst
 * available outcome, because the server is then holding a credential the device cannot use.
 *
 * The encryption itself is out of scope and deliberately so: it is Jetpack's, it needs a device, and
 * `SecureCredentialStoreKeystoreTest` covers it on hardware. What breaks is the sequence above it.
 */
public class SessionCredentialPersistenceTest {

    /** The credential file, as the next process would find it. */
    private val preferences: FakeSharedPreferences = FakeSharedPreferences()

    /** The unencrypted server mirror, which is a separate file and must stay one. */
    private val mirror: FakeSharedPreferences = FakeSharedPreferences()

    private val lines: MutableList<String> = mutableListOf()

    private val diagnostics = DiagnosticsSink { level, message ->
        lines += level.name + " " + message
    }

    private val v1: FakeV1Api = FakeV1Api()
    private val subsonic: FakeSubsonicApi = FakeSubsonicApi()

    /**
     * A store over the shared preferences, as [SecureCredentialStore.create] would build it.
     *
     * Called twice in the same test on purpose: two stores over one file is what a relaunch is.
     */
    private fun store(): SecureCredentialStore = SecureCredentialStore.createForTesting(
        preferences = preferences,
        files = null,
        serverMirror = mirror,
        state = CredentialStoreState.Opened,
        diagnostics = diagnostics,
    )

    private fun repository(credentials: SecureCredentialStore): SessionRepository =
        DefaultSessionRepository(
            credentials = credentials,
            v1 = v1,
            capabilityProbe = CapabilityProbe(v1 = v1, subsonic = subsonic),
            networkMonitor = FakeNetworkMonitor(),
            nowMillis = { NOW },
            pins = MutableCertificatePinStore(),
        )

    private suspend fun SessionRepository.signIn(): Outcome<SessionState> = connect(
        serverUrl = SERVER,
        username = "yourname",
        password = "hunter2",
        deviceName = "Needler on a Pixel",
    )

    @Test
    public fun `signing in puts both secrets and the address on the disk`(): Unit = runTest {
        val outcome: Outcome<SessionState> = repository(store()).signIn()

        assertTrue(outcome.toString(), outcome is Outcome.Success)
        // Read from the preferences, not from the store: the store's cache is memory and the
        // question is what the next process will find.
        assertEquals("companion-token", preferences.getString("companion_bearer", null))
        assertEquals("secret", preferences.getString("app_password", null))
        assertEquals(SERVER, preferences.getString("server_url", null))
        // The issue time goes down with the bearer, because the 30-day lifetime does not slide and
        // the day-25 warning is computed from it.
        assertEquals(NOW, preferences.getLong("companion_bearer_issued_at", 0L))
        // And the address is mirrored outside the encrypted file, which is what lets a keystore
        // failure cost the user their secrets without also costing them their server.
        assertEquals(SERVER, mirror.getString("server_url", null))
    }

    @Test
    public fun `the session survives the process that created it`(): Unit = runTest {
        repository(store()).signIn()

        // A second store and a second repository over the same file, with nothing shared in memory.
        val restored: SessionState = repository(store()).currentSession()

        assertTrue(restored.toString(), restored is SessionState.Authenticated)
    }

    @Test
    public fun `a store that cannot persist aborts the sign-in instead of reporting success`(): Unit =
        runTest {
            // Every write refused, which is what a locked store looks like to the flow above it.
            val refusing: SecureCredentialStore = SecureCredentialStore.createForTesting(
                preferences = EmptySharedPreferences,
                files = null,
                serverMirror = null,
                state = CredentialStoreState.Opened,
                diagnostics = diagnostics,
            )

            val outcome: Outcome<SessionState> = repository(refusing).signIn()

            assertTrue(outcome.toString(), outcome is Outcome.Failure)
            // Nothing was minted that would need revoking. The companion bearer is written before
            // the app-password is created precisely so that the cheap failure happens first: the
            // bearer can be minted again, the app-password's secret cannot.
            assertFalse(
                v1.calls.toString(),
                v1.calls.any { it.startsWith("createAppPassword") },
            )
        }

    @Test
    public fun `a write that landed leaves a line, so a silent log means nothing was written`(): Unit =
        runTest {
            repository(store()).signIn()

            assertTrue(lines.toString(), lines.any { it.contains("wrote companion_bearer") })
            assertTrue(lines.toString(), lines.any { it.contains("wrote app_password") })
            assertTrue(
                lines.toString(),
                lines.all { !it.contains("wrote") || it.contains("reached the disk") },
            )
            // The line is a key name and an outcome. A credential has never been in it and must
            // not be: REQUIREMENTS.md "Security" rule 1 keeps secrets out of logs entirely, and
            // this is the one file in the module allowed to write any line at all.
            assertFalse(lines.toString(), lines.any { it.contains("companion-token") })
        }

    private companion object {
        const val SERVER: String = "https://music.yourhome.net"

        /** Fixed, so the issue time stored with the bearer is assertable. */
        const val NOW: Long = 1_760_000_000_000
    }
}

package app.needler.core.data.security

import android.content.SharedPreferences
import app.needler.core.domain.diagnostics.DiagnosticsLevel
import app.needler.core.domain.diagnostics.DiagnosticsSink
import app.needler.core.network.ServerUrl
import java.io.IOException
import java.security.GeneralSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Which exception means delete, which means retry, and which means report.
 *
 * This is the test that would have caught the worst bug in the app. `openPreferences` caught
 * `GeneralSecurityException` **and** `IOException`, deleted the credential file and its `.bak`, and
 * reopened - so a momentary failure around process death destroyed a working session, and because
 * nothing logged, the sign-out was "cause unknown" for the length of an audit. Reproduced twice on
 * a device on 2026-10-01: once by `am force-stop`, once by `am kill`, the second being an ordinary
 * low-memory process death.
 *
 * The decision is now pure - [nextStep], [isPermanentKeyLoss], [stateAfter] - and the sequence
 * around it reaches the filesystem only through [CredentialStoreFiles], so all of it is decidable
 * on the JVM. `SecureCredentialStoreKeystoreTest` covers the real `EncryptedSharedPreferences` on
 * hardware and says in its own KDoc that it cannot provoke this path honestly; that is precisely
 * why the path has to be reachable without it.
 */
public class CredentialStoreUnlockTest {

    // ---------------------------------------------------------------- the decision table

    @Test
    public fun `the first failure is always retried, whatever it looked like`(): Unit {
        // Retrying costs one file read. Misreading a transient fault as a permanent one costs the
        // user their session, so the cheap guard runs before anything is classified.
        assertEquals(
            UnlockStep.Reopen,
            nextStep(UnlockStep.Open, permanentKeyLoss = false, mayRebuild = false),
        )
        assertEquals(
            UnlockStep.Reopen,
            nextStep(UnlockStep.Open, permanentKeyLoss = true, mayRebuild = false),
        )
        assertEquals(
            UnlockStep.Reopen,
            nextStep(UnlockStep.Open, permanentKeyLoss = true, mayRebuild = true),
        )
    }

    @Test
    public fun `a transient failure on the retry deletes nothing`(): Unit {
        // The whole bug, in one assertion. A read failure is not proof that the data is corrupt.
        assertEquals(
            UnlockStep.Fail,
            nextStep(UnlockStep.Reopen, permanentKeyLoss = false, mayRebuild = false),
        )
    }

    @Test
    public fun `a permanently invalidated key is the one failure that clears the store`(): Unit {
        assertEquals(
            UnlockStep.Rebuild,
            nextStep(UnlockStep.Reopen, permanentKeyLoss = true, mayRebuild = false),
        )
    }

    @Test
    public fun `a write the user asked for may clear a store that will not open`(): Unit {
        // The user has typed a replacement credential, so the unreadable blob is one they have
        // asked to replace. Consented loss, not silent loss - and it is what stops the Connect
        // screen being a dead end where sign-in appears to work and nothing persists.
        assertEquals(
            UnlockStep.Rebuild,
            nextStep(UnlockStep.Reopen, permanentKeyLoss = false, mayRebuild = true),
        )
    }

    @Test
    public fun `a rebuild that still will not open gives up rather than looping`(): Unit {
        assertEquals(
            UnlockStep.Fail,
            nextStep(UnlockStep.Rebuild, permanentKeyLoss = true, mayRebuild = true),
        )
        assertEquals(
            UnlockStep.Fail,
            nextStep(UnlockStep.Fail, permanentKeyLoss = true, mayRebuild = true),
        )
    }

    @Test
    public fun `each step names the state a store that opened on it is in`(): Unit {
        assertEquals(CredentialStoreState.Opened, stateAfter(UnlockStep.Open))
        assertEquals(CredentialStoreState.OpenedOnRetry, stateAfter(UnlockStep.Reopen))
        assertEquals(CredentialStoreState.Rebuilt, stateAfter(UnlockStep.Rebuild))
        assertEquals(CredentialStoreState.Locked, stateAfter(UnlockStep.Fail))
    }

    @Test
    public fun `only the two failure states report the saved session as unavailable`(): Unit {
        assertFalse(CredentialStoreState.Opened.savedSessionUnavailable)
        assertFalse(CredentialStoreState.OpenedOnRetry.savedSessionUnavailable)
        assertTrue(CredentialStoreState.Rebuilt.savedSessionUnavailable)
        assertTrue(CredentialStoreState.Locked.savedSessionUnavailable)

        // Only a locked store cannot write: a rebuilt one has a working key and an empty file.
        assertTrue(CredentialStoreState.Rebuilt.canPersist)
        assertFalse(CredentialStoreState.Locked.canPersist)
    }

    // ---------------------------------------------------------------- classifying the failure

    @Test
    public fun `an ordinary security exception is not permanent key loss`(): Unit {
        // This is the catch that used to delete. A keyset that cannot be unwrapped on this launch
        // may unwrap on the next one.
        assertFalse(isPermanentKeyLoss(GeneralSecurityException("cannot read keyset")))
    }

    @Test
    public fun `a read failure is not permanent key loss`(): Unit {
        assertFalse(isPermanentKeyLoss(IOException("EBUSY")))
        assertFalse(isPermanentKeyLoss(null))
    }

    @Test
    public fun `permanent key loss is found through the wrappers`(): Unit {
        // Tink wraps, and EncryptedSharedPreferences.create wraps what Tink throws, so the one
        // exception that decides anything is never the one caught.
        val permanent: Set<String> = setOf(FakePermanentKeyLoss::class.java.name)
        val wrapped: Throwable =
            GeneralSecurityException("keyset", IllegalStateException(FakePermanentKeyLoss()))

        assertTrue(isPermanentKeyLoss(wrapped, permanent))
        assertFalse(isPermanentKeyLoss(GeneralSecurityException("keyset"), permanent))
    }

    @Test
    public fun `the permanent set names the one framework exception that means it`(): Unit {
        assertEquals(
            setOf("android.security.keystore.KeyPermanentlyInvalidatedException"),
            PERMANENT_KEY_LOSS_TYPES,
        )
    }

    @Test
    public fun `a self-referential cause does not hang the walk`(): Unit {
        assertEquals(1, causeChain(SelfCausing()).size)

        val first = Relinkable()
        val second = Relinkable()
        first.link = second
        second.link = first
        assertEquals(2, causeChain(first).size)
    }

    @Test
    public fun `the failure line names the step and every exception in the chain`(): Unit {
        val line: String = describeFailure(
            UnlockStep.Reopen,
            GeneralSecurityException("cannot read keyset", IOException("EBUSY")),
        )

        assertTrue(line, line.contains("could not be opened on retry"))
        assertTrue(line, line.contains("java.security.GeneralSecurityException"))
        assertTrue(line, line.contains("java.io.IOException"))
        assertTrue(line, line.contains("cannot read keyset"))
    }

    @Test
    public fun `the failure line is redacted, because it reaches logcat too`(): Unit {
        // Nothing is decrypted at the point create() fails, so a message cannot hold a stored
        // value - but the line goes to a system buffer, and a rule applied only where it is
        // currently needed is a rule that stops being applied.
        val line: String = describeFailure(
            UnlockStep.Open,
            IOException("reading https://music.example.net/rest/ping?apiKey=supersecret failed"),
        )

        assertFalse(line, line.contains("supersecret"))
        assertTrue(line, line.contains("REDACTED"))
    }

    // ---------------------------------------------------------------- the sequence

    @Test
    public fun `a healthy launch opens once and touches nothing`(): Unit {
        val files = FakeCredentialFiles(failures = 0)
        val log = RecordingSink()

        val result: UnlockResult = unlock(files, log)

        assertEquals(CredentialStoreState.Opened, result.state)
        assertEquals(1, files.openAttempts)
        assertEquals(0, files.rebuilds)
        assertTrue(log.lines.toString(), log.lines.isEmpty())
    }

    @Test
    public fun `a store that opens on the retry keeps every secret it had`(): Unit {
        val files = FakeCredentialFiles(failures = 1) { GeneralSecurityException("concurrent") }
        val log = RecordingSink()

        val result: UnlockResult = unlock(files, log)

        assertEquals(CredentialStoreState.OpenedOnRetry, result.state)
        assertEquals(2, files.openAttempts)
        assertEquals(0, files.rebuilds)
        assertSame(files.preferences, result.preferences)
        // The one line that was missing. It is the evidence that the retry is doing something.
        assertTrue(log.lines.toString(), log.lines.any { it.contains("GeneralSecurityException") })
    }

    @Test
    public fun `two transient failures leave the store locked and the file untouched`(): Unit {
        val files = FakeCredentialFiles(failures = Int.MAX_VALUE) {
            GeneralSecurityException("cannot read keyset")
        }
        val log = RecordingSink()

        val result: UnlockResult = unlock(files, log)

        // The fix, stated as a test: the credentials are still on disk.
        assertEquals(CredentialStoreState.Locked, result.state)
        assertEquals(0, files.rebuilds)
        assertEquals(2, files.openAttempts)
        assertSame(EmptySharedPreferences, result.preferences)
    }

    @Test
    public fun `a read failure never reaches the delete path`(): Unit {
        val files = FakeCredentialFiles(failures = Int.MAX_VALUE) { IOException("EBUSY") }

        val result: UnlockResult = unlock(files, RecordingSink())

        assertEquals(CredentialStoreState.Locked, result.state)
        assertEquals(0, files.rebuilds)
    }

    @Test
    public fun `an unmodelled runtime failure is a failure to open, not a crash on launch`(): Unit {
        // create() documents two exceptions and can throw others out of Tink. The old catch let
        // those through, which crashed the app on every launch with no way out but reinstalling.
        val files = FakeCredentialFiles(failures = Int.MAX_VALUE) {
            IllegalStateException("half-written keyset")
        }

        val result: UnlockResult = unlock(files, RecordingSink())

        assertEquals(CredentialStoreState.Locked, result.state)
        assertEquals(0, files.rebuilds)
    }

    @Test
    public fun `an Error is rethrown rather than costing anyone their session`(): Unit {
        val files = FakeCredentialFiles(failures = Int.MAX_VALUE) { OutOfMemoryError("not a key") }

        try {
            unlock(files, RecordingSink())
            fail("an OutOfMemoryError must not be swallowed as a keystore failure")
        } catch (expected: OutOfMemoryError) {
            assertEquals("not a key", expected.message)
        }
        assertEquals(0, files.rebuilds)
    }

    @Test
    public fun `every failure is logged before anything is deleted`(): Unit {
        val files = FakeCredentialFiles(failures = 2) { FakeKeystore.permanent() }
        val log = RecordingSink()

        val result: UnlockResult = unlock(files, log, permanentTypes = FakeKeystore.types)

        assertEquals(CredentialStoreState.Rebuilt, result.state)
        assertEquals(1, files.rebuilds)
        // Two failures reported, then the line announcing the discard, then the outcome. The order
        // is the requirement: "log the exception before deleting anything".
        val discardAt: Int = log.lines.indexOfFirst { it.contains("discarding the master key") }
        assertTrue(log.lines.toString(), discardAt >= 2)
        assertEquals(2, log.lines.take(discardAt).count { it.contains("could not be opened") })
        assertTrue(log.lines.toString(), log.lines.last().contains("Rebuilt"))
    }

    @Test
    public fun `a permanently invalidated key is discarded once and the store comes back usable`(): Unit {
        val files = FakeCredentialFiles(failures = 2) { FakeKeystore.permanent() }

        val result: UnlockResult = unlock(files, RecordingSink(), permanentTypes = FakeKeystore.types)

        assertEquals(CredentialStoreState.Rebuilt, result.state)
        assertEquals(1, files.rebuilds)
        assertEquals(3, files.openAttempts)
        assertSame(files.preferences, result.preferences)
    }

    @Test
    public fun `a rebuild that does not help still leaves a usable, locked store`(): Unit {
        val files = FakeCredentialFiles(failures = Int.MAX_VALUE, rebuildHelps = false) {
            FakeKeystore.permanent()
        }

        val result: UnlockResult = unlock(files, RecordingSink(), permanentTypes = FakeKeystore.types)

        assertEquals(CredentialStoreState.Locked, result.state)
        // Exactly one rebuild, however hopeless: a loop here is an app that never starts.
        assertEquals(1, files.rebuilds)
        assertEquals(3, files.openAttempts)
    }

    // ---------------------------------------------------------------- the store over all of it

    @Test
    public fun `a locked store reads nothing and says why`(): Unit {
        val locked: SecureCredentialStore = lockedStore(files = null)

        assertNull(locked.bearerToken())
        assertNull(locked.appPassword())
        assertFalse(locked.isFullyProvisioned())
        assertFalse(locked.canPlay())
        assertEquals(CredentialStoreState.Locked, locked.state)
        assertTrue(locked.state.savedSessionUnavailable)
        // And it still does not render a secret, in the state most likely to end up in a report.
        assertFalse(locked.toString().contains("bearer"))
    }

    @Test
    public fun `the server address survives a store that will not open`(): Unit {
        // Not a secret, and the one thing that made the logged-out state indistinguishable from a
        // fresh install when it was lost with the key.
        val mirror = FakeSharedPreferences()
        mirror.edit().putString("server_url", "https://music.example.net").commit()

        val locked: SecureCredentialStore = lockedStore(files = null, mirror = mirror)

        assertEquals("https://music.example.net", locked.serverUrl()?.baseUrl)
        // Which is what makes this re-onboarding rather than first-run: a server and no secrets.
        assertTrue(locked.isReonboardingRequired())
    }

    @Test
    public fun `a locked store heals itself on the first write the user asks for`(): Unit {
        val files = FakeCredentialFiles(failures = Int.MAX_VALUE) {
            GeneralSecurityException("cannot read keyset")
        }
        val mirror = FakeSharedPreferences()
        val store: SecureCredentialStore = lockedStore(files = files, mirror = mirror)

        assertTrue(store.saveServerUrl(ServerUrl.parseOrNull("https://music.example.net")!!))

        // Two more attempts, then the consented discard, and the value lands.
        assertEquals(CredentialStoreState.Rebuilt, store.state)
        assertEquals(1, files.rebuilds)
        assertEquals("https://music.example.net", store.serverUrl()?.baseUrl)
        assertTrue(store.saveAppPassword("app-password-secret"))
        assertEquals("app-password-secret", store.appPassword())
    }

    @Test
    public fun `a locked store heals only once, not once per write`(): Unit {
        val files = FakeCredentialFiles(failures = Int.MAX_VALUE) { IOException("EBUSY") }
        val store: SecureCredentialStore = lockedStore(files = files)

        assertTrue(store.saveAppPassword("one"))
        assertTrue(store.saveAppPassword("two"))
        assertTrue(store.saveCompanionBearer("bearer", issuedAtMillis = 1L))

        assertEquals(1, files.rebuilds)
    }

    @Test
    public fun `a locked store with no file behind it refuses the write rather than losing it`(): Unit {
        // The unit-test construction, and the honest answer for it: the app-password is shown once
        // and is never re-fetchable, so a setter that returned true without persisting would make
        // the caller discard a credential neither side could then use.
        val locked: SecureCredentialStore = lockedStore(files = null)

        assertFalse(locked.saveAppPassword("app-password-secret"))
        assertNull(locked.appPassword())
    }

    @Test
    public fun `a store that opens backfills the unencrypted address for the next launch`(): Unit {
        // An install that was signed in before the mirror existed has an address only inside the
        // encrypted file. Copying it out on the first successful open is what stops the launch
        // immediately after an upgrade being the one launch that still loses it.
        val encrypted = FakeSharedPreferences()
        encrypted.edit().putString("server_url", "https://music.example.net").commit()
        val mirror = FakeSharedPreferences()

        SecureCredentialStore.createForTesting(
            preferences = encrypted,
            files = null,
            serverMirror = mirror,
            state = CredentialStoreState.Opened,
        )

        assertEquals("https://music.example.net", mirror.getString("server_url", null))
    }

    @Test
    public fun `signing out takes the unencrypted server address with it`(): Unit {
        val mirror = FakeSharedPreferences()
        val store: SecureCredentialStore = SecureCredentialStore.createForTesting(
            preferences = FakeSharedPreferences(),
            files = null,
            serverMirror = mirror,
            state = CredentialStoreState.Opened,
        )
        assertTrue(store.saveServerUrl(ServerUrl.parseOrNull("https://music.example.net")!!))
        assertEquals("https://music.example.net", mirror.getString("server_url", null))

        assertTrue(store.clear())

        // A failure must not cost the user their address; a deliberate sign-out must not leave it
        // pre-filled on a form they chose to reach.
        assertNull(mirror.getString("server_url", null))
        assertNull(store.serverUrl())
    }

    private fun lockedStore(
        files: CredentialStoreFiles?,
        mirror: SharedPreferences? = null,
    ): SecureCredentialStore = SecureCredentialStore.createForTesting(
        preferences = EmptySharedPreferences,
        files = files,
        serverMirror = mirror,
        state = CredentialStoreState.Locked,
    )
}

/**
 * The encrypted file, faked: fails [failures] times and then opens. `Int.MAX_VALUE` never opens on
 * its own, which is the interesting case.
 *
 * @param rebuildHelps whether a rebuild clears the failure. True models the ordinary device - the
 *   key and the file are gone, so the next open starts from nothing. False models a device whose
 *   Keystore is broken rather than whose key was replaced, where even starting over does not work
 *   and the app still has to launch.
 */
private class FakeCredentialFiles(
    failures: Int,
    private val rebuildHelps: Boolean = true,
    private val error: () -> Throwable = { GeneralSecurityException("fake") },
) : CredentialStoreFiles {

    val preferences: SharedPreferences = FakeSharedPreferences()

    var openAttempts: Int = 0
        private set

    var rebuilds: Int = 0
        private set

    private var failuresLeft: Int = failures

    override fun open(): SharedPreferences {
        openAttempts++
        if (failuresLeft > 0) {
            failuresLeft--
            throw error()
        }
        return preferences
    }

    override fun rebuild() {
        rebuilds++
        if (rebuildHelps) failuresLeft = 0
    }
}

/** Collects the lines, in order, because the order is part of the requirement. */
private class RecordingSink : DiagnosticsSink {

    val lines: MutableList<String> = ArrayList()

    override fun record(level: DiagnosticsLevel, message: String) {
        lines.add(message)
    }
}

/**
 * A stand-in for `KeyPermanentlyInvalidatedException`, which the JVM unit-test classpath provides
 * only as a stub whose members throw.
 *
 * [FakeKeystore.permanent] wraps one the way Tink does and registers its name, which is what
 * [isPermanentKeyLoss] matches on and why that function takes an injectable name set.
 */
private class FakePermanentKeyLoss : GeneralSecurityException("the key is gone")

private object FakeKeystore {
    val types: Set<String> = setOf(FakePermanentKeyLoss::class.java.name)
    fun permanent(): Throwable = GeneralSecurityException("keyset", FakePermanentKeyLoss())
}

/** `cause` returning `this` is legal and some wrappers do it. The walk must not care. */
private class SelfCausing : RuntimeException("loops") {
    override val cause: Throwable get() = this
}

/** Two exceptions each claiming the other as its cause. */
private class Relinkable : RuntimeException("links") {
    var link: Throwable? = null
    override val cause: Throwable? get() = link
}

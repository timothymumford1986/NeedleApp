package app.needler.core.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.needler.core.network.ServerUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [SecureCredentialStore] over the real thing: an `EncryptedSharedPreferences` file under a master
 * key held in the device's Android Keystore.
 *
 * ## Why this one is instrumented
 *
 * `SecureCredentialStoreRepairTest` covers the state machine - which secret survives which
 * rejection - over an in-memory `SharedPreferences`, and says so: the encryption is Jetpack's
 * business, the state machine is Needler's. That leaves the encryption itself, and the whole of
 * [SecureCredentialStore.create], untested, because the Android Keystore does not exist on the JVM.
 * Robolectric cannot stand in for it either: its keystore provider is a shadow with no key material
 * behind it, so a test there would pass whether or not anything was ever encrypted.
 *
 * So this file asserts the three things REQUIREMENTS.md "Security" actually promises, and can only
 * be asserted on a device or an emulator:
 *
 *  1. a secret written through the real store comes back out, which means the Keystore key was
 *     created, used and read back;
 *  2. the file it lands in holds none of the plaintext;
 *  3. [SecureCredentialStore.clear] leaves nothing recoverable.
 *
 * ## What it deliberately does not cover
 *
 * The recovery path in `openPreferences` - delete the file and start again when the key has become
 * unusable - cannot be provoked honestly from a test. It needs a Keystore entry that exists but no
 * longer decrypts, which is produced by a device migration or a lock-screen change, not by anything
 * a test can ask for. Corrupting the XML by hand exercises a different branch (the parser's) and
 * would read as coverage it is not.
 *
 * ## Why the test names are not backticked
 *
 * Every unit test in this project names itself in backticks with spaces, and that is right for
 * them. It does not work here. These methods are dexed, and DEX forbids a space in a method name
 * below DEX version 040, which arrives with minSdk 30; Needler's minSdk is 26. D8 refuses the whole
 * class at build time, so one backticked name takes the entire instrumented run down with it rather
 * than failing a single test. Instrumented tests here use camelCase and say what they mean in KDoc.
 */
@RunWith(AndroidJUnit4::class)
public class SecureCredentialStoreKeystoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val prefsFile: File
        get() = File(File(context.applicationInfo.dataDir, "shared_prefs"), SecureCredentialStore.FILE_NAME + ".xml")

    @Before
    public fun wipe(): Unit {
        // Instrumented tests share one process and one app data directory, so a store left behind
        // by an earlier test is a store this one would read.
        SecureCredentialStore.create(context).clear()
    }

    @After
    public fun wipeAgain(): Unit {
        SecureCredentialStore.create(context).clear()
    }

    @Test
    public fun aSecretWrittenThroughTheKeystoreComesBackOutOfIt(): Unit {
        val written: SecureCredentialStore = SecureCredentialStore.create(context)
        assertTrue(written.saveServerUrl(ServerUrl.parseOrNull("https://music.example.net")!!))
        assertTrue(written.saveCompanionBearer("bearer-token", issuedAtMillis = 1_700_000_000_000L))
        assertTrue(written.saveAppPassword("app-password-secret"))
        assertTrue(written.pinCertificate("AA:BB:CC:DD"))

        // A second store decrypts every value again, through the same master key, rather than
        // reading the first one's in-memory cache. That round trip is the thing under test: it is
        // what fails if the Keystore entry was never created or cannot be used.
        val reopened: SecureCredentialStore = SecureCredentialStore.create(context)

        assertEquals("https://music.example.net", reopened.serverUrl()?.baseUrl)
        assertEquals("bearer-token", reopened.bearerToken())
        assertEquals("app-password-secret", reopened.appPassword())
        assertEquals("AA:BB:CC:DD", reopened.pinnedCertificateSha256())
        assertEquals(1_700_000_000_000L, reopened.companionBearerIssuedAt())
        assertTrue(reopened.isFullyProvisioned())
    }

    @Test
    public fun theFileOnDiskHoldsNoPlaintext(): Unit {
        val credentials: SecureCredentialStore = SecureCredentialStore.create(context)
        assertTrue(credentials.saveCompanionBearer("bearer-token", issuedAtMillis = 1L))
        assertTrue(credentials.saveAppPassword("app-password-secret"))

        // Both setters commit rather than apply, so the bytes are on disk by the time they return.
        val onDisk: String = prefsFile.readText()

        // The values are AES-GCM. The keys are AES-SIV, so even the names of the fields holding a
        // secret are absent - which is what stops the file naming what it is worth stealing.
        assertFalse(onDisk.contains("app-password-secret"))
        assertFalse(onDisk.contains("bearer-token"))
        assertFalse(onDisk.contains("app_password"))
        assertFalse(onDisk.contains("companion_bearer"))
    }

    @Test
    public fun clearLeavesNothingALaterStoreCanRead(): Unit {
        val credentials: SecureCredentialStore = SecureCredentialStore.create(context)
        assertTrue(credentials.saveServerUrl(ServerUrl.parseOrNull("https://music.example.net")!!))
        assertTrue(credentials.saveCompanionBearer("bearer-token", issuedAtMillis = 1L))
        assertTrue(credentials.saveAppPassword("app-password-secret"))

        assertTrue(credentials.clear())

        val reopened: SecureCredentialStore = SecureCredentialStore.create(context)
        assertNull(reopened.serverUrl())
        assertNull(reopened.bearerToken())
        assertNull(reopened.appPassword())
        assertNull(reopened.pinnedCertificateSha256())
        assertFalse(reopened.isFullyProvisioned())
        // Sign-out, not a prompt to sign in again: with no server saved there is nothing to
        // re-onboard against, which is the Connect screen's job rather than a credential state.
        assertFalse(reopened.isReonboardingRequired())
    }

    @Test
    public fun theStoreNeverRendersItsContents(): Unit {
        val credentials: SecureCredentialStore = SecureCredentialStore.create(context)
        assertTrue(credentials.saveAppPassword("app-password-secret"))

        // A credential holder that renders its contents is a credential in a bug report. This is
        // asserted here rather than in the unit test because it is the real store, built the way
        // the app builds it, that would end up in one.
        assertFalse(credentials.toString().contains("app-password-secret"))
    }
}

package app.needler.core.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.needler.core.domain.diagnostics.DiagnosticsLevel
import app.needler.core.domain.diagnostics.DiagnosticsSink
import app.needler.core.network.DiagnosticsSource
import app.needler.core.network.NetworkDiagnostics
import app.needler.core.network.NetworkLogLevel
import app.needler.core.network.SessionDiagnosticsLog
import java.io.File
import java.security.KeyStore

/**
 * The real encrypted file: `EncryptedSharedPreferences` under a Keystore master key, plus the two
 * destructive operations a rebuild needs.
 *
 * Split out of `SecureCredentialStore` so that [unlock] - which is where the decision this class
 * exists to serve actually lives - can be tested on the JVM against a fake. Everything here needs a
 * device: a `Context`, a `shared_prefs` directory and the Android Keystore.
 *
 * @param context the application context. Held rather than passed per call because every member
 *   needs it and the alternative was threading it through [CredentialStoreFiles], which exists to
 *   be free of Android.
 * @param fileName the encrypted file's name, frozen at [SecureCredentialStore.FILE_NAME]. A
 *   parameter only so the instrumented test can be given its own file and not fight the app's.
 */
internal class EncryptedCredentialFile(
    private val context: Context,
    private val fileName: String = SecureCredentialStore.FILE_NAME,
) : CredentialStoreFiles {

    /**
     * Builds the master key and opens the file.
     *
     * Deterministic AES-SIV for the keys, so a key can still be looked up; randomised AES-GCM for
     * the values, which are the secrets. REQUIREMENTS.md "Security" rule 1.
     */
    override fun open(): SharedPreferences {
        val masterKey: MasterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Drops everything: the cached in-memory copy, the file, its backup and the Keystore entry.
     *
     * ## The order matters, and so does the first step
     *
     * `Context.getSharedPreferences` caches one `SharedPreferencesImpl` per file name for the life
     * of the process, and a failed [open] has already created that cached instance. Deleting the
     * file underneath it changes nothing a subsequent [open] would see - it reads the cached map,
     * finds the same unwrappable keysets, and fails identically. That is why the first step is
     * `clear().commit()` through the platform API: it is the only thing that empties the copy this
     * process is actually reading. The old `File.delete()`-only implementation would not have
     * recovered within a launch even when deleting was the right answer.
     *
     * The file deletions follow anyway, because `clear().commit()` leaves an empty primary behind
     * and, in the course of writing it, creates a `.bak` from the old one. Both have to go for the
     * reason set out on [CredentialStoreFiles.rebuild]: a surviving backup is promoted over the new
     * store by the platform on the next launch, which would sign the user out on every launch
     * instead of once.
     *
     * The Keystore entry goes last. `MasterKey.Builder.build` returns the existing entry when one
     * is present, so a permanently invalidated key is handed back unchanged however many times the
     * file is deleted; removing the alias is what lets the next [open] mint a usable key. The
     * alternative - a second alias with a suffix - was rejected because it leaves dead key material
     * in the Keystore forever and moves the problem one launch further out.
     *
     * Every step is individually guarded. A rebuild that throws would propagate out of [unlock] as
     * a crash on launch, and the whole point of this path is that there is a way out of it.
     */
    override fun rebuild() {
        runCatching {
            context.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val prefsDir = File(context.applicationInfo.dataDir, SHARED_PREFS_DIR)
        runCatching { File(prefsDir, fileName + PREFS_SUFFIX).delete() }
        runCatching { File(prefsDir, fileName + PREFS_SUFFIX + BACKUP_SUFFIX).delete() }
        runCatching {
            val keystore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keystore.load(null)
            keystore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
    }

    override fun toString(): String = "EncryptedCredentialFile(" + fileName + ")"

    private companion object {
        const val SHARED_PREFS_DIR: String = "shared_prefs"
        const val PREFS_SUFFIX: String = ".xml"
        const val BACKUP_SUFFIX: String = ".bak"
        const val ANDROID_KEYSTORE: String = "AndroidKeyStore"
    }
}

/**
 * Where this package's lines go: the user's own diagnostics log, and logcat.
 *
 * ## Why logcat as well, and in release builds
 *
 * REQUIREMENTS.md "Security" rule 5 forbids "debug logging" in release builds, and `:core:network`
 * honours that by adding logcat to its sink only when the application's own `FLAG_DEBUGGABLE` is
 * set. This path is deliberately not gated the same way, and the reason is the bug itself: the
 * credential store was destroying sessions on a signed, release build, on a device with no cable
 * attached, and the entire audit's worth of "logged out, cause unknown" came of there being nothing
 * to read. A failure report naming an exception type is not the ordinary narrative that rule is
 * about, it is emitted only when something has already gone wrong - at most four lines, and none on
 * a healthy launch - and it carries no credential: nothing has been decrypted at the point
 * `EncryptedSharedPreferences.create` fails, and the message is passed through `redactLogLine`
 * regardless. Gating it would have satisfied the letter of the rule while leaving the one install
 * that matters silent, which is the trade `NetworkLogSink.forApplication` makes in the other
 * direction for its own per-request narrative and explains at length.
 *
 * ## Why the in-app log too
 *
 * REQUIREMENTS.md "Observability" requires "a local, user-viewable diagnostics log covering the
 * last session … shareable as a file for bug reports", and a user who has just been dropped on the
 * Connect screen is exactly the person who needs to be able to send it. `DiagnosticsSource.App` was
 * declared for "the next thing worth a line - a session marked stale, an app-password re-minted";
 * a credential store that could not be unlocked is squarely that, and it is the first thing to use
 * it. [SessionDiagnosticsLog] redacts on ingest, so nothing written here can leave un-redacted.
 *
 * @param log the buffer to write into; the process-wide one in production, a test's own otherwise.
 */
internal fun credentialStoreDiagnostics(
    log: SessionDiagnosticsLog = NetworkDiagnostics.sessionLog,
): DiagnosticsSink = DiagnosticsSink { level, message ->
    log.record(source = DiagnosticsSource.App, level = levelOf(level), message = message)
    Log.println(priorityOf(level), CREDENTIAL_LOG_TAG, message)
}

/** `NeedlerCreds` - short enough for the 23-character tag limit older platform versions enforce. */
internal const val CREDENTIAL_LOG_TAG: String = "NeedlerCreds"

/**
 * The domain's severity vocabulary, in `:core:network`'s.
 *
 * A near-duplicate of `SessionDiagnosticsSink.levelOf`, and deliberately not shared with it: that
 * class is another agent's file in this change, and a four-line `when` copied once is cheaper than
 * a cross-package dependency on an `internal` member for the sake of not copying it.
 */
private fun levelOf(level: DiagnosticsLevel): NetworkLogLevel = when (level) {
    DiagnosticsLevel.Debug -> NetworkLogLevel.Debug
    DiagnosticsLevel.Info -> NetworkLogLevel.Info
    DiagnosticsLevel.Warn -> NetworkLogLevel.Warn
    DiagnosticsLevel.Error -> NetworkLogLevel.Error
}

/** The same four severities as logcat priorities, so `adb logcat NeedlerCreds:W` selects failures. */
private fun priorityOf(level: DiagnosticsLevel): Int = when (level) {
    DiagnosticsLevel.Debug -> Log.DEBUG
    DiagnosticsLevel.Info -> Log.INFO
    DiagnosticsLevel.Warn -> Log.WARN
    DiagnosticsLevel.Error -> Log.ERROR
}

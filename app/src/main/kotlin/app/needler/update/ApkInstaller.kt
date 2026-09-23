package app.needler.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Installs a downloaded APK over this app, using the [PackageInstaller] session API.
 *
 * ## The rule this class exists to enforce: this is an update, never a reinstall
 *
 * Everything the listener has is preserved for free, and the reason is worth stating precisely
 * because it is also the reason one particular mistake would be unrecoverable.
 *
 * Android keeps an app's data directory across an *update* — same `applicationId`, same signing
 * certificate, a higher `versionCode`. All three hold here by construction: the package is
 * `app.needler` in every build, `.github/workflows/release.yml` signs every release with the one
 * keystore held in repository secrets, and the workflow derives an increasing `versionCode` from
 * the tag. So the Room mirror, the downloaded audio, the settings and the credentials all survive,
 * and **no code in this package has to do anything to make that true**. There is deliberately no
 * backup step, no export, no migration: the correct amount of work for data preservation on an
 * update path is none, and anything else would be a second copy of the listener's secrets written
 * somewhere less safe than where they already are.
 *
 * What would destroy all of it is an **uninstall**. `SecureCredentialStore` keeps the companion
 * bearer and the app-password in `EncryptedSharedPreferences` under an Android Keystore master key,
 * and the platform destroys that key when the package is uninstalled. The ciphertext might survive
 * on disk somewhere; it would be permanently undecryptable. Alongside it go the mirror and every
 * downloaded album — which REQUIREMENTS.md already flags as the consequence of losing the signing
 * key: "no future build can install over an existing one and every user has to uninstall first,
 * losing their settings and their downloaded music."
 *
 * Therefore:
 *
 *  * This class never calls `PackageInstaller.uninstall`, and never constructs
 *    `Intent.ACTION_DELETE` or `ACTION_UNINSTALL_PACKAGE`. Grep for it — it is not here.
 *  * A failed install is **never** followed by a suggestion to uninstall and try again. The two
 *    failures a listener could plausibly meet are a signature mismatch (an APK not built by this
 *    project's release workflow) and a downgrade; the honest answer to both is to leave the working
 *    install exactly as it is. An install that fails costs nothing. An uninstall that "fixes" it
 *    costs a re-sign-in, a full re-sync and a multi-gigabyte re-download, and there is no way back.
 *  * `SessionParams.MODE_FULL_INSTALL` — a whole APK replacing a whole APK. Never
 *    `MODE_INHERIT_EXISTING`, which is for splits and is not what a GitHub release asset is.
 *
 * ## Why `PackageInstaller` and not a `FileProvider` plus `ACTION_VIEW`
 *
 * The old sideload dance — expose the APK through a `FileProvider`, fire `ACTION_VIEW` with
 * `application/vnd.android.package-archive`, hope something handles it — needs a `<provider>` in
 * the manifest, a shared-path XML resource, a `grantUriPermission`, and hands the file to whichever
 * package the system picks. It also reports nothing: the app learns the result only by noticing it
 * has been restarted. The session API keeps the bytes inside a session only this app can write to,
 * reports every outcome through [status], and is the API the platform actually maintains.
 *
 * ## Install-unknown-apps
 *
 * From Android 8 the permission is per-source and granted by the listener, not by the manifest.
 * `REQUEST_INSTALL_PACKAGES` in the manifest only makes Needler *eligible* to ask;
 * [canInstallPackages] is whether the listener has said yes. When they have not,
 * [unknownSourcesSettingsIntent] sends them to the one Settings page for this package — not the
 * global list, which would leave them hunting for Needler among everything installed.
 *
 * ## Status, and the process that stops existing
 *
 * `commit` is asynchronous and reports through an [IntentSender], which is why
 * [UpdateInstallReceiver] exists. The interesting value is `STATUS_PENDING_USER_ACTION`: the
 * platform is asking for the confirmation dialogue to be shown, and the [Intent] to show it arrives
 * in the broadcast. On success the app is replaced and this process is killed, so `STATUS_SUCCESS`
 * is frequently never observed by anyone — which is correct, and is why nothing downstream waits
 * for it.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val mutableStatus = MutableStateFlow<InstallStatus>(InstallStatus.Idle)

    /** The live install state, folded into the banner's state by [UpdateRepository]. */
    val status: StateFlow<InstallStatus> = mutableStatus.asStateFlow()

    /**
     * Has the listener allowed Needler to install packages?
     *
     * `canRequestPackageInstalls()` arrived in API 26, which is this project's minSdk, so there is
     * no branch below it. It is re-read every time rather than cached: the listener can revoke it
     * in Settings while the app is in the background, and a cached `true` would turn that into a
     * silent failure at commit time.
     */
    fun canInstallPackages(): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /**
     * The Settings page that grants it, scoped to this package.
     *
     * `ACTION_MANAGE_UNKNOWN_APP_SOURCES` with a `package:` URI opens the toggle for Needler alone.
     * The caller is expected to launch this from an activity result contract so that returning from
     * Settings can re-check and carry on, rather than leaving the listener to work out that they
     * now have to press the banner again.
     */
    fun unknownSourcesSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:" + context.packageName),
    )

    /** Put the state machine back to its resting position, before a retry or after a dismissal. */
    fun reset() {
        mutableStatus.value = InstallStatus.Idle
    }

    /**
     * Say that nothing can proceed until install-unknown-apps is granted.
     *
     * Called by [UpdateRepository] *before* the download rather than after it, so a listener who
     * has not granted the permission is asked for it while they are still looking at the banner,
     * instead of paying for a few megabytes on mobile data and then being asked.
     */
    fun requirePermission() {
        mutableStatus.value = InstallStatus.PermissionRequired
    }

    /**
     * Stage [apk] into a session and commit it.
     *
     * Returns once the session is committed, which is long before the install has happened: from
     * here on the platform drives, and the outcome arrives on [status] by way of
     * [UpdateInstallReceiver].
     */
    suspend fun install(apk: File): Unit = withContext(Dispatchers.IO) {
        if (!canInstallPackages()) {
            mutableStatus.value = InstallStatus.PermissionRequired
            return@withContext
        }
        if (!apk.isFile || apk.length() <= 0L) {
            mutableStatus.value = InstallStatus.Failed(GENERIC_FAILURE)
            return@withContext
        }

        mutableStatus.value = InstallStatus.Staging
        val packageInstaller = context.packageManager.packageInstaller
        var sessionId = NO_SESSION

        try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            // Naming the package lets the platform reject a session holding something else before
            // any bytes are written, rather than at commit. It is the same package as ours, which
            // is the only package this app is ever allowed to install.
            params.setAppPackageName(context.packageName)
            params.setSize(apk.length())

            sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite(WRITE_NAME, 0L, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output, BUFFER_BYTES) }
                    // Without fsync the session can be committed over bytes still in a buffer, and
                    // the install fails with a corrupt-APK error that reads like a bad download.
                    session.fsync(output)
                }
                mutableStatus.value = InstallStatus.Committing
                session.commit(statusIntentSender(sessionId))
            }
        } catch (failure: IOException) {
            abandon(sessionId)
            mutableStatus.value = InstallStatus.Failed(GENERIC_FAILURE)
        } catch (failure: SecurityException) {
            // The permission was revoked between the check above and the session being created.
            abandon(sessionId)
            mutableStatus.value = InstallStatus.PermissionRequired
        }
    }

    /**
     * Handle one status broadcast from the platform. Called only by [UpdateInstallReceiver].
     *
     * [broadcastContext] rather than the injected application context because the confirmation
     * activity is started from whatever context the broadcast arrived on, which is what the
     * platform's temporary background-launch allowance is granted against.
     *
     * `UnsafeIntentLaunch` is suppressed deliberately. The lint check is about launching an intent
     * that arrived from another app; this one cannot have. [UpdateInstallReceiver] is
     * `android:exported="false"` with no intent filter, and the only thing that can address it is
     * the explicit [PendingIntent] created in [statusIntentSender], so the `EXTRA_INTENT` being
     * launched here is the platform's own install-confirmation intent and nothing else.
     */
    @Suppress("UnsafeIntentLaunch")
    fun onStatusBroadcast(broadcastContext: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_INTENT,
                    Intent::class.java,
                )
                if (confirmation == null) {
                    abandon(sessionId)
                    mutableStatus.value = InstallStatus.Failed(GENERIC_FAILURE)
                    return
                }
                mutableStatus.value = InstallStatus.AwaitingConfirmation
                // NEW_TASK because a BroadcastReceiver has no task of its own. The platform grants
                // a short background-activity-launch allowance when it sends this, which is what
                // makes the dialogue appear even if the listener has just left the app.
                runCatching {
                    broadcastContext.startActivity(
                        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure {
                    abandon(sessionId)
                    mutableStatus.value = InstallStatus.Failed(GENERIC_FAILURE)
                }
            }

            // Rarely seen: by the time the platform can say this, this process has usually already
            // been replaced by the new one. Handled anyway, because "usually" is not "always".
            PackageInstaller.STATUS_SUCCESS -> {
                mutableStatus.value = InstallStatus.Succeeded
            }

            // The listener pressed Cancel on the platform's dialogue. Not a failure, and not
            // something to report as one: the banner simply goes back to offering the update.
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                mutableStatus.value = InstallStatus.Cancelled
            }

            else -> {
                // The platform's own message is deliberately not shown. It is untranslated,
                // developer-facing ("INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package app.needler
                // signatures do not match previously installed version") and the one thing it must
                // not do is send a listener looking for a way to uninstall first.
                mutableStatus.value = InstallStatus.Failed(GENERIC_FAILURE)
            }
        }
    }

    /**
     * The [IntentSender] the platform reports through.
     *
     * An **explicit** intent naming [UpdateInstallReceiver], so the broadcast can only ever be
     * delivered to that class in this app. That is what lets the receiver be declared
     * `android:exported="false"` in the manifest with no action filter at all: nothing outside the
     * app can address it, and the platform sends this under our own identity because we created the
     * [PendingIntent].
     *
     * `FLAG_MUTABLE` is required from API 31 because the platform fills in the status extras. The
     * constant itself only exists from 31, hence the branch; below it a pending intent is mutable
     * by default and no flag is needed.
     *
     * The session id is the request code, so two overlapping sessions cannot collide on one pending
     * intent and report each other's outcomes.
     */
    private fun statusIntentSender(sessionId: Int): IntentSender {
        val intent = Intent(context, UpdateInstallReceiver::class.java)
        val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag,
        ).intentSender
    }

    /**
     * Abandon a session so its staged bytes are released.
     *
     * This discards a *pending install*, which has nothing in common with an uninstall: the app on
     * the device is untouched, and so is everything in its data directory.
     */
    private fun abandon(sessionId: Int) {
        if (sessionId == NO_SESSION) return
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
    }

    private companion object {
        const val NO_SESSION = -1
        const val WRITE_NAME = "needler-update"
        const val BUFFER_BYTES = 64 * 1024

        /**
         * One message for every failure.
         *
         * Not laziness: there is exactly one thing a listener can usefully do about any of them,
         * which is try again later, and the alternative — surfacing the platform's own string —
         * would put "signatures do not match" in front of someone whose next search result is an
         * instruction to uninstall first. That is the one action this package must never encourage.
         */
        const val GENERIC_FAILURE = "Update could not be installed"
    }
}

/**
 * Where an install has got to.
 *
 * Distinct from [UpdateState] because the platform owns this half and the app owns the other; they
 * are combined in [UpdateRepository] rather than one pretending to be the other.
 */
sealed interface InstallStatus {

    /** Nothing in flight. */
    data object Idle : InstallStatus

    /** The listener has not allowed installs from Needler, so nothing can proceed until they do. */
    data object PermissionRequired : InstallStatus

    /** Copying the APK into the session. */
    data object Staging : InstallStatus

    /** Committed; the platform has it now. */
    data object Committing : InstallStatus

    /** The platform's confirmation dialogue is up. */
    data object AwaitingConfirmation : InstallStatus

    /** The listener declined at the dialogue. Returns the banner to its offer, not to an error. */
    data object Cancelled : InstallStatus

    /** Observed only when the process outlives the swap, which it usually does not. */
    data object Succeeded : InstallStatus

    /** Everything else, behind one deliberately uninformative message. */
    data class Failed(val message: String) : InstallStatus
}

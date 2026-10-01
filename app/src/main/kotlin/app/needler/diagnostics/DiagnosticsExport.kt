package app.needler.diagnostics

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

/**
 * Turning the in-memory log into the one file REQUIREMENTS.md "Observability" requires: "It must be
 * shareable as a file for bug reports, and it must never leave the device automatically."
 *
 * ## Where the file goes, and why there is only one of it
 *
 * `<cacheDir>/diagnostics/needler-diagnostics.txt`, and nowhere else. That directory is the only thing
 * `app/src/main/res/xml/diagnostics_paths.xml` exposes through the `FileProvider`, and that file says
 * why it is narrowed rather than rooted at the cache: "The audio store is app-private too, and a
 * provider rooted at the cache would hand any consumer that asked a way to read cached music by
 * guessing a path."
 *
 * The name is fixed rather than timestamped, deliberately. A timestamped name would leave one file per
 * share in a directory nothing prunes, each one a full copy of a session's request log, which is
 * exactly the growing-on-disk record that [app.needler.core.network.SessionDiagnosticsLog] exists to
 * avoid. One name means each share overwrites the last and the directory holds at most one file. The
 * cost is that a user cannot keep two exports side by side, which is what a bug tracker is for.
 *
 * `cacheDir` rather than `filesDir` for the reason `ApkDownloader` gives for the same choice: the
 * system may reclaim it under storage pressure, which for a file whose only purpose is to exist for
 * the four seconds between a tap and a share sheet is a feature.
 *
 * ## What the URI grant actually permits
 *
 * A read on one file, to one app, until the task that received it finishes.
 * [DIAGNOSTICS_SHARE_FLAGS] is `FLAG_GRANT_READ_URI_PERMISSION` and nothing else: no write, no
 * persistence, no directory. The provider itself is `android:exported="false"` with
 * `grantUriPermissions="true"`, so nothing can read this file without the user having handed it over
 * through a chooser.
 */
internal object DiagnosticsExport {

    /** The sole directory `res/xml/diagnostics_paths.xml` exposes. Must match it exactly. */
    const val DIRECTORY_NAME: String = "diagnostics"

    /** One file, overwritten on every share. See this object's documentation. */
    const val FILE_NAME: String = "needler-diagnostics.txt"

    /**
     * The authority declared in `AndroidManifest.xml` as `${applicationId}.diagnostics`.
     *
     * Built from the running package name rather than hard-coded, because `applicationId` is a build
     * value and a hard-coded authority is a crash on the first build that changes it.
     */
    fun authority(context: Context): String = context.packageName + ".diagnostics"

    /** `text/plain`. The file is a log, and every mail client and issue tracker accepts one. */
    const val MIME_TYPE: String = "text/plain"

    /**
     * The only URI permission the share grants: read, on one file, to whoever the user chose.
     *
     * Written out rather than inlined so that a future change to it is a change to a documented
     * constant rather than a flag added to an intent somewhere.
     */
    val DIAGNOSTICS_SHARE_FLAGS: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION

    /**
     * Writes [text] to the export file, replacing whatever was there, and returns it.
     *
     * Blocking. Called from a background dispatcher by [DiagnosticsViewModel]; the log is at most a
     * few hundred kilobytes, but it is still a file write and the main thread is drawing a list.
     */
    fun write(context: Context, text: String): File {
        val directory = File(context.cacheDir, DIRECTORY_NAME)
        if (!directory.exists()) directory.mkdirs()
        val file = File(directory, FILE_NAME)
        file.writeText(text)
        return file
    }

    /** A `content://` URI for [file], from the app's own narrowed `FileProvider`. */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /**
     * The chooser intent.
     *
     * `ACTION_SEND` with a chooser rather than a resolved target, because the user decides where a
     * bug report goes. `EXTRA_SUBJECT` and `EXTRA_TEXT` are filled in so that a mail client opens with
     * something in it: an attachment with an empty body is an email most people will not send.
     */
    fun chooserIntent(uri: Uri, subject: String, body: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(DIAGNOSTICS_SHARE_FLAGS)
        }
        return Intent.createChooser(send, CHOOSER_TITLE).apply {
            // The chooser forwards the grant to whichever target the user picks, and it needs the
            // flag itself to be allowed to.
            addFlags(DIAGNOSTICS_SHARE_FLAGS)
        }
    }

    /**
     * Deletes the export, if one has been written.
     *
     * Called when the user clears the log, so that "cleared" means cleared: leaving a written export
     * behind after the buffer it came from has been emptied would keep the very record the user just
     * asked to be rid of, in the one place on this device that another app can be granted a read on.
     */
    fun deleteExport(context: Context): Boolean {
        val file = File(File(context.cacheDir, DIRECTORY_NAME), FILE_NAME)
        return if (file.exists()) file.delete() else true
    }

    /**
     * The block of context printed above the log in the exported file.
     *
     * Pure, and separated from every `Build` and `PackageManager` read, so a test can assert on what
     * it says without a device. What goes in it is the set of questions a maintainer asks first on
     * every bug report — which build, which Android, which phone — and nothing else. In particular
     * there is no server address, no username and no library content: the header is the one part of
     * this file a person writes by hand, and it is the easiest place to add a leak by accident.
     *
     * @param exportedAt an ISO-8601 instant in UTC. The one absolute time in the file; every log line
     *   below carries a time of day only, so this is what anchors them to a date.
     */
    fun header(
        versionName: String,
        versionCode: Long,
        androidRelease: String,
        sdkInt: Int,
        manufacturer: String,
        model: String,
        exportedAt: String,
    ): List<String> = listOf(
        "Exported " + exportedAt,
        "Needler " + versionName.ifBlank { "unknown" } + " (" + versionCode + ")",
        "Android " + androidRelease + " (API " + sdkInt + ")",
        manufacturer + " " + model,
    )

    /**
     * [header], filled in from the running device. Not pure, and does nothing else.
     *
     * The version code goes through [PackageInfoCompat.getLongVersionCode], which reads
     * `longVersionCode` where the platform has it and the deprecated 32-bit field below it, so one
     * call covers minSdk 26 upwards with no `DEPRECATION` suppression of its own. That is the same
     * call `InstalledVersion` in the update package makes, for the same reason.
     */
    fun headerForDevice(context: Context, exportedAt: String): List<String> {
        val info: PackageInfo? = readPackageInfo(context)
        val versionCode: Long = if (info == null) 0L else PackageInfoCompat.getLongVersionCode(info)
        return header(
            versionName = info?.versionName.orEmpty(),
            versionCode = versionCode,
            androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" },
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "unknown" },
            model = Build.MODEL.orEmpty().ifBlank { "unknown" },
            exportedAt = exportedAt,
        )
    }

    /**
     * The installed version, or null.
     *
     * The same read `SettingsViewModel` does, and the same reasoning: `buildConfig` is not enabled in
     * this project so there is no `BuildConfig.VERSION_NAME`, and the package manager reports what was
     * actually installed, which is the only thing a bug report can be matched against. A failure here
     * must not cost the user their diagnostics log, so it renders as "unknown".
     */
    @Suppress("DEPRECATION")
    private fun readPackageInfo(context: Context): PackageInfo? = try {
        // The single-argument overload is deprecated from API 33 but correct on every level this app
        // supports (minSdk 26), and a version branch here would be two code paths for one string.
        context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (error: PackageManager.NameNotFoundException) {
        null
    }

    private const val CHOOSER_TITLE: String = "Share the diagnostics log"
}

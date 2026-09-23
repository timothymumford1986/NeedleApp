package app.needler.update

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What is installed right now, read from the platform.
 *
 * ## Why not `BuildConfig`
 *
 * `buildConfig` is not enabled for `:app` — nothing in the project turns it on — so there is no
 * `BuildConfig.VERSION_CODE` or `BuildConfig.VERSION_NAME` to import, and adding the feature just
 * to learn a number the platform already holds would be the wrong trade. `PackageManager` is also
 * the more truthful source: it reports what is *installed*, which is what the package installer
 * will compare a candidate against, rather than what the build that produced this code was told to
 * call itself.
 *
 * `versionCode` and `versionName` come from `defaultConfig` in `app/build.gradle.kts`, which reads
 * them from the `needler.versionCode` / `needler.versionName` Gradle properties that the release
 * workflow derives from the tag. A local build keeps the defaults, `1` and `0.0.0-dev`, which is
 * why a developer build will cheerfully believe every release is newer than it — correct, and
 * harmless, since installing one over a local debug build fails on the signature anyway.
 *
 * ## Failing safe
 *
 * If the package manager cannot answer — which should be impossible, since the package asking is
 * the package being asked about — [versionCode] returns [Long.MAX_VALUE] rather than zero. The
 * comparison in [UpdateCheckPolicy] is "is the candidate strictly greater", so the effect of not
 * knowing is that no update is ever offered. Silence is the correct failure here; guessing low
 * would offer an update on every launch forever.
 */
@Singleton
class InstalledVersion @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * The installed `versionCode`, as the 64-bit value the platform has used since API 28.
     *
     * [PackageInfoCompat.getLongVersionCode] reads `longVersionCode` where it exists and the
     * deprecated 32-bit `versionCode` below it, so one call covers every API level this app
     * supports from minSdk 26 upwards.
     */
    fun versionCode(): Long = packageInfoVersionCode ?: Long.MAX_VALUE

    /** The installed `versionName`, e.g. `1.2.3`, or an empty string if it cannot be read. */
    fun versionName(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private val packageInfoVersionCode: Long?
        get() = runCatching {
            // The two-argument overload is deprecated from API 33 in favour of the PackageInfoFlags
            // one, which does not exist below 33. Suppressed rather than branched: the deprecated
            // call still works on every level this app supports, and a version-gated pair of
            // branches for an identical result is more code to get wrong.
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info)
        }.getOrNull()
}

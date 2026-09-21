package app.needler

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The manifest half of the WorkManager wiring, asserted against the **merged** manifest.
 *
 * Both of these are silent when they are wrong, which is the only reason they are worth a test.
 *
 * A `WorkManagerInitializer` left in place initialises WorkManager at content-provider time with
 * its default worker factory, which cannot construct a `@HiltWorker`. Every job then fails at
 * start, on a background thread, with nothing in the app reporting it - the app looks perfectly
 * healthy and simply never polls, never syncs and never downloads.
 *
 * A missing `POST_NOTIFICATIONS` declaration is worse, because the runtime request itself fails
 * silently on Android 13: the dialog never appears, the permission is never granted, and no
 * notification this app posts is ever seen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackgroundManifestTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `WorkManager's default initializer is removed`() {
        val provider: ProviderInfo? = runCatching {
            context.packageManager.getProviderInfo(
                ComponentName(context.packageName, "androidx.startup.InitializationProvider"),
                PackageManager.GET_META_DATA,
            )
        }.getOrNull()

        // The provider itself may legitimately be absent when no other library contributes an
        // initializer. What must never be true is that it is present *and* still names
        // WorkManager's.
        val stillInitialisesWorkManager: Boolean =
            provider?.metaData?.containsKey("androidx.work.WorkManagerInitializer") == true

        assertFalse(
            "WorkManager would initialise itself with a factory that cannot build a @HiltWorker, " +
                "and every background job would fail to start with nothing saying why",
            stillInitialisesWorkManager,
        )
    }

    @Test
    fun `the application supplies a WorkManager configuration`() {
        // The other half of the same change. Removing the initializer without this would leave
        // WorkManager uninitialised at runtime rather than misconfigured, which fails louder but
        // just as completely.
        assertTrue(
            "NeedlerApplication must implement Configuration.Provider so on-demand initialisation " +
                "finds the HiltWorkerFactory",
            Configuration.Provider::class.java.isAssignableFrom(NeedlerApplication::class.java),
        )
    }

    @Test
    fun `the notification permission is declared`() {
        val declared: List<String> = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()

        assertTrue(
            "without the declaration the runtime request fails silently on Android 13",
            declared.contains("android.permission.POST_NOTIFICATIONS"),
        )
    }
}

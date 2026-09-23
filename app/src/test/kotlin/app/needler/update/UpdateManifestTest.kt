package app.needler.update

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The manifest half of the update mechanism, asserted against the **merged** manifest.
 *
 * Here for the same reason `BackgroundManifestTest` is: both of these are silent when they are
 * wrong, and a mechanism that fails silently is a mechanism nobody finds out about until it has
 * been broken for three releases.
 *
 * Without `REQUEST_INSTALL_PACKAGES`, `canRequestPackageInstalls()` returns `false` forever, there
 * is no error and no log line, and the banner simply asks the listener to grant a permission that
 * the Settings page it sends them to will not offer — because the app never declared it could want
 * it. Without the receiver, `PackageInstaller` has nowhere to report to, so
 * `STATUS_PENDING_USER_ACTION` is never handled, the platform's confirmation dialogue never
 * appears, and the install waits for a confirmation nobody was asked for.
 *
 * The receiver's `exported="false"` is asserted too. It has no intent filter and is addressed only
 * by an explicit `PendingIntent` from [ApkInstaller], so exporting it would grant every app on the
 * device the ability to fabricate an install result for this one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class UpdateManifestTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the app may ask to install packages`() {
        val declared: List<String> = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()

        assertTrue(
            "REQUEST_INSTALL_PACKAGES is not declared, so canRequestPackageInstalls() can never " +
                "return true and the in-app update can never install anything",
            declared.contains("android.permission.REQUEST_INSTALL_PACKAGES"),
        )
    }

    @Test
    fun `the install status receiver exists and is not exported`() {
        val receiver: ActivityInfo? = runCatching {
            context.packageManager.getReceiverInfo(
                ComponentName(context.packageName, RECEIVER_CLASS),
                0,
            )
        }.getOrNull()

        assertNotNull(
            "PackageInstaller has nowhere to report to, so the confirmation dialogue is never " +
                "shown and no install outcome is ever observed",
            receiver,
        )
        assertFalse(
            "an exported install-status receiver lets any app on the device fabricate an " +
                "install result for this one",
            receiver?.exported == true,
        )
    }

    private companion object {
        const val RECEIVER_CLASS = "app.needler.update.UpdateInstallReceiver"
    }
}

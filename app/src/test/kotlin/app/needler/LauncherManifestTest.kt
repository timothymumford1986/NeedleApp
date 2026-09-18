package app.needler

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app can be opened.
 *
 * This looks trivial and is not. Before this work `:app` held an `Application`
 * class and no activity at all, so the APK installed and then sat on the home
 * screen with no way in - and nothing in the build said so, because an
 * `<application>` with no launcher activity is a perfectly valid manifest.
 *
 * Robolectric reads the *merged* manifest, so this asserts on what actually
 * ships rather than on the source file: a library that merged in a competing
 * `MAIN`/`LAUNCHER` filter, or a refactor that renamed the activity, fails
 * here.
 *
 * Hilt is kept out with `application = Application::class`. Resolving a launch
 * intent asks the package manager a question about the manifest; it does not
 * construct the activity, so no dependency graph is needed to answer it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LauncherManifestTest {

    @Test
    fun `the app declares a launcher activity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)

        assertNotNull("the app has no launcher activity, so it cannot be opened", launch)
        assertEquals(
            "app.needler.MainActivity",
            requireNotNull(launch).component?.className,
        )
    }
}

package app.needler

import java.io.File
import java.util.Properties
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Predictive back is opted in to, asserted against the **merged** manifest.
 *
 * One attribute, and without it every transition Needler defines for a back
 * gesture is unreachable. Android routes back through
 * `OnBackInvokedDispatcher` only for an application that has asked for it;
 * otherwise it uses the legacy `onBackPressed` path, which carries no
 * `onBackStarted` and no `onBackProgressed`. androidx.activity therefore reports
 * no progress, navigation-compose's `NavHostEventHandler` never sets
 * `inPredictiveBack`, and the `predictivePopEnterTransition` and
 * `predictivePopExitTransition` that `app.needler.ui.navigation` supplies are
 * never called: the gesture gives no preview and then commits in one jump, which
 * is how "the transition looks hideous" was reported in the first place.
 *
 * It is tested rather than trusted for two reasons. The platform defaults it to
 * true only for an app targeting Android 15 or above, and REQUIREMENTS.md
 * "Target devices" sets minSdk 26 - so on API 33 and 34 the default is false and
 * the attribute is the only thing that turns it on. And it is a single
 * `<application>` attribute with no visible effect on a JVM test, a screenshot
 * or a lint run, so deleting it costs nothing at all until somebody swipes.
 *
 * ## Why it reads the file rather than an ApplicationInfo
 *
 * The sibling manifest tests in this module ask the package manager under
 * Robolectric, which is better when it is possible. It is not possible here:
 * `ApplicationInfo.isOnBackInvokedCallbackEnabled()` and the
 * `privateFlagsExt` bit behind it are hidden API and absent from the public
 * `android.jar`, so there is nothing for a test to read at runtime.
 *
 * The merged manifest is read directly instead, from exactly the file Robolectric
 * is pointed at - the Android Gradle plugin writes its location into
 * `com/android/tools/test_config.properties` on the unit-test classpath for that
 * purpose - so this still asserts on what ships, and a library merging
 * `enableOnBackInvokedCallback="false"` in fails here. Asserting on
 * `src/main/AndroidManifest.xml` was the alternative and was rejected for that
 * last reason.
 */
class PredictiveBackManifestTest {

    @Test
    fun `the application opts in to the back-invoked callback`() {
        val manifest: String = mergedManifest()

        assertTrue(
            "android:enableOnBackInvokedCallback is not true on <application> in the merged " +
                "manifest, so a back gesture gets no predictive animation and the predictive " +
                "transition specs are never called",
            ENABLE_ON_BACK_INVOKED.containsMatchIn(manifest),
        )
    }

    private fun mergedManifest(): String {
        // Three loaders, because which one holds the test classpath is the test
        // runner's business and not something to assume: under this Gradle
        // worker `javaClass.classLoader` is null outright.
        val loaders: List<ClassLoader> = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            javaClass.classLoader,
            ClassLoader.getSystemClassLoader(),
        )
        val config = Properties().apply {
            val stream = checkNotNull(
                loaders.firstNotNullOfOrNull { it.getResourceAsStream(TEST_CONFIG) },
            ) { "$TEST_CONFIG is not on the test classpath, so the merged manifest cannot be found" }
            stream.use { load(it) }
        }
        val recorded = checkNotNull(config.getProperty(MERGED_MANIFEST_KEY)) {
            "$TEST_CONFIG names no $MERGED_MANIFEST_KEY"
        }
        // The plugin records it relative to the module directory, which is also
        // the test JVM's working directory, but it may be absolute.
        val file: File = File(recorded).takeIf { it.isFile }
            ?: File(recorded.replace('\\', File.separatorChar))
        check(file.isFile) { "the merged manifest is not at $recorded" }
        return file.readText()
    }

    private companion object {
        const val TEST_CONFIG = "com/android/tools/test_config.properties"
        const val MERGED_MANIFEST_KEY = "android_merged_manifest"

        /**
         * Tolerant of attribute order and of whitespace, because the manifest
         * merger decides both and neither is what this test is about.
         */
        val ENABLE_ON_BACK_INVOKED =
            Regex("""android:enableOnBackInvokedCallback\s*=\s*"true"""")
    }
}

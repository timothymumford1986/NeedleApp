import app.needler.buildlogic.library
import app.needler.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

/**
 * `needler.screenshots` - renders Compose screens to PNG on the JVM, with no
 * emulator and no device.
 *
 * Apply it to any module that wants screenshots. It adds the rendering stack to
 * that module's unit-test classpath, points the renderer at the repository's
 * committed `screenshots/` folder, and registers one task that regenerates
 * every image:
 *
 *     ./gradlew :app:recordScreenshots
 *
 * ## How the images are produced
 *
 * Robolectric runs the Android framework on the JVM; from API 26 its native
 * graphics mode rasterises real pixels rather than no-op stubs, so a Compose
 * hierarchy can be measured, laid out, drawn and read back as a bitmap.
 * Roborazzi is the thin layer that drives that and writes the PNG.
 *
 * ## Why the Roborazzi Gradle plugin is not applied
 *
 * Roborazzi ships a Gradle plugin that registers `recordRoborazzi*`,
 * `compareRoborazzi*` and `verifyRoborazzi*` tasks. It is deliberately not used:
 *
 *  - it inspects AGP's variant model, and AGP 9 is newer than anything it has
 *    been published against, so it is a compatibility risk for a build that has
 *    to stay green;
 *  - everything it contributes is a Test task plus three system properties,
 *    which is what this plugin does in twenty lines and with no third-party
 *    plugin on the build classpath.
 *
 * ## Record and verify
 *
 * The test task runs in record mode by default, so the committed PNGs always
 * reflect the code that produced them. Passing `-Pneedler.screenshots.verify`
 * switches Roborazzi to verify mode, where a changed screen fails the test and
 * writes a diff beside the golden instead of overwriting it.
 */
class ScreenshotConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            dependencies.apply {
                addProvider("testImplementation", libs.library("robolectric"))
                addProvider("testImplementation", libs.library("roborazzi"))
                addProvider("testImplementation", libs.library("roborazzi.compose"))
                addProvider("testImplementation", libs.library("androidx.test.core"))
                // The rendered composables are the real ones, so the unit-test
                // classpath needs the same Compose artifacts main does. The BOM
                // keeps them on the versions the app ships with.
                addProvider("testImplementation", platform(libs.library("compose.bom")))
                addProvider("testImplementation", libs.library("compose.ui"))
                addProvider("testImplementation", libs.library("compose.ui.graphics"))
            }

            // A predictable, committed folder at the repository root, so a human
            // can open the images without hunting through build output - and so
            // they survive `needler.buildDir` redirecting build/ off the
            // checkout entirely.
            val screenshotsDir = rootProject.layout.projectDirectory
                .dir("screenshots")
                .asFile
                .absolutePath

            val verifyRequested = providers
                .gradleProperty("needler.screenshots.verify")
                .map { it != "false" }
                .orElse(false)

            tasks.withType(Test::class.java).configureEach {
                // Robolectric's stub graphics draw nothing at all; NATIVE is what
                // makes a readable bitmap come out the other end.
                systemProperty("robolectric.graphicsMode", "NATIVE")
                // Read by NeedlerScreenshots in the test source set.
                systemProperty("needler.screenshots.dir", screenshotsDir)
                // Roborazzi's own output root, for the diff and compare images it
                // writes in verify mode.
                systemProperty("roborazzi.output.dir", screenshotsDir)
                if (verifyRequested.get()) {
                    systemProperty("roborazzi.test.verify", "true")
                } else {
                    systemProperty("roborazzi.test.record", "true")
                }
                // Robolectric downloads its android-all jar on first use and
                // instruments a lot of framework classes; the default heap is
                // not enough to render a tablet-sized bitmap.
                maxHeapSize = "2g"
            }

            tasks.register("recordScreenshots") {
                group = "verification"
                description =
                    "Renders every Compose screen in this module to a PNG under screenshots/ " +
                        "at the repository root. No emulator or device is involved."
                dependsOn("testDebugUnitTest")
            }
        }
    }
}

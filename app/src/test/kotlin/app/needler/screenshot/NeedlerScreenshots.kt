package app.needler.screenshot

import androidx.compose.runtime.Composable
import app.needler.core.design.theme.NeedlerTheme
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import java.io.File
import org.robolectric.RuntimeEnvironment

/**
 * Renders a Needler composable to a PNG on the JVM.
 *
 * No emulator, no device, no connected phone: Robolectric runs the Android
 * framework in the test JVM and, in native graphics mode, rasterises real
 * pixels; Roborazzi measures, lays out, draws and writes the bitmap.
 *
 * ## Using it
 *
 * ```
 * @RunWith(RobolectricTestRunner::class)
 * @GraphicsMode(GraphicsMode.Mode.NATIVE)
 * @Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
 * class MyScreenshotTest {
 *     @Test fun connect() = captureNeedlerScreen("connect", NeedlerDevice.Phone) {
 *         ConnectScreen(...)
 *     }
 * }
 * ```
 *
 * The three annotations are the whole of the boilerplate, and they are what
 * this object exists to keep short. Everything else - the theme, the device
 * size, the density, suppressing motion, where the file goes and what it is
 * called - is handled by [captureNeedlerScreen].
 *
 * ## Where the images go
 *
 * `screenshots/` at the repository root, which is committed. The path is handed
 * in by the `needler.screenshots` convention plugin as the
 * `needler.screenshots.dir` system property, so it is an absolute path that
 * does not move when `needler.buildDir` redirects build output off the
 * checkout. Running the tests from an IDE with no property set falls back to
 * resolving `screenshots/` upward from the working directory.
 *
 * ## Reuse from other modules
 *
 * The rendering stack is set up by one line - `id("needler.screenshots")` - in
 * any module's build file, so a feature module can screenshot its own screens
 * without repeating any of it. This helper currently lives in `:app`'s test
 * source set because `:app` is the only module with screens today; when the
 * second module wants it, it moves to a shared test module and nothing else
 * changes.
 */
object NeedlerScreenshots {

    /**
     * The API level the renders run at.
     *
     * targetSdk, not compileSdk. Robolectric does publish an `android-all` for
     * API 37, but 36 is what this app actually targets and therefore what its
     * layout behaviour is defined against.
     */
    const val SDK: Int = 36

    /** Where the PNGs are written. */
    val outputDirectory: File by lazy {
        val fromGradle = System.getProperty("needler.screenshots.dir")
        val directory = if (fromGradle != null) {
            File(fromGradle)
        } else {
            // Run from an IDE: walk up from the module directory to the
            // repository root.
            generateSequence(File(".").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?.let { File(it, "screenshots") }
                ?: File("screenshots")
        }
        directory.apply { mkdirs() }
    }
}

/**
 * A device to render at.
 *
 * Both are the design pack's own artboards, at the density it exported them at,
 * so a rendered PNG is directly comparable with the matching file in
 * `design/png/`: the phone screens are 780x1688 and the tablet ones 2560x1600.
 *
 * @property widthDp logical width, which is also the width the pack's HTML uses
 *   in CSS pixels.
 * @property fileSuffix appended to the screen name, so the two widths of one
 *   screen sort next to each other in a file listing.
 */
enum class NeedlerDevice(
    val widthDp: Int,
    val heightDp: Int,
    val fileSuffix: String,
) {
    /** `design/html/01-Main.html`: 390x844, the iPhone-class artboard the pack draws phones at. */
    Phone(widthDp = 390, heightDp = 844, fileSuffix = "phone"),

    /** `design/html/09-TabletLibrary.html`: 1280x800 landscape. */
    Tablet(widthDp = 1280, heightDp = 800, fileSuffix = "tablet"),
    ;

    /**
     * Robolectric qualifiers for this device.
     *
     * Only the density is set here; the dimensions are applied by Roborazzi's
     * own size option, which configures the window as well as the
     * configuration. `+` makes it an amendment to the current qualifiers rather
     * than a replacement, so nothing a test's own `@Config` sets is lost.
     */
    internal val qualifiers: String get() = "+xhdpi"
}

/**
 * Renders [content] inside [NeedlerTheme] at [device]'s size and writes
 * `<name>-<device>.png`.
 *
 * Motion is suppressed, and that is a correctness requirement rather than a
 * convenience. Robolectric's clock does not advance on its own, so a screen
 * whose entry animation has not been driven forward renders at the animation's
 * *first* frame - which, for the pack's launch rise, is fully transparent and
 * 18dp out of place. `NeedlerTheme(reducedMotion = true)` takes the same branch
 * the platform's "remove animations" setting does: the rise is complete
 * immediately, the record does not spin, and the splash is not drawn at all.
 * The image is therefore the screen at rest, which is the thing worth
 * regression-testing.
 *
 * @param name the screen's name, lower-case and hyphenated, e.g. `connect` or
 *   `nav-library`.
 * @return the file written, so a test can assert on it.
 */
fun captureNeedlerScreen(
    name: String,
    device: NeedlerDevice,
    content: @Composable () -> Unit,
): File {
    RuntimeEnvironment.setQualifiers(device.qualifiers)

    val file = File(NeedlerScreenshots.outputDirectory, "$name-${device.fileSuffix}.png")

    captureRoboImage(
        file = file,
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = device.widthDp, heightDp = device.heightDp)
        },
    ) {
        NeedlerTheme(reducedMotion = true) {
            content()
        }
    }

    return file
}

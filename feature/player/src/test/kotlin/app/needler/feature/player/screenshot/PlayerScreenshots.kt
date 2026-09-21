package app.needler.feature.player.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.needler.core.design.theme.NeedlerTheme
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment

/**
 * Renders a player screen to a PNG on the JVM.
 *
 * The same harness `:app` uses for Connect, in this module's own test source set. `:app`'s copy says
 * why it is not shared yet - "This helper currently lives in `:app`'s test source set because
 * `:app` is the only module with screens today; when the second module wants it, it moves to a
 * shared test module and nothing else changes" - and `:feature:player` is that second module. It is
 * duplicated rather than moved because moving it means creating a new module and editing `:app`,
 * and this module is not allowed to do either. The two are otherwise identical, and the shared
 * version should replace both.
 *
 * ```
 * @RunWith(RobolectricTestRunner::class)
 * @GraphicsMode(GraphicsMode.Mode.NATIVE)
 * @Config(sdk = [PlayerScreenshots.SDK], application = Application::class)
 * class MyScreenshotTest {
 *     @Test fun nowPlaying() = capturePlayerScreen("player-now-playing", PlayerDevice.Phone) {
 *         NowPlayingScreen(...)
 *     }
 * }
 * ```
 *
 * `application = Application::class` keeps Hilt out of it: every test here renders a stateless
 * screen from a literal state, so nothing needs a dependency graph, a repository or a session.
 */
object PlayerScreenshots {

    /**
     * The API level the renders run at: targetSdk, not compileSdk, because that is what the app's
     * layout behaviour is defined against.
     */
    const val SDK: Int = 36

    /** Where the PNGs are written: `screenshots/` at the repository root, which is committed. */
    val outputDirectory: File by lazy {
        val fromGradle: String? = System.getProperty("needler.screenshots.dir")
        val directory: File = if (fromGradle != null) {
            File(fromGradle)
        } else {
            generateSequence(File(".").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?.let { File(it, "screenshots") }
                ?: File("screenshots")
        }
        directory.apply { mkdirs() }
    }
}

/**
 * A device to render at, matching the design pack's own artboards so a rendered PNG is directly
 * comparable with the matching file in `design/png/`.
 */
enum class PlayerDevice(
    val widthDp: Int,
    val heightDp: Int,
    val fileSuffix: String,
) {
    /** The pack's phone artboard: 390 by 844. */
    Phone(widthDp = 390, heightDp = 844, fileSuffix = "phone"),

    /** The pack's tablet artboard: 1280 by 800 landscape. */
    Tablet(widthDp = 1280, heightDp = 800, fileSuffix = "tablet"),

    /**
     * The tablet sidebar on its own: 400 dp wide, the full height of the tablet.
     *
     * Rendering the panel at its real width rather than inside a 1280 dp frame keeps the image
     * readable beside `design/png/09-TabletLibrary.png`, where the sidebar is one column of a much
     * wider screen.
     */
    Sidebar(widthDp = 401, heightDp = 800, fileSuffix = "sidebar"),

    /** A mini player on its own, at phone width: enough height for the bar and nothing else. */
    Bar(widthDp = 390, heightDp = 80, fileSuffix = "phone"),
}

/**
 * Renders [content] inside [NeedlerTheme] at [device]'s size and writes `<name>-<device>.png`.
 *
 * Motion is suppressed, which is a correctness requirement rather than a convenience: Robolectric's
 * clock does not advance on its own, so an un-driven animation renders at its first frame. Under
 * `reducedMotion = true` the record does not spin and the entry animations are already complete, so
 * the image is the screen at rest - which is the thing worth regression-testing.
 *
 * @param fontScale the system font scale to render at. REQUIREMENTS.md asks that "text must scale to
 *   200% without clipping, which the fixed 54 to 56 px control heights in the design pack will need
 *   care to honour", so `2f` renders exactly that case and the image is how it is checked.
 */
fun capturePlayerScreen(
    name: String,
    device: PlayerDevice,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
): File {
    RuntimeEnvironment.setQualifiers("+xhdpi")

    val file = File(PlayerScreenshots.outputDirectory, name + "-" + device.fileSuffix + ".png")

    captureRoboImage(
        file = file,
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = device.widthDp, heightDp = device.heightDp)
        },
    ) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
        ) {
            NeedlerTheme(reducedMotion = true) {
                content()
            }
        }
    }

    return file
}

/**
 * Asserts a PNG was actually written, at the size the device says.
 *
 * This is what makes the screenshots a regression test rather than only a record: a composable that
 * throws, lays out to zero height or renders at the wrong density fails here without anyone opening
 * the image.
 */
fun assertRendered(file: File, device: PlayerDevice) {
    assertTrue("no PNG written at " + file.absolutePath, file.isFile)
    assertTrue("PNG at " + file.absolutePath + " is empty", file.length() > 0)

    val image = ImageIO.read(file)
    assertTrue("PNG at " + file.absolutePath + " is not readable as an image", image != null)
    // xhdpi: two device pixels per dp.
    assertEquals("width of " + file.name, device.widthDp * 2, image.width)
    assertEquals("height of " + file.name, device.heightDp * 2, image.height)
}

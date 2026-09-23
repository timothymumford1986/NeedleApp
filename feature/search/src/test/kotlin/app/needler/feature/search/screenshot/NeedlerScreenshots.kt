package app.needler.feature.search.screenshot

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
 * Renders a Needler composable to a PNG on the JVM.
 *
 * ## Why this is a copy of a copy
 *
 * The original lives in `:app`'s test source set. `:feature:library` copied it,
 * with a note saying so, because a test source set is not published and there is
 * no way for one module to depend on another's. This is the third copy and it
 * makes the case unarguable: the right fix is a `:core:testing` module holding
 * one of these, and it is in the handover notes. Until then the file is kept
 * behaviourally identical to the other two — same output directory, same device
 * sizes, same density, same reduced-motion rule — so all three sets of images
 * are directly comparable.
 */
object NeedlerScreenshots {

    /** targetSdk, matching `:app`'s renders. */
    const val SDK: Int = 36

    /** Where the PNGs are written: `screenshots/` at the repository root. */
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
 * A device to render at.
 *
 * The same two artboards the design pack exports, at the same density, so a
 * rendered PNG can be put beside the matching file in `design/png/` and
 * compared directly: phone screens are 780x1688 and tablet ones 2560x1600.
 */
enum class NeedlerDevice(
    val widthDp: Int,
    val heightDp: Int,
    val fileSuffix: String,
) {
    /** `design/html/03-Search.html`: 390x844. */
    Phone(widthDp = 390, heightDp = 844, fileSuffix = "phone"),

    /** `design/html/10-TabletSearch.html`: 1280x800 landscape. */
    Tablet(widthDp = 1280, heightDp = 800, fileSuffix = "tablet"),
    ;

    internal val qualifiers: String get() = "+xhdpi"
}

/**
 * Renders [content] inside [NeedlerTheme] at [device]'s size and writes
 * `<name>-<device>.png`.
 *
 * Motion is suppressed: Robolectric's clock does not advance on its own, so an
 * un-driven entry animation would render at its first frame. `reducedMotion`
 * takes the same branch the platform's "remove animations" setting does, and
 * the image is therefore the screen at rest.
 *
 * @param fontScale the user's text size. 2f is the 200% REQUIREMENTS.md says
 *   every screen must survive without clipping, and rendering it is the only
 *   way to find out whether it does.
 */
fun captureNeedlerScreen(
    name: String,
    device: NeedlerDevice,
    fontScale: Float = 1f,
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
            if (fontScale == 1f) {
                content()
            } else {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale),
                ) {
                    content()
                }
            }
        }
    }

    return file
}

/**
 * Asserts a PNG was written at the size the device says.
 *
 * This is what makes a screenshot a regression test rather than only a record:
 * a composable that throws, lays out to zero height or renders at the wrong
 * density fails here without anyone opening the image.
 */
internal fun assertRendered(file: File, device: NeedlerDevice) {
    assertTrue("no PNG written at ${file.absolutePath}", file.isFile)
    assertTrue("PNG at ${file.absolutePath} is empty", file.length() > 0)

    val image = ImageIO.read(file)
    assertTrue("PNG at ${file.absolutePath} is not readable as an image", image != null)
    // xhdpi: two device pixels per dp.
    assertEquals("width of ${file.name}", device.widthDp * 2, image.width)
    assertEquals("height of ${file.name}", device.heightDp * 2, image.height)
}

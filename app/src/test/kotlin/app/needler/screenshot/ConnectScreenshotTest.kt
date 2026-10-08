// kotlinx.datetime.Instant is a typealias for kotlin.time.Instant from
// kotlinx-datetime 0.7.0 onwards, and the underlying type has carried an
// opt-in marker through several Kotlin releases. Opting in here costs a
// warning if it turns out not to be needed, and avoids a build break if it is.
@file:OptIn(ExperimentalTime::class)

package app.needler.screenshot

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import app.needler.connect.ConnectFailure
import app.needler.connect.ConnectScreen
import app.needler.connect.ConnectUiState
import app.needler.connect.CustomHeaderDraft
import app.needler.connect.ProxyFormState
import app.needler.connect.ProxyPreset
import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.OfflineCause
import java.awt.image.BufferedImage
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import java.io.File
import javax.imageio.ImageIO
import kotlin.time.ExperimentalTime
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders Connect - screens 01 and 16 - and every failure the screen has to
 * explain.
 *
 * REQUIREMENTS.md names five conditions this screen must handle and render, and
 * there is one test for each, because the whole point of modelling them
 * separately is that they look and read differently: a bad URL, an unreachable
 * server, wrong credentials, an untrusted certificate with its fingerprint and
 * an explicit trust action, and the Subsonic-protocol-disabled case naming the
 * setting an administrator has to switch on.
 *
 * `application = Application::class` keeps Hilt out of it. These tests render
 * the stateless [ConnectScreen] from a literal [ConnectUiState]; nothing here
 * needs a dependency graph, a repository or a server.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class ConnectScreenshotTest {

    @Test
    fun `connect on a phone`() {
        capture("connect", NeedlerDevice.Phone, FILLED)
    }

    @Test
    fun `connect on a tablet`() {
        capture("connect", NeedlerDevice.Tablet, FILLED)
    }

    @Test
    fun `connect empty on a phone`() {
        capture("connect-empty", NeedlerDevice.Phone, ConnectUiState())
    }

    @Test
    fun `connect while connecting on a tablet`() {
        capture("connect-connecting", NeedlerDevice.Tablet, FILLED.copy(connecting = true))
    }

    // ---- the five failures --------------------------------------------------

    @Test
    fun `a bad server address`() {
        capture(
            "connect-bad-address",
            NeedlerDevice.Phone,
            FILLED.copy(
                server = "musci.yourhome.net",
                failure = ConnectFailure.BadServerAddress("musci.yourhome.net"),
            ),
        )
    }

    @Test
    fun `an unreachable server`() {
        capture(
            "connect-unreachable",
            NeedlerDevice.Phone,
            FILLED.copy(failure = ConnectFailure.ServerUnreachable(OfflineCause.TIMEOUT)),
        )
    }

    @Test
    fun `wrong credentials`() {
        capture(
            "connect-wrong-credentials",
            NeedlerDevice.Phone,
            FILLED.copy(failure = ConnectFailure.WrongCredentials),
        )
    }

    @Test
    fun `an untrusted certificate`() {
        capture(
            "connect-untrusted-certificate",
            NeedlerDevice.Phone,
            FILLED.copy(failure = ConnectFailure.UntrustedCertificate(SELF_SIGNED)),
        )
    }

    @Test
    fun `an untrusted certificate on a tablet`() {
        capture(
            "connect-untrusted-certificate",
            NeedlerDevice.Tablet,
            FILLED.copy(failure = ConnectFailure.UntrustedCertificate(SELF_SIGNED)),
        )
    }

    @Test
    fun `the subsonic protocol is disabled`() {
        capture(
            "connect-subsonic-disabled",
            NeedlerDevice.Phone,
            FILLED.copy(failure = ConnectFailure.SubsonicDisabled),
        )
    }

    // ---- the proxy states ---------------------------------------------------

    @Test
    fun `a proxy intercepted the connection`() {
        capture(
            "connect-proxy-intercepted",
            NeedlerDevice.Phone,
            FILLED.copy(
                failure = ConnectFailure.ProxyIntercepted(
                    host = "yourteam.cloudflareaccess.com",
                    vendorName = "Cloudflare Access",
                    credentialsSent = false,
                ),
                // The failure opens the fields whose absence caused it.
                proxy = ProxyFormState(expanded = true),
            ),
        )
    }

    @Test
    fun `a proxy intercepted the connection on a tablet`() {
        capture(
            "connect-proxy-intercepted",
            NeedlerDevice.Tablet,
            FILLED.copy(
                failure = ConnectFailure.ProxyIntercepted(
                    host = "sso.yourhome.net",
                    vendorName = null,
                    credentialsSent = true,
                ),
                proxy = ProxyFormState(expanded = true),
            ),
        )
    }

    /**
     * The Cloudflare preset with both credentials typed in.
     *
     * [assertRevealsTheProxySection] is what makes this a test of the proxy section rather than
     * another picture of the Connect form. Both of these goldens used to show the **top two
     * millimetres** of the segmented preset control and nothing else: the section is last in a
     * scrolling column, the Connect button is pinned below that column, and the scroll stopped
     * wherever it happened to stop - so the cut ran through the middle of the control's glyphs and
     * the two images named for the feature contained none of it. The fix is
     * [app.needler.connect.ConnectScreen]'s rather than this file's; the assertion is this file's
     * job, and it fails if opening the section stops moving most of the screen, which is precisely
     * the state these two were committed in.
     */
    @Test
    fun `the proxy fields filled in`() {
        assertRevealsTheProxySection(
            capture(
                "connect-proxy-fields",
                NeedlerDevice.Phone,
                FILLED.copy(
                    proxy = ProxyFormState(
                        expanded = true,
                        preset = ProxyPreset.CloudflareAccess,
                        cloudflareClientId = "8f3c1d2e4b5a6978.access",
                        cloudflareClientSecret = "0123456789abcdef",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `the custom header editor`() {
        assertRevealsTheProxySection(
            capture(
                "connect-proxy-custom",
                NeedlerDevice.Phone,
                FILLED.copy(
                    proxy = ProxyFormState(
                        expanded = true,
                        preset = ProxyPreset.Custom,
                        customHeaders = listOf(CustomHeaderDraft("X-Api-Key", "0123456789abcdef")),
                        problem = "Needler sends that header itself. Choose another name.",
                    ),
                ),
            ),
        )
    }

    /**
     * Fails unless opening the proxy section changed a large part of the screen.
     *
     * The baseline is the same form with the section shut, rendered here and written to a scratch
     * file rather than into `screenshots/`: it is a measurement, not a golden. Rendering it in this
     * run rather than reading `connect-phone.png` off disk keeps the comparison about this build,
     * and means the check cannot be satisfied by a stale file.
     *
     * The quantity is a fraction of all pixels because that is what the defect was expressible in
     * and because a bounds assertion is not available here: `:app` carries no `compose-ui-test` on
     * its unit-test classpath, so there is no semantics tree to read a node's position out of.
     * `:feature:player`'s `NowPlayingMeasureTest` is what the stronger version of this looks like,
     * and adding that dependency to `:app` to write one is a larger change than the screenshots are
     * owed. See [MIN_REVEALED] for why the threshold is where it is.
     */
    private fun assertRevealsTheProxySection(expanded: File) {
        val shut: File = File.createTempFile("needler-connect-proxy-shut", ".png")
        captureTo(shut, NeedlerDevice.Phone, FILLED)

        val fraction: Double = fractionOfPixelsDiffering(expanded, shut)
        shut.delete()
        assertTrue(
            expanded.name + " differs from the same form with the proxy section shut in only " +
                fraction * 100 + "% of its pixels, so the section it is named for is not in it",
            fraction >= MIN_REVEALED,
        )
    }

    /** How much of two same-sized renders disagrees, as a fraction of their pixels. */
    private fun fractionOfPixelsDiffering(one: File, other: File): Double {
        val a: BufferedImage = ImageIO.read(one)
        val b: BufferedImage = ImageIO.read(other)
        assertEquals("widths of " + one.name + " and " + other.name, a.width, b.width)
        assertEquals("heights of " + one.name + " and " + other.name, a.height, b.height)

        var differing = 0L
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) differing++
            }
        }
        return differing.toDouble() / (a.width.toLong() * a.height.toLong()).toDouble()
    }

    @Test
    fun `an attempt that is taking too long can be stopped`() {
        capture(
            "connect-still-trying",
            NeedlerDevice.Phone,
            FILLED.copy(connecting = true, attemptIsSlow = true),
        )
    }

    private fun capture(name: String, device: NeedlerDevice, state: ConnectUiState): File {
        val file = captureNeedlerScreen(name, device) {
            Screen(state, device)
        }
        assertRendered(file, device)
        return file
    }

    /**
     * The same render, left where it is asked for rather than in `screenshots/`.
     *
     * `captureNeedlerScreen` owns the file name, which is right for a golden and wrong for a
     * baseline nobody commits, so the one caller that wants a scratch render moves the file
     * afterwards. A name parameter on the shared helper would have every other caller reading past
     * a case that applies to one.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureTo(file: File, device: NeedlerDevice, state: ConnectUiState) {
        val rendered: File = captureNeedlerScreen(
            SCRATCH_NAME,
            device,
            // Forced to Record. Roborazzi picks record-or-verify from a system property for the
            // whole run, so under `-Pneedler.screenshots.verify` this would try to compare against
            // `screenshots/scratch-connect-proxy-shut-phone.png`, which is deliberately never
            // committed, and fail with "The original file was not found". It is a baseline rendered
            // in this run, not a golden, so it records in both modes.
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
        ) {
            Screen(state, device)
        }
        rendered.copyTo(file, overwrite = true)
        rendered.delete()
    }

    @Composable
    private fun Screen(state: ConnectUiState, device: NeedlerDevice) {
        ConnectScreen(
            state = state,
            widthSizeClass = when (device) {
                NeedlerDevice.Phone -> WindowWidthSizeClass.Compact
                NeedlerDevice.Tablet -> WindowWidthSizeClass.Expanded
            },
            onServerChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onConnect = {},
            onTrustCertificate = {},
        )
    }

    private companion object {

        /**
         * The share of the image that opening the proxy section has to repaint.
         *
         * Ten per cent. The occluded renders moved a strip about 25px tall, which is under 2% of a
         * 780x1688 image; a section actually brought into view repaints most of the form above it.
         * Anything between the two is a disclosure half-done, which is worth failing on rather than
         * recording.
         */
        const val MIN_REVEALED: Double = 0.10

        /**
         * The file name the scratch baseline is rendered under before it is moved.
         *
         * Prefixed so that a run interrupted between the render and the move leaves something
         * obviously not a golden in `screenshots/` rather than something that looks like one.
         */
        const val SCRATCH_NAME: String = "scratch-connect-proxy-shut"

        /** The pack's own placeholder values, typed in. */
        val FILLED = ConnectUiState(
            server = "https://music.yourhome.net",
            username = "yourname",
            password = "hunter2hunter2",
        )

        /**
         * A self-signed certificate of the kind most self-hosted servers
         * present. The fingerprint is fabricated, and deliberately looks like
         * one: it is rendered, not verified.
         */
        val SELF_SIGNED = CertificateInfo(
            sha256Fingerprint =
                "9F:86:D0:81:88:4C:7D:65:9A:2F:EA:A0:C5:5A:D0:15:" +
                    "A3:BF:4F:1B:2B:0B:82:2C:D1:5D:6C:15:B0:F0:0A:08",
            subject = "CN=music.yourhome.net",
            issuer = "CN=music.yourhome.net (self-signed)",
            notAfter = Instant.parse("2027-04-01T00:00:00Z"),
        )
    }
}

/**
 * Asserts that a PNG was actually written, and at the size the device says.
 *
 * This is what makes the screenshots a regression test rather than only a
 * record: a composable that throws, lays out to zero height or renders at the
 * wrong density fails here, without anyone having to open the image. Comparing
 * the pixels themselves against the committed copy is the
 * `-Pneedler.screenshots.verify` mode.
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

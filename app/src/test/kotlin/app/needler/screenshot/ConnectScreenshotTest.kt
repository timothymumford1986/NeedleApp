// kotlinx.datetime.Instant is a typealias for kotlin.time.Instant from
// kotlinx-datetime 0.7.0 onwards, and the underlying type has carried an
// opt-in marker through several Kotlin releases. Opting in here costs a
// warning if it turns out not to be needed, and avoids a build break if it is.
@file:OptIn(ExperimentalTime::class)

package app.needler.screenshot

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import app.needler.connect.ConnectFailure
import app.needler.connect.ConnectScreen
import app.needler.connect.ConnectUiState
import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.OfflineCause
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

    private fun capture(name: String, device: NeedlerDevice, state: ConnectUiState) {
        val file = captureNeedlerScreen(name, device) {
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
        assertRendered(file, device)
    }

    private companion object {
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

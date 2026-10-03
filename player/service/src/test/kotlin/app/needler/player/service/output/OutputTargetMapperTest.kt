package app.needler.player.service.output

import app.needler.core.domain.model.OutputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Play on" picker's rows, from a device list a test writes by hand.
 *
 * None of this touches `AudioManager`, which is the point: the one platform read is behind
 * [AudioOutputDevices] for the reason `WearAudioStore` has a `WearFreeSpace` port in front of its disk
 * check - a test that measured the real thing passed or failed according to the machine it ran on. These
 * assertions are about what is paired in the fixture, not about what is paired with this desk.
 */
class OutputTargetMapperTest {

    private val names = OutputNames(thisDevice = "This phone", localProductName = "Pixel 9")

    @Test
    fun `no Bluetooth device leaves one row, and it is this device`() {
        val routes: OutputRoutes = OutputTargetMapper.routes(
            snapshot = AudioOutputSnapshot(devices = listOf(builtIn())),
            names = names,
        )

        assertEquals(1, routes.targets.size)
        assertEquals(OutputTarget.ThisDevice("This phone"), routes.targets.single())
        assertEquals(OutputTarget.ThisDevice("This phone"), routes.active)
    }

    @Test
    fun `a refused read still produces a usable picker`() {
        // AudioOutputSnapshot.Empty is what AndroidAudioOutputDevices reports when the platform throws.
        // One row, this device: the picker that shipped before route discovery existed.
        val routes: OutputRoutes = OutputTargetMapper.routes(AudioOutputSnapshot.Empty, names)

        assertEquals(listOf(OutputTarget.ThisDevice("This phone")), routes.targets)
        assertEquals(OutputTarget.ThisDevice("This phone"), routes.active)
    }

    @Test
    fun `one connected speaker is a row of its own, above this device`() {
        val routes: OutputRoutes = OutputTargetMapper.routes(
            snapshot = AudioOutputSnapshot(
                devices = listOf(builtIn(), bluetooth(id = "addr:11", name = "Living room speaker")),
                activeDeviceId = "addr:11",
            ),
            names = names,
        )

        assertEquals(2, routes.targets.size)
        val speaker = routes.targets.first() as OutputTarget.Bluetooth
        assertEquals("Living room speaker", speaker.displayName)
        // Every device AudioManager reports is attached, so there is no "nearby" case to represent.
        assertTrue(speaker.isConnected)
        assertTrue(routes.targets.last() is OutputTarget.ThisDevice)
        assertEquals(speaker, routes.active)
    }

    @Test
    fun `the platform decides which of two connected speakers is live`() {
        // The case the device report is really about: both are connected, only one is playing.
        val routes: OutputRoutes = OutputTargetMapper.routes(
            snapshot = AudioOutputSnapshot(
                devices = listOf(
                    builtIn(),
                    bluetooth(id = "addr:aa", name = "Kitchen speaker"),
                    bluetooth(id = "addr:bb", name = "Living room speaker"),
                ),
                activeDeviceId = "addr:bb",
            ),
            names = names,
        )

        assertEquals("Living room speaker", routes.active?.displayName)
        // The order does not follow the route: the tick moves, the rows stay where the eye left them.
        assertEquals(
            listOf("Kitchen speaker", "Living room speaker", "This phone"),
            routes.targets.map { it.displayName },
        )
    }

    @Test
    fun `below API 33 the live route falls back to the platform's own precedence`() {
        // getAudioDevicesForAttributes arrived in API 33 and minSdk is 26, so activeDeviceId is null
        // on a real share of devices. Bluetooth outranks the built-in speaker.
        val routes: OutputRoutes = OutputTargetMapper.routes(
            snapshot = AudioOutputSnapshot(
                devices = listOf(builtIn(), bluetooth(id = "addr:11", name = "Pixel Buds")),
                activeDeviceId = null,
            ),
            names = names,
        )

        assertEquals("Pixel Buds", routes.active?.displayName)
    }

    @Test
    fun `a wire is not a row, but it does take the sound back to this device`() {
        val routes: OutputRoutes = OutputTargetMapper.routes(
            snapshot = AudioOutputSnapshot(
                devices = listOf(builtIn(), wired()),
                activeDeviceId = null,
            ),
            names = names,
        )

        assertEquals(listOf(OutputTarget.ThisDevice("This phone")), routes.targets)
        assertTrue(routes.active is OutputTarget.ThisDevice)
    }

    @Test
    fun `an LE Audio sink outranks classic Bluetooth when nothing says otherwise`() {
        val routes: OutputRoutes = OutputTargetMapper.routes(
            snapshot = AudioOutputSnapshot(
                devices = listOf(
                    builtIn(),
                    bluetooth(id = "addr:aa", name = "Old speaker"),
                    AudioOutputDevice(id = "addr:bb", kind = AudioOutputKind.BLUETOOTH_LE, name = "New buds"),
                ),
                activeDeviceId = null,
            ),
            names = names,
        )

        assertEquals("New buds", routes.active?.displayName)
    }

    @Test
    fun `a hearing aid is listed, and wins the route`() {
        val routes: OutputRoutes = OutputTargetMapper.routes(
            snapshot = AudioOutputSnapshot(
                devices = listOf(
                    builtIn(),
                    bluetooth(id = "addr:aa", name = "Pixel Buds"),
                    AudioOutputDevice(id = "addr:hh", kind = AudioOutputKind.HEARING_AID, name = null),
                ),
                activeDeviceId = null,
            ),
            names = names,
        )

        assertEquals(3, routes.targets.size)
        assertEquals("Hearing aid", routes.active?.displayName)
    }

    @Test
    fun `a device with no readable name gets one anyway`() {
        val blank: OutputRoutes = OutputTargetMapper.routes(
            snapshot = AudioOutputSnapshot(devices = listOf(bluetooth(id = "addr:11", name = "   "))),
            names = names,
        )
        val absent: OutputRoutes = OutputTargetMapper.routes(
            snapshot = AudioOutputSnapshot(devices = listOf(bluetooth(id = "addr:11", name = null))),
            names = names,
        )

        // "The current output is always named in the player", so a blank row is not an option.
        assertEquals("Bluetooth device", blank.targets.first().displayName)
        assertEquals("Bluetooth device", absent.targets.first().displayName)
    }

    @Test
    fun `a sink reporting this phone's own model is treated as nameless`() {
        // The framework substitutes the local product name when it has no Bluetooth name to hand.
        // "Pixel 9" on a row that is demonstrably not this phone is a worse lie than a generic label.
        val routes: OutputRoutes = OutputTargetMapper.routes(
            snapshot = AudioOutputSnapshot(devices = listOf(bluetooth(id = "addr:11", name = "Pixel 9"))),
            names = names,
        )

        assertEquals("Bluetooth device", routes.targets.first().displayName)
    }

    @Test
    fun `one headset reported twice is one row`() {
        // A dual-mode headset appears as TYPE_BLUETOOTH_A2DP and again as TYPE_BLE_HEADSET, same name,
        // different ids. Two identical rows reads as the app seeing double.
        val routes: OutputRoutes = OutputTargetMapper.routes(
            snapshot = AudioOutputSnapshot(
                devices = listOf(
                    builtIn(),
                    bluetooth(id = "addr:classic", name = "Pixel Buds"),
                    AudioOutputDevice(
                        id = "addr:le",
                        kind = AudioOutputKind.BLUETOOTH_LE,
                        name = "Pixel Buds",
                    ),
                ),
                activeDeviceId = "addr:classic",
            ),
            names = names,
        )

        assertEquals(2, routes.targets.size)
        // The live entry survives the collapse, so the tick lands on a row that is in the list.
        assertEquals("addr:classic", routes.targets.first().id)
        assertEquals("addr:classic", routes.active?.id)
    }

    @Test
    fun `two nameless sinks stay two rows`() {
        // Collapsing on the fallback label would hide a second speaker rather than a duplicate of the
        // first, so a device with no name keys on its own id.
        val routes: OutputRoutes = OutputTargetMapper.routes(
            snapshot = AudioOutputSnapshot(
                devices = listOf(bluetooth(id = "addr:aa", name = null), bluetooth(id = "addr:bb", name = "")),
            ),
            names = names,
        )

        assertEquals(3, routes.targets.size)
    }

    @Test
    fun `an active id naming a device that has gone falls back rather than ticking nothing`() {
        val routes: OutputRoutes = OutputTargetMapper.routes(
            snapshot = AudioOutputSnapshot(
                devices = listOf(builtIn()),
                activeDeviceId = "addr:gone",
            ),
            names = names,
        )

        assertTrue(routes.active is OutputTarget.ThisDevice)
        assertTrue(routes.targets.none { it.id == "addr:gone" })
    }

    @Test
    fun `Unknown carries nothing, which is what a surface draws before the first reading`() {
        assertEquals(emptyList<OutputTarget>(), OutputRoutes.Unknown.targets)
        assertNull(OutputRoutes.Unknown.active)
    }

    private fun builtIn(): AudioOutputDevice = AudioOutputDevice(
        id = "dev:2:1",
        kind = AudioOutputKind.BUILT_IN,
        name = "Pixel 9",
    )

    private fun wired(): AudioOutputDevice = AudioOutputDevice(
        id = "dev:3:2",
        kind = AudioOutputKind.WIRED,
        name = "Wired headset",
    )

    private fun bluetooth(id: String, name: String?): AudioOutputDevice = AudioOutputDevice(
        id = id,
        kind = AudioOutputKind.BLUETOOTH_A2DP,
        name = name,
    )
}

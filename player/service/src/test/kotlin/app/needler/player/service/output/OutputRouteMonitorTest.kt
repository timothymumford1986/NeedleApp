package app.needler.player.service.output

import app.cash.turbine.test
import app.needler.core.domain.model.OutputTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one subscription to the platform's device list: when it starts, when it stops, and what arrives
 * on it while a speaker comes and goes.
 *
 * [FakeAudioOutputDevices] counts registrations as well as scripting snapshots, because the thing worth
 * pinning here is not only the mapping - [OutputTargetMapperTest] covers that - but the *lifetime*.
 * REQUIREMENTS.md "Battery and data" forbids keeping anything open behind a backgrounded app, and the
 * only honest way to test that claim is to watch the count go back to zero.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutputRouteMonitorTest {

    private val names = OutputNames(thisDevice = "This phone", localProductName = "Pixel 9")

    @Test
    fun `the first reading arrives without waiting for a device to change`() = runTest {
        val devices = FakeAudioOutputDevices(snapshot(bluetooth("addr:11", "Living room speaker")))
        val monitor = OutputRouteMonitor(devices, names, backgroundScope)

        monitor.observeTargets().test {
            val targets: List<OutputTarget> = awaitItem()
            assertEquals(2, targets.size)
            assertEquals("Living room speaker", targets.first().displayName)
        }
    }

    @Test
    fun `a speaker connecting and disconnecting mid-session moves the list and the route`() = runTest {
        val devices = FakeAudioOutputDevices(snapshot())
        val monitor = OutputRouteMonitor(devices, names, backgroundScope)

        monitor.routes.test {
            // Nothing attached: one row, and sound is on this device.
            val alone: OutputRoutes = awaitItem()
            assertEquals(listOf("This phone"), alone.targets.map { it.displayName })
            assertTrue(alone.active is OutputTarget.ThisDevice)

            devices.emit(snapshot(bluetooth("addr:11", "Pixel Buds"), activeId = "addr:11"))

            val connected: OutputRoutes = awaitItem()
            assertEquals(listOf("Pixel Buds", "This phone"), connected.targets.map { it.displayName })
            assertEquals("Pixel Buds", connected.active?.displayName)

            devices.emit(snapshot())

            // Gone. The row goes with it and the route falls back rather than naming a speaker that
            // is no longer there - which is the case REQUIREMENTS.md "Output" has the player naming
            // the live route for.
            val gone: OutputRoutes = awaitItem()
            assertEquals(listOf("This phone"), gone.targets.map { it.displayName })
            assertTrue(gone.active is OutputTarget.ThisDevice)
        }
    }

    @Test
    fun `a change that moves no row does not re-emit the list`() = runTest {
        val devices = FakeAudioOutputDevices(
            snapshot(bluetooth("addr:aa", "Kitchen"), bluetooth("addr:bb", "Study"), activeId = "addr:aa"),
        )
        val monitor = OutputRouteMonitor(devices, names, backgroundScope)

        monitor.observeTargets().test {
            assertEquals(3, awaitItem().size)

            // The live route moved between two speakers that are both still listed. The sheet's list
            // has not changed, so it must not be re-diffed.
            devices.emit(
                snapshot(bluetooth("addr:aa", "Kitchen"), bluetooth("addr:bb", "Study"), activeId = "addr:bb"),
            )
            advanceUntilIdle()

            expectNoEvents()
        }
    }

    @Test
    fun `two surfaces share one registration, and it ends when both let go`() = runTest {
        val devices = FakeAudioOutputDevices(snapshot(bluetooth("addr:11", "Pixel Buds")))
        val monitor = OutputRouteMonitor(devices, names, backgroundScope)

        // The sheet, then the player, both watching at once.
        monitor.observeTargets().test {
            awaitItem()
            monitor.observeActive().test {
                awaitItem()

                // One AudioDeviceCallback for the whole app, however many surfaces are watching.
                assertEquals(1, devices.registrations)
                assertEquals(1, devices.live)
            }
        }

        // Both have let go. The five-second stop timeout elapses - which is what keeps a rotation from
        // re-registering - and then the platform callback is released.
        advanceTimeBy(6_000L)
        runCurrent()

        assertEquals(0, devices.live)
        assertEquals(1, devices.registrations)
    }

    @Test
    fun `a platform read that throws degrades the picker instead of breaking the player`() = runTest {
        val devices = FakeAudioOutputDevices(snapshot())
        devices.failWith = IllegalStateException("audio service is in a strange mood")
        val monitor = OutputRouteMonitor(devices, names, backgroundScope)

        monitor.routes.test {
            // Not a thrown exception: this flow is folded into PlaybackState, so failing it would stop
            // the lock screen, the widgets and Wear from seeing playback at all. A SharedFlow never
            // completes, so there is no terminal event to await - the degraded value is the whole
            // observable behaviour.
            assertEquals(OutputRoutes.Unknown, awaitItem())
        }
    }

    // ---- fixtures ----------------------------------------------------------

    private fun snapshot(
        vararg devices: AudioOutputDevice,
        activeId: String? = null,
    ): AudioOutputSnapshot = AudioOutputSnapshot(
        devices = listOf(
            AudioOutputDevice(id = "dev:2:1", kind = AudioOutputKind.BUILT_IN, name = "Pixel 9"),
        ) + devices,
        activeDeviceId = activeId,
    )

    private fun bluetooth(id: String, name: String?): AudioOutputDevice =
        AudioOutputDevice(id = id, kind = AudioOutputKind.BLUETOOTH_A2DP, name = name)
}

/**
 * An [AudioOutputDevices] that counts its own subscriptions.
 *
 * `onStart` and `onCompletion` stand in for what the real implementation does with
 * `registerAudioDeviceCallback` and `awaitClose`: [live] goes up when something starts watching and
 * down when it stops - cancellation included - which is the only way to assert that the platform
 * callback is actually released rather than merely documented as being.
 */
private class FakeAudioOutputDevices(
    initial: AudioOutputSnapshot = AudioOutputSnapshot.Empty,
) : AudioOutputDevices {

    private val snapshots = MutableStateFlow(initial)

    /** How many times anything has subscribed, ever. One, if the sharing is doing its job. */
    var registrations: Int = 0
        private set

    /** How many subscriptions are open right now. */
    var live: Int = 0
        private set

    /** Set to make the read fail, the way an OEM audio service in a bad state does. */
    var failWith: Throwable? = null

    override fun observeDevices(): Flow<AudioOutputSnapshot> = snapshots
        .onStart {
            registrations++
            live++
            failWith?.let { throw it }
        }
        .onCompletion { live-- }

    fun emit(snapshot: AudioOutputSnapshot) {
        snapshots.value = snapshot
    }
}

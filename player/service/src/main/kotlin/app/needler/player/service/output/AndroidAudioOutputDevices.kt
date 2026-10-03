package app.needler.player.service.output

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * [AudioOutputDevices] over `AudioManager`, and the only file in the app that reads the platform's
 * device list.
 *
 * REQUIREMENTS.md "Output" assigns this to `:player:service` and says why in one sentence: "a repository
 * over Room and HTTP that opened a route-discovery callback would be keeping the radio awake from the
 * wrong place entirely." Nothing here opens a radio - `AudioManager` is already listening, and this
 * subscribes to what it reports - but the layering holds for the same reason the sentence gives: the
 * thing that owns the session owns the routes.
 *
 * ## Permissions: none, and that was checked rather than assumed
 *
 * Read from the installed SDK's own `data/annotations.zip` for `compileSdk` 37 - the file Lint itself
 * reads - and from `data/api-versions.xml` for the levels:
 *
 *  * `AudioManager.getDevices(int)`, API 23, no `RequiresPermission` entry.
 *  * `AudioManager.registerAudioDeviceCallback` and `unregisterAudioDeviceCallback`, API 23, no entry.
 *  * `AudioManager.getAudioDevicesForAttributes`, API 33, no entry.
 *  * `AudioDeviceInfo.getProductName()` and `getAddress()`, no entry.
 *
 * The only `AudioManager` members in that file that do carry one are the surround-format and
 * preferred-mixer setters, none of which this uses. So **no new manifest permission is needed**, and in
 * particular not `BLUETOOTH_CONNECT`. What that permission would buy is named in
 * `OutputTargetMapper.bluetoothRows` and in the module report: paired-but-absent devices, and a
 * guaranteed-readable name on a connected one. Neither is worth a runtime prompt in front of a picker
 * without being asked first.
 *
 * ## Where the callback is registered, and where it stops
 *
 * In [observeDevices], on collection, and it is unregistered in `awaitClose` when the last collector
 * goes away. `OutputRouteMonitor` shares one subscription across every surface and drops it five seconds
 * after the last one leaves, so a backgrounded app with no player screen, no widget and no watch
 * attached holds nothing at all - which is what REQUIREMENTS.md "Battery and data" asks for.
 */
internal class AndroidAudioOutputDevices(
    private val audioManager: AudioManager,
) : AudioOutputDevices {

    /**
     * The device list, re-read on every add and remove.
     *
     * The whole list is re-read rather than patched from the callback's arguments. The callback reports
     * a delta, and applying deltas to a list the platform also mutates is how a stale row survives a
     * reconnect; re-reading is one cheap in-process call and cannot drift.
     *
     * `registerAudioDeviceCallback` delivers the current devices to `onAudioDevicesAdded` immediately on
     * registration, so the explicit send is belt and braces - but it is what makes the first emission
     * synchronous, which `currentState()` depends on to avoid waiting on a device.
     */
    override fun observeDevices(): Flow<AudioOutputSnapshot> = callbackFlow {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                trySend(read())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                trySend(read())
            }
        }
        // The main looper, not the caller's: this flow is collected from a view model's scope, and
        // `AudioManager` wants a live Looper for the life of the registration.
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        trySend(read())
        awaitClose { audioManager.unregisterAudioDeviceCallback(callback) }
    }.distinctUntilChanged()

    /**
     * One read of the platform's output devices.
     *
     * Wrapped, because this flow is folded into the player's state and a thrown exception here would
     * take the lock screen, the widgets and Wear down with it. OEM audio services have been known to
     * throw from these calls on a device in a strange state, and [AudioOutputSnapshot.Empty] still
     * produces a usable picker - one row, this device - which is exactly what the picker showed before
     * this work existed.
     */
    private fun read(): AudioOutputSnapshot = try {
        AudioOutputSnapshot(
            devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { info -> info.isSink }
                .map { info -> toDevice(info) },
            activeDeviceId = activeDeviceId(),
        )
    } catch (error: RuntimeException) {
        AudioOutputSnapshot.Empty
    }

    /**
     * Which device media audio is actually routed to, straight from the platform.
     *
     * `getAudioDevicesForAttributes` answers the question the device report is really about - a
     * connected A2DP speaker is not necessarily the one receiving audio - and it answers it for the
     * attributes the player actually uses, so two connected speakers resolve correctly rather than by a
     * guess. It arrived in API 33 and this app's `minSdk` is 26, so below that the answer is null and
     * `OutputTargetMapper` falls back to the platform's routing precedence.
     *
     * The alternative was `AudioTrack.getRoutedDevice()`, which is the authoritative answer. Media3 does
     * not expose the sink's `AudioTrack`, and reaching for it would mean a second audio sink
     * implementation inside the service to read one field.
     */
    private fun activeDeviceId(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return audioManager.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES)
            .firstOrNull { info -> info.isSink }
            ?.let { info -> deviceId(info) }
    }

    private fun toDevice(info: AudioDeviceInfo): AudioOutputDevice = AudioOutputDevice(
        id = deviceId(info),
        kind = kindOf(info.type),
        name = info.productName?.toString(),
    )

    /**
     * A device identity that survives a reconnect.
     *
     * `AudioDeviceInfo.getId()` does not: the platform reassigns it, so headphones that drop out and
     * come back would be a new row with a lost selection. The address is stable where it is readable -
     * it is the Bluetooth MAC - the name is stable where the address is not, and the platform id is the
     * last resort rather than the first.
     */
    private fun deviceId(info: AudioDeviceInfo): String {
        val address: String = info.address.trim()
        if (address.isNotEmpty()) return "addr:" + address
        val name: String = info.productName?.toString()?.trim().orEmpty()
        if (name.isNotEmpty()) return "name:" + info.type + ":" + name
        return "dev:" + info.type + ":" + info.id
    }

    /**
     * An `AudioDeviceInfo` type as the kind the picker reasons about.
     *
     * Every constant named here is a Java compile-time `static final int`, so Kotlin inlines its value
     * and nothing is looked up at runtime: referring to `TYPE_BLE_HEADSET` on an API 26 device is a
     * comparison against 26, not a field access that could not resolve.
     *
     * `TYPE_BLUETOOTH_SCO` counts as classic Bluetooth deliberately. Media does not normally route over
     * SCO, but a headset in a call shows up that way, and a headset the user is wearing belongs in the
     * list whichever profile is carrying it.
     */
    private fun kindOf(type: Int): AudioOutputKind = when (type) {
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEARING_AID,
        -> AudioOutputKind.HEARING_AID

        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> AudioOutputKind.BLUETOOTH_LE

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        -> AudioOutputKind.BLUETOOTH_A2DP

        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC,
        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_DOCK_ANALOG,
        -> AudioOutputKind.WIRED

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        -> AudioOutputKind.BUILT_IN

        else -> AudioOutputKind.OTHER
    }

    private companion object {
        /**
         * The attributes the player's own sink uses, so the routing answer is the one that applies to
         * music rather than to a notification or a call.
         */
        val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}

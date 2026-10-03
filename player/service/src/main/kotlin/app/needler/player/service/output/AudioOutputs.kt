package app.needler.player.service.output

import kotlinx.coroutines.flow.Flow

/**
 * The platform's output devices, as a port this module can fake.
 *
 * REQUIREMENTS.md "Output" puts route discovery here rather than in a repository: "Bluetooth sinks come
 * from `AudioManager`'s device list and Cast receivers from a `MediaRouter` discovery session; both belong
 * to the layer that owns the Media3 session, because a repository over Room and HTTP that opened a
 * route-discovery callback would be keeping the radio awake from the wrong place entirely."
 *
 * ## Why it is an interface at all
 *
 * Because a test must not depend on what is paired with the machine running it. There is precedent in this
 * repository and it is exactly this shape: `WearAudioStore` measured the real disk through
 * `java.io.File.getUsableSpace`, so its tests passed or failed according to how full the host drive was,
 * and the fix was to put a `WearFreeSpace` port in front of it. An `AudioManager` read is the same hazard
 * with a worse symptom - the test would pass on a desk with no headphones paired and fail on a desk with
 * two - so the one Android call is behind this interface and everything above it is decided by
 * [OutputTargetMapper] from a value a test can write by hand.
 *
 * The flow is **cold and self-unregistering**. Its implementation registers an `AudioDeviceCallback` when
 * it is collected and unregisters on cancellation, so nothing is held while nobody is watching -
 * REQUIREMENTS.md "Battery and data" forbids keeping anything open behind a backgrounded app.
 */
public interface AudioOutputDevices {

    /**
     * The current output devices, re-emitted whenever one is added or removed.
     *
     * Emits once, synchronously, on collection, so a caller taking `first()` never waits on a device
     * arriving. Implementations must not fail the flow when the platform refuses a read; an empty snapshot
     * is the honest answer and a thrown exception here would take the player's state flow down with it.
     */
    public fun observeDevices(): Flow<AudioOutputSnapshot>
}

/**
 * What the platform's output devices are right now, and which one is receiving audio.
 *
 * Two fields rather than a flag per device, because the second question has a different answer source
 * from the first: the list comes from `AudioManager.getDevices`, which says what exists, while
 * [activeDeviceId] comes from `getAudioDevicesForAttributes` where that is available and from
 * [AudioOutputKind.routingPriority] where it is not. Keeping them apart is what lets the fallback be
 * tested as the arithmetic it is.
 */
public data class AudioOutputSnapshot(
    val devices: List<AudioOutputDevice> = emptyList(),
    /**
     * [AudioOutputDevice.id] of the device media audio is actually routed to, or null when the platform
     * will not say - which is every device below API 33, since `getAudioDevicesForAttributes` arrived
     * there and this app's `minSdk` is 26.
     *
     * Null is not "nothing is playing". It means the question was not answered, and
     * [OutputTargetMapper] falls back to the platform's own routing precedence rather than guessing that
     * the built-in speaker won.
     */
    val activeDeviceId: String? = null,
) {
    public companion object {
        /** No devices and no answer: what a refused read reports. */
        public val Empty: AudioOutputSnapshot = AudioOutputSnapshot()
    }
}

/**
 * One output device, narrowed to the three things the picker needs.
 *
 * Deliberately not an `AudioDeviceInfo`: that type cannot be constructed outside the framework, so a
 * snapshot made of them could only come from a device. The `AudioDeviceInfo.getType()` integer is mapped
 * to [AudioOutputKind] at the edge for the same reason - a test that had to name `TYPE_BLE_HEADSET` by
 * its numeric value would be a test of the platform's constants.
 */
public data class AudioOutputDevice(
    /**
     * A stable identifier, and stable is the requirement rather than unique.
     *
     * `AudioDeviceInfo.getId()` is not usable here: it is reassigned on every reconnect, so a pair of
     * headphones that drops out and comes back would be a different row with a different selection
     * state. The implementation prefers the device address, then the name, then the platform id.
     */
    val id: String,
    val kind: AudioOutputKind,
    /**
     * The device's human-readable name, or null when there is not one to show.
     *
     * Null is a real case and the picker has to survive it: `AudioDeviceInfo.getProductName()` returns a
     * `CharSequence` that can be empty, and on a Bluetooth sink it can come back as *this* phone's own
     * model name instead of the speaker's. [OutputTargetMapper] treats both as no name and labels the row
     * by its kind, because "Pixel 9" on a row that is not this phone is worse than "Bluetooth device".
     */
    val name: String? = null,
)

/**
 * The kinds of output this app distinguishes, in the order the platform routes media to them.
 *
 * [routingPriority] is the fallback for "which one is actually playing" below API 33, where
 * `getAudioDevicesForAttributes` does not exist. It follows the platform's own media routing strategy:
 * a hearing aid or an LE Audio sink wins over classic Bluetooth, Bluetooth wins over a wire, and the
 * built-in speaker is what is left when nothing is attached. Lower wins.
 *
 * The rejected alternative was to assume the built-in speaker unless exactly one Bluetooth device was
 * present. That is wrong in the case the device report names - two paired speakers, one of them actually
 * receiving audio - and it is wrong in the ordinary case of headphones plugged in while a speaker is
 * connected.
 */
public enum class AudioOutputKind(public val routingPriority: Int) {
    /** A hearing aid, classic or LE. Routed before anything else, by platform policy and by decency. */
    HEARING_AID(0),

    /** An LE Audio headset, speaker or broadcast sink. */
    BLUETOOTH_LE(1),

    /** Classic Bluetooth: A2DP, or SCO when a call profile is carrying audio. */
    BLUETOOTH_A2DP(2),

    /** A wire or a dock: headset, headphones, USB, line, HDMI. */
    WIRED(3),

    /** This device's own speaker or earpiece. */
    BUILT_IN(4),

    /** Something else the platform reported. Listed nowhere, counted as this device. */
    OTHER(5),
    ;

    /**
     * True when this kind is a Bluetooth sink and therefore a row of its own in the picker.
     *
     * A hearing aid counts: it is a Bluetooth device, the user thinks of it as one, and hiding it would
     * leave the one listener who most needs to know where sound is going unable to see it.
     */
    public val isBluetoothSink: Boolean
        get() = this == HEARING_AID || this == BLUETOOTH_LE || this == BLUETOOTH_A2DP
}

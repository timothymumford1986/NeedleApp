package app.needler.player.service.output

import app.needler.core.domain.model.OutputTarget

/**
 * The platform's device list as the "Play on" picker's rows, and the row sound is actually coming out of.
 *
 * Pure, and that is the point. Every decision the picker depends on - which devices become rows, which of
 * two entries for one speaker survives, what a nameless sink is called, and which row is the live one -
 * is made here from an [AudioOutputSnapshot] a test can write by hand. The one Android call sits behind
 * [AudioOutputDevices] and does nothing but read; see that interface for the `WearFreeSpace` precedent
 * this follows.
 *
 * ## What becomes a row
 *
 * REQUIREMENTS.md "Output" lists "Cast devices, connected and nearby Bluetooth targets, and this device"
 * in one list, and that is the order built here with the Cast half absent. So:
 *
 *  * **Bluetooth sinks** - A2DP, SCO, LE Audio and hearing aids - are rows.
 *  * **This device** is always the last row, and is always present.
 *  * **A wire is not a row.** Headphones, USB and HDMI are folded into this device, because the picker
 *    the pack draws offers three kinds of destination and a wire is not one of them: unplugging it is
 *    the control, not a list entry. A wired sink still counts for *which* row is live, so plugging
 *    headphones in while a speaker is connected moves the tick back to this device.
 *  * **Cast** is deliberately unpopulated. REQUIREMENTS.md:1141 keeps whether Cast ships at all an open
 *    question, and line 515 forbids offering a target that fails after it is picked, so listing
 *    receivers before the reachability probe exists would build exactly the failure the requirement
 *    names. The row order above leaves Cast the head of the list when it arrives.
 *
 * ## Why one speaker can arrive twice
 *
 * A dual-mode headset is reported once as `TYPE_BLUETOOTH_A2DP` and again as `TYPE_BLE_HEADSET`, with
 * the same name and two platform ids. Two identical rows in a picker is a bug the user reads as the app
 * seeing double, so entries that share a name collapse to one - keeping whichever of them the platform
 * says is live, and otherwise the higher-priority kind.
 */
public object OutputTargetMapper {

    /**
     * The picker's rows and the live row, from one snapshot.
     *
     * Both come from one function because they are one decision: the live row has to *be* one of the
     * rows, or the picker draws a tick against nothing and the player names an output the list does not
     * contain.
     */
    public fun routes(snapshot: AudioOutputSnapshot, names: OutputNames): OutputRoutes {
        val thisDevice = OutputTarget.ThisDevice(displayName = names.thisDevice)
        val activeId: String? = activeDevice(snapshot)?.id
        val sinks: List<Pair<AudioOutputDevice, OutputTarget.Bluetooth>> =
            bluetoothRows(snapshot, names, activeId)
        val active: OutputTarget = sinks
            .firstOrNull { (device, _) -> device.id == activeId }
            ?.second
            ?: thisDevice
        return OutputRoutes(
            targets = sinks.map { (_, target) -> target } + thisDevice,
            active = active,
        )
    }

    /**
     * The device media audio is going to, or null when the snapshot holds nothing at all.
     *
     * Two sources, in order. [AudioOutputSnapshot.activeDeviceId] is the platform's own answer and is
     * preferred whenever it names a device still in the list; below API 33 there is no such answer, so
     * the fallback is the platform's routing precedence through [AudioOutputKind.routingPriority].
     *
     * The fallback matters more than it looks: a connected A2DP device is not necessarily the one
     * receiving audio, and the case that proves it is two connected speakers. The precedence rule gets
     * that wrong as readily as any other guess, which is why the platform's answer is taken first and
     * the guess is confined to the devices that cannot give one.
     */
    internal fun activeDevice(snapshot: AudioOutputSnapshot): AudioOutputDevice? {
        val named: AudioOutputDevice? = snapshot.activeDeviceId?.let { id ->
            snapshot.devices.firstOrNull { device -> device.id == id }
        }
        if (named != null) return named
        return snapshot.devices.minByOrNull { device -> device.kind.routingPriority }
    }

    /**
     * The Bluetooth rows, deduplicated, named and ordered by name.
     *
     * Every one of them is [OutputTarget.Bluetooth.isConnected] `true`, and that is not a shortcut:
     * `AudioManager` reports devices that are *attached*, so a paired-but-absent speaker never appears
     * in this list at all. The "nearby" half of REQUIREMENTS.md's "connected and nearby Bluetooth
     * targets" needs `BluetoothAdapter.getBondedDevices`, which needs the `BLUETOOTH_CONNECT` runtime
     * permission, which is a user-visible prompt this work is not authorised to add. So the field is
     * honest rather than unused, and the gap is named in the module report.
     */
    private fun bluetoothRows(
        snapshot: AudioOutputSnapshot,
        names: OutputNames,
        activeId: String?,
    ): List<Pair<AudioOutputDevice, OutputTarget.Bluetooth>> {
        val sinks: List<AudioOutputDevice> = snapshot.devices.filter { it.kind.isBluetoothSink }
        val chosen: List<AudioOutputDevice> = sinks
            .groupBy { device -> duplicateKey(device, names) }
            .map { (_, group) -> pickOne(group, activeId) }
        return chosen
            // By name, and deliberately not with the live one first. Floating the active row to the top
            // was the first version of this and it made the list jump under the user's finger every time
            // the platform moved the route - and it made every route change a list change, so the sheet
            // re-diffed its rows to show the same rows in a different order. The tick says which one is
            // live; the order stays where the eye left it.
            .sortedBy { device -> displayName(device, names).lowercase() }
            .map { device ->
                device to OutputTarget.Bluetooth(
                    id = device.id,
                    displayName = displayName(device, names),
                    isConnected = true,
                )
            }
    }

    /**
     * What two entries for one speaker have in common.
     *
     * The name, lowercased, when there is one - the platform ids differ between the A2DP and LE Audio
     * views of the same headset, and the addresses can too, so the name is the only thing that matches.
     * A nameless device keys on its own id instead, because collapsing every unnamed sink into one row
     * would hide a second speaker rather than a duplicate of the first.
     */
    private fun duplicateKey(device: AudioOutputDevice, names: OutputNames): String =
        readableName(device, names)?.lowercase() ?: ("id:" + device.id)

    /** The live entry of a duplicate pair, or the kind the platform would route to first. */
    private fun pickOne(group: List<AudioOutputDevice>, activeId: String?): AudioOutputDevice =
        group.firstOrNull { device -> device.id == activeId }
            ?: group.minByOrNull { device -> device.kind.routingPriority }
            ?: group.first()

    /**
     * The row's label, which is never blank.
     *
     * REQUIREMENTS.md "Output": "The current output is always named in the player - 'Living room
     * speaker', 'This tablet' - so a user never wonders where sound is going." A row with no text fails
     * that outright, so a sink with nothing readable is labelled by its kind.
     */
    private fun displayName(device: AudioOutputDevice, names: OutputNames): String =
        readableName(device, names) ?: fallbackName(device.kind)

    /**
     * The device's own name, or null when what came back cannot be shown.
     *
     * Two rejections. Blank or whitespace is obvious. The second is not: a Bluetooth sink's
     * `getProductName()` can come back as **this** phone's model - the framework falls back to the local
     * product name when it has no Bluetooth name to hand, which happens when the app holds no
     * `BLUETOOTH_CONNECT` grant. "Pixel 9" on a row that is demonstrably not this phone is a worse lie
     * than "Bluetooth device", so [OutputNames.localProductName] is compared out.
     */
    private fun readableName(device: AudioOutputDevice, names: OutputNames): String? {
        val trimmed: String = device.name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val local: String? = names.localProductName?.trim()?.takeIf { it.isNotEmpty() }
        if (local != null && trimmed.equals(local, ignoreCase = true)) return null
        return trimmed
    }

    private fun fallbackName(kind: AudioOutputKind): String = when (kind) {
        AudioOutputKind.HEARING_AID -> "Hearing aid"
        AudioOutputKind.BLUETOOTH_LE, AudioOutputKind.BLUETOOTH_A2DP -> "Bluetooth device"
        AudioOutputKind.WIRED -> "Wired output"
        AudioOutputKind.BUILT_IN, AudioOutputKind.OTHER -> "This device"
    }
}

/**
 * The two names the mapper cannot work out for itself.
 *
 * Both come from the platform at the edge, so they are passed in rather than read here - the whole file
 * stays testable that way.
 */
public data class OutputNames(
    /**
     * What this phone or tablet is called in the list: "This phone", "This tablet".
     *
     * REQUIREMENTS.md "Output" prints "This tablet" in its own example, so the form factor is worth
     * telling apart; the provider reads it from the configuration's smallest width.
     */
    val thisDevice: String,
    /**
     * This device's own model name, or null when it is not known.
     *
     * Used only to reject it when a Bluetooth sink reports it as its own name. See
     * `OutputTargetMapper.readableName`.
     */
    val localProductName: String? = null,
)

/**
 * The picker's rows and the row that is live, as one value.
 *
 * One value rather than two flows because they are read together and must agree: see
 * [OutputTargetMapper.routes]. [active] is never null once a snapshot has been mapped - the worst case is
 * this device - so a surface naming the output always has something to print.
 */
public data class OutputRoutes(
    val targets: List<OutputTarget> = emptyList(),
    val active: OutputTarget? = null,
) {
    public companion object {
        /** Before the first snapshot, and after a read the platform refused. */
        public val Unknown: OutputRoutes = OutputRoutes()
    }
}

package app.needler.feature.player.output

import app.needler.core.domain.model.CastAvailability
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OutputTarget
import app.needler.feature.player.ui.PlayerFormat

/**
 * The "Play on" picker, screen 21.
 *
 * One list holding Cast receivers, Bluetooth sinks and this device, which is the whole reason the
 * picker exists instead of the system output dialog: "Cast targets must appear beside Bluetooth ones
 * in one list."
 */
data class OutputUiState(
    val targets: List<OutputTarget> = emptyList(),
    val selected: OutputTarget? = null,
    /** True while the first list is still being assembled - Bluetooth scanned, Cast probed. */
    val discovering: Boolean = false,
    /** A failure from trying to switch output, shown in the sheet rather than as a disappearing toast. */
    val error: NeedlerError? = null,
    /**
     * Output volume, 0f..1f, or null when nothing can set it.
     *
     * Null is the honest default today. Screen 21 draws a volume slider at the foot of the sheet,
     * and `PlaybackController` has no volume member at all - there is no `setVolume`, and
     * `PlaybackState` carries no level - so there is nothing for the control to drive. The field is
     * here, and the slider is drawn when it is non-null, so that adding `setVolume` to the controller
     * is a two-line change in the view model rather than a redesign of this screen.
     */
    val volume: Float? = null,
) {
    /** True when the list is empty and nothing is still being looked for. */
    val isEmpty: Boolean get() = targets.isEmpty() && !discovering

    /**
     * What the sheet actually draws: [targets], with this phone guaranteed to be on it.
     *
     * ## Why the discovering sheet may not be empty
     *
     * It was. `player-output-discovering-phone.png` is a sheet headed "Play on" carrying one grey
     * sentence - "Looking for speakers nearby" - and nothing selectable at all: no spinner, no
     * skeleton, and not even this phone, which is the one output that is always reachable and needs
     * no discovery to find. A picker that offers nothing is a dead end, and the listener who opened
     * it did so because they wanted sound somewhere.
     *
     * Discovery enumerates Bluetooth sinks and Cast receivers. The speaker in the listener's hand is
     * not discovered, it is always there, so it is listed from the first frame and the scan fills in
     * around it. `OutputTargetMapper` already makes this device "always the last row, and always
     * present" once `:player:service` is wired; this is the same guarantee held one layer up, so the
     * sheet is honest before the routes arrive as well as after.
     *
     * Synthesised rather than required of the caller: a view model that has not heard from the router
     * yet has no [OutputTarget.ThisDevice] to hand, and the one it would construct is this one.
     */
    val offerable: List<OutputTarget>
        get() = if (targets.any { it is OutputTarget.ThisDevice }) {
            targets
        } else {
            targets + OutputTarget.ThisDevice(displayName = PlayerFormat.LOCAL_OUTPUT)
        }

    /** Whether [target] is the one in use. Compared by id, since the objects are re-created on each scan. */
    fun isSelected(target: OutputTarget): Boolean = selected?.id == target.id

    /**
     * Cast targets whose reachability has not come back yet.
     *
     * Worth knowing separately from [discovering]: the list is complete and usable, but one row in
     * it is still making up its mind, and the sheet says so rather than letting a row sit greyed for
     * no visible reason.
     */
    val hasPendingProbe: Boolean
        get() = targets.any { it is OutputTarget.Cast && it.availability == CastAvailability.UNKNOWN }

    companion object {
        val Empty: OutputUiState = OutputUiState()
    }
}

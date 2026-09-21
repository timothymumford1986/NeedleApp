package app.needler.feature.player.output

import app.needler.core.domain.model.CastAvailability
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OutputTarget

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

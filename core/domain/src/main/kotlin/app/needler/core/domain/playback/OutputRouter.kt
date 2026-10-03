package app.needler.core.domain.playback

import app.needler.core.domain.model.OutputTarget
import kotlinx.coroutines.flow.Flow

/**
 * Where sound can go, and where it is going: the discovery half of the "Play on" picker.
 *
 * REQUIREMENTS.md "Output" is specific about which layer owns this, and it is not a repository:
 * "Bluetooth sinks come from `AudioManager`'s device list and Cast receivers from a `MediaRouter`
 * discovery session; both belong to the layer that owns the Media3 session, because a repository over
 * Room and HTTP that opened a route-discovery callback would be keeping the radio awake from the wrong
 * place entirely." So the contract is declared here, in domain types, and `:player:service` implements
 * it over `AudioManager` - the same arrangement, and for the same reason, as [PlaybackController].
 *
 * ## Why this is not a member of PlaybackController
 *
 * It was going to be, and the rejected alternative is worth recording because the two interfaces look
 * alike. [PlaybackController] is implemented by the session **and by a hand-written fake in three
 * separate test source sets** - `:feature:player`, `:feature:library` and `:feature:search` - because
 * every screen that can start playback holds one. Discovery is not something any of those screens does,
 * so adding a member there would have made three fakes pretend to enumerate speakers in order to compile.
 *
 * It is also a different kind of thing. [PlaybackController]'s own documentation calls its flows "state
 * a person changed"; this one is a subscription with a lifetime - an `AudioDeviceCallback` that is
 * registered on collection and unregistered when the last collector leaves - and keeping it on its own
 * interface is what makes that lifetime inspectable instead of buried among twenty transport commands.
 * One object implements both, so nothing is duplicated: `Media3PlaybackController` is bound to both
 * interfaces as the same singleton.
 *
 * ## What is here and what is not
 *
 * Listing targets is here. **Remembering the choice is not** - that stays on
 * [app.needler.core.domain.repository.PlaybackSettingsRepository], which is where REQUIREMENTS.md puts
 * it, and the two answers differ on purpose: [observeActiveOutput] is where sound is *actually* going,
 * the repository's selection is where the user last pointed it, and a speaker that drops out underneath
 * a running track makes those two different sentences that are both true.
 */
public interface OutputRouter {

    /**
     * Everything the picker lists: Bluetooth sinks, then this device.
     *
     * One list, because that is the requirement the picker exists for - REQUIREMENTS.md "Output": the
     * picker "replaces the system output dialog because Cast targets must appear beside Bluetooth ones
     * in one list." [OutputTarget.Cast] entries are not emitted yet and the open question at
     * REQUIREMENTS.md:1141 is why: a receiver fetches audio itself and cannot reach a VPN-only,
     * self-signed or plain-HTTP server, so offering one before the reachability probe exists would
     * produce the failure line 515 forbids - a speaker that fails after it has been picked.
     *
     * Never empty: this device is always a row, because something is always able to make a sound.
     *
     * **Cold, and that is part of the contract.** Collecting registers whatever the implementation needs
     * to watch for devices arriving and leaving; cancelling releases it. A caller that wants the list
     * for the life of a screen collects it for the life of that screen and nothing longer.
     */
    public fun observeOutputTargets(): Flow<List<OutputTarget>>

    /**
     * The target sound is actually coming out of, or null before the first answer has arrived.
     *
     * REQUIREMENTS.md "Output": "The current output is always named in the player - 'Living room
     * speaker', 'This tablet' - so a user never wonders where sound is going." Naming the *selected*
     * target is not enough to satisfy that, which is the whole reason this is a second flow: a
     * connected Bluetooth device is not necessarily the one receiving audio, and with two speakers
     * connected the difference is visible on the chip under the transport.
     *
     * Null means the question has not been answered yet, not that audio is going nowhere. A caller
     * showing the output falls back to the remembered selection in that case, which is what
     * [PlaybackState.output] does.
     */
    public fun observeActiveOutput(): Flow<OutputTarget?>
}

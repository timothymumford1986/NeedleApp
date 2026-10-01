package app.needler.wear.playback

/**
 * Which of the two things the watch can drive is on screen.
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Wear OS" puts three things in Wear's v1 scope - the
 * transport, the crate, and playback of on-device audio - and the first two are the *phone's* while the
 * third is the *watch's*. That leaves one transport screen describing two completely different players,
 * and a user who cannot tell which is a user who presses pause and hears nothing stop.
 *
 * So the source is named on screen, in both states. It is tempting to mark only the unusual one and
 * leave the phone remote unlabelled, since that is what the watch has always been; that is worse, because
 * "unlabelled" is indistinguishable from "this build does not say", and the whole point is that the
 * answer is never in doubt.
 *
 * ## The watch does not ask the user to choose
 *
 * There is no source picker. [WATCH] is shown whenever the watch's own session has something loaded, and
 * [PHONE] otherwise, because music coming out of the user's earbuds is not something to make them select
 * - and because the alternative is a screen offering to pause a phone in another room while the watch
 * plays on. Starting an album from the on-watch screen is what switches to [WATCH]; the local session
 * ending is what switches back.
 */
enum class WearPlaybackSource {

    /**
     * The phone's session, over the data layer. Every command is a message and the state is a published
     * snapshot - see [WearPlaybackProtocol].
     */
    PHONE,

    /**
     * The watch's own session, playing files out of [app.needler.wear.store.WearAudioStore].
     *
     * Works with the phone out of range, switched off, or not carrying the album at all, which is the
     * entire reason the transfer exists.
     */
    WATCH,
    ;

    /** What the transport screen prints. Two words at most: there is room for a caption, not a sentence. */
    val label: String
        get() = when (this) {
            PHONE -> "On phone"
            WATCH -> "On watch"
        }
}

package app.needler.wear

import app.needler.core.domain.playback.PlaybackController
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything the watch sends the phone arrives here.
 *
 * Declared in `app/src/main/AndroidManifest.xml` with an intent filter scoped to the `wear://` scheme
 * and the `/needler` path prefix, which is the machine-readable form of
 * [WearPlaybackProtocol.NAMESPACE]: without it this service would be woken for every app's data-layer
 * traffic on the device.
 *
 * ## Why a listener service at all, and why it publishes nothing itself
 *
 * A `WearableListenerService` runs on demand. Google Play services starts it, delivers one message,
 * and stops it when it goes idle - which is exactly right for a command and no use whatever for
 * observing a flow. So this class does one thing per message and returns; the publishing that answers
 * a [WearPlaybackProtocol.PATH_REQUEST_STATE] lives in [WearStatePublisher], a `@Singleton` whose
 * window outlives this callback. See that class for the whole lifecycle argument.
 *
 * ## Exported, and what that obliges
 *
 * `android:exported="true"` is mandatory - Google Play services is the caller, and an unexported
 * service would simply never be reached. It is not a licence to trust the input, so three things are
 * checked before anything touches the session:
 *
 *  1. The path is inside Needler's own namespace.
 *  2. The path is one this contract actually defines. An unknown path under the prefix is dropped
 *     rather than matched loosely, so a future command cannot be half-handled by an older phone.
 *  3. **The sending node is currently connected to this device.** That is the check the manifest's
 *     comment promises. The data layer already restricts delivery to apps sharing a package name and
 *     signing key, so this is defence in depth rather than the only lock on the door - but the door is
 *     exported, and "Google Play services is the only caller" is an assumption worth verifying rather
 *     than asserting.
 *
 * Nothing is logged about a rejected message. There is nothing useful to say, and REQUIREMENTS.md
 * "Security" is explicit that release builds carry no debug logging.
 *
 * ## The sync nudge lands here too, and needed no manifest change
 *
 * [WearPlaybackProtocol.PATH_SYNC_NUDGE] sits under the same `/needler` prefix the intent filter already
 * names, so the message that drives on-device audio sync arrives through the same door as a pause. Two
 * things about it are deliberate:
 *
 *  * **`DATA_CHANGED` is still not in the filter.** The watch's selection is a *data item*, and it would
 *    have been the obvious thing to listen for - it changes every time the watch finishes a track. That
 *    is exactly why it is not: a watch charging overnight would wake this phone once per track for a
 *    whole album. One message saying "read it" replaces every one of those events, and the phone reads
 *    the item itself when it gets there.
 *  * **The nudge returns before the transport handling.** It opens no publishing window, because a nudge
 *    means the watch is copying music, not that anybody is looking at a transport - and a window per
 *    ingested track would have the phone observe its own session all night.
 *
 * ## `onCapabilityChanged` is not overridden
 *
 * The manifest's intent filter names `CAPABILITY_CHANGED` as well as `MESSAGE_RECEIVED`, and nothing
 * here handles it. That is consistent rather than incomplete: no capability is declared in
 * `res/values/wear.xml` on either side, so no capability event exists to receive. Declaring one is the
 * follow-up `DataLayerPlaybackClient.broadcast` already notes - it is what would let the watch address
 * the phone instead of shouting a command at every paired device - and the filter is ready for it.
 *
 * ## Blocking is correct here
 *
 * `onMessageReceived` is called on a background thread and the service is alive for as long as the
 * callback runs. So the work is done with [runBlocking] rather than launched into a scope that would
 * be torn down underneath it: launching and returning is how a command reaches a process that Google
 * Play services has already stopped. The whole callback is capped by [MESSAGE_TIMEOUT_MS] because
 * `PlaybackController`'s commands deliberately wait for the session to connect, and a session that
 * never connects must not hold a Play services dispatch thread for ever.
 */
class NeedlerWearListenerService : WearableListenerService() {

    /**
     * Built on first use rather than in `onCreate`.
     *
     * A message that fails the path checks above never touches Google Play services at all, which is
     * the common case for a service woken by traffic that turns out not to be ours.
     */
    private val nodeClient: NodeClient by lazy { Wearable.getNodeClient(applicationContext) }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path: String = messageEvent.path
        if (!path.startsWith(WearPlaybackProtocol.NAMESPACE)) return
        val known: Boolean = path == WearPlaybackProtocol.PATH_REQUEST_STATE ||
            path == WearPlaybackProtocol.PATH_SYNC_NUDGE ||
            path in WearPlaybackProtocol.COMMAND_PATHS
        if (!known) return

        // Read off the event before any suspending work: a MessageEvent belongs to the callback.
        val payload: ByteArray? = messageEvent.data
        val sourceNodeId: String? = messageEvent.sourceNodeId

        // A sync pass gets its own, much larger budget. Everything else on this wire is a command whose
        // slowest case is a cold MediaController bind; a pass reads the mirror, may fetch a cover, and
        // opens a descriptor per track, and putting a thirty-megabyte asset into Play services' store is
        // not instant even though the transfer itself happens afterwards and without us.
        val budget: Long = if (path == WearPlaybackProtocol.PATH_SYNC_NUDGE) {
            SYNC_TIMEOUT_MS
        } else {
            MESSAGE_TIMEOUT_MS
        }

        runBlocking {
            withTimeoutOrNull(budget) {
                handle(path = path, payload = payload, sourceNodeId = sourceNodeId)
            }
        }
    }

    /**
     * One verified message.
     *
     * Every path ends by asking the publisher for a window, commands included. A command's own effect
     * arrives on the watch as the next snapshot - that is the only honest confirmation a fire-and-forget
     * message can have - and renewing here means a watch whose renewal went astray still sees the pause
     * it pressed.
     */
    private suspend fun handle(path: String, payload: ByteArray?, sourceNodeId: String?) {
        if (!isFromConnectedNode(sourceNodeId)) return

        val dependencies: WearEntryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            WearEntryPoint::class.java,
        )

        // The nudge is answered and returns. It touches no session, so it deliberately does not fall
        // through to the transport handling below - and above all it must not open a publishing window:
        // a watch charging overnight nudges after every track it ingests, and each one would otherwise
        // have the phone observe its own session for a minute for a watch whose screen is off.
        if (path == WearPlaybackProtocol.PATH_SYNC_NUDGE) {
            // One byte, or nothing. An absent or unrecognised payload is read as "only if charging",
            // which is the conservative reading: the alternative would spend a watch's battery on the
            // strength of a byte nobody sent.
            val urgent: Boolean = payload?.firstOrNull() == WearPlaybackProtocol.NUDGE_NOW
            dependencies.audioSync().onNudge(urgent = urgent)
            return
        }

        val controller: PlaybackController = dependencies.playbackController()

        when (path) {
            WearPlaybackProtocol.PATH_PLAY_PAUSE -> controller.playPause()
            WearPlaybackProtocol.PATH_NEXT -> controller.skipToNext()
            WearPlaybackProtocol.PATH_PREVIOUS -> controller.skipToPrevious()
            WearPlaybackProtocol.PATH_SKIP_TO_ROW -> {
                // The row id, or nothing. An empty payload is dropped rather than guessed at, and a
                // row id that has since left the crate matches nothing inside the controller - which
                // is the correct outcome, not a failure to report.
                val rowId: String? = payload
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { bytes -> String(bytes, Charsets.UTF_8) }
                if (rowId != null) controller.skipToQueueItem(rowId)
            }
            // PATH_REQUEST_STATE touches nothing a user can hear. The publish below is the answer.
            else -> Unit
        }

        dependencies.statePublisher().onStateRequested()
    }

    /**
     * Whether [sourceNodeId] names a node currently connected to this device.
     *
     * A message that was delivered came from a node that was connected, so in ordinary operation this
     * is true; a race between delivery and the link dropping can make it false, and dropping the
     * command is the right answer there too - the watch that sent it is no longer in a position to see
     * what happened.
     */
    private suspend fun isFromConnectedNode(sourceNodeId: String?): Boolean {
        if (sourceNodeId.isNullOrEmpty()) return false
        val nodes: List<Node> =
            orNullOnFailure { nodeClient.connectedNodes.awaitResult() } ?: return false
        return nodes.any { node -> node.id == sourceNodeId }
    }

    private companion object {
        /**
         * The whole callback's budget.
         *
         * It has to cover a cold `MediaController` bind plus [WearStatePublisher]'s own wait for the
         * first publish, which is five seconds, so ten leaves room without letting a wedged session
         * hold a Google Play services thread indefinitely.
         */
        const val MESSAGE_TIMEOUT_MS: Long = 10_000L

        /**
         * A sync pass's budget.
         *
         * It has to cover reading the mirror for one album, a cover fetch over whatever connection the
         * phone has, and opening and handing over [WearPlaybackProtocol.MAX_TRACKS_IN_FLIGHT]
         * descriptors. It does **not** have to cover the transfer: the bytes cross the link after the
         * item is published, with nothing of Needler's awake, which is the whole reason the audio is an
         * `Asset` and not a channel.
         *
         * A minute is generous for that and still bounded, so a wedged pass cannot hold a Google Play
         * services dispatch thread indefinitely. A pass that times out costs nothing: the items are
         * retained, and the watch nudges again.
         */
        const val SYNC_TIMEOUT_MS: Long = 60_000L
    }
}

package app.needler.wear.sync

import app.needler.wear.playback.WearPlaybackProtocol
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Audio arriving from the phone, whether or not the watch app is open.
 *
 * ## Why the watch now has a listener service, when it deliberately did not
 *
 * `wear/src/main/AndroidManifest.xml` used to say, in so many words, that no `WearableListenerService`
 * was declared because "the watch has nothing to do with a playback snapshot while its screen is off,
 * and a manifest-declared listener would be woken for every track change on the phone all day".
 *
 * That argument is intact and this service does not contradict it. Its intent filter names
 * `DATA_CHANGED` only, and its path prefixes are the track and cover families under
 * [WearPlaybackProtocol.SYNC_NAMESPACE] - not [WearPlaybackProtocol.PATH_NOW_PLAYING], not
 * [WearPlaybackProtocol.PATH_CRATE], and not the selection item this watch publishes itself. So nothing
 * here is woken by a transport snapshot, and the traffic it *is* woken by is a thirty-megabyte album
 * arriving, which nobody will hold a watch app open for twenty minutes to receive.
 *
 * ## Why the work happens in the callback rather than in a job
 *
 * `onDataChanged` runs on a background thread and the service stays alive for as long as it is running,
 * so the ingest is done with [runBlocking] rather than launched into a scope that would be torn down
 * underneath it. That is the same call [app.needler.wear.playback.WearPlaybackProtocol]'s phone-side
 * listener makes, for the same reason.
 *
 * `WorkManager` is the textbook answer and it is deliberately not used. It would mean a new dependency
 * and an initialiser to configure in a module that has no Hilt, to buy reliability that the data layer
 * already provides: the items are *retained*, so an ingest that is cut short leaves them published, and
 * the next pass - the next item to arrive, or the next time the app opens - finds them again. See
 * [WearSyncCoordinator] on why the retained set rather than the event is the source of truth.
 *
 * ## The timeout, and what it is really bounding
 *
 * Resolving an `Asset` is where the bytes actually cross the link, so a pass can legitimately take
 * minutes. [PASS_TIMEOUT_MS] is therefore generous by the standards of a service callback and is not
 * trying to keep the pass short: it is there so that a transfer which has stalled - a watch that walked
 * out of range mid-asset - cannot hold this service alive indefinitely. Losing a stalled pass costs
 * nothing, because the item is still published.
 *
 * Nothing is logged about a message this service drops. REQUIREMENTS.md "Security" requires release
 * builds to carry no debug logging, and there is nothing useful to say that would not name a path.
 */
class NeedlerWearSyncService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        // The event is only a trigger. What is ingested is whatever is retained, which is what makes a
        // cut-short pass, a killed process and a reboot mid-transfer all recover on their own.
        //
        // The buffer is read here and not released: this callback's contract is that the framework
        // closes it when the method returns, so releasing it as well would be a second release of
        // something already owned - and every field is read before the suspending work starts anyway.
        val relevant: Boolean = events.any { event -> isSyncPayload(event) }
        if (!relevant) return

        val coordinator: WearSyncCoordinator = WearSyncCoordinator.get(applicationContext)
        runBlocking {
            withTimeoutOrNull(PASS_TIMEOUT_MS) {
                coordinator.ingestPending()
            }
        }
    }

    /**
     * Whether an event names a track or a cover.
     *
     * A deletion counts, because the phone deletes an item once the watch has reported holding what it
     * carried - and the pass that follows is what notices there is now room for the next one. Checking
     * the type at all would make the pipeline stall on exactly the event that says it may advance.
     */
    private fun isSyncPayload(event: DataEvent): Boolean {
        val path: String = event.dataItem.uri.path ?: return false
        return path.startsWith(WearPlaybackProtocol.PATH_SYNC_TRACK_PREFIX) ||
            path.startsWith(WearPlaybackProtocol.PATH_SYNC_COVER_PREFIX)
    }

    private companion object {
        /** The whole pass's budget. See the class note for what it is bounding. */
        const val PASS_TIMEOUT_MS: Long = 4L * 60L * 1000L
    }
}

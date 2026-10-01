package app.needler.wear

import app.needler.core.domain.playback.PlaybackController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * How [NeedlerWearListenerService] reaches the application graph.
 *
 * ## Pulled rather than injected
 *
 * Hilt could field-inject a `Service` with `@AndroidEntryPoint`, and this does not. Two reasons, and
 * the first is the one that matters:
 *
 *  * **There is no lifecycle for injected fields to span.** Google Play services constructs this
 *    service, hands it one message, and stops it when it goes idle. Fields injected in `onCreate` buy
 *    nothing over two calls at the top of the one method that uses them, and the graph is then touched
 *    only on the code path that actually needs it.
 *  * It keeps a generated Hilt superclass out from between Google Play services and the service it
 *    constructs and destroys on its own schedule.
 *
 * `:widget` pulls for a related reason - a `GlanceAppWidget` is not an injectable component at all -
 * and `WidgetEntryPoint` is the pattern this follows.
 *
 * ## Why this interface is not `internal`
 *
 * Everything else new in this package that can be internal, is. This one is public because Hilt's
 * processor generates Java that implements it, and a Kotlin `internal` member's JVM name carries a
 * module-name suffix that generated Java cannot spell. Nothing outside this package has a reason to
 * use it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WearEntryPoint {

    /**
     * The one `@Singleton` bound in `:player:service`'s `PlayerServiceBindings`, and therefore the
     * *same* object the player screen, the widgets and Android Auto drive.
     *
     * This is what makes a watch "one more client of the session" in the sense REQUIREMENTS.md
     * "Playback" means it: a pause from the watch is the same pause as a pause from the lock screen,
     * and the restart-the-track threshold on previous is decided once, in the controller, rather than
     * by each surface.
     */
    fun playbackController(): PlaybackController

    /**
     * The publisher that owns the publishing window.
     *
     * A `@Singleton`, which is the point: the window has to outlive the service callback that opened
     * it. See [WearStatePublisher] for why it is a window at all.
     */
    fun statePublisher(): WearStatePublisher

    /**
     * The audio sync: the phone half of REQUIREMENTS.md's "playback of on-device audio synced from the
     * phone over the data layer".
     *
     * A `@Singleton` for a different reason from [statePublisher]. There is no window to outlive here -
     * a pass starts and finishes inside one callback - but two nudges must not run two passes at once,
     * and the `Mutex` that stops them has to be the same object on both. See [WearAudioSync].
     */
    fun audioSync(): WearAudioSync
}

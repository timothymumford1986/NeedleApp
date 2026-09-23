package app.needler

import app.needler.core.network.NetworkDiagnostics
import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.needler.core.data.background.NeedlerNotifier
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import app.needler.core.network.NeedlerHttpClient
import app.needler.core.network.subsonic.SubsonicApi
import app.needler.image.buildArtworkImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt's application entry point.
 *
 * This is the root of the dependency graph: :core:data's repository
 * implementations are bound to the :core:domain interfaces that every feature
 * module, the Glance widgets and the Media3 service ask for. Those bindings go
 * in Hilt modules under :app, not here.
 *
 * Two things happen here that could not happen anywhere else.
 *
 * **`Configuration.Provider` with the injected `HiltWorkerFactory`.** The
 * polling, sync and download Workers in :core:data are `@HiltWorker`s with
 * constructor dependencies, and WorkManager's default factory cannot build one:
 * it would throw at job start, on a background thread, with nothing in the app
 * saying why. Supplying this configuration is also what makes removing
 * WorkManager's default initializer from AndroidManifest.xml correct - the two
 * changes are one change, and doing the manifest half first would leave
 * WorkManager uninitialised at runtime. Note that returning a configuration
 * does not *do* anything on its own: initialisation happens on demand, the
 * first time something calls `WorkManager.getInstance`.
 *
 * **Notification channel creation.** Mandatory since Android 8, and mandatory
 * *before* anything posts: a notification sent to a channel that does not exist
 * is dropped silently. It is done here because this is the only place
 * guaranteed to run before a Worker in this process does.
 *
 * Nothing that touches the network, the database or the credential store runs
 * in `onCreate`: REQUIREMENTS.md budgets cold start to library content at under
 * 1.2 s on a mid-range 2022 phone. Channel creation is a handful of binder
 * calls against a service that is already up, and no I/O of ours.
 */
@HiltAndroidApp
class NeedlerApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    @Inject lateinit var http: NeedlerHttpClient

    @Inject lateinit var subsonic: SubsonicApi

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var notifier: NeedlerNotifier

    /**
     * Read by WorkManager the first time `getInstance` is called, which is long
     * after Hilt has injected [workerFactory].
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // The network layer has always built its redacting log interceptor and
        // always thrown the output away, because nothing ever installed a sink
        // and the interceptor short-circuits on NetworkLogSink.None. That is
        // why a failure on a device left no trace at all. This is the line that
        // was missing; it checks FLAG_DEBUGGABLE itself, so a release build
        // stays silent whether or not anyone remembers to guard the call.
        NetworkDiagnostics.installForApplication(this)
        // Idempotent, and the only thing standing between a posted notification
        // and it being discarded without a trace.
        notifier.ensureChannels()
    }

    /**
     * Coil asks for this lazily, the first time a screen actually draws artwork, so nothing here runs
     * during `onCreate` and the cold-start budget is unaffected. By then Hilt has injected both
     * fields.
     *
     * Registering it on the singleton loader is what makes every `AsyncImage` in every module able to
     * take a domain `ArtworkRef` as its model. Without it, the refs reach Coil as an unrecognised type
     * and each one silently renders a placeholder.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        buildArtworkImageLoader(context = this, http = http, subsonic = subsonic)
}

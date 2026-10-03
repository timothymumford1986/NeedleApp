package app.needler.player.service.di

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.common.util.UnstableApi
import app.needler.core.data.diagnostics.SessionDiagnosticsSink
import app.needler.core.domain.cache.AudioCacheWriter
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.playback.OutputRouter
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.repository.PlaybackSettingsRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.domain.usecase.ResolvePlayableSourceUseCase
import app.needler.core.network.NeedlerHttpClient
import app.needler.core.network.media.StreamQuality
import app.needler.core.network.media.SubsonicMediaUrls
import app.needler.core.network.subsonic.SubsonicApi
import app.needler.player.service.audio.EqualiserAudioProcessor
import app.needler.player.service.controller.Media3PlaybackController
import app.needler.player.service.output.AndroidAudioOutputDevices
import app.needler.player.service.output.AudioOutputDevices
import app.needler.player.service.output.OutputNames
import app.needler.player.service.output.OutputRouteMonitor
import app.needler.player.service.source.AudioUrls
import app.needler.player.service.source.NeedlerAudioDataSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

/**
 * What `:player:service` contributes to the application graph.
 *
 * Nothing here is new policy. The repositories, the audio store and the cache index are provided by
 * `:core:data`'s own module; this one assembles the Media3 pieces on top of them and binds
 * [PlaybackController] so that `:feature:player`, the widgets and Wear can talk to the session in domain
 * types without ever seeing a Media3 class.
 */
@Module
@InstallIn(SingletonComponent::class)
public object PlayerServiceModule {

    /**
     * One equaliser for the process.
     *
     * It is shared between the player - which holds it in its sink's processor chain - and the coordinator,
     * which pushes new settings into it. A second instance would be the one on screen 19 while the one in the
     * chain stayed flat.
     */
    @Provides
    @Singleton
    public fun provideEqualiser(): EqualiserAudioProcessor = EqualiserAudioProcessor()

    /**
     * The platform's output device list, behind the module's own port.
     *
     * REQUIREMENTS.md "Output" assigns Bluetooth enumeration here rather than to a repository. The
     * implementation needs no manifest permission - checked against the installed SDK's
     * `data/annotations.zip` rather than assumed; see [AndroidAudioOutputDevices] for the four entries
     * that were read.
     */
    @Provides
    @Singleton
    public fun provideAudioOutputDevices(
        @ApplicationContext context: Context,
    ): AudioOutputDevices = AndroidAudioOutputDevices(
        audioManager = context.getSystemService(AudioManager::class.java),
    )

    /**
     * What this device and its model are called, for the picker's labels.
     *
     * REQUIREMENTS.md "Output" prints "This tablet" in its own example of naming the current output, so
     * the form factor is told apart here rather than hard-coded. The signal is
     * `smallestScreenWidthDp`, not the window's width, and the difference matters: the window is what
     * decides whether the sidebar is drawn, and a phone in landscape gets that sidebar without becoming a
     * tablet. The name has to follow the hardware, or a listener turning their phone sideways watches
     * the output rename itself.
     *
     * 600 dp is the Material window-size-class boundary the app's own layout switch is built on, reused
     * here so the number has one origin.
     *
     * [OutputNames.localProductName] is this device's own model, and it is carried for one purpose: a
     * Bluetooth sink whose name the framework could not read reports *this* phone's model instead, and
     * the mapper has to be able to recognise and reject it.
     */
    @Provides
    @Singleton
    public fun provideOutputNames(@ApplicationContext context: Context): OutputNames = OutputNames(
        thisDevice = if (context.resources.configuration.smallestScreenWidthDp >= TABLET_WIDTH_DP) {
            "This tablet"
        } else {
            "This phone"
        },
        localProductName = Build.MODEL,
    )

    /**
     * One route monitor for the process, because it is one `AudioDeviceCallback` for the process.
     *
     * Provided by hand rather than `@Inject`-constructed so that the sharing scope stays a constructor
     * parameter with a default: a test hands in its own scope and keeps the flow on the test dispatcher.
     * See [OutputRouteMonitor] for why the subscription is reference-counted rather than tied to the
     * session's lifetime.
     */
    @Provides
    @Singleton
    public fun provideOutputRouteMonitor(
        devices: AudioOutputDevices,
        names: OutputNames,
    ): OutputRouteMonitor = OutputRouteMonitor(devices = devices, names = names)

    @Provides
    @Singleton
    public fun provideResolvePlayableSource(
        libraryRepository: LibraryRepository,
        pinRepository: PinRepository,
        playbackSettingsRepository: PlaybackSettingsRepository,
        sessionRepository: SessionRepository,
    ): ResolvePlayableSourceUseCase = ResolvePlayableSourceUseCase(
        libraryRepository = libraryRepository,
        pinRepository = pinRepository,
        playbackSettingsRepository = playbackSettingsRepository,
        sessionRepository = sessionRepository,
    )

    /**
     * The adapter from the player's [AudioUrls] port to the Subsonic URL builder.
     *
     * This is the only file in the module that imports `:core:network`, and it does nothing but build a
     * string. The stream and download endpoints are the one thing the player genuinely cannot get from a
     * repository: Media3 fetches the bytes itself, so it needs a self-contained URL rather than a byte
     * stream someone else has already opened.
     */
    @Provides
    @Singleton
    public fun provideAudioUrls(subsonic: SubsonicApi): AudioUrls = SubsonicAudioUrls(subsonic.mediaUrls)

    /**
     * The HTTP data source for audio, over the project's one OkHttp client.
     *
     * `mediaClient` rather than `client`: it carries the longer read timeout a stalled stream needs, and it
     * shares the connection pool, the certificate pin and the credential interceptor with every other request
     * the app makes. A second client would be a second cert-pinning policy, which is the thing
     * REQUIREMENTS.md has one client to avoid.
     */
    @OptIn(UnstableApi::class)
    @Provides
    @Singleton
    public fun provideHttpDataSourceFactory(http: NeedlerHttpClient): HttpDataSource.Factory =
        OkHttpDataSource.Factory(http.mediaClient)

    @OptIn(UnstableApi::class)
    @Provides
    @Singleton
    public fun provideAudioDataSourceFactory(
        resolveSource: ResolvePlayableSourceUseCase,
        cacheWriter: AudioCacheWriter,
        pinRepository: PinRepository,
        audioUrls: AudioUrls,
        httpDataSourceFactory: HttpDataSource.Factory,
    ): NeedlerAudioDataSource.Factory = NeedlerAudioDataSource.Factory(
        resolveSource = resolveSource,
        cacheWriter = cacheWriter,
        pinRepository = pinRepository,
        audioUrls = audioUrls,
        httpDataSourceFactory = httpDataSourceFactory,
        // Not a graph binding, for the same reason `DataModule` gives where it hands the same sink
        // to the store: the buffer is a process-wide object installed before Hilt exists, and making
        // it a graph type would need a qualifier per source for no gain.
        diagnostics = SessionDiagnosticsSink.forPlayback(),
    )

    /** The Material window-size-class boundary, reused so "tablet" means one thing in this app. */
    private const val TABLET_WIDTH_DP: Int = 600
}

/** Interface bindings, which Dagger wants on an abstract type rather than an object. */
@Module
@InstallIn(SingletonComponent::class)
public abstract class PlayerServiceBindings {

    /**
     * The one binding that makes the player boundary real.
     *
     * `:feature:player` injects `PlaybackController` and gets this. It has no Media3 dependency, cannot reach
     * a `MediaController`, and can be tested against a fake with no session running.
     */
    @Binds
    @Singleton
    public abstract fun bindPlaybackController(impl: Media3PlaybackController): PlaybackController

    /**
     * The same singleton, bound again as the discovery half of the picker.
     *
     * Two interfaces on one object, not two objects: `:feature:player`'s output picker injects
     * [OutputRouter] and gets the thing that owns the session, with no Media3 on its own classpath.
     * `@Singleton` on both bindings is what keeps it one instance - and therefore one shared
     * `AudioDeviceCallback` - rather than a second route monitor watching the same devices.
     */
    @Binds
    @Singleton
    public abstract fun bindOutputRouter(impl: Media3PlaybackController): OutputRouter
}

/**
 * [AudioUrls] over `SubsonicMediaUrls`.
 *
 * The one job worth naming is the format translation, and the rule it must not break: the format arrives
 * already resolved, and this maps it across without re-deciding anything. Re-deciding here would be the bug
 * REQUIREMENTS.md spends a whole section on - a 320 kbps transcode becoming a FLAC track's permanent offline
 * copy, silently, because two places both thought they owned the choice.
 */
internal class SubsonicAudioUrls(
    private val urls: SubsonicMediaUrls,
) : AudioUrls {

    override fun streamUrl(handle: TrackFetchHandle, format: StreamFormat): String = urls.streamUrl(
        trackId = handle.subsonicTrackId,
        quality = when (format) {
            StreamFormat.Original -> StreamQuality.Original
            is StreamFormat.Transcoded -> StreamQuality.Transcoded(
                format = format.codec,
                maxBitRateKbps = format.maxBitrateKbps,
            )
        },
        // Asked for on a transcode so the player has a length to draw a scrubber against before the stream
        // ends. On the raw path the server sends a real Content-Length and this is ignored.
        estimateContentLength = format != StreamFormat.Original,
    )
}

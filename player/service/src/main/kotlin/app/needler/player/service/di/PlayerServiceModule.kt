package app.needler.player.service.di

import androidx.annotation.OptIn
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.common.util.UnstableApi
import app.needler.core.domain.cache.AudioCacheWriter
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.TrackFetchHandle
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
import app.needler.player.service.source.AudioUrls
import app.needler.player.service.source.NeedlerAudioDataSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
    )
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

package app.needler.player.service.session

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import app.needler.player.service.audio.EqualiserAudioProcessor
import app.needler.player.service.source.NeedlerAudioDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the one `ExoPlayer` the session owns.
 *
 * Everything that has to be decided at construction time is decided here, and the reason for each is worth
 * writing down because several of them are the difference between a player that behaves and one that does
 * not.
 *
 * ## Gapless
 *
 * Nothing is done for it, which is the point. ExoPlayer concatenates a playlist gaplessly on its own: it
 * reads the encoder delay and padding out of the container, starts decoding the next item before the current
 * one ends, and does not re-buffer at the join. The settings toggle therefore has nothing to switch *on*;
 * see `FadePlanner` for what switching it *off* means.
 *
 * ## Audio focus and ducking
 *
 * `setAudioAttributes(..., handleAudioFocus = true)` hands focus to ExoPlayer, which is the only correct
 * answer. Media3 then pauses for a permanent loss, ducks to a fifth of the volume for a transient duck -
 * navigation prompts, which is the common case in a car - and restores afterwards. Implementing that by hand
 * means reimplementing `AudioManager.requestAudioFocus` and getting the API 26 focus-request path wrong.
 *
 * ## Becoming noisy
 *
 * `setHandleAudioBecomingNoisy(true)`. Headphones pulled out means pause, immediately, before the next buffer
 * reaches the speaker. Not handling it is the bug where a bus hears the rest of the album.
 *
 * ## Wake mode
 *
 * `WAKE_MODE_NETWORK`, because the same player streams and plays local files. It takes a wifi lock as well as
 * a partial wake lock, and holds neither while paused.
 *
 * ## Buffering
 *
 * The default `DefaultLoadControl` buffers for video. Audio is two orders of magnitude smaller, so the
 * buffer is widened: a 50-second target costs a couple of megabytes of RAM and covers a tunnel, a lift and a
 * carriage full of people on the same cell.
 */
@OptIn(UnstableApi::class)
@Singleton
public class NeedlerPlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataSourceFactory: NeedlerAudioDataSource.Factory,
    private val equaliser: EqualiserAudioProcessor,
) {

    public fun create(): ExoPlayer {
        val extractors = DefaultExtractorsFactory()
            // Constant-bitrate seeking, so seeking in an MP3 with no seek table lands where the user
            // dragged to instead of somewhere near it. The server serves plenty of those.
            .setConstantBitrateSeekingEnabled(true)

        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(equaliser))
                // Float output would give the equaliser more headroom, and is deliberately off: the
                // kernel is 16-bit, and a sink that hands it float buffers would make it refuse the
                // format and silently stop equalising.
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractors))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
    }

    public companion object {
        internal const val MIN_BUFFER_MS: Int = 30_000
        internal const val MAX_BUFFER_MS: Int = 50_000
        internal const val BUFFER_FOR_PLAYBACK_MS: Int = 1_500
        internal const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS: Int = 3_000
        internal const val SEEK_INCREMENT_MS: Long = 10_000L
    }
}

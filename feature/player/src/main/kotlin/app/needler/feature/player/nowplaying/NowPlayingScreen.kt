package app.needler.feature.player.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerChevronDownIcon
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.StreamRung
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.feature.player.PlayerUiState
import app.needler.feature.player.ui.ArtworkOnRecord
import app.needler.feature.player.ui.ArtworkOnRecordMetrics
import app.needler.feature.player.ui.FavouriteButton
import app.needler.feature.player.ui.PlayerQueueIcon
import app.needler.feature.player.ui.QualityTags
import app.needler.feature.player.ui.Scrubber
import app.needler.feature.player.ui.SessionControls
import app.needler.feature.player.ui.SleepTimerChoice
import app.needler.feature.player.ui.TrackByline
import app.needler.feature.player.ui.TransportRow
import app.needler.feature.player.ui.TransportSize

/**
 * Now Playing, screen 07.
 *
 * Artwork on its record, the title, the quality tag pair, elapsed and remaining in tabular numerals,
 * the scrub bar, the transport, and the output named plainly underneath - "so a user never wonders
 * where sound is going".
 *
 * The pack draws one format badge beside the title. It is a pair now, on its own line - see
 * [QualityTags] for why one badge could not answer the question it appeared to: what is playing and
 * what the server would send are different facts whenever a rung transcodes or a download exists, and
 * the badge silently showed the library's format in both cases.
 *
 * Stateless, like `ConnectScreen`: it takes a [PlayerUiState] and a handful of callbacks, which is
 * what lets every state of it be screenshot and asserted from a literal value with no session, no
 * Hilt graph and no Media3 anywhere near it.
 *
 * ## Nothing playing
 *
 * The commonest state of this screen, and drawn rather than hidden. The sleeve becomes the
 * placeholder tint carrying "Nothing playing", the record holds still, both timecodes read `--:--`,
 * the scrub bar is inert and the transport is disabled but still drawn - a transport that disappears
 * when the crate empties makes the screen jump the moment a listener reaches the end of an album.
 * The output chip stays, because where sound *would* go is still worth knowing, and so does the
 * sleep-timer chip, because the timer belongs to the session rather than to the track. The heart does
 * not: there is nothing to favourite.
 *
 * @param progress a lambda, not a value. See [Scrubber] for why: it keeps the several-times-a-second
 *   position tick out of this composable and inside the two labels that actually show it.
 * @param onOpenArtist the artist's page. `:app` supplies it; see [TrackByline] for why the line is
 *   drawn as plain text until the artist has been resolved to an MBID.
 */
@Composable
fun NowPlayingScreen(
    state: PlayerUiState,
    progress: () -> PlaybackProgress,
    onClose: () -> Unit,
    onOpenCrate: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onChooseOutput: () -> Unit,
    onToggleFavourite: () -> Unit,
    onChooseSleepTimer: (SleepTimerChoice) -> Unit,
    onOpenArtist: (ArtistMbid) -> Unit,
    modifier: Modifier = Modifier,
    onOverrideQuality: ((StreamRung) -> Unit)? = null,
    onClearQualityOverride: (() -> Unit)? = null,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography

    Column(modifier = modifier.fillMaxSize()) {
        NowPlayingHeader(onClose = onClose, onOpenCrate = onOpenCrate)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // Scrolls so that the screen still works at 200% font scale, where the title,
                // the subtitle and the timecodes together are taller than the space the pack
                // leaves them. At the default scale nothing scrolls - until the sleep timer's
                // choices unfold, which is why [SessionControls] asks to be scrolled to.
                .verticalScroll(rememberScrollState())
                // Inside the scroll, so the inset is trailing *content* that can be scrolled
                // clear rather than a band carved out of the viewport, and so the pack's own
                // 36dp gap below the chips is preserved on top of it.
                //
                // This screen is the one that has to read the inset for itself. Every other
                // destination sits inside `NeedlerNavigationScaffold`, which pads the bottom
                // safe area with its own chrome and then calls `consumeWindowInsets` so a
                // descendant's `safeDrawingPadding()` contributes nothing - Now Playing is a
                // sibling of Home in the *outer* nav graph, with no scaffold and no bottom bar
                // under it, so nothing had reserved the navigation bar's height and the last
                // row of the column was drawn beneath it.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ArtworkOnRecord(
                    artwork = state.item?.track?.artwork,
                    identity = state.item?.track?.releaseGroupMbid?.value,
                    albumTitle = state.item?.track?.albumTitle,
                    artistName = state.item?.track?.artistName,
                    playing = state.isPlaying,
                    metrics = ArtworkOnRecordMetrics.phone(),
                    emptyLabel = if (state.hasTrack) null else "Nothing playing",
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = state.title,
                        style = typography.nowPlayingTitle,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    val artist: ArtistMbid? = state.artistMbid
                    if (state.hasTrack) {
                        TrackByline(
                            artistName = state.artistName,
                            albumTitle = state.albumTitle,
                            // The form the crate rows use for their optional accessibility
                            // actions: a nullable lambda from an `if`, with no inference to do.
                            onOpenArtist = if (artist == null) {
                                null
                            } else {
                                { onOpenArtist(artist) }
                            },
                            style = typography.bodyLarge,
                        )
                    } else {
                        Text(
                            text = state.subtitle,
                            style = typography.bodyLarge,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Beside the title, where every player puts it and where the thing being favourited is
                // named. Absent with nothing playing: there is no track to star.
                if (state.hasTrack) {
                    FavouriteButton(
                        isFavourite = state.isFavourite,
                        onToggle = onToggleFavourite,
                    )
                }
            }

            QualityTags(
                state = state,
                onSelectRung = onOverrideQuality,
                onClearRung = onClearQualityOverride,
            )

            Scrubber(
                progress = progress,
                durationMs = state.durationMs,
                onSeek = onSeek,
                enabled = state.hasTrack,
            )

            // Playback first: a track that will not play is the more urgent of the two, and a
            // refused star is still true on the next frame.
            val error: String? = state.errorMessage ?: state.favouriteErrorMessage
            if (error != null) {
                Text(
                    text = error,
                    style = typography.meta,
                    color = colors.destructive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            TransportRow(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                shuffleEnabled = state.shuffleEnabled,
                repeatMode = state.repeatMode,
                onShuffle = onToggleShuffle,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onRepeat = onCycleRepeat,
                modifier = Modifier.padding(horizontal = 8.dp),
                size = TransportSize.Phone,
                enabled = state.hasTrack,
            )

            SessionControls(
                output = state.output,
                onChooseOutput = onChooseOutput,
                timer = state.sleepTimer,
                onChooseSleepTimer = onChooseSleepTimer,
                emphasised = true,
            )
        }
    }
}

/**
 * The 96 dp header: dismiss on the left, the screen's name in the middle, the crate on the right.
 *
 * The 52 dp of top padding is the pack's own status-bar inset. It is spelled out here rather than
 * taken from `WindowInsets` because this composable is rendered to a PNG at a fixed size, where
 * there is no system bar to measure - and because the pack's artboards are what the screenshots are
 * compared against.
 */
@Composable
private fun NowPlayingHeader(onClose: () -> Unit, onOpenCrate: () -> Unit) {
    val colors = NeedlerTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(start = 12.dp, end = 12.dp, top = 52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerIconButton(
            contentDescription = "Close now playing",
            onClick = onClose,
            visualSize = 44.dp,
        ) {
            NeedlerChevronDownIcon(tint = colors.textPrimary, size = 24.dp)
        }
        Text(
            text = "Now playing".uppercase(),
            style = NeedlerTheme.typography.sectionHeader,
            color = colors.textSecondary,
            modifier = Modifier.semantics { heading() },
        )
        NeedlerIconButton(
            contentDescription = "In the crate",
            onClick = onOpenCrate,
            visualSize = 44.dp,
        ) {
            PlayerQueueIcon(tint = colors.textPrimary, size = 24.dp)
        }
    }
}

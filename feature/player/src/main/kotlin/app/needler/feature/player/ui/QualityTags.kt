package app.needler.feature.player.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerAlbumSource
import app.needler.core.design.component.NeedlerPillButton
import app.needler.core.design.component.NeedlerQualityTag
import app.needler.core.design.component.NeedlerQualityTagEmphasis
import app.needler.core.design.component.tagLabel
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.StreamRung
import app.needler.feature.player.PlayerUiState

/**
 * The quality tag pair under the title: `Server: MP3 192` beside `Device: FLAC`.
 *
 * ## Why two tags replaced one badge
 *
 * The player used to draw one format badge taken from the track's metadata, which answered the wrong
 * question. What a listener wants to know is *what am I hearing*, and on this product that has two
 * possible answers that are frequently not the same: the bytes on the device, or whatever the server
 * would send over this connection right now. A single badge silently picked the library's format and
 * was therefore wrong in both of the interesting cases - a transcoded stream, and a download of a
 * record whose server copy has since been upgraded.
 *
 * **Whichever is in force reads as in force.** A local copy always wins - `ResolvePlayableSourceUseCase`
 * checks local bytes before it makes any streaming decision - so when a track is on the device,
 * `Device:` is the active tag and `Server:` is dormant. That is not decoration: drawing them as equals
 * implies the server rate is what is playing, which it is not.
 *
 * **The hue does not move with it.** `Server:` is the accent blue and `Device:` the positive green
 * whichever one applies, because REQUIREMENTS.md "Vocabulary" makes hue a property of the state. The
 * pair used to hand the green to whichever tag was active, so pulling an album moved the styling from
 * one tag to the other and the colour meant "this one applies" rather than "this is the server". In
 * force is carried by the chip and the value's weight instead; see `NeedlerQualityTagEmphasis`.
 *
 * ## What each tap does, and what it deliberately does not
 *
 * Tapping **Server** expands the rung ladder and writes a *per-track* override, absolute across both
 * connections. **Use my setting** at the end of the ladder is how the override is withdrawn, and it is
 * spelled out rather than being a second tap on the selected chip: a toggle whose off state is
 * indistinguishable from its on state is how a user ends up unable to get back to the default.
 *
 * **The rung is a preference, not a transport command.** A tapped rung is written to
 * `PlaybackSettingsRepository` and read back by `ResolvePlayableSourceUseCase` the next time the
 * track is *loaded*, which is what the `Server:` tag then shows. It does not re-open the stream
 * under a listener mid-track, and the ladder now says so in as many words - see
 * [OVERRIDE_EXPLANATION], and `PlayerViewModel.overridePlayingTrackQuality` for the alternative that
 * was rejected.
 *
 * Tapping **Device** does nothing here, and the tag is not drawn as a control. Downloading is an album
 * action - REQUIREMENTS.md's **Pull to device** acquires a record, not a song - and the album screen already
 * offers it as a labelled button, where the thing being downloaded is named. A tap target in the
 * player that removed an album's worth of audio without saying which album is not a control worth
 * having.
 */
@Composable
internal fun QualityTags(
    state: PlayerUiState,
    modifier: Modifier = Modifier,
    onSelectRung: ((StreamRung) -> Unit)? = null,
    onClearRung: (() -> Unit)? = null,
) {
    val serverValue: String? = state.serverTagValue
    val pulledValue: String? = state.pulledTagValue
    if (!state.hasTrack || (serverValue == null && pulledValue == null)) return

    // Which ladder is open. Screen-local and deliberately not hoisted: it is not state any other
    // surface can act on, and it must close when the player is dismissed.
    var pickerOpen: Boolean by remember { mutableStateOf(false) }

    // 6dp between the tags, the ladder and the caveat. The three used to butt straight into one
    // another - a Column with no arrangement - which is half of why the open ladder read as crowded.
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pulledValue != null) {
                NeedlerQualityTag(
                    label = DEVICE_SOURCE.tagLabel(),
                    value = pulledValue,
                    source = DEVICE_SOURCE,
                    emphasis = NeedlerQualityTagEmphasis.Active,
                    contentDescription = state.pulledTagDescription,
                )
            }
            if (serverValue != null) {
                NeedlerQualityTag(
                    label = SERVER_SOURCE.tagLabel(),
                    value = serverValue,
                    source = SERVER_SOURCE,
                    // Dormant while a local copy exists, because the local copy is what plays. The
                    // colour is unchanged by this; only the chip and the weight are.
                    emphasis = if (state.isPulled) {
                        NeedlerQualityTagEmphasis.Dormant
                    } else {
                        NeedlerQualityTagEmphasis.Active
                    },
                    onClick = if (onSelectRung == null) null else { { pickerOpen = !pickerOpen } },
                    contentDescription = state.serverTagDescription,
                )
            }
        }
        val select: ((StreamRung) -> Unit)? = onSelectRung
        if (pickerOpen && select != null) {
            Text(
                text = OVERRIDE_EXPLANATION,
                style = NeedlerTheme.typography.caption,
                color = NeedlerTheme.colors.textMuted,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StreamRung.entries.forEach { rung ->
                    NeedlerPillButton(
                        text = rungLabel(rung),
                        onClick = {
                            select(rung)
                            pickerOpen = false
                        },
                        // The caveat is spoken on the seven rungs it is true of and not on
                        // `Original`, which costs no pixels at all and says it before the tap
                        // rather than after it. Drawn, it is one line under the ladder - see
                        // [TRANSCODE_CAVEAT].
                        contentDescription = "Stream this track at " + rungLabel(rung) +
                            if (rung.isTranscode) ". " + TRANSCODE_CAVEAT else "",
                    )
                }
                val clear: (() -> Unit)? = onClearRung
                if (clear != null) {
                    NeedlerPillButton(
                        text = "Use my setting",
                        onClick = {
                            clear()
                            pickerOpen = false
                        },
                        contentDescription = "Stop overriding this track and use the setting for " +
                            "this connection",
                    )
                }
            }
        }
        // Last, and outside the ladder, so it survives the ladder folding away on the tap that
        // caused it. See [TRANSCODE_CAVEAT] for why it is drawn here and not above the pills.
        if (state.isStreamingTranscoded) {
            Text(
                text = TRANSCODE_CAVEAT,
                style = NeedlerTheme.typography.caption,
                color = NeedlerTheme.colors.textMuted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A rung as a chip label.
 *
 * `Original` is never labelled FLAC, however much it means FLAC on a lossless library: it means
 * whatever the file already is, and on an MP3 library it yields MP3.
 *
 * Duplicated from `:app`'s `SettingsFormat` rather than shared, because that object is `internal` to
 * `:app` and this module cannot see it. The right home for a third caller is `:core:design`.
 */
private fun rungLabel(rung: StreamRung): String = when (rung) {
    StreamRung.ORIGINAL -> "Original"
    StreamRung.OPUS_192 -> "Opus 192"
    StreamRung.OPUS_128 -> "Opus 128"
    StreamRung.OPUS_96 -> "Opus 96"
    StreamRung.MP3_320 -> "MP3 320"
    StreamRung.MP3_256 -> "MP3 256"
    StreamRung.MP3_192 -> "MP3 192"
    StreamRung.MP3_128 -> "MP3 128"
}

// The words and the hues come from one place, so a tag cannot be labelled for one state and coloured
// for another - which is the defect this pair had. REQUIREMENTS.md "Vocabulary".
private val SERVER_SOURCE: NeedlerAlbumSource = NeedlerAlbumSource.Server
private val DEVICE_SOURCE: NeedlerAlbumSource = NeedlerAlbumSource.Device

/**
 * What the ladder does, said before it is used rather than discovered afterwards.
 *
 * Two facts in eight words: the choice follows this track onto every connection, and it takes effect
 * from the track's next play rather than mid-stream. The second one is new and it is the honest half
 * of the device report "hitting any of those buttons doesn't seem to make a difference anyway" - the
 * override is read, but it is read by `NeedlerAudioDataSource.open`, which is to say when a track is
 * *loaded*. An item already prepared keeps the stream it was opened with, and
 * [app.needler.core.domain.playback.PlaybackController] has no command that would re-prepare it. See
 * `PlayerViewModel.overridePlayingTrackQuality` for why that was left alone rather than fixed by
 * re-buffering under the listener.
 *
 * It used to carry the retention caveat in a second sentence, above all eight rungs, whether or not
 * any of them was in force. That is [TRANSCODE_CAVEAT] now.
 */
private const val OVERRIDE_EXPLANATION: String =
    "This track, every connection, from its next play."

/**
 * The one fact a listener cannot guess, drawn where it bites.
 *
 * REQUIREMENTS.md "Why transcoded bytes are never cached" is the requirement behind it: a transcode's
 * bytes are played and thrown away, every time, so a rung below the library's own quality never
 * builds an offline copy. A listener who is not told will wonder why a track they played twice
 * downloaded twice - the same cliff the Settings screen names beside its own picker - so this is not
 * padding and was not deleted.
 *
 * What changed is where it costs room. It is drawn only while a transcode is actually in force for
 * this track, which is the only state it is true in: on the default Wi-Fi rung - `Original`,
 * REQUIREMENTS.md "Streaming" - nothing is drawn at all, and the open ladder is one short line
 * instead of two sentences. Three placements were rejected. Above the pills, as it was, it is a
 * paragraph the listener has to read past to reach the control, in the state where it is usually
 * false. Under each transcoding pill it does not fit: the ladder is a horizontally scrolled row of
 * eight chips. Inside the ladder it would be invisible exactly when it matters, because choosing a
 * rung folds the ladder away - which is why this sits outside it and stays on screen afterwards.
 *
 * It is also spoken by every transcoding pill's own label, so TalkBack has it before the tap.
 */
private const val TRANSCODE_CAVEAT: String =
    "Re-encoded bytes are not kept on this device."

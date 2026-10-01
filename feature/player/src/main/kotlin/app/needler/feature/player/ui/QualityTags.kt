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
import app.needler.core.design.component.NeedlerPillButton
import app.needler.core.design.component.NeedlerQualityTag
import app.needler.core.design.component.NeedlerQualityTagEmphasis
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.StreamRung
import app.needler.feature.player.PlayerUiState

/**
 * The quality tag pair under the title: `Server: MP3 192` beside `Pulled: FLAC`.
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
 * checks local bytes before it makes any streaming decision - so when a track is pulled, `Pulled:` is
 * the active tag and `Server:` is dimmed. That is not decoration: drawing them as equals implies the
 * server rate is what is playing, which it is not.
 *
 * ## What each tap does, and what it deliberately does not
 *
 * Tapping **Server** expands the rung ladder and writes a *per-track* override, absolute across both
 * connections. **Use my setting** at the end of the ladder is how the override is withdrawn, and it is
 * spelled out rather than being a second tap on the selected chip: a toggle whose off state is
 * indistinguishable from its on state is how a user ends up unable to get back to the default.
 *
 * Tapping **Pulled** does nothing here, and the tag is not drawn as a control. Downloading is an album
 * action - REQUIREMENTS.md's "Pull local" acquires a record, not a song - and the album screen already
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

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pulledValue != null) {
                NeedlerQualityTag(
                    label = PULLED_LABEL,
                    value = pulledValue,
                    emphasis = NeedlerQualityTagEmphasis.Active,
                    contentDescription = state.pulledTagDescription,
                )
            }
            if (serverValue != null) {
                NeedlerQualityTag(
                    label = SERVER_LABEL,
                    value = serverValue,
                    // Dimmed while a local copy exists, because the local copy is what plays.
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
                        contentDescription = "Stream this track at " + rungLabel(rung),
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

private const val SERVER_LABEL: String = "Server:"
private const val PULLED_LABEL: String = "Pulled:"

/**
 * What the ladder does, said before it is used rather than discovered afterwards.
 *
 * The two facts a person needs are that the choice sticks to this record wherever they are, and that
 * anything the server re-encodes is not kept for offline - REQUIREMENTS.md "Why transcoded bytes are
 * never cached".
 */
private const val OVERRIDE_EXPLANATION: String =
    "Applies to this track on every connection. Anything the server re-encodes is not kept on this " +
        "device."

package app.needler.feature.player.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerHairline
import app.needler.core.design.component.NeedlerPillButton
import app.needler.core.design.component.NeedlerTextButton
import app.needler.core.design.component.NeedlerToggleRow
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.EqBand
import app.needler.core.domain.model.EqPreset
import app.needler.core.domain.model.EqSettings

/**
 * The equaliser, screen 19.
 *
 * Ten bands at 31 Hz to 16 kHz, plus or minus 12 dB, five presets and a preamp - the bands and the
 * range matching DroppedNeedle's web player exactly, so a listener moving between the two finds the
 * same controls.
 *
 * ## The caption
 *
 * The pack's caption reads "Same 10 bands and presets as Dropped Needle's player". Half of that is
 * not true and REQUIREMENTS.md says so: the server ships ten presets and Needler ships five, by
 * choice, because five is the better mobile set. The fix the document names is to "change the
 * screen's caption to claim the same *bands*, not the same presets", which is what it says here.
 *
 * ## Switched off
 *
 * The sliders stay on screen, greyed, rather than disappearing: they are the record of what the
 * listener set up, and hiding them makes the toggle feel like it deleted something. Nothing in the
 * card responds until the equaliser is on again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualiserScreen(
    settings: EqSettings,
    onEnabledChange: (Boolean) -> Unit,
    onPresetSelected: (EqPreset) -> Unit,
    onBandChange: (EqBand, Float) -> Unit,
    onPreampChange: (Float) -> Unit,
    onResetToFlat: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val enabled: Boolean = settings.isEnabled

    Column(modifier = modifier.fillMaxSize()) {
        PlayerSettingsHeader(title = "Equaliser", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            NeedlerToggleRow(
                label = "Equaliser on",
                checked = enabled,
                onCheckedChange = onEnabledChange,
                showDivider = false,
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EqPresets.offered.forEach { preset ->
                    NeedlerPillButton(
                        text = EqPresets.label(preset),
                        onClick = { onPresetSelected(preset) },
                        selected = settings.preset == preset,
                        enabled = enabled,
                        contentDescription = EqPresets.label(preset) + " preset",
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(NeedlerTheme.shapes.extraLarge)
                    .background(colors.surface)
                    .border(
                        width = NeedlerTheme.sizes.hairlineThickness,
                        color = colors.hairline,
                        shape = NeedlerTheme.shapes.extraLarge,
                    )
                    .padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EqBand.entries.forEach { band ->
                    EqBandSlider(
                        band = band,
                        gainDb = settings.gainFor(band),
                        onGainChange = { gain -> onBandChange(band, gain) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PreampSlider(
                    preampDb = settings.preampDb,
                    onPreampChange = onPreampChange,
                    enabled = enabled,
                )
                NeedlerHairline()
                NeedlerTextButton(
                    text = "Reset to flat",
                    onClick = onResetToFlat,
                    enabled = enabled,
                    contentDescription = "Reset every band to flat",
                )
            }

            Text(
                text = "Same 10 bands as Dropped Needle's player, saved on this device.",
                style = typography.caption,
                color = colors.textMuted,
            )
        }
    }
}

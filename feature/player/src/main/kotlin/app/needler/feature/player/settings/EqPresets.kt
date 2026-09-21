package app.needler.feature.player.settings

import app.needler.core.domain.model.EqPreset
import app.needler.core.domain.model.EqSettings

/**
 * The gain curves behind the five preset pills on screen 19.
 *
 * They live in the presentation layer on purpose - `EqSettings` says so: "Gain curves belong to the
 * presentation layer, not the domain." The domain owns the bands, the range and the *names*; what
 * "Vinyl" sounds like is a product opinion, and one that can change without touching a model.
 *
 * REQUIREMENTS.md: the server's web player ships ten presets (Flat, Rock, Pop, Jazz, Classical, Bass
 * Boost, Treble Boost, Vocal, Electronic and Acoustic) and Needler ships these five, deliberately -
 * "Needler's five are the better mobile set, so keep them and change the screen's caption to claim
 * the same *bands*, not the same presets". The caption on [EqualiserScreen] is that change.
 *
 * Vinyl's curve is the one the pack actually draws, band for band: +2, +3, +1, 0, -1, 0, +1, +2, +3,
 * +2 across 31 Hz to 16 kHz, with the preamp at -2 dB.
 */
object EqPresets {

    /** The order the pills are drawn in. [EqPreset.CUSTOM] is not offered; it is arrived at. */
    val offered: List<EqPreset> = listOf(
        EqPreset.FLAT,
        EqPreset.BASS,
        EqPreset.VOCAL,
        EqPreset.BRIGHT,
        EqPreset.VINYL,
    )

    /** The label on the pill. */
    fun label(preset: EqPreset): String = when (preset) {
        EqPreset.FLAT -> "Flat"
        EqPreset.BASS -> "Bass"
        EqPreset.VOCAL -> "Vocal"
        EqPreset.BRIGHT -> "Bright"
        EqPreset.VINYL -> "Vinyl"
        EqPreset.CUSTOM -> "Custom"
    }

    /** Band gains in dB, in [app.needler.core.domain.model.EqBand] order. */
    fun gains(preset: EqPreset): List<Float> = when (preset) {
        EqPreset.FLAT -> EqSettings.FlatGains
        EqPreset.BASS -> listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 1f, 2f)
        EqPreset.VOCAL -> listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 2f, 0f, -1f)
        EqPreset.BRIGHT -> listOf(-1f, -1f, 0f, 0f, 1f, 2f, 3f, 4f, 5f, 5f)
        EqPreset.VINYL -> listOf(2f, 3f, 1f, 0f, -1f, 0f, 1f, 2f, 3f, 2f)
        EqPreset.CUSTOM -> EqSettings.FlatGains
    }

    /**
     * The preamp each preset sets.
     *
     * A preset that only ever adds gain clips, so the ones that lift bands pull the preamp down by
     * roughly what they added. Flat leaves it alone; Vinyl's -2 dB is the value screen 19 prints.
     */
    fun preampDb(preset: EqPreset): Float = when (preset) {
        EqPreset.FLAT -> 0f
        EqPreset.BASS -> -4f
        EqPreset.VOCAL -> -2f
        EqPreset.BRIGHT -> -3f
        EqPreset.VINYL -> -2f
        EqPreset.CUSTOM -> 0f
    }

    /** The whole settings object a preset produces, with the equaliser left switched however it was. */
    fun apply(preset: EqPreset, to: EqSettings): EqSettings = to.copy(
        bandGainsDb = gains(preset),
        preampDb = preampDb(preset),
        preset = preset,
    )
}

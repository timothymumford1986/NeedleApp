package app.needler.player.service.feature

import app.needler.core.domain.model.CrossfadeDuration
import app.needler.core.domain.model.CrossfadeSettings
import app.needler.core.domain.model.Track
import app.needler.player.service.audio.FadeCurve

/** Why a fade is happening. Carried through so a log line can say which rule fired. */
public enum class FadeReason {

    /** Crossfade at a track boundary, at the length the user chose on screen 20. */
    CROSSFADE,

    /**
     * The 1-second fade when the user presses next or previous.
     *
     * Separate from [CROSSFADE] because it applies even with crossfade off, and even inside an album
     * where crossfade is suppressed: the abrupt cut it replaces is the response to a button the user
     * just pressed, not part of how the record is meant to run.
     */
    MANUAL_SKIP,

    /** The short ramp instead of an abrupt stop when playback pauses. */
    PAUSE,

    /** The matching ramp back up when playback resumes, so a pause is symmetrical. */
    RESUME,

    /**
     * The short break inserted between tracks when gapless is switched off.
     *
     * Media3 concatenates by default and there is no knob that turns that off, so "gapless off" is
     * implemented rather than delegated: a brief fade and silence at the join. See [FadePlanner].
     */
    GAPLESS_OFF,

    /** Nothing to do. */
    NONE,
    ;
}

/** One fade to run: how long, which shape, and which rule asked for it. */
public data class FadePlan(
    public val durationMs: Long,
    public val reason: FadeReason,
    public val curve: FadeCurve,
    /**
     * Silence to insert after the fade-out completes, before the next track starts.
     *
     * Zero for a crossfade, where the two tracks overlap instead. Non-zero only for
     * [FadeReason.GAPLESS_OFF].
     */
    public val gapMs: Long = 0L,
) {
    public val isNoOp: Boolean get() = reason == FadeReason.NONE || durationMs <= 0L

    public companion object {
        public val None: FadePlan = FadePlan(
            durationMs = 0L,
            reason = FadeReason.NONE,
            curve = FadeCurve.LINEAR,
        )
    }
}

/** What caused a track boundary, which is the difference between a crossfade and a skip fade. */
public enum class TransitionCause {

    /** One track ended and the next began on its own. */
    AUTO_ADVANCE,

    /** The user pressed next or previous, or tapped a row of the crate. */
    MANUAL_SKIP,

    /** A seek landed in a different item. Never faded: the user is navigating, not listening. */
    SEEK,
}

/**
 * Every fade decision in one place, with no Media3 and no clock, so all of it is tested on the JVM.
 *
 * The four rules from screen 20 interact, and the interaction is the part worth writing down.
 *
 * ## Crossfade is suppressed inside an album
 *
 * [CrossfadeSettings.suppressWithinAlbum] exists so gapless records survive. A live album, a DJ mix or
 * anything segued has no silence at its joins, and a 6-second crossfade across a join that was already
 * seamless removes six seconds of the record and replaces them with a smear. Two tracks count as the
 * same album when they share a release-group MBID, which is the identity the whole app is keyed on.
 *
 * ## A manual skip still fades
 *
 * The suppression above is about how a record plays itself. Pressing next is a different event, and
 * [CrossfadeSettings.fadeOnSkip] gives it a 1-second fade whatever the crossfade setting says -
 * including inside an album, and including with crossfade off entirely. This is a deliberate reading of
 * screen 20's two separate rows: one governs the automatic transition, the other governs the button.
 *
 * ## Gapless off is implemented here, not delegated
 *
 * Media3's concatenation is gapless and there is no setting that makes it otherwise, so the "Gapless"
 * toggle cannot simply be passed through to the player. With it off, a boundary gets a short fade and a
 * short silence - which is what a user who turned gapless off is asking for, and is at least an audible
 * difference rather than a control that does nothing.
 */
public class FadePlanner(
    /** The manual-skip fade length. One second, per screen 20. */
    private val skipFadeMs: Long = DEFAULT_SKIP_FADE_MS,
    /** The pause and resume ramp. Short enough not to feel like a delay on the button. */
    private val pauseFadeMs: Long = DEFAULT_PAUSE_FADE_MS,
    /** The fade either side of the inserted break when gapless is off. */
    private val gaplessOffFadeMs: Long = DEFAULT_GAPLESS_OFF_FADE_MS,
    /** The break itself when gapless is off. */
    private val gaplessOffGapMs: Long = DEFAULT_GAPLESS_OFF_GAP_MS,
) {

    /**
     * The fade for a track boundary.
     *
     * @param from the track ending, or null when playback is starting from nothing.
     * @param to the track starting, or null when the crate has run out.
     */
    public fun forTransition(
        settings: CrossfadeSettings,
        gaplessEnabled: Boolean,
        cause: TransitionCause,
        from: Track?,
        to: Track?,
    ): FadePlan {
        // A seek is navigation. Fading it would make the player feel slow to respond, and the user is
        // already hearing a discontinuity they asked for.
        if (cause == TransitionCause.SEEK) return FadePlan.None

        if (cause == TransitionCause.MANUAL_SKIP) {
            return if (settings.fadeOnSkip) {
                FadePlan(
                    durationMs = skipFadeMs,
                    reason = FadeReason.MANUAL_SKIP,
                    curve = FadeCurve.LINEAR,
                )
            } else {
                FadePlan.None
            }
        }

        // Automatic advance from here on.
        val sameAlbum: Boolean = from != null &&
            to != null &&
            from.releaseGroupMbid == to.releaseGroupMbid

        val crossfadeApplies: Boolean = settings.isEnabled &&
            to != null &&
            !(sameAlbum && settings.suppressWithinAlbum)

        if (crossfadeApplies) {
            return FadePlan(
                durationMs = settings.duration.duration.inWholeMilliseconds,
                reason = FadeReason.CROSSFADE,
                curve = FadeCurve.EQUAL_POWER,
            )
        }

        // No crossfade. If gapless is off, the join still gets a break; otherwise Media3's own
        // concatenation is exactly what is wanted and nothing is done to it.
        if (!gaplessEnabled && to != null) {
            return FadePlan(
                durationMs = gaplessOffFadeMs,
                reason = FadeReason.GAPLESS_OFF,
                curve = FadeCurve.LINEAR,
                gapMs = gaplessOffGapMs,
            )
        }

        return FadePlan.None
    }

    /** The ramp down on pause, or none when the user turned it off. */
    public fun forPause(settings: CrossfadeSettings): FadePlan =
        if (settings.fadeOnPause) {
            FadePlan(durationMs = pauseFadeMs, reason = FadeReason.PAUSE, curve = FadeCurve.LINEAR)
        } else {
            FadePlan.None
        }

    /** The ramp back up on resume. Symmetrical with [forPause], and gated by the same setting. */
    public fun forResume(settings: CrossfadeSettings): FadePlan =
        if (settings.fadeOnPause) {
            FadePlan(durationMs = pauseFadeMs, reason = FadeReason.RESUME, curve = FadeCurve.LINEAR)
        } else {
            FadePlan.None
        }

    /**
     * How long before the end of a track the crossfade has to begin.
     *
     * Zero when no crossfade applies. Clamped to a third of the track, because a 12-second crossfade
     * into a 20-second interlude would otherwise start before the track's own first chorus.
     */
    public fun crossfadeStartOffsetMs(plan: FadePlan, trackDurationMs: Long?): Long {
        if (plan.reason != FadeReason.CROSSFADE) return 0L
        if (trackDurationMs == null || trackDurationMs <= 0L) return 0L
        return minOf(plan.durationMs, trackDurationMs / 3L)
    }

    public companion object {
        public const val DEFAULT_SKIP_FADE_MS: Long = 1_000L
        public const val DEFAULT_PAUSE_FADE_MS: Long = 250L
        public const val DEFAULT_GAPLESS_OFF_FADE_MS: Long = 150L
        public const val DEFAULT_GAPLESS_OFF_GAP_MS: Long = 350L

        /** The four lengths screen 20 offers. Kept here so a test can assert the set has not drifted. */
        public val OFFERED_DURATIONS: List<CrossfadeDuration> = listOf(
            CrossfadeDuration.OFF,
            CrossfadeDuration.FOUR_SECONDS,
            CrossfadeDuration.SIX_SECONDS,
            CrossfadeDuration.TWELVE_SECONDS,
        )
    }
}

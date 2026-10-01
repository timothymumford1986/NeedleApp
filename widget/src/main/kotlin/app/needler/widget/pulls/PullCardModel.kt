package app.needler.widget.pulls

import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullState
import app.needler.widget.internal.WidgetFormat

/**
 * Everything the pull card draws: one pull out of the queue, and the number beside it.
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Widgets" asks for "the active pull with its percentage",
 * singular, and screens 15 and 18 draw exactly that - a percentage, an album title and a bar. The
 * queue behind it is not singular, so the first thing this type does is choose, and that choice is
 * the whole reason the type exists rather than the card reading a list directly.
 *
 * ## Which pull, out of several
 *
 * The one furthest along, and the newest of those when nothing reports progress.
 *
 * "Newest first" is what `PullRepository.observePulls` gives, and taking its head would have been
 * simpler and wrong: a pull placed a minute ago and still searching would hide one that is 94 percent
 * downloaded and about to land. The card answers one question - *is anything arriving, and how soon*
 * - and the pull nearest completion is the only one whose percentage answers it. Ties fall back to
 * the repository's own order, so the choice is stable between redraws and does not flicker between
 * two pulls sitting at the same percentage.
 *
 * ## An empty queue is the resting state, not an error
 *
 * Nothing pulling is what this card shows nearly all the time - REQUIREMENTS.md "Widgets" says the
 * widgets "must render sensibly with no network and no active playback, since that is their most
 * common state" - so it is drawn as a resting card and not as a failure or a blank.
 *
 * It keeps all three of the pack's slots, at the pack's sizes, so the card does not change shape when
 * a pull finishes: an em dash where the percentage goes, "Nothing pulling" where the album goes, and
 * the bar's empty track with no fill. Three decisions inside that, each deliberate:
 *
 *  * **A dash, not `0%`.** Zero percent is a claim that something is underway and has got nowhere.
 *    It is the same call `WidgetFormat.timecode` makes for an unknown length: "a bar at zero and a
 *    bar of unknown length look identical, and only one of them is a lie".
 *  * **The track with no fill, not a hidden bar.** A 4dp rule that vanishes and reappears makes the
 *    card jump as pulls come and go, and the empty track is a truthful drawing of no progress.
 *  * **Muted, not green.** REQUIREMENTS.md "Design system" gives `#bbdb9b` one job - progress and
 *    ready states - and this card is the only place in the product drawn almost entirely in it. So
 *    the green carries the signal: it is on the home screen only while the server is actually working
 *    on something, and a resting card draws the same shapes in the muted grey. A glance answers "is
 *    anything happening" from colour alone, before a single character is read.
 *
 * ## A pull with no percentage is a normal state too
 *
 * A pull that is searching for sources, or parked for an admin, has no `progress_percent` and no
 * bytes - see `PullProgress.fraction`, which returns null rather than guessing. Such a pull is
 * nonetheless active and the green stays on, with the dash in the percentage slot and [stage] named
 * beside the album title: `Searching · Black Classical Music`. The stage is the one thing that makes
 * the dash mean something, and it uses the product's own badge words from `:core:design`'s
 * `NeedlerAlbumBadge` so the home screen and the Pulls screen say the same word about the same pull.
 *
 * ## Where the wording lives, and why not here
 *
 * [stage] is the domain enum, not a string. Every word this card prints is in
 * `res/values/widget_strings.xml`, because that file already holds the widgets' copy for a stated
 * reason - two of its strings are read by the widget picker in another process before any of this
 * code runs - and splitting the card's copy between a resource file and a Kotlin constant is how the
 * two drift. What is worth testing is the choosing and the formatting, and both of those are here.
 */
internal data class PullCardModel(
    /** False when nothing is being acquired: the resting card. */
    val hasActivePull: Boolean = false,
    /**
     * How many pulls are active in total, including the one drawn.
     *
     * Screens 15 and 18 draw no count, and this is an addition to the pack with a reason: the card
     * shows one pull out of however many, and a queue of five rendered as one album is a card that
     * misreports the queue it is a glance at. It goes in the eyebrow slot beside "Pulls", which is
     * empty space in the drawing, so nothing else moves to make room - and it appears only above one,
     * where it is telling the user something they cannot already see.
     */
    val activeCount: Int = 0,
    /**
     * `Black Classical Music`, **trimmed**, and empty when the server sent no title at all.
     *
     * Trimmed rather than taken as given, so that `isEmpty` and `isBlank` cannot disagree about it
     * and a title of three spaces is the same thing as no title. That is not hypothetical: the
     * downloads lane's `album_title` had no reader in main source for a while, and on a device 34 of
     * 35 pulls came through with a blank one. The data path is fixed; the guard stays, because a card
     * drawing an empty line on a home screen looks broken and tells the user nothing.
     *
     * Empty is never *drawn*. [hasAlbumTitle] is what the card branches on, and it falls back to
     * `R.string.widget_untitled_album` - the same words the Pulls screen uses for the same absence.
     */
    val albumTitle: String = "",
    /**
     * The stage of the drawn pull, or null when nothing is being acquired.
     *
     * Named beside the album only when [percent] is null, because a percentage already says the pull
     * is downloading and `Pulling · 62% · Black Classical Music` is three ways of saying one thing on
     * a 166dp card.
     */
    val stage: PullState? = null,
    /** `62%`, or null when the server has not reported progress for this pull yet. */
    val percent: String? = null,
    /** 0f..1f for the bar, or null for the empty track. */
    val fraction: Float? = null,
) {

    /** How much of the bar is filled. Null is drawn as nothing, not as zero; see this type's KDoc. */
    val barFraction: Float get() = fraction ?: 0f

    /** True when the stage word has to carry the card, because there is no number to draw. */
    val needsStageLabel: Boolean get() = hasActivePull && percent == null

    /** True above one active pull, i.e. when the eyebrow's count is worth printing. */
    val showsCount: Boolean get() = activeCount > 1

    /**
     * Whether [albumTitle] is worth drawing, or the card has to name the absence instead.
     *
     * A plain `isNotEmpty` rather than `isNotBlank`, and it is exact because [albumTitle] is already
     * trimmed. Checking blankness here as well would hide the fact that the trim is what makes the
     * two agree.
     */
    val hasAlbumTitle: Boolean get() = albumTitle.isNotEmpty()

    companion object {

        /** Nothing pulling: the resting card, and what is drawn before the mirror answers. */
        val Idle: PullCardModel = PullCardModel()

        /**
         * Chooses one pull out of the active bucket and folds it onto the card.
         *
         * [pulls] is expected to be `PullRepository.observePulls(PullBucket.ACTIVE)` - already
         * filtered to the bucket and already newest first. Anything the caller passes that is not
         * active is filtered again here rather than trusted, because a bucket is a derived property
         * of a state (`PullState.bucket`) and a completed pull reaching this card would draw a
         * hundred percent bar over an album that already landed.
         */
        fun of(pulls: List<Pull>): PullCardModel {
            val active: List<Pull> = pulls.filter { it.state.isActive }
            val chosen: Pull = active.maxByOrNull { it.progress.fraction ?: -1f } ?: return Idle
            val fraction: Float? = chosen.progress.fraction
            return PullCardModel(
                hasActivePull = true,
                activeCount = active.size,
                albumTitle = chosen.albumTitle.trim(),
                stage = chosen.state,
                percent = WidgetFormat.percent(fraction),
                fraction = fraction,
            )
        }
    }
}

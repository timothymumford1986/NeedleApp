package app.needler.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.design.theme.tabularNumerals

/**
 * Where an album is: the product's three states, and the only place their words are written down.
 *
 * REQUIREMENTS.md "Vocabulary" fixes exactly three, and this enum is the single definition of the
 * word each one renders as. Everything that names a state - a badge, a quality tag, a car browse
 * node, a content description - reads it from here, so a surface cannot invent a fourth word for a
 * state that has three.
 *
 * | State | Word | Hue | `AlbumState` |
 * | --- | --- | --- | --- |
 * | [NotRetrieved] | Not retrieved | none | `NotOwned` |
 * | [Server] | Server | accent `#aed5f2` | `Owned` |
 * | [Device] | Device | positive `#bbdb9b` | `Pinned` |
 *
 * ## Why hue is attached to the state and not to the emphasis
 *
 * The player and the album screen draw two quality tags side by side, and the pair used to give the
 * emphasised style to whichever one was in force. So pulling an album moved the green from the
 * `Server:` tag onto the `Pulled:` tag, and the hue was reporting *which tag matters* rather than
 * *what the tag is*. A colour that means one thing before a tap and another thing afterwards is not
 * a code the user can learn. Hue now names the source and never moves; see
 * [NeedlerQualityTagEmphasis] for the two channels that carry "in force" instead.
 *
 * The pairing is the palette's own: `positive` is already the on-device colour in REQUIREMENTS.md
 * "Design system", and `accent` is its complement, so nothing was added to the palette to do this.
 *
 * ## Hue is redundant by construction, deliberately
 *
 * `#aed5f2` and `#bbdb9b` measure **1.01:1 against each other** - they are the same lightness in two
 * hues, which is what makes them a complementary pair and also what makes them very nearly the same
 * swatch to a red-green colour-blind reader. So the hue is never the signal: each state's **word** is
 * drawn or spoken in every place the hue appears, and the hue only makes the distinction quicker for
 * the readers who can see it. Against the three backgrounds the badges actually sit on, both measure
 * far above AA: accent 12.27:1 on canvas, 11.16:1 on surface, 9.97:1 on surface raised; positive
 * 12.38:1, 11.26:1 and 10.06:1.
 */
enum class NeedlerAlbumSource {
    /** Not on the server and not on the device. Drawn as [NeedlerPullButton], never as a badge. */
    NotRetrieved,

    /** On the server, not on the device. Streams on demand. */
    Server,

    /** On the server and on the device. Plays with no network at all. */
    Device,
}

/** The one word this state renders as, on screen and to a screen reader alike. */
fun NeedlerAlbumSource.label(): String = when (this) {
    NeedlerAlbumSource.NotRetrieved -> "Not retrieved"
    NeedlerAlbumSource.Server -> "Server"
    NeedlerAlbumSource.Device -> "Device"
}

/**
 * The same word with the quality tag's colon: `Server:`, `Device:`.
 *
 * Exposed so that a caller cannot write the label and pick the hue separately, which is how the two
 * came to disagree in the first place.
 */
fun NeedlerAlbumSource.tagLabel(): String = label() + ":"

/**
 * The state of an album, as the pack badges it.
 *
 * REQUIREMENTS.md fixes one label per state, and screens 03 and 10 show four of them on the same
 * list. The three extra members are the Pulls screen's own states (06) plus the two states the
 * server model derives client-side.
 *
 * | State | Badge | Colour |
 * | --- | --- | --- |
 * | `Owned` | Server | accent |
 * | `Pinned` | Device | positive |
 * | `Acquiring` | Pulling, with percentage | positive |
 * | `PendingApproval` | Waiting | secondary |
 * | `Failed` | no source found | muted |
 * | (pull, searching) | Searching | secondary |
 * | (pull, parked) | Needs attention | secondary |
 * | (pull, complete) | Ready | positive |
 * | (pull, failed) | Failed | secondary |
 * | (pull, part-delivered) | Partly delivered | secondary |
 * | (pull, cancelled) | Cancelled | secondary |
 *
 * `NotOwned` has no badge at all - it gets [NeedlerPullButton] instead, and
 * [NeedlerAlbumSource.NotRetrieved] is the word for it wherever a state has to
 * be named in prose rather than drawn.
 *
 * ## Why the first two carry a hue of their own
 *
 * [InLibrary] and [OnDevice] are the two states a library row can be in, they
 * appear in the same column of the same list, and until now they were told
 * apart by nothing but the words - both took a text colour that said nothing
 * about the state. They now take their state's own hue from
 * [NeedlerAlbumSource], which is stable: pulling an album changes the word and
 * the hue together, and neither ever moves to the other state.
 *
 * The member names are the old vocabulary and are deliberately left alone for
 * now. Renaming them is a mechanical pass across four feature modules, and the
 * words a user reads are in [label], which is the thing this change is about.
 *
 * ## Why the last three exist
 *
 * The Pulls screen drew nothing in the trailing column of a row whose pull had
 * ended badly, on the argument that the failure reason in the subtitle was
 * already the label. It is not the same thing: every other row on that list
 * names its state in the trailing column, so a reader scanning that column
 * found a Retry pill under no heading at all, and the three end states - no
 * source, part-delivered, cancelled - were indistinguishable without reading
 * the prose. These three carry the state's **name**; the subtitle keeps the
 * explanation, which is the same split [Searching] and "asking slskd" already
 * use.
 */
sealed interface NeedlerAlbumBadge {
    /**
     * [NeedlerAlbumSource.Server]: on the server, not on the device. Accent with a check (03, 10).
     *
     * Named for the retired vocabulary; the word it renders is "Server".
     */
    data object InLibrary : NeedlerAlbumBadge

    /**
     * [NeedlerAlbumSource.Device]: on the server and on the device, plays with no network.
     *
     * Positive green with the phone-and-check (03, 13). Named for the retired vocabulary; the word it
     * renders is "Device".
     *
     * Also the badge for a **part**-downloaded pin. The album is on the device - pinned, exempt from
     * eviction, playing offline for the tracks it holds - and how many tracks that is, is a per-track
     * fact the track list already states in position. A separate "partly on the device" badge would be
     * a fourth state, and REQUIREMENTS.md "Vocabulary" fixes three.
     */
    data object OnDevice : NeedlerAlbumBadge

    /**
     * The server is acquiring it: the transition into [NeedlerAlbumSource.Server].
     *
     * Accent with the download arrow (03, 10), **not** the pack's positive green. The hue is the one
     * the record is heading for, so a record on its way to the server is the server's colour for the
     * whole journey and changes colour exactly once - when it reaches the device. The pack drew this
     * green, which is also the on-device colour, and that is the ambiguity being removed; recorded
     * under REQUIREMENTS.md "Design pack discrepancies".
     *
     * @param percent 0-100 when the server has reported progress, `null` while it has not.
     */
    data class Pulling(val percent: Int? = null) : NeedlerAlbumBadge

    /**
     * This device is downloading it: the transition into [NeedlerAlbumSource.Device].
     *
     * ## Why this is a badge and not a banner
     *
     * The album screen drew a full-width progress banner for this, and a device audit found two faults
     * with it. It was distracting - a long-running background operation taking the width of the screen
     * it is happening on - and it did not go away, because the one state that could clear it was
     * written by a comparison that could not come out true on a part-delivered album. A state whose
     * only exit is an event that cannot arrive is the same defect as the updater's permanent
     * "Installing" bar.
     *
     * A badge fixes both at once. REQUIREMENTS.md "Album states" already draws the *other* transition -
     * the server's acquisition - as "Pulling, with percentage" on the badge of the thing that is
     * changing, and a local download is the same shape of event, so the asymmetry had nothing
     * defending it. And a badge derived from the pin row has nothing to dismiss: it is recomputed from
     * the column on every emission, so success, failure, cancellation and a backgrounded app all clear
     * it by the same route, which is the row changing.
     *
     * The percentage is a summary of a per-track reality and must not read as "unusable until
     * complete": REQUIREMENTS.md "Partial content is a normal state" has a part-downloaded pin playing
     * what it has while the rest streams. Play stays offered throughout, and [accessibleLabel] says so
     * in words.
     *
     * @param percent 0-100 from tracks complete against tracks fetchable, `null` before the first one
     *   lands.
     */
    data class PullingToDevice(val percent: Int? = null) : NeedlerAlbumBadge

    /** Requested by a `user` role and waiting for an admin. Secondary with a clock. */
    data object Waiting : NeedlerAlbumBadge

    /**
     * The download is held because "Download to device on Wi-Fi only" is on and this is mobile data.
     *
     * Secondary with a clock, like [Waiting], and deliberately not either state's hue: nothing is
     * moving and the record is still where it was, so a badge claiming the device's colour would be
     * reporting an intention as a location.
     *
     * It exists so the hold has somewhere to be said. [PullingToDevice] cannot carry it - that badge
     * means bytes are arriving - and the reason is the one thing the user can act on, by finding Wi-Fi
     * or turning the setting off. REQUIREMENTS.md "The Wi-Fi-only setting is mislabelled" is why the
     * setting is about downloading to the device and not about pulling.
     */
    data object WaitingForWifi : NeedlerAlbumBadge

    /** `queued` with no search job: the server is still looking for sources (06). */
    data object Searching : NeedlerAlbumBadge

    /**
     * `queued` with a search job but no candidate: a manual pick is parked on the server.
     *
     * ## Why the drawn label no longer says where
     *
     * It read "Needs attention on the server", and a badge is laid out before the text column it
     * sits beside: the trailing column is unweighted, so it takes the width it asks for and the
     * title gets the remainder. Measured off `screenshots/pulls-all-awaiting-review-phone.png` at
     * 2px to the dp, that sentence drew 192dp of the 266dp a 390dp phone row has to divide, leaving
     * 74dp for the album title and the artist - about ten characters of `rowTitle` a line. Every
     * title on that render is cut to `Death's Dateles...`, a twelve-character artist name wraps to
     * two lines, and in `screenshots/pulls-every-state-phone.png` a long artist fills the second
     * line on its own and the row loses its state line entirely. The user's device has all 35 of
     * its pulls in this state, so that was not an edge case, it was the screen.
     *
     * Fifteen characters draw about 112dp and give the title column 154dp back - roughly twenty
     * characters a line instead of ten, which is a title read rather than recognised.
     *
     * ## What "on the server" was earning, and where it went
     *
     * A real distinction, and not one the user can act on from the phone: REQUIREMENTS.md
     * "Acquisition lifecycle" derives this state client-side precisely because a human has to pick
     * a source in DroppedNeedle's own web interface, so the badge was saying "not your move". That
     * fact now lives in the three places with room for it. The Pulls screen's banner says the items
     * are held for review on the server; the row's spoken description says the whole sentence; and
     * [accessibleLabel] keeps "Needs attention on the server" for anything that reads this badge on
     * its own. The screen is called Pulls and a pull is a thing the server does, so the shortened
     * label is read in a context that already answers "where".
     *
     * It also settles a divergence. `PullWidget` has drawn "Needs attention" since the widget was
     * written, because the long form is four words past what a 166dp card holds, and its own notes
     * require the home screen and the app to use the same words for a stage. They now do.
     *
     * ## Rejected
     *
     * **Cap the title instead** - a fixed width on the text column, or an ellipsis sooner. The
     * title is the thing the user is scanning for; trading it for a sentence that repeats what the
     * screen is called is the wrong way round.
     *
     * **Drop the badge on this state** and leave the explanation to the subtitle. The Pulls row
     * deliberately empties its subtitle of that sentence *because* the badge names the state, so
     * removing both would leave the one state that needs a human the only unnamed state on the
     * list.
     *
     * **Let the label wrap** - it already may, at [NeedlerStateBadge]'s `maxLines = 2`. That spends
     * row height instead of row width and does not give the title back a single dp, because the
     * trailing column still measures itself first.
     */
    data object NeedsAttention : NeedlerAlbumBadge

    /** The pull landed and the album is playable. Positive green with a check (06). */
    data object Ready : NeedlerAlbumBadge

    /** No usable source was found. Muted, and the pack gives it no icon (06). */
    data object NoSource : NeedlerAlbumBadge

    /** The pull ended without the album. The reason stays in the row's subtitle. */
    data object Failed : NeedlerAlbumBadge

    /**
     * Some tracks arrived and some did not.
     *
     * REQUIREMENTS.md, "Partial content is a normal state": this is not a
     * failure to hide. The album is in the library and plays, so the badge says
     * what is true rather than borrowing [Failed].
     */
    data object PartlyDelivered : NeedlerAlbumBadge

    /** The pull was stopped, by this user or on the server. */
    data object Cancelled : NeedlerAlbumBadge
}

/**
 * The album state badge: an icon and a label, in the colour the state carries.
 *
 * 13sp/600 with a 16dp glyph and 6dp between them, as drawn on screens 03, 06, 10 and 13. There is
 * no chip, no fill and no border - the colour is the whole treatment.
 *
 * The icon and label merge into one accessibility node, so TalkBack says "Pulling, 62 percent"
 * rather than reading a decorative glyph and then a number.
 */
@Composable
fun NeedlerStateBadge(
    badge: NeedlerAlbumBadge,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography

    val tint: Color = when (badge) {
        // The device's own hue, for the state and for the journey into it.
        NeedlerAlbumBadge.OnDevice,
        is NeedlerAlbumBadge.PullingToDevice,
        NeedlerAlbumBadge.Ready -> colors.positive

        // The server's hue, for the state and for the journey into it.
        NeedlerAlbumBadge.InLibrary,
        is NeedlerAlbumBadge.Pulling -> colors.accent

        NeedlerAlbumBadge.Waiting,
        NeedlerAlbumBadge.WaitingForWifi,
        NeedlerAlbumBadge.Searching,
        NeedlerAlbumBadge.NeedsAttention,
        // Not the destructive colour. `#e8908a` is reserved for data the user is
        // about to lose; a pull that did not land has cost them nothing but time.
        NeedlerAlbumBadge.Failed,
        NeedlerAlbumBadge.PartlyDelivered,
        NeedlerAlbumBadge.Cancelled -> colors.textSecondary

        // The pack renders this one as muted metadata rather than as a coloured badge. It uses the
        // AA-compliant muted value because "no source found" is the only explanation the user gets.
        NeedlerAlbumBadge.NoSource -> colors.textMuted
    }

    val label = badge.label()
    val percent = when (badge) {
        is NeedlerAlbumBadge.Pulling -> badge.percent
        is NeedlerAlbumBadge.PullingToDevice -> badge.percent
        else -> null
    }

    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = badge.accessibleLabel()
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (badge) {
            NeedlerAlbumBadge.InLibrary, NeedlerAlbumBadge.Ready ->
                NeedlerCheckIcon(tint = tint)

            NeedlerAlbumBadge.OnDevice ->
                NeedlerOnDeviceIcon(tint = tint)

            is NeedlerAlbumBadge.Pulling,
            is NeedlerAlbumBadge.PullingToDevice ->
                NeedlerPullIcon(tint = tint)

            NeedlerAlbumBadge.Waiting,
            NeedlerAlbumBadge.WaitingForWifi,
            NeedlerAlbumBadge.Searching,
            NeedlerAlbumBadge.NeedsAttention ->
                NeedlerClockIcon(tint = tint)

            // The pack gives its one end-state badge no glyph, and the three
            // added beside it follow: nothing in the icon set says "stopped"
            // without also saying "error", which this palette has no colour for.
            // The label is the whole signal, and each label is a different word,
            // so nothing here is encoded in colour alone.
            NeedlerAlbumBadge.NoSource,
            NeedlerAlbumBadge.Failed,
            NeedlerAlbumBadge.PartlyDelivered,
            NeedlerAlbumBadge.Cancelled -> Unit
        }
        Text(text = label, style = typography.metaStrong, color = tint, maxLines = 2)
        if (percent != null) {
            Text(
                text = "$percent%",
                // Tabular so the row does not twitch as the percentage counts up.
                style = typography.metaStrong.tabularNumerals(),
                color = tint,
            )
        }
    }
}

/**
 * The word this state renders as.
 *
 * The two location states take theirs from [NeedlerAlbumSource], so the badge and anything else that
 * names a state cannot drift apart. The rest are the pull lifecycle's own words, which are a
 * different axis - they say what is happening to a record, not where it is.
 */
fun NeedlerAlbumBadge.label(): String = when (this) {
    NeedlerAlbumBadge.InLibrary -> NeedlerAlbumSource.Server.label()
    NeedlerAlbumBadge.OnDevice -> NeedlerAlbumSource.Device.label()
    is NeedlerAlbumBadge.Pulling -> "Pulling"
    is NeedlerAlbumBadge.PullingToDevice -> "Pulling to device"
    NeedlerAlbumBadge.Waiting -> "Waiting"
    NeedlerAlbumBadge.WaitingForWifi -> "Waiting for Wi-Fi"
    NeedlerAlbumBadge.Searching -> "Searching"
    NeedlerAlbumBadge.NeedsAttention -> "Needs attention"
    NeedlerAlbumBadge.Ready -> "Ready"
    NeedlerAlbumBadge.NoSource -> "no source found"
    NeedlerAlbumBadge.Failed -> "Failed"
    NeedlerAlbumBadge.PartlyDelivered -> "Partly delivered"
    NeedlerAlbumBadge.Cancelled -> "Cancelled"
}

/**
 * The same, spoken: a full phrase, with the percentage read out rather than shown as a symbol.
 *
 * The two location states read back exactly as they are drawn. A one-word state whose spoken form is
 * a sentence gives a screen-reader user a second vocabulary to learn for the same three facts, which
 * is the opposite of what reducing nine words to three was for.
 *
 * Two of the lifecycle states say one thing more, and neither of them is a location.
 * [NeedlerAlbumBadge.PullingToDevice] adds that the album plays meanwhile, because the thing a
 * listener most needs to know about a download in flight is that it is not in their way.
 * [NeedlerAlbumBadge.NeedsAttention] adds **where** the attention is needed, which the drawn label
 * had to give up for the room it was taking; a listener has no column to run out of, so this is the
 * reading that keeps the whole phrase.
 */
fun NeedlerAlbumBadge.accessibleLabel(): String = when (this) {
    is NeedlerAlbumBadge.Pulling ->
        if (percent == null) "Pulling" else "Pulling, $percent percent"
    is NeedlerAlbumBadge.PullingToDevice ->
        if (percent == null) {
            "Pulling to device, playing now"
        } else {
            "Pulling to device, $percent percent, playing now"
        }
    NeedlerAlbumBadge.NeedsAttention -> "Needs attention on the server"
    NeedlerAlbumBadge.NoSource -> "No source found"
    else -> label()
}

/**
 * The Pull action, offered on any album the server does not own.
 *
 * A 40dp accent pill with the download arrow, from the search results on screens 03 and 10. The
 * album title goes into the content description so a screen reader hears which album a lone "Pull"
 * button would acquire.
 */
@Composable
fun NeedlerPullButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    albumTitle: String? = null,
    enabled: Boolean = true,
) {
    NeedlerPrimaryButton(
        text = "Pull",
        onClick = onClick,
        modifier = modifier,
        size = NeedlerButtonSize.Small,
        enabled = enabled,
        leadingIcon = { tint -> NeedlerPullIcon(tint = tint) },
        contentDescription = albumTitle?.let { "Pull $it" },
    )
}

/**
 * Which of a pair of quality tags is the one actually in force.
 *
 * Not styling for its own sake: a local copy always wins over any streaming setting, so a screen that
 * drew `Server: MP3 192` and `Device: FLAC` as equals would imply the 192 is what you are hearing,
 * which it is not.
 *
 * ## What this no longer controls
 *
 * It used to pick the hue. [Active] was the positive green and [Dormant] was `#6f7a68`, so pulling an
 * album local moved the green off the `Server:` tag and onto the `Pulled:` tag, and the same two
 * colours meant "this one applies" before the tap and the opposite after it. Hue now comes from
 * [NeedlerAlbumSource] and never moves; emphasis is carried by the two channels below.
 *
 * Dropping [Dormant] out of `#6f7a68` is also a contrast repair. That value measures 4.21:1 on the
 * canvas and 3.82:1 on surface, below the 4.5:1 AA threshold for text this size, and it was being used
 * on a tag whose whole job is to be read. Both hues clear 9.9:1 on every background these tags sit on,
 * so the dormant tag is now legible as well as correctly coloured.
 */
enum class NeedlerQualityTagEmphasis {
    /** What the next play will actually use. A chip in the tag's own hue, with the label at 600. */
    Active,

    /** True, and not in force. No chip, and the label at the body weight. Same hue. */
    Dormant,
}

/**
 * One half of the quality tag pair: `Server: MP3 192` or `Device: FLAC`.
 *
 * The pair answers two different questions, which is why there are two tags and not one badge.
 * **Server** is what pressing play would fetch right now - live and network-dependent, so the same
 * album reads `Server: FLAC` at home and `Server: MP3 192` on mobile data. **Device** is what is
 * actually on this device, and is only ever shown for a *downloaded* copy: an opportunistically
 * cached one is evictable under disk pressure, and promising offline availability the app cannot keep
 * is worse than saying nothing.
 *
 * ## Hue says which source; shape and weight say which applies
 *
 * [source] fixes the colour, for the whole of the reasoning on [NeedlerAlbumSource]. Emphasis then
 * gets two channels of its own, neither of them colour and neither of them a change of size: [Active]
 * draws a hairline chip round the tag and sets the label at 600, [Dormant] draws no chip and sets it at
 * 400.
 * Colour alone would be a WCAG 1.4.1 failure of exactly the kind the album row's format label already
 * had to fix, and this one matters more, because "which of these two is real" is the entire point of
 * drawing both.
 *
 * TalkBack gets the sentence rather than the two words: pass [contentDescription] saying what the tag
 * means ("Device: FLAC, playing from this device"), because a chip border says nothing at all to a
 * screen reader.
 *
 * @param label the tag's name, with its colon. Pass [NeedlerAlbumSource.tagLabel] of the same
 *   [source], so that the word and the hue cannot be chosen separately - which is how they came to
 *   disagree.
 * @param value the format or rate, already formatted: `FLAC`, `MP3 192`, `Opus 128`.
 * @param source which of the three states this tag reports, and therefore its hue.
 * @param onClick what tapping does, or null for a tag that only reports. Tapping **Server** opens the
 *   rung picker and writes an override; tapping **Device** pulls or removes the download. They are
 *   deliberately not the same kind of control, which is why each screen passes its own.
 */
@Composable
fun NeedlerQualityTag(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    source: NeedlerAlbumSource = NeedlerAlbumSource.Server,
    emphasis: NeedlerQualityTagEmphasis = NeedlerQualityTagEmphasis.Active,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val shape = NeedlerTheme.shapes.formatBadge
    val active: Boolean = emphasis == NeedlerQualityTagEmphasis.Active
    val hue: Color = when (source) {
        NeedlerAlbumSource.Device -> colors.positive
        // Nothing draws a tag for a record that is on neither, so the un-retrieved case has no tag of
        // its own; it takes the server's hue rather than inventing a third.
        NeedlerAlbumSource.Server, NeedlerAlbumSource.NotRetrieved -> colors.accent
    }
    // The *label's* weight, at one size. Emphasis must not change the tag's height: the value's own
    // Space Grotesk is 11sp and `meta` is 13sp, so carrying emphasis on the value grew the dormant tag
    // by two points and pushed the player's sleep-timer row 3dp off a phone viewport at 200% text -
    // caught by `NowPlayingMeasureTest`, which is what that test is for. 13sp/600 against 13sp/400 is
    // the same box.
    val labelStyle = if (active) typography.metaStrong else typography.meta

    Row(
        modifier = modifier
            .then(
                if (active) {
                    Modifier
                        .clip(shape)
                        .border(NeedlerTheme.sizes.hairlineThickness, hue, shape)
                } else {
                    Modifier
                },
            )
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    // Clipped above, so the ripple follows the chip rather than a rectangle round it.
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                },
            )
            .defaultMinSize(minHeight = 24.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription ?: (label + " " + value)
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = labelStyle, color = hue, maxLines = 1)
        Text(text = value, style = typography.badge, color = hue, maxLines = 1)
    }
}

/**
 * The count badge on the Pulls nav item.
 *
 * An 18dp positive-green pill with the count in Space Grotesk 11sp/700, from every screen's nav.
 * REQUIREMENTS.md calls this "the reliable channel" for pull state, so it is a shared component
 * rather than something each nav host draws for itself.
 */
@Composable
fun NeedlerCounterBadge(
    count: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    if (count <= 0) return
    val colors = NeedlerTheme.colors
    Box(
        modifier = modifier
            .defaultMinSize(
                minWidth = NeedlerTheme.sizes.counterBadge,
                minHeight = NeedlerTheme.sizes.counterBadge,
            )
            .clip(NeedlerTheme.shapes.pill)
            .background(colors.positive)
            .padding(horizontal = 5.dp)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = NeedlerTheme.typography.counter,
            color = colors.onPositive,
            maxLines = 1,
        )
    }
}

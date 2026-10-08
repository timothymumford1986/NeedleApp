package app.needler.feature.library.artist

import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Track
import app.needler.core.design.component.NeedlerAlbumSource
import app.needler.core.design.component.label
import app.needler.feature.library.album.AlbumNotice
import app.needler.feature.library.common.LibraryFormat
import app.needler.feature.library.common.RequestSheetState
import app.needler.feature.library.common.showsOnDeviceCheck

/**
 * Everything the artist screen renders.
 *
 * REQUIREMENTS.md "Library browse": "Artist detail is where the two lanes meet
 * visibly. It shows owned albums from the mirror and the artist's full
 * discography from `GET /api/v1/artists/{mbid}/releases`, with everything
 * un-owned carrying a request action." So the two lists are separate fields
 * rather than one sorted list: they come from different places, they fail
 * independently, and only one of them needs a connection.
 *
 * The design pack draws no artist screen. The layout is therefore built from
 * the pack's own parts — the section header from screens 03 and 06, the album
 * row from 13, the Pull pill from 03 — rather than invented.
 */
data class ArtistUiState(

    val loading: Boolean = true,

    val artist: Artist? = null,

    /**
     * The artist this screen was opened for, straight off the route.
     *
     * Separate from `artist.mbid` because it is known on a path where [artist] is
     * not: catalogue search opens this screen for an artist the mirror has no row
     * for. It exists so the header's artwork has a stable identity even then —
     * `NeedlerArtwork` tints its letter placeholder from whatever identity it is
     * handed, so falling back to the name would give the same artist one colour in
     * search and another here, which is exactly the inconsistency
     * `:feature:search`'s own avatar comments on.
     */
    val mbid: ArtistMbid? = null,

    /**
     * The artist's name as the caller already knew it, for an artist the mirror has
     * no row for.
     *
     * Catalogue search returns artists the library does not own, labels them "Not in
     * your library yet" and lets the user tap one. `refreshArtistDiscography` writes
     * the *albums* it finds into the mirror and never an artist row, so
     * [Artist] stays null on that path and the screen drew "That artist is not here"
     * over a discography it had just fetched — having never once said the name the
     * user tapped.
     *
     * The search result already carried the name, so the screen need not wait for a
     * mirror row before rendering anything. Taking it as a hint is what makes an
     * artist screen possible for an artist nobody owns.
     */
    val knownName: String? = null,

    /** The subtitle the caller already knew, e.g. search's "Not in your library yet". */
    val knownSubtitle: String? = null,

    /** From the mirror. Present with or without a connection. */
    val ownedAlbums: List<Album> = emptyList(),

    /**
     * The rest of the discography, from the catalogue lane, already stripped of
     * anything in [ownedAlbums] by release-group MBID.
     */
    val catalogueAlbums: List<Album> = emptyList(),

    /**
     * Why the catalogue half could not be fetched, or `null` when it could.
     *
     * The owned half is unaffected whatever this says, which is the whole point of splitting
     * the two lists: an offline artist screen still lists everything you own by that artist.
     *
     * This is the error and not a flag because [NeedlerError]'s own KDoc says the distinctions
     * matter to the UI. A 404 for an artist the catalogue has never heard of, a 500, and a read
     * timeout are three different things to be told, and collapsing them to one sentence made a
     * failure that happens on every artist impossible to tell apart from one that happens on one.
     */
    val discographyError: NeedlerError? = null,

    /**
     * True when this artist's id cannot reach the catalogue at all, because
     * DroppedNeedle derived it from their name rather than matching them to
     * MusicBrainz.
     *
     * A separate field from [discographyError] because it is a different fact with
     * a different answer. An error means "not this time" — offline, a 500, a
     * timeout — and the screen invites the user to come back. This means "not
     * ever", for this artist, on this server, and the screen has to say so
     * plainly. Conflating them was the shape of the bug: every name-derived artist
     * logged `Rejected 400: Use the local library artist route for a DroppedNeedle
     * artist ID` and showed a silently short list, which reads on a device as an
     * artist with two albums rather than as an artist whose discography was never
     * available.
     *
     * See `ArtistMbid.isNameDerived`, which is where the identity question is
     * answered.
     */
    val artistNotInCatalogue: Boolean = false,

    /**
     * True once the catalogue lookup has answered, whatever it answered.
     *
     * The missing third fact. [catalogueAlbums] being empty was doing double duty for "nothing came
     * back" and "nothing has come back yet", and the screen therefore said nothing in either case —
     * which on a device read as an artist whose entire output is the four records you own. See
     * [discographyEmpty], which is the sentence this flag makes sayable.
     *
     * Defaults false so that a state built by hand — a screenshot test, a preview — draws no claim
     * about a lookup it never ran.
     */
    val discographySettled: Boolean = false,

    /**
     * True when the last catalogue lookup failed, whatever is on screen.
     *
     * [discographyError] is deliberately suppressed once there are un-owned rows to draw, because
     * "the discography is unavailable" printed under a discography is a contradiction. This is the
     * fact that survives that suppression, and it carries the legitimate half of the same message:
     * the rows on screen came out of the mirror, the lane that refreshes them did not answer, so the
     * list may be short. The user needs that; they do not need to be told the lane is unavailable
     * while reading what it fetched last time.
     */
    val discographyFetchFailed: Boolean = false,

    val offline: Boolean = false,

    val busy: Boolean = false,

    val notice: AlbumNotice? = null,

    /** The pull sheet, open, or null. Same sheet the album screen opens. */
    val requestSheet: RequestSheetState? = null,

    /**
     * Catalogue artists of this name that *do* have MusicBrainz ids, for a name-derived artist.
     *
     * The way out of the one screen in the app that was accurate and useless at the same time. See
     * `ArtistViewModel.onFindInCatalogue`, which explains why these are offered rather than resolved
     * automatically. Empty until the user asks, which is what [namesakeSearchDone] distinguishes
     * from "asked, and there are none".
     */
    val catalogueNamesakes: List<Artist> = emptyList(),

    /** That look-up is in flight. */
    val searchingCatalogue: Boolean = false,

    /** It failed, which is worth another tap where an empty answer is not. */
    val namesakeSearchFailed: Boolean = false,

    /** It finished. Without this an empty result is indistinguishable from never having asked. */
    val namesakeSearchDone: Boolean = false,

    /**
     * The catalogue has more release groups for this artist than have been fetched.
     *
     * `ArtistDiscographyPage.hasMore`, which is the server's `has_more` reduced to "there is an
     * offset we could ask for". The defect this closes was silent: the fetch asked for the
     * endpoint's first fifty release groups and nothing anywhere knew there were more, so a
     * prolific artist's screen listed fifty and then claimed, in [CATALOGUE_COMPLETE]'s own words,
     * that this was their whole discography.
     */
    val discographyHasMore: Boolean = false,

    /** The next page is in flight. */
    val loadingMoreDiscography: Boolean = false,

    /**
     * The next page did not arrive.
     *
     * Separate from [discographyFetchFailed], which is about the list already on screen being a
     * cached one. This is about the *next* page, it is answered by tapping the same row again, and
     * conflating them would put two sentences on the screen that each tell the reader to use a
     * different control.
     */
    val moreDiscographyFailed: Boolean = false,

    /**
     * How many release groups the catalogue has been asked for so far, summed over the pages fetched.
     *
     * Not `catalogueAlbums.size`. The response counts every release group it holds for the artist,
     * owned and un-owned alike, and the owned ones are dropped on the join with the mirror - so the
     * rows on screen are fewer than the catalogue has answered with, and comparing them against
     * [discographyTotal] would report a shortfall that is really the user's own library.
     */
    val discographyFetched: Int = 0,

    /**
     * How many release groups the catalogue holds for this artist, or null when the server did not
     * say.
     *
     * `source_total_count`, which this endpoint sends and which `GET /api/v1/downloads` - blind by
     * REQUIREMENTS.md "Queue screen requirements" - does not. It is null while the artist is still
     * warming upstream, so every use of it has to survive not having it.
     */
    val discographyTotal: Int? = null,

    /**
     * Tracks of every owned album, in the order the owned list is drawn, for Play
     * and Shuffle.
     *
     * Held in state rather than fetched on the tap so that the buttons can be
     * disabled honestly: an artist whose owned albums are all part-delivered has
     * nothing to play, and a Play button that did nothing would be worse than one
     * that is visibly unavailable.
     */
    val playableTracks: List<Track> = emptyList(),

    /**
     * How many rows the crate holds right now, from `PlaybackController.observeQueue`.
     *
     * The session's own figure rather than one this screen works out, for the reason
     * [crateLine] gives: the crate is the session's, and a predicted count is a claim
     * about something this screen does not own.
     */
    val crateTrackCount: Int = 0,

    /** The crate's total running time in milliseconds, from the same flow. */
    val crateDurationMs: Long = 0L,
) {

    val discographyUnavailable: Boolean get() = discographyError != null || artistNotInCatalogue

    /**
     * The catalogue answered, and it had nothing this artist does not already own.
     *
     * A different fact from [discographyUnavailable] and the one that was missing. "The lookup
     * failed" and "the lookup succeeded and the catalogue holds nothing more" are two answers a
     * listener needs told apart, and until this existed the screen gave the second one no
     * representation at all: with owned albums present, [hasNothing] is false, so the one empty-state
     * line on the screen was unreachable and the catalogue half simply vanished.
     *
     * Deliberately false while the lookup is in flight, which is what [discographySettled] is for.
     * Announcing an empty catalogue and replacing it with a discography a moment later would be
     * worse than the silence it replaces.
     */
    val discographyEmpty: Boolean
        get() = discographySettled &&
            !discographyUnavailable &&
            !discographyHasMore &&
            catalogueAlbums.isEmpty()

    /**
     * The one row under the discography: show more, looking, a failure, or nothing.
     *
     * One type for the three states because they occupy one place and only one can be true, which is
     * the shape `SearchUiState.moreRow` already settled on for the same problem in the search lane -
     * and there the alternative is recorded too: the screen must not be the thing that decides which,
     * or the two screens drift into offering a page differently.
     *
     * A name-derived artist is excluded before anything else. Nothing was ever fetched for them and
     * nothing ever will be, so the only row they get is the catalogue search the notice names; see
     * [canFindInCatalogue].
     *
     * A failure outranks the offer and stays tappable, because tapping it asks for the same page
     * again. The end of the discography is not a row at all: there is nothing left to ask for, and
     * [discographyEmpty]'s sentence covers the case where there never was anything.
     */
    val discographyMoreRow: DiscographyMoreRow?
        get() = when {
            artistNotInCatalogue -> null
            loadingMoreDiscography -> DiscographyMoreRow(label = LOOKING_UP_MORE_RELEASES, enabled = false)
            moreDiscographyFailed -> DiscographyMoreRow(
                label = MORE_RELEASES_FAILED,
                isProblem = true,
            )
            discographyHasMore -> DiscographyMoreRow(
                label = SHOW_MORE_RELEASES,
                detail = lookedUpSoFar(),
            )
            else -> null
        }

    /**
     * `50 of 212 releases looked up`, or null when the server named no total.
     *
     * The figures are the server's own: [discographyFetched] is what the pages returned and
     * [discographyTotal] is `source_total_count`. It is a running count and deliberately not a page
     * number - REQUIREMENTS.md's paging note for `GET /api/v1/downloads` rules out "page 3 of 7" where
     * the server reports no total, and this endpoint reporting one still does not make its offset
     * cursor into a page index.
     *
     * The total is dropped when it does not exceed what has been fetched. A server that answers
     * `has_more` with a total already reached is contradicting itself, and "50 of 50 releases looked
     * up" next to an offer of more would make the screen look broken rather than the response.
     *
     * ## Why this is no longer part of the label
     *
     * It used to be, as `Show more · 50 of 212 releases looked up`, and the whole string was drawn in
     * the accent blue - the colour this app uses for nothing but controls. So the counter read as
     * tappable, and a reader scanning for the control found a sentence. The offer is the two words
     * that are an instruction; the figures are a report, and REQUIREMENTS.md "Design system" gives
     * reports the muted metadata colour. Separating them lets each take the treatment it has earned.
     *
     * ## Rejected
     *
     * **Leave the whole string accent and accept it.** The same screen's failure row is the counter-
     * example: it was drawn in the primary text colour precisely so that it would not look like the
     * counter, and the result was a retry that did not look tappable at all. Two rows in one place
     * were each wearing the other's treatment.
     */
    private fun lookedUpSoFar(): String? {
        val total: Int = discographyTotal?.takeIf { it > discographyFetched } ?: return null
        return discographyFetched.toString() + " of " + total + " releases looked up"
    }

    /**
     * There is a discography on screen, and it is the cached one: the lane that refreshes it failed.
     *
     * The signal that has to survive [discographyError]'s suppression. A reader looking at nine
     * un-owned records has no way to know whether that is the artist's catalogue or the part of it
     * the mirror happened to keep, and the difference is the whole reason the catalogue lane exists.
     * Drawn as a quiet line under the section rather than as the unavailable sentence, which would
     * be claiming the lane produced nothing while its output is on the screen.
     */
    val discographyIncomplete: Boolean
        get() = discographyFetchFailed && catalogueAlbums.isNotEmpty()

    /**
     * Whether to offer the one action a name-derived artist can actually take.
     *
     * Gated on [artistNotInCatalogue] and nothing else: that flag is exactly "this artist's id can
     * never reach the catalogue", which is exactly the situation the name is the only usable key in.
     * A name is required because the name *is* the query - an artist the mirror has no row for and
     * the route gave no name to has nothing to search for, and the button would open a dead search.
     */
    val canFindInCatalogue: Boolean
        get() = artistNotInCatalogue && (artist?.name ?: knownName)?.isNotBlank() == true

    /** The look-up ran and the catalogue had nobody else of this name either. */
    val noNamesakesFound: Boolean
        get() = namesakeSearchDone && catalogueNamesakes.isEmpty() && !searchingCatalogue

    /**
     * Truly nothing to show: no mirror row, no name from the caller, and no albums of
     * either kind.
     *
     * It used to be `artist == null` alone, which is what made an artist reached from
     * catalogue search a dead end. That artist has no mirror row by definition — the
     * discography refresh writes album rows and never an artist row — so the screen
     * declared them absent while holding their whole discography, and blamed the
     * network for it. The empty screen is now reserved for the case where there is
     * genuinely nothing: not a name, not a record.
     */
    val notFound: Boolean
        get() = !loading && artist == null && knownName == null && hasNothing

    val hasNothing: Boolean
        get() = ownedAlbums.isEmpty() && catalogueAlbums.isEmpty()

    /** Whether this artist is starred. Joined onto [Artist] by the mirror, like an album's. */
    val isFavourite: Boolean get() = artist?.isFavourite == true

    /**
     * The name to draw: the mirror's, the caller's hint, or `Unknown artist`.
     *
     * Never blank and never empty, so the header always names something. A screen you
     * reached by tapping a name and which then does not repeat it gives you no way to
     * confirm you opened the right one.
     */
    val displayName: String get() = LibraryFormat.artistName(artist?.name ?: knownName)

    /**
     * The artist this page is about, as a name, or null when the page itself does not know one.
     *
     * The difference from [displayName] is the whole point of having both: that one is never null
     * because a header must say something, this one is null precisely when nothing is known, so a
     * caller can leave the artist out rather than assert ignorance. Fed to
     * [discographyRowSubtitle]; see it for what the rows do with it.
     */
    val creditedName: String?
        get() = (artist?.name ?: knownName)?.trim()?.takeIf { it.isNotEmpty() }

    /** The name to put inside a sentence, e.g. a content description. */
    val spokenName: String get() = LibraryFormat.artistLabel(artist?.name ?: knownName)

    /** True when the mirror has no row for this artist and the screen is drawn from hints. */
    val fromHintsOnly: Boolean get() = artist == null && knownName != null

    /**
     * `19 in the crate · 1 hr 14 min`, or null when the crate is empty.
     *
     * Shown under the notice an add leaves behind, because adding to a queue with no
     * visible change is indistinguishable from a tap that did not register - and these
     * are the two figures REQUIREMENTS.md "Queue" asks the crate screen itself for.
     */
    val crateLine: String? get() = LibraryFormat.crateLine(crateTrackCount, crateDurationMs)

    /**
     * True when there is something for the server to be asked for.
     *
     * Gate on the artist-wide **Pull** action. It is a real answer rather than a
     * hopeful one: there is no artist request endpoint — REQUIREMENTS.md "Placing a
     * request" lists album, track and batch — so "pull this artist" can only mean
     * "ask for every un-owned release group we know of", and with none known there is
     * nothing to send. The screen then offers a retry of the discography instead,
     * which is an action that can actually succeed.
     */
    val canPullArtist: Boolean get() = catalogueAlbums.any { it.state == AlbumState.NotOwned }

    /** The un-owned release groups an artist-wide pull would ask for. */
    val pullableAlbums: List<Album>
        get() = catalogueAlbums.filter { it.state == AlbumState.NotOwned }

    /**
     * True when the screen has nothing on it but a name, and is therefore only worth
     * looking at if it offers a way forward.
     *
     * Drives the retry. An artist whose id can never reach the catalogue is excluded:
     * retrying that is retrying a `400`.
     *
     * It used to require [hasNothing], and that is what hid the retry from the case it is most
     * needed in. An artist with four owned albums and no catalogue half has plenty on screen, so
     * `hasNothing` is false — and yet the half that is missing is exactly the one a retry could
     * fetch. The gate is now the missing half itself: nothing un-owned to show, and a reason to
     * believe asking again might change that.
     *
     * "A reason to believe" is the lookup having answered, which is also what keeps the button from
     * flashing on and off: the catalogue half is empty for the first few frames of every artist
     * screen, and an offer to try again that appears before the first attempt has finished is an
     * offer to abandon it.
     *
     * [discographyIncomplete] qualifies too, and it is the one case where the discography is on
     * screen and the retry is still offered. It has to be: the line drawn under that list tells the
     * reader the list may be short and to try again, and a sentence naming a control that is not
     * there is worse than no sentence.
     */
    val canRetryDiscography: Boolean
        get() = !loading && !artistNotInCatalogue && (
            discographyIncomplete ||
                (catalogueAlbums.isEmpty() && (discographySettled || discographyError != null))
            )

    /**
     * True when there is something to press Play on.
     *
     * The list is already filtered to tracks with a file behind them, which is the
     * whole check: an artist with owned albums and no playable file in any of them —
     * every record a part-delivered pull — has nothing to play, and REQUIREMENTS.md
     * "Partial content is a normal state" says that is a state to expect rather than an
     * edge case.
     */
    val canPlay: Boolean get() = playableTracks.isNotEmpty()

    /**
     * `12 albums · 9 more to pull`, or just `12 albums` when the catalogue is unknown.
     *
     * With nothing owned and nothing in the catalogue it falls back to whatever the
     * caller knew — search's own "Not in your library yet" — rather than reading
     * `0 albums`, which states a fact about the library as though it were a fact about
     * the artist.
     */
    val subtitle: String
        get() {
            if (ownedAlbums.isEmpty() && catalogueAlbums.isEmpty()) {
                return knownSubtitle ?: NOT_IN_LIBRARY_YET
            }
            val owned: String = LibraryFormat.plural(ownedAlbums.size.toLong(), "album")
            return if (catalogueAlbums.isEmpty()) {
                owned
            } else {
                owned + " · " + catalogueAlbums.size + " more to pull"
            }
        }

    companion object {
        /**
         * The header subtitle for an artist with nothing owned and nothing in the catalogue.
         *
         * REQUIREMENTS.md "Vocabulary" fixes three words for where a record is, and this is the
         * first of them: [NeedlerAlbumSource.NotRetrieved]. It read "Not in your library yet",
         * which is a fourth word for a state that has three - and the one the goldens showed
         * most often, because catalogue search passes its own copy of the same sentence down the
         * route. A listener who has learnt that a record is Not retrieved, on the Server or on the
         * Device should not have to learn a second phrase for the first of those.
         *
         * ## Rejected
         *
         * **Leave it, to match `:feature:search`.** The two would then agree on a word neither
         * should be using. The canonical set is the thing to agree with; search passing its own
         * string through [knownSubtitle] still wins here, and that divergence is reported rather
         * than papered over from this side.
         */
        val NOT_IN_LIBRARY_YET: String = NeedlerAlbumSource.NotRetrieved.label()
    }
}

/**
 * `1999`, or `1999 · Faithless` — the subtitle of one row in an artist's discography.
 *
 * ## Why the year leads, and the artist is usually absent
 *
 * It used to read `Dido · 1999`, and on Dido's own page every row then opened with the word "Dido".
 * At 200% text that cost the whole subtitle: `artist-catalogue-only-large-text-phone.png` wraps
 * "My Friend the Chocolate C…" over two lines and pushes the **year** - the only thing that tells
 * one row from the next - off the row entirely. The screen spent its narrowest column repeating its
 * own heading.
 *
 * So the year leads. It is the fact that distinguishes the rows, and it is four characters.
 *
 * ## What was wrong before that
 *
 * The real Dido's page listed forty releases and all but one read **"Unknown artist · 1999"**. The
 * exception was the one record that also existed in the mirror from an earlier search. The chain:
 * `ReleaseItemDto` carries no artist field at all — `GET /api/v1/artists/{mbid}/releases` does not
 * repeat the credit on every row of one artist's discography, which is reasonable of it — so
 * `DefaultLibraryRepository.refreshArtistDiscographyPage` substitutes the mirror's own artist row,
 * and there is no such row: that fetch writes albums and never an artist. The name went in blank,
 * and `LibraryFormat.albumRowSubtitle` drew the placeholder it draws for a blank one.
 *
 * ## Why this is the artist screen's own function
 *
 * **On an artist's page the artist is already known.** REQUIREMENTS.md "Library browse" is explicit
 * about what this screen is - "Artist detail is where the two lanes meet visibly. It shows the albums
 * the server has from the mirror and the artist's full discography" - and both lanes are one artist's.
 * The header above these rows names them, from the mirror or from the name the tap carried. So the
 * row needs no placeholder, no second opinion and - now - no repetition: [credit] is the page's
 * [ArtistUiState.creditedName], and a row credited to exactly that artist says nothing about it,
 * because the heading above already has.
 *
 * Rejected: `LibraryFormat.albumRowSubtitle`, which is the right answer on the Library and Search
 * screens and the wrong one here. There a row's artist is information the reader does not otherwise
 * have, so a placeholder marks a real gap; here it would be the screen claiming not to know the name
 * printed at the top of it. Showing nothing is better than asserting ignorance when the surrounding
 * context already supplies the answer.
 *
 * A **different** credit still shows, because a discography contains collaborations and
 * various-artists records: `1999 · Faithless` on Dido's page is the one case where the row's artist
 * is news. The comparison ignores case, because the catalogue and the mirror capitalise
 * independently and `dido` beside `Dido` is not a collaboration.
 *
 * Returns an empty string when there is neither a credit nor a year. The caller draws that as a
 * blank line rather than a placeholder, and leaves it out of the spoken label — a content
 * description that ends in ", " is the shape of bug the device audit reads out loud.
 */
fun discographyRowSubtitle(album: Album, credit: String?): String {
    val page: String? = credit?.trim()?.takeIf { it.isNotEmpty() }
    val own: String? = album.artistName.trim().takeIf { it.isNotEmpty() }
    val collaborator: String? = own?.takeUnless { it.equals(page, ignoreCase = true) }
    val parts: List<String> = listOfNotNull(album.year?.toString(), collaborator)
    return parts.joinToString(separator = " · ")
}

/**
 * The same, with the record's **state and format in words**: `2024 · Device · FLAC`.
 *
 * ## The failure this closes
 *
 * `artist-phone.png` drew `Submarine` with a green-outlined `FLAC` chip and `Cinema` with a
 * grey-outlined one, and that was the entire difference between a record that plays with no network
 * and one that needs the server. Four identical characters, two hues, a 12px glyph on one of them.
 *
 * `accent` and `positive` measure **1.01:1 against each other** - the same lightness in two hues -
 * so to a red-green colour-blind reader those two rows are identical. [NeedlerAlbumSource]'s own
 * KDoc says what makes that pair safe to use at all: *"each state's word is drawn or spoken in every
 * place the hue appears, and the hue only makes the distinction quicker for the readers who can see
 * it."* On these rows the word was nowhere. It was a WCAG 1.4.1 failure of exactly the kind
 * `AlbumFormatLabel` had already been rewritten to fix one column to the left.
 *
 * ## Why the subtitle, and why the chip went with it
 *
 * The album screen says this as `Device: FLAC` - `NeedlerQualityTag` in the header - and copying
 * that component into the trailing slot was the obvious move. **Rejected on width**, twice over.
 *
 * `NeedlerRowLayout.TRAILING_WEIGHT` caps a row's trailing block at 0.38 of the width the row has
 * to divide, which on a 390dp phone is about 109dp. The owned row's trailing slot already holds a
 * play control and a crate menu - 32dp each with 14dp between them - so there is room for one more
 * short thing, not for a tag twice the width of the one that was there. Measured: the `FLAC` chip
 * beside both controls came to about 140dp and `NeedlerAlbumRow` clipped the crate menu off the
 * end of the row entirely. Paying for the word in trailing width costs a control; paying for it in
 * title width is what breaks "Submarine" into "Sub / m…".
 *
 * So the format moves to the subtitle with the state word and the trailing slot keeps its two
 * controls. The subtitle is a full-width line that was holding four characters of year, and
 * `2024 · Device · FLAC` is the same three facts the album header draws, in the same order, in the
 * `LibraryFormat.albumMetaLine` style the rest of the module uses.
 *
 * ## What this gives up, and why that is an improvement
 *
 * `AlbumFormatLabel` carried two channels the plain text does not: the hue, which is the thing this
 * whole function exists to stop relying on, and a hairline chip marking lossless. The chip existed
 * because the pack had green meaning *both* lossless and on-device, leaving lossless with no channel
 * but hue. With the format as text beside the state word there is **no colour on the row at all**:
 * `FLAC` against `MP3 320` is the format distinction spelled out, and `Device` against `Server` is
 * the location distinction spelled out. Two words, two facts, nothing encoded in a colour or a
 * border. That is a stronger answer to WCAG 1.4.1 than redundancy was.
 *
 * Only the three location words appear here, never the pull lifecycle's. A row mid-download is
 * [NeedlerAlbumSource.Server] - the bytes are on the server and not yet here - and "Pulling to
 * device, 37%" in a subtitle would be a progress report that only updates when the mirror emits.
 */
internal fun ownedRowSubtitle(album: Album, credit: String?): String {
    val source: NeedlerAlbumSource = if (album.showsOnDeviceCheck) {
        NeedlerAlbumSource.Device
    } else {
        NeedlerAlbumSource.Server
    }
    val parts: List<String> = listOfNotNull(
        discographyRowSubtitle(album, credit).takeIf { it.isNotEmpty() },
        source.label(),
        // Null for a record whose format the mirror does not know, which is the state
        // `AlbumFormatLabel` drew nothing at all for. A row that said "FLAC" on a guess would be
        // worse than one that says where the record is and stops there.
        LibraryFormat.quality(album.quality),
    )
    return parts.joinToString(separator = " · ")
}

/**
 * The row under the discography.
 *
 * One type for the offer, the wait and the failure, because the list draws exactly one row there.
 * See [ArtistUiState.discographyMoreRow], which decides which of the three it is; the screen only
 * draws it and reports the tap.
 */
data class DiscographyMoreRow(
    val label: String,
    /**
     * The server's running count under the offer: `50 of 212 releases looked up`, or null.
     *
     * Separate from [label] because it is a different kind of thing and takes a different
     * treatment. The label is the control and is drawn in the accent colour; this is a report and is
     * drawn muted. Joined into one accent string, as it used to be, the figures read as tappable -
     * see `ArtistUiState.lookedUpSoFar`.
     */
    val detail: String? = null,
    /** False while a page is in flight: there is nothing a second tap could do. */
    val enabled: Boolean = true,
    /**
     * True when the label is bad news.
     *
     * It no longer changes the colour. The failure row was drawn in the primary text colour so that
     * it could not be mistaken for the offer, and the result was a retry that did not look like a
     * control at all - plain white body text with no affordance of any kind, in a palette whose one
     * signal for "you may tap this" is the accent blue. Both rows are tappable and both are now
     * accent; what tells them apart is that one says "Show more" and the other says a page did not
     * arrive. The flag is kept because `ArtistViewModel` asserts on it and because it is the honest
     * name for the state.
     */
    val isProblem: Boolean = false,
)

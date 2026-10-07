package app.needler.feature.library.common

/**
 * What TalkBack says when the library announces a favourite.
 *
 * This file used to hold a `FavouriteButton` composable as well, and `:feature:player` held a second
 * one. Both were four-line forwards to [app.needler.core.design.component.NeedlerFavouriteButton]
 * that differed in one argument: the string below. Two files and 146 lines existed to choose between
 * two content descriptions, so the composables are gone and the call sites pass
 * `contentDescription` to the shared control themselves. The library's three call sites pass this;
 * the player's two pass their own wording inline, which is all either wrapper ever decided.
 *
 * Nothing about the drawn control moved with them. The heart, the accent-when-on and
 * secondary-when-off tint pair, the stroked-or-filled fill rule and the 48dp touch target are all
 * `NeedlerFavouriteButton`, and the sizes each surface asks for are unchanged: 36dp on an album
 * track row, 44dp in a top bar and on Now Playing, 40dp in the tablet sidebar.
 *
 * ## Why the wording did not get inlined with the rest
 *
 * It is internal rather than private so the tests can assert on the exact text: this is the only
 * place in the app where the state of a favourite is announced with the subject's name, and
 * "Starred" versus "Star" is one character away from telling the user the opposite of the truth.
 * Three library call sites interpolating that themselves would be three chances to get it wrong and
 * nothing to fail when one of them did.
 *
 * The wording stayed when the glyph changed from a star to a heart. It matches the server's own
 * `star`/`unstar` verbs and `getStarred2`, and the library's sort control already offers a "Starred"
 * option, so the spoken vocabulary is consistent with everything around it. The hazard
 * REQUIREMENTS.md "Playlists" describes is a five-pointed *picture* that implies a rating this
 * server silently discards; the verb carries no such implication.
 *
 * The rejected alternative was to fold this into [LibraryFormat], which does claim to hold "every
 * string the library, album and artist screens render". It was left here because `LibraryFormat` is
 * strings built from a *number* - durations, byte counts, years - and a move would have renamed the
 * call in `FavouriteControlTest` for no gain. The test is the whole reason this function has a name.
 *
 * @param name what is being starred: an album title, an artist name, a track title.
 */
internal fun favouriteContentDescription(isFavourite: Boolean, name: String): String =
    if (isFavourite) {
        "Starred. Remove " + name + " from your favourites"
    } else {
        "Star " + name
    }

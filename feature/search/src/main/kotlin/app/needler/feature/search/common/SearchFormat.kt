package app.needler.feature.search.common

import app.needler.core.design.component.NeedlerAlbumBadge
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.PullState
import app.needler.core.domain.model.Track

/**
 * Every string the search screen renders from a number, and the mapping from an
 * album's state onto the badge the pack draws for it.
 *
 * These are pure functions for the same reason `:feature:library` keeps its own
 * set pure: formatting is where a screen quietly lies — a rounded count that
 * never reaches the next unit, a "0 albums" for an artist whose discography has
 * not been fetched — and a pure function is the part of a screen that can be
 * tested without rendering anything.
 *
 * ## Why this duplicates `:feature:library`
 *
 * `LibraryFormat`, `albumBadge` and `problemMessage` already exist, three
 * modules' worth of identical logic away, as `internal` members of
 * `:feature:library`. `internal` means module-private in Kotlin, so this module
 * cannot see them, and a test source set is not the only thing that cannot be
 * shared across a module boundary. The right home for all of it is a small
 * `:core:ui` (or an expansion of `:core:design`, which already owns
 * `NeedlerAlbumBadge` and therefore already owns half the mapping below); this
 * is written down in the handover notes rather than solved here, because
 * `:core:design` is off limits to this work.
 *
 * What is **not** duplicated is the wording. `:feature:library` writes an artist
 * row as "2 albums · 3 more to pull" because that is what its own screens ask
 * for; `design/html/03-Search.html` writes the same artist as
 * "5 albums · 2 in your library", counting from the catalogue down rather than
 * from the library up. The search screens are the source of truth here, so the
 * search wording is what [artistRowSubtitle] produces.
 */
internal object SearchFormat {

    /** Matches `LibraryFormat.UNTITLED_ALBUM`. */
    const val UNTITLED_ALBUM: String = "Untitled album"


    // ---- durations ----------------------------------------------------------

    /** `3:47`, as the song rows on screen 03 draw it. Null for an unknown duration. */
    fun duration(milliseconds: Long?): String? {
        val ms: Long = milliseconds ?: return null
        if (ms < 0L) return null
        val totalSeconds: Long = ms / 1_000L
        val seconds: Long = totalSeconds % 60L
        val minutes: Long = (totalSeconds / 60L) % 60L
        val hours: Long = totalSeconds / 3_600L
        return if (hours > 0L) {
            hours.toString() + ":" + pad(minutes) + ":" + pad(seconds)
        } else {
            minutes.toString() + ":" + pad(seconds)
        }
    }

    /**
     * The same duration as words, for TalkBack.
     *
     * `3:47` read literally is announced as a clock time, which is why every
     * drawn duration on this screen has a spoken twin rather than being handed
     * to a content description as-is.
     */
    fun spokenDuration(milliseconds: Long?): String? {
        val ms: Long = milliseconds ?: return null
        if (ms < 0L) return null
        val totalSeconds: Long = ms / 1_000L
        val seconds: Long = totalSeconds % 60L
        val minutes: Long = (totalSeconds / 60L) % 60L
        val hours: Long = totalSeconds / 3_600L
        val parts: List<String> = buildList {
            if (hours > 0L) add(plural(hours, "hour"))
            if (minutes > 0L) add(plural(minutes, "minute"))
            if (seconds > 0L || isEmpty()) add(plural(seconds, "second"))
        }
        return parts.joinToString(separator = " ")
    }

    // ---- composed lines -----------------------------------------------------

    /**
     * `Khruangbin · 2024`, the second line of an album row on screens 03 and 10.
     *
     * The year is dropped when the catalogue does not know it rather than drawn
     * as a placeholder, because a release group with no date is common in
     * MusicBrainz and "Khruangbin · —" says nothing a blank does not.
     */
    /**
     * An album title that is safe to render.
     *
     * The wire DTO defaults `title` to the empty string and `CatalogueMappers`
     * carries that through with `orEmpty()`, so a catalogue result legitimately
     * reaches the UI with nothing to show. Drawing it raw is what left the Pulls
     * screen with 34 blank rows on a real device, and what made its Cancel label
     * read "Cancel the pull of " with nothing after it.
     *
     * The wording matches `:feature:library`'s `LibraryFormat.albumTitle`
     * deliberately - the same album must not be "Untitled album" on one screen
     * and something else on the next. It is duplicated rather than shared
     * because a feature module may not depend on another feature module; the
     * shared home, if this spreads further, is `:core:design`.
     */
    fun albumTitle(raw: String?): String =
        raw?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED_ALBUM

    fun albumRowSubtitle(album: Album): String {
        val parts: List<String> = buildList {
            if (album.artistName.isNotBlank()) add(album.artistName)
            album.year?.let { add(it.toString()) }
        }
        return parts.joinToString(separator = " · ")
    }

    /** `Khruangbin · Mordechai`, the second line of a song row on screen 03. */
    fun songRowSubtitle(track: Track): String {
        val parts: List<String> = buildList {
            if (track.artistName.isNotBlank()) add(track.artistName)
            track.albumTitle?.let { add(it) }
        }
        return parts.joinToString(separator = " · ")
    }

    /**
     * `5 albums · 2 in your library`, the artist row on screen 03.
     *
     * The pack's tablet artist row (10) adds a third clause —
     * "4 albums · 1 in your library · drummer, London" — from the artist's
     * MusicBrainz disambiguation and area. `Artist` carries neither field, and
     * inventing one here would mean inventing a network call in a module that
     * is not allowed to make one, so the clause is deliberately dropped rather
     * than faked. It is in the handover notes: `Artist` wants a
     * `disambiguation: String?`.
     *
     * [Artist.catalogueAlbumCount] is null until a discography has been fetched,
     * which for a search result is the normal case — so the common rendering is
     * the library-only one.
     */
    fun artistRowSubtitle(artist: Artist): String {
        val owned: Int = artist.ownedAlbumCount
        val catalogue: Int? = artist.catalogueAlbumCount
        return when {
            catalogue != null && catalogue > 0 ->
                plural(catalogue.toLong(), "album") + " · " + owned + " in your library"

            owned > 0 -> plural(owned.toLong(), "album") + " in your library"

            // A catalogue-only artist: found in MusicBrainz, nothing of theirs
            // synced. Saying "0 albums" would read as a broken count.
            else -> "Not in your library yet"
        }
    }

    /** `album`/`albums`, `1 track`/`8 tracks`. */
    fun plural(count: Long, noun: String): String =
        if (count == 1L) "$count $noun" else "$count ${noun}s"

    /**
     * `19 in the crate · 1 hr 14 min`, or null when the crate is empty.
     *
     * The two figures REQUIREMENTS.md "Queue" puts on the crate screen - "an Up next
     * list with a count and total duration" - said on the screen the user added from,
     * because that is where "did that work" gets asked. "In the crate" is the product's
     * own phrase and the only one allowed in anything a user reads.
     *
     * A duplicate of `LibraryFormat.crateLine`, as every other member of this object is
     * a duplicate of its twin there: a test source set is not publishable and neither is
     * a formatter shared between two feature modules without a `:core:*` to put it in.
     * The totals are deliberately **not** pushed into `:core:design` with the control
     * that shows them, because the sentence is product copy about domain figures and
     * that module takes no domain dependency.
     */
    fun crateLine(trackCount: Int, durationMs: Long): String? {
        if (trackCount <= 0) return null
        val parts: List<String> = buildList {
            add(trackCount.toString() + " in the crate")
            runningTime(durationMs)?.let { add(it) }
        }
        return parts.joinToString(separator = " · ")
    }

    /**
     * `28 min`, the whole-crate figure. Null for an unknown or zero total.
     *
     * Private because [crateLine] is the only caller this module has; `LibraryFormat`'s
     * copy is public there because screen 11 draws an album's running time on its own.
     */
    private fun runningTime(milliseconds: Long?): String? {
        val ms: Long = milliseconds ?: return null
        if (ms <= 0L) return null
        val totalMinutes: Long = (ms / 60_000L).coerceAtLeast(1L)
        val hours: Long = totalMinutes / 60L
        val minutes: Long = totalMinutes % 60L
        return when {
            hours == 0L -> totalMinutes.toString() + " min"
            minutes == 0L -> hours.toString() + " hr"
            else -> hours.toString() + " hr " + minutes + " min"
        }
    }

    // The letter drawn in an artist avatar when there is no image used to be computed here. It is
    // `:core:design`'s `artworkPlaceholderInitial` now, behind `NeedlerArtwork`, which draws the
    // letter and the tint together for albums, artists and widgets alike. Two copies of that rule
    // meant the search screen's coverless tiles looked like nowhere else in the app.

    private fun pad(value: Long): String = if (value < 10L) "0$value" else value.toString()
}

/**
 * The badge one [AlbumState] wears, from REQUIREMENTS.md "Album states".
 *
 * | State | Badge |
 * | --- | --- |
 * | `NotOwned` | none |
 * | `PendingApproval` | Waiting |
 * | `Acquiring` | Pulling, with percentage — or Searching / Needs attention |
 * | `Owned` | In library |
 * | `Pinned` | On device |
 * | `Failed` | no source found |
 *
 * All four of the badges screen 03 draws on one list come out of this one
 * function, which is the point: a merged search result is a single [Album] at a
 * single [AlbumState], so there is no branch anywhere on this screen that asks
 * whether a row came from the mirror or from the catalogue.
 *
 * Returns null where the pack draws nothing, which is the un-owned case: an
 * album you do not own wears a **Pull** button instead of a badge.
 */
internal fun albumBadge(state: AlbumState): NeedlerAlbumBadge? = when (state) {
    AlbumState.NotOwned -> null
    is AlbumState.PendingApproval -> NeedlerAlbumBadge.Waiting
    is AlbumState.Acquiring -> when (state.stage) {
        PullState.PENDING_APPROVAL -> NeedlerAlbumBadge.Waiting
        PullState.SEARCHING -> NeedlerAlbumBadge.Searching
        PullState.AWAITING_SOURCE_REVIEW -> NeedlerAlbumBadge.NeedsAttention
        else -> NeedlerAlbumBadge.Pulling(state.progress.percent)
    }
    AlbumState.Owned -> NeedlerAlbumBadge.InLibrary
    is AlbumState.Pinned -> NeedlerAlbumBadge.OnDevice
    is AlbumState.Failed -> NeedlerAlbumBadge.NoSource
}

/** True when this album is complete on this device, so its row carries the green check. */
internal val Album.showsOnDeviceCheck: Boolean
    get() = state is AlbumState.Pinned && isFullyOnDevice

/**
 * Whether this track has a file behind it on the server.
 *
 * A part-delivered pull leaves an album that is *in library* with some of its
 * tracks missing, and a Songs result that offered a tap on one of those would
 * offer a tap that cannot work. The domain model carries no flag for this, so
 * the check is made against the sentinel `:core:data` writes into `FileId` when
 * a mirror row has no `file_id` — the same magic string `:feature:library`
 * matches, and the same entry in the handover notes: `Track` wants an explicit
 * `isPlayable`.
 */
internal val Track.hasPlayableFile: Boolean
    get() = fetch.fileId.value != MISSING_FILE_ID

/** The placeholder `:core:data` writes when a mirror row has no `file_id`. */
internal const val MISSING_FILE_ID: String = "unavailable"

/**
 * What to say to the user about a [NeedlerError] raised by an action on this
 * screen.
 *
 * The errors search can actually produce are a short list, and each has a
 * different next move: a stale session stops the catalogue lane but leaves
 * playback and the mirror working, a rate limit is a wait rather than a failure,
 * and offline is a queue rather than a loss. `diagnostic` is deliberately never
 * shown for a modelled error — it names status codes and internal state and is
 * written for the log — so it surfaces only in the unmodelled fallback, where
 * something specific beats "something went wrong".
 */
internal fun problemMessage(error: NeedlerError): String = when (error) {
    NeedlerError.SessionExpired ->
        "Your sign-in has expired, so catalogue search and pulls are unavailable until you " +
            "sign in again. Your library still searches and plays."

    is NeedlerError.AppPasswordRevoked ->
        "This device's access to the library was revoked on the server. Sign in again from " +
            "Settings."

    NeedlerError.SubsonicProtocolDisabled ->
        "The Subsonic interface is switched off on the server. An administrator has to turn " +
            "on \"Enable Subsonic API\" under Settings, Connect Apps."

    is NeedlerError.RateLimited -> {
        val seconds: Long? = error.retryAfter?.inWholeSeconds?.coerceAtLeast(1L)
        if (seconds == null) {
            "The server is rate limiting requests. Try again in a moment."
        } else {
            "The server is rate limiting requests. Try again in " + seconds + "s."
        }
    }

    is NeedlerError.PermissionDenied ->
        "Your account is not allowed to do that on this server."

    is NeedlerError.Offline ->
        "No connection. This is queued and sent as soon as you are back online."

    is NeedlerError.ServerError ->
        "The server answered " + error.statusCode + ". Try again in a moment."

    is NeedlerError.NotFound ->
        "The server no longer has that."

    is NeedlerError.Rejected ->
        error.message ?: "The server refused that request."

    NeedlerError.Cancelled -> "Cancelled."

    else -> error.diagnostic
}

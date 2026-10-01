package app.needler.widget.recent

import android.graphics.Bitmap
import app.needler.core.domain.model.Album

/**
 * Everything the recently added card draws, with the work already done.
 *
 * Same bargain as `NowPlayingModel`: a Glance composable is the worst possible place to do anything
 * that can fail or block, so the album is reduced to strings and a decoded cover before the card
 * exists. The fold is a pure function of one [Album], which makes it the only part of this widget
 * that can be exercised without a launcher, a Hilt graph or a server.
 *
 * ## Why it holds an MBID
 *
 * The card has two tap targets and both need the album's identity: the card itself opens that
 * album's screen and the disc plays it. `NowPlayingModel` needs no such field because the session
 * already knows what it is playing; here the widget is the only thing that knows which album it drew,
 * and the identity has to survive into a `PendingIntent` and into an `ActionParameters` bundle. It is
 * carried as the bare MBID string rather than a [app.needler.core.domain.model.ReleaseGroupMbid]
 * because both destinations are string-valued - an intent extra and an action parameter - and
 * wrapping it here would only mean unwrapping it twice at the boundary.
 */
internal data class RecentlyAddedModel(
    /** False when the mirror has no albums at all: the empty state the card draws instead. */
    val hasAlbum: Boolean = false,
    /** The release-group MBID, for the tap target and the play action. Empty in the empty state. */
    val releaseGroupMbid: String = "",
    /**
     * `Mordechai`, **trimmed**, and empty when the mirror holds no title for this album.
     *
     * Trimmed so that `isEmpty` and `isBlank` cannot disagree about it, and a title of three spaces is
     * the same thing as no title. Empty is never drawn: [hasTitle] is what the card branches on, and it
     * falls back to `R.string.widget_untitled_album`. A blank line on a home screen looks like a broken
     * widget and tells the user nothing, which is exactly the failure a missing reader on the pull
     * lane's `album_title` produced on a device - 34 of 35 pulls with nothing in the slot - and the
     * reason every title slot in this module guards rather than trusting its input.
     */
    val title: String = "",
    /** `Khruangbin`, trimmed. Empty when the mirror has no artist name; see [hasArtist]. */
    val artistName: String = "",
    /** `Mordechai by Khruangbin`, or null when there is nothing worth announcing. */
    val artworkDescription: String? = null,
    /** The cropped, rounded cover, or null for the placeholder tint. */
    val cover: Bitmap? = null,
) {

    /**
     * Whether the Play disc is worth drawing.
     *
     * Unlike the now-playing card's Play button, this one has nothing to fall back on: it plays *this
     * album*, and with no album there is no command to issue. `PlaybackController.play()` would
     * resume the persisted crate, which is a different thing entirely and not what a disc on a card
     * headed "Recently added" promises. So the disc goes, exactly as Previous and Next go from the
     * now-playing card when nothing is loaded - a dead button is worse than no button.
     */
    val canPlay: Boolean get() = hasAlbum && releaseGroupMbid.isNotBlank()

    /**
     * Whether [title] is worth drawing, or the card has to name the absence instead.
     *
     * A plain `isNotEmpty`, exact because [title] is already trimmed. An album with no title is still
     * playable and still a link - a thin sync is not a broken album - so this gates the words on the
     * card and nothing else.
     */
    val hasTitle: Boolean get() = title.isNotEmpty()

    /**
     * Whether [artistName] is worth drawing, or the card has to name the absence instead.
     *
     * With no artist name the line reads "Unknown artist", which is `LibraryFormat.UNKNOWN_ARTIST` and
     * therefore what the rest of the app already calls the same hole. Named rather than dropped, so
     * the card keeps the three lines the pack draws and does not change shape on a thin sync - and
     * rather than invented, because the widget has no more idea who made the record than the app does.
     */
    val hasArtist: Boolean get() = artistName.isNotEmpty()

    companion object {

        /** Nothing in the mirror: what the card draws on a fresh install, and before sync answers. */
        val Empty: RecentlyAddedModel = RecentlyAddedModel()

        /**
         * Folds one album onto the card.
         *
         * [cover] is passed in rather than derived, because fetching it is a suspending network call
         * and this has to stay pure.
         *
         * The album's [app.needler.core.domain.model.AlbumState] is deliberately not read. Screens 15
         * and 18 draw no badge on this card, and every album this widget can show is owned by
         * definition - `AlbumListKind.NEWEST` is the mirror's own arrival order over owned content, so
         * a catalogue-only album has no arrival time to sort by and cannot appear here. Drawing "In
         * library" on a card that can only ever show library albums would be noise.
         */
        fun of(album: Album?, cover: Bitmap?): RecentlyAddedModel {
            if (album == null) return Empty
            return RecentlyAddedModel(
                hasAlbum = true,
                releaseGroupMbid = album.releaseGroupMbid.value,
                title = album.title.trim(),
                artistName = album.artistName.trim(),
                artworkDescription = artworkDescription(album),
                cover = cover,
            )
        }

        /**
         * `Mordechai by Khruangbin`, in the pack's own wording.
         *
         * Mirrors `:core:design`'s `albumArtContentDescription` and `WidgetFormat.artworkDescription`,
         * including the null case: with nothing useful to say, the caller keeps the image out of the
         * accessibility tree rather than announcing "image".
         *
         * Trimmed on the way in, like [title] and [artistName], so that a padded title does not reach
         * TalkBack as `Mordechai   by Khruangbin`. A trim and a blankness check in the same expression
         * is deliberate belt and braces: the trim is what makes the check exact.
         */
        private fun artworkDescription(album: Album): String? {
            val title: String? = album.title.trim().takeIf { it.isNotEmpty() }
            val artist: String? = album.artistName.trim().takeIf { it.isNotEmpty() }
            return when {
                title == null && artist == null -> null
                artist == null -> title
                title == null -> artist
                else -> title + " by " + artist
            }
        }
    }
}

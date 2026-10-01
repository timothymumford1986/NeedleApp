package app.needler.player.service.media

import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.Track

/**
 * One row of a browse list, before Media3 sees it.
 *
 * ## Why the tree does not build `MediaItem`s directly
 *
 * A `MediaItem` is an Android type: it holds a `Uri`, a `Bundle` and Media3's own media-type integers, and
 * building one outside an instrumented test gives a hollow object whose fields are whatever the JVM stubs
 * return. The rules worth protecting here are about *content* - which node lists what, in which order, one
 * page at a time, and that nothing un-owned reaches a car - and every one of them can be asserted on this
 * type with no Android runtime at all. [BrowseItems] does the Media3 half, and it does nothing but assign
 * fields, which is the smallest thing this module could leave untested.
 *
 * It also keeps [BrowseTree] honest about the layering rule: the tree reads domain repositories and speaks
 * domain types, and the one file that knows Media3 exists is the mapper.
 *
 * @property mediaId the id Auto will hand back, and therefore the thing that has to round-trip: a browse
 *   node id for a folder, a bare track id for a song. Never a crate row id - a row id is minted when the
 *   track is enqueued, not when it is listed.
 * @property track set on a song row so the mapper can carry the duration, disc and track number across
 *   without asking the mirror a second question.
 */
public data class BrowseRow(
    val mediaId: String,
    val title: String,
    val kind: BrowseRowKind,
    val subtitle: String? = null,
    val artwork: ArtworkRef? = null,
    val track: Track? = null,
)

/**
 * What a row is, which fixes how Auto draws it.
 *
 * Auto's rendering is driven entirely by `isBrowsable`, `isPlayable` and the media type: a row with neither
 * flag set is dropped by Media3's own assertions, and a folder that claims to be playable gets a play button
 * that has to do something. So the three travel together here rather than being set item by item, where the
 * album node would eventually get one of them wrong.
 *
 * The four "one thing" kinds are browsable *and* playable, which is the behaviour REQUIREMENTS.md "Android
 * Auto" implies for a tree that "mirrors the app": tapping an album opens it, and the play affordance on it
 * enqueues the whole record in order. [BrowseTree.tracksFor] is the other half of that promise.
 *
 * Folders are browsable only. A play button on "Songs" would enqueue an entire library - fifty thousand rows
 * in the crate for one accidental tap at a junction - and there is no honest smaller answer, so the button
 * is not offered.
 */
public enum class BrowseRowKind(
    /** First argument of every entry below: whether opening the row shows a list. */
    public val isBrowsable: Boolean,
    /** Second argument: whether the row can be played, which for a folder it cannot. */
    public val isPlayable: Boolean,
) {
    /** A folder holding more than one kind of thing: the root, Library, Favourites. */
    FOLDER_MIXED(true, false),

    /** A folder of albums: Albums, Recently added, On device. */
    FOLDER_ALBUMS(true, false),

    /** A folder of artists. */
    FOLDER_ARTISTS(true, false),

    /** A folder of songs. */
    FOLDER_TRACKS(true, false),

    /** A folder of genres. */
    FOLDER_GENRES(true, false),

    /** A folder of playlists. */
    FOLDER_PLAYLISTS(true, false),

    /** One album. Opens to its tracks; plays as the whole record, in order. */
    ALBUM(true, true),

    /** One artist. Opens to their owned albums; plays as all of them, in order. */
    ARTIST(true, true),

    /** One playlist. Opens to its entries; plays in playlist order. */
    PLAYLIST(true, true),

    /** One genre. */
    GENRE(true, true),

    /** One song. */
    TRACK(false, true),
}

package app.needler.feature.library.playlists

import app.needler.core.domain.model.Playlist
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.feature.library.common.LibraryFormat

/**
 * Everything one playlist's screen renders.
 *
 * The design pack draws no playlist screen. This is album detail (04) with the
 * album's header replaced by the playlist's and the track list made editable, so
 * that a user who has learned one has learned the other.
 *
 * ## Star ratings are absent by construction
 *
 * REQUIREMENTS.md "Playlists": "`setRating` is a deliberate no-op on this
 * server: it validates and returns success without persisting anything. Needler
 * must not offer star ratings." There is therefore no rating field here and no
 * rating control on the screen. Binary favourites are the supported mechanism and
 * they belong to `FavouriteRepository`, not to a playlist.
 *
 * @property entries the playlist in order, one row each, including rows the
 *   mirror cannot currently resolve to a track.
 * @property rename the open rename form, or null. Inline rather than a dialog;
 *   see [PlaylistDraft].
 */
data class PlaylistUiState(

    /** True until the mirror has answered. The refresh that follows is not waited for. */
    val loading: Boolean = true,

    val playlist: Playlist? = null,

    val entries: List<PlaylistTrack> = emptyList(),

    /** The track the player is on, so its row can mark itself. */
    val nowPlayingTrackKey: TrackKey? = null,

    val offline: Boolean = false,

    /** A write is in flight, so the controls are disabled rather than tappable twice. */
    val busy: Boolean = false,

    val rename: PlaylistDraft? = null,

    val confirmingDelete: Boolean = false,

    /** The open "add tracks" picker, or null. */
    val picker: PlaylistTrackPicker? = null,

    val notice: PlaylistNotice? = null,
) {

    /** The mirror answered and had nothing: a playlist deleted on another client, usually. */
    val notFound: Boolean get() = !loading && playlist == null

    /** How much of this playlist the server knows about. Null until it has loaded. */
    val syncState: PlaylistSyncState? get() = playlist?.syncState

    /** The playlist loaded and has no tracks in it. */
    val isEmpty: Boolean get() = !loading && playlist != null && entries.isEmpty()

    /** True when there is at least one track that can actually be played. */
    val hasPlayableTracks: Boolean get() = entries.any { it.available }

    /**
     * Rows the mirror cannot play.
     *
     * Two different causes, and the screen does not distinguish them because
     * neither has a different remedy from this screen: a track of a
     * part-delivered pull that exists nowhere, and a track whose album a delta
     * sync has temporarily dropped. REQUIREMENTS.md "Local persistence" is
     * explicit that the second must not empty the playlist —
     * "`playlist_track` … has no foreign key to `track`: a delta sync that
     * briefly drops an album must not silently empty a user's playlists" — so the
     * row keeps its place and is drawn greyed.
     */
    val unplayableEntries: List<PlaylistTrack> get() = entries.filter { !it.available }

    /** True when some of the playlist plays and some of it does not. */
    val isPartlyUnplayable: Boolean
        get() = unplayableEntries.isNotEmpty() && unplayableEntries.size < entries.size

    /** True when reordering is meaningful. One track cannot be moved anywhere. */
    val canReorder: Boolean get() = entries.size > 1

    /**
     * `12 tracks · 48 min`.
     *
     * The running time falls back to summing the entries when the server did not
     * give one, but only when *every* entry has a duration: a sum over half the
     * rows is a wrong number presented with the same confidence as a right one.
     * "Unknown is not zero", as `LibraryFormat` puts it.
     */
    val headerLine: String
        get() {
            val current: Playlist = playlist ?: return ""
            val parts: List<String> = buildList {
                add(LibraryFormat.plural(current.trackCount.toLong(), "track"))
                LibraryFormat.runningTime(current.durationMs ?: summedDurationMs())?.let { add(it) }
            }
            return parts.joinToString(separator = " · ")
        }

    private fun summedDurationMs(): Long? {
        if (entries.isEmpty()) return null
        val durations: List<Long> = entries.mapNotNull { it.track.durationMs }
        return if (durations.size == entries.size) durations.sum() else null
    }
}

/**
 * The "add tracks" picker.
 *
 * ## Why it searches rather than lists
 *
 * A library of five thousand albums is fifty thousand-odd songs, and
 * `LibraryRepository.observeTracks` is explicit that it will not hand out all of
 * them: it is "bounded rather than paged" because holding the lot in a flow "would
 * spend the whole of REQUIREMENTS.md's scroll budget on allocation". A picker built
 * on a capped list would therefore be a picker that cannot reach most of the
 * library — and the one song the user wants is exactly the one past the cap.
 *
 * So the picker is a search. [app.needler.core.domain.repository.SearchRepository.searchLocal]
 * is FTS over the mirror,
 * which REQUIREMENTS.md "Search behaviour" requires to answer "immediately, with no
 * network call" and in "under 50 ms for 10,000 albums", so it works offline and the
 * results arrive on the first keystroke. The rejected alternative — the catalogue
 * lane — is not merely slower: a track that exists only in MusicBrainz has no file
 * to play and cannot go in a playlist at all.
 *
 * @property query what has been typed. Blank shows the hint rather than everything,
 *   because a blank FTS query has no defensible answer.
 * @property results tracks from the mirror, capped. Only tracks: an album or an
 *   artist is not a playlist entry, and `getPlaylist` stores songs.
 * @property selected the chosen tracks in the order they were chosen, which is the
 *   order they are appended in. A set would lose that, and appending in a hash
 *   order the user cannot predict reads as a bug.
 */
data class PlaylistTrackPicker(
    val query: String = "",
    val results: List<Track> = emptyList(),
    val selected: List<TrackKey> = emptyList(),
) {
    val canSubmit: Boolean get() = selected.isNotEmpty()

    val selectedCount: Int get() = selected.size

    /** True once something has been typed, so the hint gives way to results. */
    val hasQuery: Boolean get() = query.isNotBlank()

    /** The searched-and-found-nothing state, which is not the same as not having searched. */
    val foundNothing: Boolean get() = hasQuery && results.isEmpty()

    fun isSelected(key: TrackKey): Boolean = selected.contains(key)

    /** The button's label: `Add 3 tracks`, or `Add tracks` with nothing chosen. */
    val confirmLabel: String
        get() = if (selected.isEmpty()) {
            "Add tracks"
        } else {
            "Add " + LibraryFormat.plural(selected.size.toLong(), "track")
        }
}

/**
 * One row of the playlist.
 *
 * [position] is the entry's **0-based** index, which is what the protocol works
 * in: `updatePlaylist` removes by `songIndexToRemove`, and
 * [app.needler.core.domain.model.PlaylistEdit.RemoveTracks] passes those indices
 * straight through. The number the row draws is therefore `position + 1`. Keeping
 * the raw index here rather than the drawn number is deliberate: the one place
 * these two could be confused is a removal, and a removal off by one takes out
 * the wrong song.
 *
 * [available] wraps the track for the same reason album detail's row does: a
 * playlist can point at a track with no file behind it, and the screen has to
 * know that before it offers a tap that cannot work.
 */
data class PlaylistTrack(
    val position: Int,
    val track: Track,
    val available: Boolean,
) {
    val key: TrackKey get() = track.key

    /** The number the row draws, 1-based. */
    val displayNumber: Int get() = position + 1
}

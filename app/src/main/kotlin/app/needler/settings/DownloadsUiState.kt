package app.needler.settings

import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.ReleaseGroupMbid

/**
 * Everything the Downloaded albums screen draws.
 *
 * ## Why this screen exists, and why Settings still draws part of the list
 *
 * The list lived entirely inside the Settings `LazyColumn`, as an `items()` with no ceiling.
 * REQUIREMENTS.md "Storage, and why there is no budget" makes it load-bearing - "**Downloaded albums
 * listed by size, largest first**, each removable on its own. This replaces the budget as the way
 * space is reclaimed, and it is the only view that can answer 'what is actually taking up the room'"
 * - so it could not be dropped, and nothing here caps what a user can see or remove. What it could
 * not go on being is unbounded: the section grows with the offline library until it buries About and
 * Sign out behind it, which is what was reported from the device as "just a huge list".
 *
 * The answer is split rather than wholesale. Settings keeps the first `StorageSectionState.INLINE_DOWNLOADED_ALBUMS`
 * rows, which is the whole list on most devices and costs those users nothing; this screen holds all
 * of them for the device where that is not enough. The two alternatives, and why they lost:
 *
 *  * **Move the whole list here unconditionally.** Simplest, and wrong for the common case: it
 *    charges every user a tap and a screen transition to reach four or five rows that were previously
 *    in front of them, to fix a crowding problem those users do not have.
 *  * **Collapse the section behind its count, or cap it with a "show all" that expands in place.**
 *    Keeps it to one screen, but an expanded section is the original wall again, now with the rest of
 *    Settings jumping as it opens. The expansion is also screen-local state that does not survive
 *    leaving Settings, so a user who opens it, removes an album, goes elsewhere and comes back finds
 *    it shut - and the state cannot be asserted on from a literal the way this screen can.
 *
 * ## Why the order is derived here as well as in SQL
 *
 * `PinRepository.observeDownloadedAlbums` already defaults to `DownloadedAlbumOrder.LARGEST_FIRST`
 * and does the ordering in the query, which is the real source of it. [albums] sorts again anyway,
 * for one reason: largest-first is this screen's whole argument - it is what makes the list
 * actionable for reclaiming space - and a contract that can only be checked by standing up a
 * database is a contract that gets checked once. Sorting here makes it provable from a literal, and
 * on an already-ordered list it is a no-op. It is computed once per state value rather than in a
 * `get()`, so a scroll does not re-sort the library on every frame.
 *
 * ## Why the removal in flight is an identity and not a flag
 *
 * It was a `working: Boolean`, and the screen could do nothing with it but grey out all fourteen
 * remove controls at once. A render of that state - `screenshots/downloads-removing-phone.png` - is
 * fourteen identical disabled rows with no mark on the one the user just tapped, which is the worst
 * possible feedback for an action that deletes files: the user cannot tell which album they have
 * destroyed, only that the screen has stopped responding. [removing] names the row instead, so the
 * row says "Removing" and every other row says only that it is waiting its turn.
 *
 * @property downloaded the albums as the repository handed them over. Render [albums] instead, which
 *   is this list in the order the screen promises.
 * @property removing the album a removal is deleting right now, or null. One at a time: the removals
 *   delete files and report what they freed, and two overlapping ones would report each other's
 *   figures.
 * @property undoing true while [DownloadsNotice.undo] is being put back, which is a pin and a
 *   download rather than a deletion and so cannot share [removing].
 * @property notice what the last removal did, what it could not do, or what an undo put back.
 *   Replaced by the next one and dismissible, because it offers an action and an offer the user has
 *   declined should go away.
 */
data class DownloadsUiState(
    val loading: Boolean = true,
    val downloaded: List<DownloadedAlbum> = emptyList(),
    val removing: ReleaseGroupMbid? = null,
    val undoing: Boolean = false,
    val notice: DownloadsNotice? = null,
) {

    /** [downloaded], largest first. This is the list to render. */
    val albums: List<DownloadedAlbum> = downloaded.sortedByDescending { it.sizeBytes }

    /**
     * True when there is nothing on the device.
     *
     * Distinct from [loading]: a screen that has not read the cache index yet is not a screen with an
     * empty list, and saying "nothing is downloaded" before the answer has arrived is the same
     * mistake REQUIREMENTS.md names about staleness - "Unknown is not evidence".
     */
    val isEmpty: Boolean get() = !loading && albums.isEmpty()

    /** What every download on this device adds up to. */
    val totalBytes: Long get() = albums.sumOf { it.sizeBytes }

    /**
     * `3 albums · 2.1 GB`, drawn beside the green **Device** badge that says which tier that is.
     *
     * It is derived from [albums] and nothing else, so it cannot disagree with the list under it.
     * That is not a theoretical property: `screenshots/downloads-removed-phone.png` used to show
     * "128 MB freed" under a header still reading "14 albums · 5.5 GB", which is exactly the failure
     * REQUIREMENTS.md "Storage, and why there is no budget" calls fatal - "a 'remove' that leaves the
     * usage figure unchanged is the one thing that would make this whole screen untrustworthy". The
     * figure was right and the render was a lie told by a fixture; the golden now removes the album
     * it says it removed.
     *
     * Spoken as a polite live region, so a screen-reader user who has just removed an album hears the
     * new total rather than having to go and find it. The loading and empty cases say nothing at all:
     * "0 albums" is a figure the user could act on and that is about to change, and an empty device
     * has a whole empty state of its own rather than a header counting to zero.
     */
    val summary: String
        get() = when {
            loading || albums.isEmpty() -> ""
            else -> SettingsFormat.plural(albums.size.toLong(), "album") + " · " +
                SettingsFormat.bytes(totalBytes)
        }

    /**
     * Whether any remove control should respond.
     *
     * One removal at a time, and nothing starts a second while an undo is putting an album back:
     * both talk to the same repository about the same files.
     */
    val canRemove: Boolean get() = removing == null && !undoing

    /** What [album]'s row is doing: at rest, or being deleted right now. */
    fun rowState(album: DownloadedAlbum): DownloadedRowState = when (album.releaseGroupMbid) {
        removing -> DownloadedRowState.Removing
        else -> DownloadedRowState.Idle
    }
}

/**
 * What one row is doing.
 *
 * Two members rather than a `Boolean` pair, because the states are exclusive and a row that is both
 * at rest and in flight is not a state the screen can draw. An undo has no member: the album it puts
 * back is not in the list while the pin is being written, so there is no row to mark.
 */
enum class DownloadedRowState {
    /** Nothing is happening to this album. Its control reads **Remove**. */
    Idle,

    /** This is the album being deleted. Its control reads **Removing** and does not respond. */
    Removing,
}

/**
 * The sentence a finished removal leaves behind, and the one way back from it.
 *
 * ## Why removal is undoable rather than confirmed
 *
 * The audit offered either, and this is the one that keeps the screen usable. `DestructiveSettingsAction`
 * records why per-album removal is a single tap - "putting a confirmation in front of the only lever
 * a user has on a full device would make the screen tiring to use for its main purpose" - and a user
 * reclaiming space removes several albums in a row, so a prompt before each one doubles the taps on
 * the screen's whole reason to exist. What was actually missing was not a question before the act
 * but a way back after it: the removal is irreversible on this device, and the screen's own copy says
 * so.
 *
 * [undo] is therefore the album, whole, so that one tap re-pins it. That is honest rather than
 * magical - the bytes are gone and come back over the network, which is what
 * `PinRepository.pinAlbum` does and what the Undo's spoken label says - but it is the difference
 * between a mistake costing a tap and a mistake costing a hunt through the library.
 *
 * The rejected alternative was a soft delete: hold the files for a minute and let an undo cancel the
 * deletion. It fails this screen outright. The user is here because the device is full, so bytes
 * retained for a grace period are bytes the screen has just promised to give back and has not, and
 * REQUIREMENTS.md "Storage, and why there is no budget" is explicit that the figure has to be real.
 *
 * @property headline what happened, naming the album and nothing else. Split from [detail] because
 *   the one sentence it used to be - `Removed Dummy: 11 tracks, 128 MB freed.` - puts a colon after a
 *   title that may itself contain punctuation, and reads as a label rather than as a report.
 * @property detail the figures, or why the removal did not happen.
 * @property undo the album to put back, or null when there is nothing to put back: a removal that
 *   failed left the album alone, and an undo that has just succeeded has nothing left to offer.
 * @property destructive whether this notice reports data that has left the device, which is the one
 *   thing on this screen drawn in `NeedlerColors.destructive`.
 */
data class DownloadsNotice(
    val headline: String,
    val detail: String,
    val undo: DownloadedAlbum? = null,
    val destructive: Boolean = false,
) {
    /** The whole notice as one sentence, for a screen reader that hears it as a single region. */
    val spoken: String get() = headline + ". " + detail
}

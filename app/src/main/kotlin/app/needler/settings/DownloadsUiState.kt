package app.needler.settings

import app.needler.core.domain.model.DownloadedAlbum

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
 * @property downloaded the albums as the repository handed them over. Render [albums] instead, which
 *   is this list in the order the screen promises.
 * @property working true while a removal is in flight, which stops every remove control responding.
 *   One at a time: the removals delete files and report what they freed, and two overlapping ones
 *   would report each other's figures.
 * @property notice what the last removal freed, or why it could not. Replaced by the next one.
 */
data class DownloadsUiState(
    val loading: Boolean = true,
    val downloaded: List<DownloadedAlbum> = emptyList(),
    val working: Boolean = false,
    val notice: String? = null,
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
     * `3 albums · 2.1 GB on this device`, or the empty sentence.
     *
     * Drawn at the top of the screen and spoken as a polite live region, so a screen-reader user who
     * has just removed an album hears the new total rather than having to go and find it. The
     * loading case says nothing at all rather than "0 albums", which would be a figure the user
     * could act on and that is about to change.
     */
    val summary: String
        get() = when {
            loading -> ""
            albums.isEmpty() -> "Nothing is downloaded to this device."
            else -> SettingsFormat.plural(albums.size.toLong(), "album") + " · " +
                SettingsFormat.bytes(totalBytes) + " on this device"
        }

    /** Whether a remove control should respond. */
    val canRemove: Boolean get() = !working
}

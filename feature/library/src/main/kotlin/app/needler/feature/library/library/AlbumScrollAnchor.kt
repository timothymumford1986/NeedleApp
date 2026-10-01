package app.needler.feature.library.library

import app.needler.core.domain.model.Album

/**
 * Where the album list is, held as the **album** at the top of the viewport
 * rather than as a pixel offset, so the grid/list toggle can reopen on the
 * record the user was looking at.
 *
 * ## The defect this exists for
 *
 * On a 288-album library, scrolled to "RIOT!" by Paramore, tapping "Switch to
 * list view" landed on "(What's the Story) Morning Glory?" — the first album of
 * the whole sort. The toggle is a change of *presentation*, not of content:
 * REQUIREMENTS.md "Tablet layout" lists it beside the segmented tabs and the
 * sort control as part of the content pane's chrome, and REQUIREMENTS.md
 * "Library browse" fixes the ordering per shelf, which a view mode does not
 * touch. Nothing about the set of albums or their order changes when the
 * toggle is tapped, so nothing about the user's place in that order should
 * either — and the moment they are most likely to tap it is the moment they are
 * deepest into the list and the current density is not working for them, which
 * is exactly when being thrown back to the top costs most.
 *
 * ## Why the anchor is an album and not a scroll offset
 *
 * Rotation already keeps the exact offset, because the one lazy layout stays in
 * composition across it and its own saved state restores. The toggle is not
 * that: the grid is a `LazyVerticalGrid` and the list is a `LazyColumn`, two
 * different layouts with two different states, and the old one is disposed as
 * the new one is composed. There is nothing to restore, only something to
 * carry across.
 *
 * A **scroll offset is not that something**. Two columns of square artwork on a
 * phone is roughly a 190dp row; the list draws a 76dp row; a tablet draws four
 * columns. "4,180 pixels down" therefore describes a different album in each of
 * the three, and in the worst case — four columns to one, where the same albums
 * occupy four times the height — it describes an album that is nowhere near the
 * screen. The one quantity that survives the layout change is which record is
 * at the top, which is also the thing the user would name if asked where they
 * were. So the anchor is a release-group id, resolved to an index against
 * whatever list is being drawn at the moment it is needed.
 *
 * Identity rather than index for the same reason: an index is only a proxy for
 * an album while the list is unchanged, and a delta sync landing between the
 * two layouts would silently shift every index under it. The key cannot drift;
 * if the album is gone, [indexIn] says the top, which is honest.
 *
 * ## Alternatives rejected
 *
 * **Carry `firstVisibleItemScrollOffset` as well as the index.** This is what
 * `LazyGridState.Saver` and `LazyListState.Saver` do across a rotation, and it
 * is the obvious thing to reach for. It is wrong here for the reason above: the
 * offset is measured in the old layout's row height and spent in the new one's,
 * so it lands the user part-way into an unrelated row. Preserving a number that
 * means nothing on the other side is worse than preserving nothing, because it
 * looks deliberate.
 *
 * **Draw both densities from one `LazyVerticalGrid`, with `GridCells.Fixed(1)`
 * standing in for the list.** One layout, one state, position preserved by
 * Compose with no code here at all. Rejected on two counts. It still preserves
 * the offset rather than the item, so it lands mid-row exactly as above. And it
 * would couple the two presentations permanently: the grid's
 * `Arrangement.spacedBy` row gaps would apply to list rows that draw their own
 * dividers, and the list row would inherit the grid's cell measurement instead
 * of the pack's 76dp row. The toggle is meant to change how rows are drawn; a
 * fix that makes the two drawings share a measurement pass gives that up.
 *
 * ## Deliberately not snapshot state
 *
 * [albumKey] is a plain `var`, not a `mutableStateOf`. It is written once per
 * row the viewport passes and read once per layout, and nothing on screen
 * depends on it, so making it observable would recompose the whole screen while
 * the user scrolls for no visible benefit. REQUIREMENTS.md "Performance
 * budgets" asks for "no dropped frames on a 5,000-album grid".
 *
 * @param albumKey the album to open on, or null for the top. The constructor
 *   takes it so a screenshot test can render the library already scrolled; see
 *   [LibraryScreen].
 */
class AlbumScrollAnchor(albumKey: String? = null) {

    /**
     * The release-group id of the album last seen at the top of the viewport,
     * or null when that was the first album or there were none.
     */
    var albumKey: String? = albumKey
        private set

    /**
     * Remember the album at [firstVisibleItemIndex] of [albums].
     *
     * An index past the end of the list clears the anchor rather than throwing:
     * the index comes from a layout that is mid-scroll and a list that a sync
     * can shorten underneath it, and neither is worth a crash.
     */
    fun record(albums: List<Album>, firstVisibleItemIndex: Int) {
        albumKey = albums.getOrNull(firstVisibleItemIndex)?.releaseGroupMbid?.value
    }

    /**
     * Where the anchored album sits in [albums], for the layout about to draw
     * them.
     *
     * The top of the list when there is no anchor, and also when the anchored
     * album is no longer in the list — a pull, an un-pin or a sort change can
     * all take it out from under this. Guessing at a neighbour would be
     * pretending to know something; the top is at least a place the user can
     * recognise.
     */
    fun indexIn(albums: List<Album>): Int {
        val key: String = albumKey ?: return 0
        val index: Int = albums.indexOfFirst { album -> album.releaseGroupMbid.value == key }
        return if (index >= 0) index else 0
    }
}

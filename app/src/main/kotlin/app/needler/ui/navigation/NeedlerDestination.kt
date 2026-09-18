package app.needler.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerStrokeIcon

/**
 * The four top-level destinations, fixed by the design pack.
 *
 * Library, Search, Pulls and Settings appear in the phone bottom bar on screens
 * 02, 03, 06, 12, 13, 19 and 20, and in the tablet nav rail on 09, 10 and 11 -
 * same four, same order, same glyphs. They are one enum rather than two lists
 * because REQUIREMENTS.md "Tablet layout" requires one navigation model at two
 * widths, not two implementations.
 *
 * The glyph path data is the pack's own SVG `d` attribute, lifted from
 * `design/html/09-TabletLibrary.html`, so a nav icon here is the drawn shape
 * and not a lookalike from an icon set.
 *
 * @property route the navigation route. Stable, because it is what a deep link
 *   and a restored back stack are matched against.
 * @property label the visible label, and the basis of the spoken one.
 */
enum class NeedlerDestination(
    val route: String,
    val label: String,
    private val pathData: String,
) {
    /** Screens 02, 09, 13. Owned by `:feature:library`. */
    Library(
        route = "library",
        label = "Library",
        pathData = "M3 6h18v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM8 4v16M16 4v16",
    ),

    /** Screens 03, 10. Owned by `:feature:search`. */
    Search(
        route = "search",
        label = "Search",
        pathData = "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14M20 20l-3.5-3.5",
    ),

    /** Screen 06. Owned by `:feature:pulls`. Carries the count badge. */
    Pulls(
        route = "pulls",
        label = "Pulls",
        pathData = "M12 4v11M7 10l5 5 5-5M4 19h16",
    ),

    /** Screen 12. Owned by `:app`. */
    Settings(
        route = "settings",
        label = "Settings",
        pathData = "M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4" +
            "M5.6 18.4L7 17M17 7l1.4-1.4M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0",
    ),
    ;

    /**
     * Draws this destination's glyph in [tint].
     *
     * Decorative: the nav item merges its descendants and speaks its own label,
     * so the glyph carries no semantics of its own.
     */
    @Composable
    fun Icon(tint: Color) {
        NeedlerStrokeIcon(pathData = pathData, tint = tint, size = ICON_SIZE)
    }

    companion object {
        /** The pack draws every nav glyph at 24px. */
        private val ICON_SIZE: Dp = 24.dp

        /** Where a cold start lands once a server is configured. */
        val Start: NeedlerDestination = Library

        /** Matches a route from the back stack. Null for anything not top-level. */
        fun fromRoute(route: String?): NeedlerDestination? =
            entries.firstOrNull { it.route == route }
    }
}

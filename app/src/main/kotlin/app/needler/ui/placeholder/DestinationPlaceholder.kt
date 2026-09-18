package app.needler.ui.placeholder

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.needler.ui.navigation.NeedlerDestination

/**
 * The stand-in for one of the four destinations, and the single place that says
 * who owns each of them.
 *
 * Kept as its own composable rather than inlined into the navigation graph so
 * that the graph and the screenshot tests render exactly the same thing, and so
 * that replacing a destination is a one-line change in one file each time
 * rather than a hunt through two.
 */
@Composable
fun DestinationPlaceholder(
    destination: NeedlerDestination,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = destination.label,
        owner = destination.owningModule,
        note = destination.note,
        modifier = modifier,
    )
}

/** The Gradle path of the module that will replace this destination. */
private val NeedlerDestination.owningModule: String
    get() = when (this) {
        NeedlerDestination.Library -> ":feature:library"
        NeedlerDestination.Search -> ":feature:search"
        NeedlerDestination.Pulls -> ":feature:pulls"
        // Settings is not a feature module. REQUIREMENTS.md's module table puts
        // "Navigation, DI wiring, Connect and Settings" in :app, so this one
        // stays here - it is simply not built yet.
        NeedlerDestination.Settings -> ":app"
    }

/** One sentence on what the real screen shows, and which artboard draws it. */
private val NeedlerDestination.note: String
    get() = when (this) {
        NeedlerDestination.Library ->
            "Albums, artists and album detail, read from the Room mirror so browsing works " +
                "identically online and offline. Screens 02, 04, 09, 11 and 13."
        NeedlerDestination.Search ->
            "One field over both the owned library and the MusicBrainz catalogue, merged on " +
                "release-group MBID. Screens 03 and 10."
        NeedlerDestination.Pulls ->
            "Active, completed and failed acquisitions, plus this user's own pending " +
                "approvals. The count on this tab comes from here. Screen 06."
        NeedlerDestination.Settings ->
            "Playback, storage, pulling, notifications and the legal disclaimer. Screen 12, " +
                "with the corrections REQUIREMENTS.md makes to it - no storage budget, and " +
                "Wi-Fi-only moved to Storage."
    }

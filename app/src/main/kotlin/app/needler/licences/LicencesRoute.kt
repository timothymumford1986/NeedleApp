package app.needler.licences

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The route the navigation host registers for the Licences screen.
 *
 * ## It holds nothing, and that is not an oversight
 *
 * Every other `*Route` in this project exists to own a `ViewModel` and keep it out of its screen —
 * `ConnectRoute`, `SettingsRoute`, `LibraryRoute`. This one owns nothing, because there is nothing to
 * own: every word the Licences screen draws is a compile-time constant in [LicenceCatalogue] and
 * [NeedlerLegal], nothing it shows can be loading, empty, stale or offline, and it performs no action.
 * A `ViewModel` here would be a class whose entire body was `val state = LicenceCatalogue`.
 *
 * It exists anyway, rather than having `NeedlerNavHost` call [LicencesScreen] directly, for one
 * reason: every destination in that file is a `*Route`, and the one destination that is not would be
 * the one a future contributor has to stop and think about when they add state to it. The route is
 * where state goes when it arrives.
 *
 * ## Registering it
 *
 * `NeedlerNavHost` should add a `composable("licences")` inside the same nested graph as `settings`,
 * and pass `onOpenLicences = { navController.navigate("licences") }` to `SettingsRoute` — which
 * already takes that parameter and currently defaults it to null, which is why the "Licences and full
 * terms" button does not render today.
 *
 * @param onBack pops back to Settings. `NeedlerNavHost` wires it to `navController::popBackStack`, as
 *   it does for the equaliser and crossfade screens.
 * @param widthSizeClass the window width, as every screen in this app takes it. Used only to cap and
 *   centre the content; there is no second layout.
 */
@Composable
fun LicencesRoute(
    onBack: () -> Unit,
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
) {
    LicencesScreen(
        onBack = onBack,
        widthSizeClass = widthSizeClass,
        modifier = modifier,
    )
}

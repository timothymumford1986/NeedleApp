package app.needler.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.needler.core.design.component.NeedlerBottomNavBar
import app.needler.core.design.component.NeedlerNavItem
import app.needler.core.design.component.NeedlerNavigationRail
import app.needler.core.design.theme.NeedlerTheme

/**
 * The app's chrome: navigation on one side of the content, and on a tablet the
 * permanent player sidebar on the other.
 *
 * ## One model, two widths
 *
 * REQUIREMENTS.md "Tablet layout" is explicit that this is "one navigation
 * model at two widths, driven by `WindowSizeClass`" with "nothing tablet-only,
 * so no feature needs building twice". That is why this is a single composable
 * taking [widthSizeClass] rather than a phone scaffold and a tablet scaffold:
 * the destinations, the selection, the badge and the content slot are shared,
 * and only the arrangement differs.
 *
 * | Width | Navigation | Player |
 * | --- | --- | --- |
 * | Compact, Medium | bottom bar (screens 02, 03, 06, 12) | mini-player, then a separate crate screen |
 * | Expanded | 96dp nav rail (screen 09) | permanent 400dp right-hand sidebar (screen 09) |
 *
 * Medium is grouped with Compact deliberately. The pack draws exactly two
 * layouts, and a 700dp-wide foldable cannot hold a 400dp sidebar *and* a
 * four-column grid; it gets the phone arrangement with more room in it, which
 * is what the bottom bar was already designed to do.
 *
 * ## Insets
 *
 * The activity draws edge-to-edge, so the content slot consumes the top and
 * horizontal safe-drawing insets. The bottom bar deliberately does **not**
 * consume the navigation-bar inset: [NeedlerBottomNavBar] already carries the
 * pack's own 24dp bottom inset for the gesture area, and adding the system one
 * on top of it would double the gap.
 *
 * [miniPlayer] adds no insets of its own for the same reason: it is stacked
 * above the bar, so the bar is what stands between it and the gesture area, and
 * the pack's 8dp gap between the two is padding the bar's own card carries.
 *
 * ## There is deliberately no offline marker here
 *
 * A phone in aeroplane mode drew the library exactly as it draws it online, and
 * this was the obvious place to answer that: one chip in the chrome, visible on
 * every tab. It is not here, and the reason is what the device session actually
 * showed. The system status bar was already saying "no network" and the user
 * read right past it, because the fact on its own is not what anybody needs. The
 * questions are "will this album play", "will this pull be sent", "is this list
 * all there is" - and the answers differ per screen, per row and per action. A
 * marker in the chrome can answer none of them, and a second copy of a signal
 * the platform already shows is not an answer either.
 *
 * So each surface states its own consequence where that consequence is:
 * `:feature:search` captions the catalogue block with the source its rows really
 * came from, says under the field that the catalogue lane was not searched, and
 * labels the pull sheet's confirm button "Queue the pull"; a row that is not on
 * the device already says so in its badge. REQUIREMENTS.md "Failure handling"
 * asks for exactly that - "Offline is a first-class state, not an error" - and a
 * banner over a library that is working perfectly would be an error's shape
 * around a state that is not one.
 *
 * The rejected alternative is recorded here rather than in a parameter, because
 * a parameter is how it would come back: an `offline: Boolean = false` on this
 * function would need one line in `NeedlerNavHost`'s call to be anything at all,
 * and defaulted to false it would ship as chrome that renders the online state
 * for ever. This file has no repository access by design - everything it draws
 * arrives as a parameter or a slot - and `NavigationScreenshotTest` renders it
 * with no Hilt graph at all, so a connectivity read taken inside it would be a
 * read that no test can see fail.
 *
 * @param pullsBadgeCount the count on the Pulls item. REQUIREMENTS.md calls
 *   this "the reliable channel" for pull state - notifications are best-effort,
 *   this badge is not - so it is plumbed through the scaffold rather than being
 *   drawn from inside one screen. It is a parameter and not a repository read
 *   because `:feature:pulls` owns the source; until that lands the host passes
 *   the real value it has, which is zero.
 * @param updateBanner the "a new version is available" bar, drawn above the
 *   mini-player so the two never argue about which is the bottom of the
 *   content. Like [miniPlayer] it composes to nothing almost always - there
 *   is no update almost always - so it costs a slot and no height.
 * @param miniPlayer the phone's collapsed player, drawn between the content and
 *   the bottom bar. A parameter for the same reason [sidebar] is one: the
 *   scaffold is chrome, it does not know about playback, and the real bar is
 *   `:feature:player`'s `MiniPlayerRoute` with a Hilt view model behind it -
 *   which the host supplies and the screenshot tests, which have no Hilt graph,
 *   leave at the default. That default draws nothing, which is also what the
 *   real bar does with an empty crate: the bar is absent rather than empty, so
 *   it never steals 72dp from a list to say nothing.
 * @param sidebar the tablet's permanent player, drawn to the right of the
 *   content at expanded width. It is `:feature:player`'s `PlayerSidebarRoute`,
 *   supplied by the host for the same reason [miniPlayer] is: the scaffold is
 *   chrome and knows nothing about playback.
 *
 *   It has **no default**, which is the one place the two player slots differ.
 *   An absent [miniPlayer] is a state the real bar also produces - it draws
 *   nothing with an empty crate - so `{}` is honest there. An absent sidebar is
 *   not: REQUIREMENTS.md "Tablet layout" makes it permanent, and at expanded
 *   width it is the *only* player surface, so a window without it has no
 *   transport, no crate and no route to either. A default here would let a
 *   caller ship exactly that and still compile, which is what the placeholder
 *   default this parameter used to carry did - it drew "Nothing playing" over
 *   real playback on every phone turned to landscape. The compiler now asks the
 *   question instead.
 */
@Composable
fun NeedlerNavigationScaffold(
    widthSizeClass: WindowWidthSizeClass,
    selected: NeedlerDestination,
    onSelect: (NeedlerDestination) -> Unit,
    modifier: Modifier = Modifier,
    pullsBadgeCount: Int = 0,
    updateBanner: @Composable () -> Unit = {},
    miniPlayer: @Composable () -> Unit = {},
    sidebar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val items = NeedlerDestination.entries.map { destination ->
        NeedlerNavItem(
            label = destination.label,
            selected = destination == selected,
            onClick = { onSelect(destination) },
            badgeCount = if (destination == NeedlerDestination.Pulls) pullsBadgeCount else 0,
            icon = { tint -> destination.Icon(tint) },
        )
    }

    val root = modifier
        .fillMaxSize()
        .background(NeedlerTheme.colors.canvas)

    if (widthSizeClass == WindowWidthSizeClass.Expanded) {
        Row(modifier = root) {
            NavChrome { NeedlerNavigationRail(items = items) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                    ),
            ) {
                content()
            }
            // Outside the weighted Box, so the 400dp it takes is taken from the
            // content pane rather than overlaid on it, and drawing its own
            // hairline and surface: the panel owns its full width, as screen 09
            // draws it.
            sidebar()
        }
    } else {
        Column(modifier = root) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        ),
                    )
                    // The bottom safe area belongs to the chrome below, and this
                    // line is what tells the content so.
                    //
                    // Window insets are measured from the *window*, not from the
                    // composable reading them. This Box is weighted, so it ends
                    // where the update banner begins - several hundred pixels
                    // above the window's bottom edge - and yet a descendant
                    // calling safeDrawingPadding() still saw the full
                    // navigation-bar and IME inset and padded itself by it. The
                    // region was therefore reserved twice: once by the banner,
                    // mini-player and nav bar that physically occupy it, and
                    // again inside the content.
                    //
                    // The visible cost was a dead band above the keyboard on
                    // Search *and* a viewport short by the same amount, so the
                    // last row rendered clipped through its own button and could
                    // not be reached. Nine other destinations call
                    // safeDrawingPadding() inside this slot and carried the same
                    // band; it was only noticed where a keyboard made it large.
                    //
                    // consumeWindowInsets, not windowInsetsPadding: the padding
                    // is already applied by the chrome. This only records that
                    // fact for descendants, so the same safeDrawingPadding()
                    // call now contributes nothing at the bottom and stays
                    // correct on a screen composed outside this scaffold.
                    .consumeWindowInsets(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                    ),
            ) {
                content()
            }
            // Between the content and the bar, which is where screens 06 and 13
            // draw it, and outside the weighted Box so the bar is never
            // scrolled past or overdrawn by a destination.
            // Above the mini-player, so an update notice never sits between the
            // player and the bar it belongs to.
            updateBanner()
            miniPlayer()
            NavChrome { NeedlerBottomNavBar(items = items) }
        }
    }
}

/**
 * The bar and the rail, with their type scale capped.
 *
 * ## What this fixes
 *
 * **The Pulls badge covered the glyph it counts.** The count scales with the user's text setting -
 * `NeedlerCounterBadge` draws it in an 11sp style inside an 18dp minimum pill - while the nav glyph
 * next to it is a fixed 24dp drawing. At 200% the pill is wider than the glyph it hangs off, and
 * since it is aligned to the glyph's top-right corner and offset 10dp further right, a two-digit
 * count lands on top of the download arrow and runs into the item beside it. Observed on the device
 * on every screen with the bottom bar.
 *
 * ## Why a cap rather than the other two options
 *
 * Three were available. **Dropping to a dot past a threshold** loses the number, and REQUIREMENTS.md
 * calls this badge "the reliable channel" for pull state precisely because notifications are not -
 * so "some pulls" instead of "36" is the one thing it may not degrade to. **Moving it clear** means
 * reserving space for a pill whose size this module cannot measure; the badge is drawn inside
 * `NeedlerBottomNavBar` from a count, and there is no slot here to position it from.
 *
 * So the chrome is scaled instead. [NAV_TYPE_SCALE_CAP] is the ceiling, and it applies to the four
 * labels as well as the badge - which is the cost, stated plainly: at a 200% system setting the nav
 * labels render at 130% while every screen *inside* the chrome still scales to 200%. That is the
 * trade WCAG 1.4.4 permits for fixed chrome and does not permit for content, and this composable is
 * only ever chrome. The four labels are single words the user learns once; the badge is a number
 * they have to read.
 *
 * ## The two faults beside it that are not this module's
 *
 * Recorded here because this is where they were found, and both are fixed in `:core:design`:
 *
 *  * **The badge was `positive` green**, the hue REQUIREMENTS.md "Design system" reserves for *on
 *    this device*. A pull in flight is a record the server is still fetching and is by definition not
 *    on the device, and green and accent measure 1.01:1 against each other, so the two signal hues
 *    carry meaning only through where they are spent. The fill is chosen inside
 *    `NeedlerCounterBadge` and has no parameter, which is correct - a caller picking the hue is how a
 *    state colour starts meaning "whatever this screen wanted".
 *  * **The phone bar had no selection container**, while the rail passes `surfaceRaised` behind its
 *    selected item, so the phone marked the current tab by hue alone. `NeedlerBottomNavBar` passes
 *    the same container now and the two widths agree.
 */
@Composable
private fun NavChrome(content: @Composable () -> Unit) {
    val density: Density = LocalDensity.current
    val capped: Density = remember(density) {
        if (density.fontScale <= NAV_TYPE_SCALE_CAP) {
            density
        } else {
            Density(density.density, NAV_TYPE_SCALE_CAP)
        }
    }
    CompositionLocalProvider(LocalDensity provides capped, content = content)
}

/**
 * The largest text scale the navigation chrome follows.
 *
 * 1.3, which is where a two-digit badge still sits on the glyph's corner rather than over it: the
 * pill is 18dp at 1.0 and grows roughly with the scale, and the pack hangs it 10dp off a 24dp glyph,
 * so a pill past about 24dp has nowhere left to hang. 1.3 also keeps the 11sp labels above 14sp,
 * which is larger than they have ever been drawn.
 *
 * Deliberately not applied anywhere else. Every destination inside the chrome scales to 200% and must
 * keep doing so - REQUIREMENTS.md's accessibility bar is about the screens, and capping a screen to
 * make a bar fit would be solving the wrong half.
 */
private const val NAV_TYPE_SCALE_CAP: Float = 1.3f

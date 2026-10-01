package app.needler.licences

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerHairline
import app.needler.core.design.component.NeedlerSectionHeader
import app.needler.core.design.theme.NeedlerTheme
import app.needler.settings.SettingsSubScreenHeader

/**
 * The Licences screen: what Needler is licensed under, what is inside it, and the full terms.
 *
 * REQUIREMENTS.md "Legal and attribution" asks for three things and this screen is all three of them
 * in one scroll:
 *
 *  1. "A Licences screen must list every bundled dependency and its licence" — [LicenceCatalogue],
 *     grouped by where each dependency ends up so that "bundled" means something on screen rather
 *     than being a word in a document.
 *  2. "**Needler is licensed Apache-2.0** … The Licences screen must therefore show Apache-2.0 for
 *     Needler alongside each bundled dependency" — [LicenceCatalogue.needler] is the first row, above
 *     everything it depends on, and the Apache-2.0 text is carried in full further down.
 *  3. "The attribution line credits Dropped Needle, slskd, MusicBrainz, ListenBrainz and Cover Art
 *     Archive" and the disclaimer, which "is a requirement rather than decoration" —
 *     [NeedlerLegal.credits] and [NeedlerLegal.disclaimer], the same words Settings shows, from the
 *     same constants.
 *
 * ## Why there is no ViewModel
 *
 * Nothing here is state. Every word on this screen is a compile-time constant and none of it can
 * fail, be empty, be loading or be offline, so there is no [app.needler.settings.SettingsUiState]
 * equivalent and no route that holds one. [LicencesRoute] exists only to give the navigation host
 * something with the same shape as every other destination.
 *
 * That is also why this screen takes no callbacks other than [onBack]. It does nothing. It is read.
 *
 * ## Why the licence text scrolls sideways
 *
 * The Apache-2.0 text is a document with its own line breaks and its own indentation, and both are
 * part of it. Re-wrapping it to a phone's width would be rewriting a legal document to fit a screen.
 * So it is drawn in the pack's legal-text style inside a horizontal scroller: the block scrolls, the
 * page does not.
 *
 * ## The tablet
 *
 * Capped and centred at [TABLET_CONTENT_MAX_WIDTH], the same figure and the same reasoning as
 * `SettingsScreen`: the pack has no tablet artboard for this screen, and prose stretched across a
 * 1280dp pane is prose nobody finishes a line of.
 */
@Composable
fun LicencesScreen(
    onBack: () -> Unit,
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val typography = NeedlerTheme.typography
    val wide: Boolean = widthSizeClass != WindowWidthSizeClass.Compact
    val gutter: Dp = if (wide) spacing.tabletGutter else spacing.phoneGutterWide

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (wide) TABLET_CONTENT_MAX_WIDTH else Dp.Unspecified)
                .fillMaxSize(),
        ) {
            SettingsSubScreenHeader(title = TITLE, onBack = onBack)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = gutter,
                    end = gutter,
                    top = spacing.step2,
                    bottom = spacing.step16,
                ),
            ) {
                item(key = "intro") {
                    Text(
                        text = INTRO,
                        style = typography.caption,
                        color = colors.textMuted,
                        modifier = Modifier.padding(bottom = spacing.step4),
                    )
                }

                // Needler's own licence first, and on its own, because the requirement puts it
                // "alongside each bundled dependency" rather than among them: a reader asking what
                // licence this app is under should not have to find it in a list of thirty.
                item(key = "needler") {
                    Column(modifier = Modifier.padding(top = spacing.sectionGap)) {
                        NeedlerSectionHeader(title = "This app")
                        ComponentRow(component = LicenceCatalogue.needler)
                    }
                }

                LicenceCatalogue.byScope.forEach { (scope, inScope) ->
                    item(key = "scope-" + scope.name) {
                        Column(modifier = Modifier.padding(top = spacing.sectionGap)) {
                            NeedlerSectionHeader(
                                title = scope.label,
                                trailing = countLabel(inScope.size),
                            )
                            Text(
                                text = scope.explanation,
                                style = typography.caption,
                                color = colors.textMuted,
                                modifier = Modifier.padding(bottom = spacing.step2),
                            )
                        }
                    }
                    items(
                        items = inScope,
                        key = { component -> scope.name + "/" + component.name },
                    ) { component ->
                        ComponentRow(component = component)
                    }
                }

                item(key = "credits-header") {
                    Column(modifier = Modifier.padding(top = spacing.sectionGap)) {
                        NeedlerSectionHeader(title = "With thanks to")
                        Text(
                            text = CREDITS_EXPLANATION,
                            style = typography.caption,
                            color = colors.textMuted,
                            modifier = Modifier.padding(bottom = spacing.step2),
                        )
                    }
                }
                items(items = NeedlerLegal.credits, key = { name -> "credit/" + name }) { name ->
                    CreditRow(name = name)
                }

                item(key = "disclaimer") {
                    Column(
                        modifier = Modifier.padding(top = spacing.sectionGap),
                        verticalArrangement = Arrangement.spacedBy(spacing.step3),
                    ) {
                        NeedlerSectionHeader(title = "Terms of use")
                        NeedlerLegal.disclaimer.forEach { paragraph ->
                            Text(
                                text = paragraph,
                                style = typography.caption,
                                color = colors.textMuted,
                            )
                        }
                    }
                }

                LicenceCatalogue.licencesInUse.forEach { licence ->
                    item(key = "licence-" + licence.name) {
                        LicenceBlock(licence = licence)
                    }
                }
            }
        }
    }
}

/**
 * One dependency: what it is called, what it is licensed under, and why it is here.
 *
 * The licence identifier sits on its own line under the name rather than at the right-hand end of the
 * row, which is where `NeedlerSettingsRow` would have put it. Two reasons, and both are about text
 * scaling: `Apache-2.0` beside `AndroidX core, activity, lifecycle and navigation` leaves the name
 * two words a line at 200%, and REQUIREMENTS.md "Accessibility" requires this screen to survive that.
 * The row is not a `NeedlerSettingsRow` at all for the simpler reason that that component's whole
 * width is a tap target, and nothing here is tappable.
 *
 * TalkBack reads the whole row as one node — name, licence, note — because three separate stops for
 * one library in a list of thirty is a screen nobody listens to the end of.
 */
@Composable
private fun ComponentRow(component: LicencedComponent) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing
    val spoken: String = buildString {
        append(component.name)
        append(", licensed ")
        append(component.licence.spdxId)
        val note: String? = component.note
        if (note != null) {
            append(". ")
            append(note)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.step4)
                .semantics(mergeDescendants = true) { contentDescription = spoken },
            verticalArrangement = Arrangement.spacedBy(spacing.step1),
        ) {
            Text(text = component.name, style = typography.rowTitle, color = colors.textPrimary)
            Text(
                text = component.licence.spdxId,
                style = typography.meta,
                color = colors.textSecondary,
            )
            val note: String? = component.note
            if (note != null) {
                Text(text = note, style = typography.caption, color = colors.textMuted)
            }
        }
        NeedlerHairline()
    }
}

/** One credited service, with a line saying what it is for. */
@Composable
private fun CreditRow(name: String) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing
    val description: String? = NeedlerLegal.creditDescriptions[name]

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.step4)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (description == null) name else name + ". " + description
                },
            verticalArrangement = Arrangement.spacedBy(spacing.step1),
        ) {
            Text(text = name, style = typography.rowTitle, color = colors.textPrimary)
            if (description != null) {
                Text(text = description, style = typography.caption, color = colors.textMuted)
            }
        }
        NeedlerHairline()
    }
}

/**
 * One licence: its name, its SPDX identifier, where its canonical text lives, and the text itself
 * when this app carries a copy.
 *
 * Only Apache-2.0 carries its text, and [APACHE_2_0_TERMS] records why: it is the licence of Needler
 * and of all but one thing inside it, and section 4(a) of it requires a redistributor to hand
 * recipients a copy. The other four print their URL instead, which is the honest thing to do about a
 * document this app has no copy of — it names what is missing rather than implying the list is
 * complete.
 */
@Composable
private fun LicenceBlock(licence: Licence) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.sectionGap),
        verticalArrangement = Arrangement.spacedBy(spacing.step2),
    ) {
        NeedlerSectionHeader(title = licence.title, trailing = licence.spdxId)
        Text(text = licence.url, style = typography.caption, color = colors.textSecondary)

        val terms: String? = licence.terms
        if (terms == null) {
            Text(
                text = NO_TEXT_CARRIED,
                style = typography.caption,
                color = colors.textMuted,
            )
        } else {
            Text(
                text = terms,
                // `caption` is the pack's own legal-text style - 12sp/400 on a 17sp line - which is
                // what this is.
                style = typography.caption,
                color = colors.textMuted,
                // The document's own line breaks and indentation are part of it, so it scrolls
                // sideways inside its own box rather than being re-wrapped to the screen.
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = spacing.step2),
            )
        }
    }
}

/** `6 libraries`, or `1 library`, on the right of a scope heading. */
private fun countLabel(count: Int): String =
    count.toString() + if (count == 1) " library" else " libraries"

/** The screen's own title. */
private const val TITLE: String = "Licences"

/**
 * The widest the content gets on a tablet. The same figure as `SettingsScreen`, for the same reason.
 */
private val TABLET_CONTENT_MAX_WIDTH: Dp = 640.dp

private const val INTRO: String =
    "Everything Needler is built from, what it is licensed under, and the terms it is offered to " +
        "you under. Nothing on this screen needs a connection."

private const val CREDITS_EXPLANATION: String =
    "None of these is code inside Needler. They are the projects and the services that make it " +
        "possible, and all the heavy lifting is theirs."

private const val NO_TEXT_CARRIED: String =
    "Needler does not carry a copy of this licence. The address above is where its text lives."

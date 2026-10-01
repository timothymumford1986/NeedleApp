package app.needler.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerHairline
import app.needler.core.design.component.NeedlerSectionHeader
import app.needler.core.design.component.NeedlerTextButton
import app.needler.core.design.theme.NeedlerTheme
import app.needler.settings.SettingsSubScreenHeader

/**
 * Every callback the Diagnostics screen needs, in one value.
 *
 * Grouped for the reason `SettingsCallbacks` is, and with the same rule: **nothing here has a
 * default.** A `() -> Unit` default of `{}` is the inert tap target this project refuses to create —
 * it compiles, it renders, and there is no way to tell from the screen which one was forgotten.
 */
data class DiagnosticsCallbacks(
    val onShare: () -> Unit,
    val onRefresh: () -> Unit,
    val onClear: () -> Unit,
)

/**
 * The Diagnostics screen: the last session's log, with a way to share it and a way to empty it.
 *
 * REQUIREMENTS.md "Observability" requires it and the design pack has no artboard for it, so every
 * layout decision here is an inference. The ones worth recording:
 *
 * ## The statement about where the log goes is on the screen, not in a help page
 *
 * REQUIREMENTS.md: the log "must never leave the device automatically". That is a promise to the user,
 * and a promise the user cannot see is not worth making — a screen full of their server's addresses
 * that says nothing about what happens to them invites exactly the wrong assumption. So [PRIVACY] is
 * drawn above the actions, before the reader has decided whether to tap share, and the same sentence
 * is written into the exported file itself.
 *
 * ## Severity is a word, not a colour
 *
 * A warning or an error line carries a `WARN` or `ERROR` badge and the primary text colour; everything
 * else is muted. No line is ever drawn in the destructive colour. `#e8908a` was added to the palette
 * for one purpose — REQUIREMENTS.md "Design pack discrepancies": permanent data loss styled
 * differently from the primary action — and a failed HTTP request is not data loss. Spending the
 * palette's only alarm colour on a log line would make the colour mean nothing by the time it is
 * needed. The badge is also the accessible answer: the information does not depend on colour at all.
 *
 * ## Why a refresh control rather than a live list
 *
 * [DiagnosticsUiState] records this: the buffer is written from OkHttp's threads while the user reads
 * it, and a list that re-measured on every request in flight would be unreadable under a scrolling
 * thumb. A log is a record of what happened, so it is a snapshot with a way to take another.
 *
 * ## Clearing is one tap
 *
 * Unlike "Remove all from device", which is two. Nothing is lost by clearing: the lines describe the
 * last few minutes, using the app re-creates them, and nothing on the device or the server depends on
 * them. This is the same reasoning REQUIREMENTS.md gives for "Clear cached music" being safe behind a
 * single tap.
 *
 * Stateless: handed a [DiagnosticsUiState] and a [DiagnosticsCallbacks], so every state it can be in
 * can be rendered from a literal. [DiagnosticsRoute] is the stateful half.
 */
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    callbacks: DiagnosticsCallbacks,
    onBack: () -> Unit,
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing
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
                item(key = "summary") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.step3)) {
                        Text(
                            text = state.summary,
                            style = typography.caption,
                            color = colors.textSecondary,
                            // Polite, so a screen-reader user who has just cleared the log hears that
                            // it is now empty rather than having to go looking for the count.
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        Text(text = PRIVACY, style = typography.caption, color = colors.textMuted)
                    }
                }

                item(key = "actions") {
                    Column(modifier = Modifier.padding(top = spacing.step4)) {
                        NeedlerTextButton(
                            text = "Share as a file",
                            onClick = callbacks.onShare,
                            enabled = state.hasContent && !state.exporting,
                            contentDescription = "Share the diagnostics log as a file",
                        )
                        NeedlerTextButton(
                            text = "Refresh",
                            onClick = callbacks.onRefresh,
                            contentDescription = "Refresh the diagnostics log",
                        )
                        NeedlerTextButton(
                            text = "Clear",
                            onClick = callbacks.onClear,
                            enabled = state.hasContent,
                            contentDescription = "Clear the diagnostics log",
                        )
                        val notice: String? = state.notice
                        if (notice != null) {
                            Text(
                                text = notice,
                                style = typography.caption,
                                color = colors.textSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = spacing.step4)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }

                if (state.isEmpty) {
                    item(key = "empty") {
                        Text(
                            text = EMPTY,
                            style = typography.caption,
                            color = colors.textMuted,
                            modifier = Modifier.padding(top = spacing.sectionGap),
                        )
                    }
                } else {
                    item(key = "log-header") {
                        Column(modifier = Modifier.padding(top = spacing.sectionGap)) {
                            NeedlerSectionHeader(title = "This session")
                        }
                    }
                    items(items = state.lines, key = { line -> line.key }) { line ->
                        LogLineRow(line = line)
                    }
                }
            }
        }
    }
}

/**
 * One log line: the time, what wrote it, and what it says.
 *
 * The clock uses the pack's `timecode` style, which is the one place in this app where tabular
 * numerals earn their keep on something other than a duration: four hundred timestamps in a column
 * that shifts by a digit's width is four hundred rows that will not line up.
 *
 * The message wraps rather than scrolling or truncating. A redacted stream URL is long, and on a log
 * the interesting part is usually at the end — the status code, the byte count, the error — so
 * ellipsising it would hide exactly the part the reader came for. `TalkBack` gets the row as one node
 * with the message first and the clock last; see [DiagnosticsLine.spoken].
 */
@Composable
private fun LogLineRow(line: DiagnosticsLine) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.step3)
                .semantics(mergeDescendants = true) { contentDescription = line.spoken },
            horizontalArrangement = Arrangement.spacedBy(spacing.step4),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = line.clock,
                style = typography.timecode,
                color = colors.textMuted,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.step1),
            ) {
                Text(
                    text = prefixOf(line),
                    style = typography.micro,
                    color = if (line.emphasised) colors.textSecondary else colors.textMuted,
                )
                Text(
                    text = line.message,
                    style = typography.caption,
                    color = if (line.emphasised) colors.textPrimary else colors.textSecondary,
                )
            }
        }
        NeedlerHairline()
    }
}

/** `NET`, or `NET · WARN`. The badge is a word, not a colour; see the screen's documentation. */
private fun prefixOf(line: DiagnosticsLine): String {
    val badge: String? = line.badge
    return if (badge == null) line.source else line.source + " · " + badge
}

/** The screen's own title. */
private const val TITLE: String = "Diagnostics"

/** The same figure and reasoning as `SettingsScreen` and the Licences screen. */
private val TABLET_CONTENT_MAX_WIDTH: Dp = 640.dp

/**
 * The promise REQUIREMENTS.md makes, said where the person it is about can read it.
 *
 * Both halves matter. "Nothing here has been sent anywhere" is the requirement's "never leave the
 * device automatically"; "credentials are removed before a line is stored" is REQUIREMENTS.md
 * "Security" rule 1, and it is the sentence that makes it reasonable to attach this file to a public
 * bug report at all.
 */
private const val PRIVACY: String =
    "Nothing here has been sent anywhere, and nothing ever will be unless you share it. Your " +
        "password and your sign-in token are removed before a line is stored, so what you see is " +
        "what a bug report would contain."

private const val EMPTY: String =
    "Nothing yet. Use the app — browse your library, play something, sync — and come back."

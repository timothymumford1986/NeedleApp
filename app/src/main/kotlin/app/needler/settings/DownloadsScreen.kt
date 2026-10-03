package app.needler.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerHairline
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathClose
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.DownloadedAlbum

/**
 * The albums-on-this-device screen: everything on this device, largest first, each one removable.
 *
 * The design pack has no artboard for it - screen 12 drew this list inline - so every layout decision
 * here is an inference, framed with [SettingsSubScreenHeader] so it reads as a sibling of Licences,
 * Diagnostics, Crossfade and the equaliser, which is what it is.
 *
 * ## What moved, and what did not
 *
 * The rows are the same rows, in the same order, with the same per-album removal and the same spoken
 * labels. Settings still draws the largest few in place - see `StorageSectionState.INLINE_DOWNLOADED_ALBUMS` - and sends
 * the overflow here, so this screen is reached by a user whose library has outgrown that section
 * rather than by everyone. Nothing is capped or hidden on either side of that split:
 * REQUIREMENTS.md "Storage, and why there is no budget" leaves no storage limit in the product, which
 * makes this list the user's only lever on a full device, and [DownloadsUiState] records the
 * alternatives weighed and why each lost.
 *
 * ## The notice is on this screen, not on Settings
 *
 * A removal reports what it freed, and that sentence has to be where the person who tapped remove is
 * looking. Inside Settings the notice is drawn under "Clear cached music", which for a row near the
 * bottom of a long list is well above the screen - so the one piece of feedback REQUIREMENTS.md
 * insists on ("a 'remove' that leaves the usage figure unchanged is the one thing that would make
 * this whole screen untrustworthy") was easy to miss. Here it is directly under the summary, and both
 * are polite live regions.
 *
 * ## Accessibility
 *
 * REQUIREMENTS.md "Accessibility": "Every control carries a content description and transport
 * controls are at least 48 dp." Each row is one merged node naming the album, the artist and what it
 * occupies, so TalkBack reads it once rather than as three fragments; the remove control is a
 * separate target whose description names the album and the bytes it would free, because "remove" on
 * its own in a list of forty identical buttons says nothing. The glyph is 36dp and
 * `NeedlerIconButton` floors its touch target at 48dp.
 *
 * Stateless, so every state it can be in - a full list, an empty device, a removal in flight, a
 * removal that failed - renders from a literal [DownloadsUiState] with nothing behind it.
 * [DownloadsRoute] is the stateful half.
 *
 * @param onRemove removes one album and deletes its bytes there and then. No confirmation, which is
 *   the behaviour this list already had inside Settings; the figure it reports back is the
 *   confirmation.
 * @param onBack pops back to Settings.
 */
@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    onRemove: (DownloadedAlbum) -> Unit,
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
            SettingsSubScreenHeader(title = DOWNLOADS_TITLE, onBack = onBack)

            LazyColumn(
                // No verticalArrangement spacing, for the reason SettingsScreen gives: each row
                // draws its own 1dp hairline underneath itself, and a gap would break every hairline
                // away from the row it belongs to.
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
                        val summary: String = state.summary
                        if (summary.isNotEmpty()) {
                            Text(
                                text = summary,
                                style = typography.caption,
                                color = colors.textSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        Text(
                            text = EXPLAINER,
                            style = typography.caption,
                            color = colors.textMuted,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val notice: String? = state.notice
                        if (notice != null) {
                            Text(
                                text = notice,
                                style = typography.caption,
                                color = colors.textSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = spacing.sectionGap),
                        )
                    }
                } else {
                    items(
                        items = state.albums,
                        key = { album -> album.releaseGroupMbid.value },
                    ) { album ->
                        DownloadedAlbumRow(
                            album = album,
                            enabled = state.canRemove,
                            onRemove = { onRemove(album) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One downloaded album, with what it occupies and a control that removes it.
 *
 * Not a `NeedlerSettingsRow`: that row's whole width is the tap target, and here the tap target
 * deletes files. The size has to be visible beside the title rather than only in the removal's
 * report, because REQUIREMENTS.md "Storage, and why there is no budget" requires that "a size shown
 * against an album is the bytes actually on disk, not what the server says the album weighs" - this
 * list is how a user decides which album to give up.
 *
 * `internal` rather than private to this file because [SettingsScreen] still draws these rows inline
 * when no host has registered this screen's destination. See `SettingsCallbacks.onOpenDownloads`.
 */
@Composable
internal fun DownloadedAlbumRow(
    album: DownloadedAlbum,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val sizeLabel: String = SettingsFormat.bytes(album.sizeBytes)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = NeedlerTheme.sizes.listRowMinHeight)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    // Merged so TalkBack reads the album once, as a whole, and then finds the
                    // remove button as a separate target rather than three fragments and a button.
                    .semantics(mergeDescendants = true) {
                        contentDescription =
                            album.title + ", " + album.artistName + ", " + sizeLabel + " on this device"
                    },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = album.title,
                    style = typography.rowTitle,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.artistName,
                    style = typography.meta,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(text = sizeLabel, style = typography.bodySmall, color = colors.textSecondary)
            NeedlerIconButton(
                contentDescription = "Remove " + album.title + " from this device, freeing " + sizeLabel,
                onClick = onRemove,
                enabled = enabled,
                visualSize = 36.dp,
            ) {
                NeedlerStrokeIcon(
                    pathData = PathClose,
                    tint = if (enabled) colors.destructive else colors.textMuted,
                    size = 18.dp,
                )
            }
        }
        NeedlerHairline()
    }
}

/** The screen's own title, which is also the label of the Settings row that opens it. */
private const val DOWNLOADS_TITLE: String = "Albums on this device"

/**
 * Why the list is ordered the way it is, and why nothing ever leaves it on its own.
 *
 * Two facts from REQUIREMENTS.md "Storage, and why there is no budget", said once, where the person
 * acting on them is standing: "Downloaded albums have no limit at all" and nothing evicts one, so the
 * only thing that frees these bytes is a tap here; and the order is by size because the question the
 * screen answers is "what is actually taking up the room".
 */
private const val EXPLAINER: String =
    "Largest first. Nothing here is ever removed automatically, however full the device gets, so " +
        "removing an album is the only thing that frees the room it is using."

/**
 * What the screen says with nothing on the device.
 *
 * It names both ways music gets here, because a blank screen with no explanation reads as a fault
 * rather than as an empty state - and because the second way is a switch on the screen the reader
 * just came from. The control is called "Pull to device" on an album, which is what it has to be
 * called here too.
 */
private const val EMPTY: String =
    "Nothing is on this device yet. \"Pull to device\" on an album downloads it, and \"Keep " +
        "pulled albums on the device\" in Settings downloads anything this device pulls."

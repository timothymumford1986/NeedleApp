package app.needler.widget.recent

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
// No import for defaultWeight(): Glance declares it as a member of RowScope and ColumnScope, the
// way Compose declares weight(), so it resolves from the Row's own receiver and importing it is an
// unresolved reference rather than a tidy-up.
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumListKind
import app.needler.core.domain.repository.LibraryRepository
import app.needler.widget.R
import app.needler.widget.internal.WidgetArtwork
import app.needler.widget.internal.WidgetDependencies
import app.needler.widget.internal.WidgetDimensions
import app.needler.widget.internal.WidgetLaunch
import app.needler.widget.internal.WidgetSquareDimensions
import app.needler.widget.internal.WidgetText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * The recently added widget: the left-hand square card on `design/html/15-Widget.html` and
 * `design/html/18-TabletWidget.html`.
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Widgets" asks for "the newest album, tappable straight
 * into it", and the pack draws that as a cover, an eyebrow, the album, the artist and a Play disc.
 *
 * ## Why this one reads a repository and the now-playing card does not
 *
 * REQUIREMENTS.md "Widgets" allows the widgets two ways in - "the domain repositories and the
 * `PlaybackController`, never through `:core:data`" - and which of the two a card uses is decided by
 * what the card is *about*. The now-playing card is about the session, so it is a client of the
 * session. This card is about the library, so it is a client of [LibraryRepository], and the mirror is
 * what makes that safe on a home screen: REQUIREMENTS.md "Library browse" has every library query
 * served from local Room, so "the newest album" is a local read that cannot fail, cannot block and
 * behaves identically with the server switched off. Nothing here calls a `refresh`. A widget must not
 * decide to go to the network; sync does that, on the schedule REQUIREMENTS.md "Sync" sets out.
 *
 * ## How state reaches the launcher, and how it goes stale
 *
 * The mechanics are the now-playing card's - see `NowPlayingWidget`, "How state reaches the launcher",
 * for the whole of it - but the staleness is a different problem with a different answer.
 *
 * While this process is alive the Room query is collected inside the Glance session, so an album
 * arriving from sync redraws the card with no prompting. When the process is *not* alive the launcher
 * keeps the last `RemoteViews`, and unlike the now-playing card this one has no press for a user to
 * make that would correct it: the card is a link, and tapping it leaves for the app. So a newest album
 * that changed while Needler was dead stays wrong until something wakes the process.
 *
 * That is what [app.needler.widget.NeedlerWidgets.refreshRecentlyAdded] is for, and the signal is a
 * finished library sync rather than a timer: `android:updatePeriodMillis` is 0 here for the same
 * reason it is 0 everywhere in this module - the platform clamps it to thirty minutes and wakes the
 * device to honour it, which is a battery cost for a card whose content changes when an album arrives
 * and at no other time. The call site is deliberately not here; this module is a leaf.
 *
 * ## Only one album, and only the newest
 *
 * `AlbumListKind.NEWEST` with a limit of one, which is what REQUIREMENTS.md "Widgets" asks for - "the
 * newest album" - and what both artboards draw. Fetching ten and using the head would be nine bitmaps
 * decoded for nothing, and a `RemoteViews` has about a megabyte of Binder transaction to spend, shared
 * with every other widget updating at that moment (see `WidgetArtwork`).
 *
 * `design/html/18-TabletWidget.html` does draw a strip of four covers, but it is labelled "Jump back
 * in", it sits inside the wide now-playing card, and it is a recently *played* list rather than a
 * recently added one. It is not a variant of this card and is deliberately not built here: nothing in
 * REQUIREMENTS.md "Widgets" names it, and it would be a fourth widget with a fourth receiver.
 */
internal class RecentlyAddedWidget : GlanceAppWidget() {

    /**
     * [SizeMode.Exact], as the now-playing card uses.
     *
     * `Responsive` would mean declaring the handful of sizes this card is allowed to be, and it has
     * no distinct layouts to attach to them - it has one square composition and two thresholds in
     * [WidgetSquareDimensions] for what it sheds when a user resizes it below what the design assumes.
     * `Exact` also means a launcher on a grid the design never anticipated still gets a card that fits.
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val library: LibraryRepository = WidgetDependencies.libraryRepository(context)
        val artwork = WidgetArtwork(context)
        val coverPx: Int = WidgetArtwork.pixels(context, WidgetSquareDimensions.artwork.value)
        val cornerPx: Float =
            WidgetDimensions.artworkCorner.value * context.resources.displayMetrics.density
        val models: Flow<RecentlyAddedModel> = recentlyAddedModels(library, artwork, coverPx, cornerPx)
        val openLibrary: Action = WidgetLaunch.openLibrary(context)

        provideContent {
            val model: RecentlyAddedModel by models.collectAsState(initial = RecentlyAddedModel.Empty)
            // Resolved in the composition rather than beside openLibrary, because the album it opens
            // is not known until the mirror has answered and it changes when a newer album arrives.
            val open: Action = if (model.hasAlbum) {
                WidgetLaunch.openAlbum(context, model.releaseGroupMbid)
            } else {
                openLibrary
            }
            RecentlyAddedCard(model = model, open = open)
        }
    }

    /**
     * The newest album, with its cover already fetched and decoded.
     *
     * `distinctUntilChanged` before the fetch is the part that matters. Room re-emits a query when any
     * table it touches is written, and a library sync writes plenty that has nothing to do with which
     * album is newest, so without it a sync would re-fetch and re-decode the same cover several times
     * and push an identical `RemoteViews` after each one.
     *
     * `flatMapLatest` after it means a newer album cancels the previous album's in-flight artwork
     * request, rather than letting a slow cover for the album before last arrive and overwrite the
     * current one. The fetch sits in the transform rather than in the composition because
     * `provideContent` runs on Glance's own recomposition, and a suspending network call inside it is
     * how a card ends up blank in the launcher when the server does not answer.
     */
    private fun recentlyAddedModels(
        library: LibraryRepository,
        artwork: WidgetArtwork,
        coverPx: Int,
        cornerPx: Float,
    ): Flow<RecentlyAddedModel> = library
        .observeAlbumList(kind = AlbumListKind.NEWEST, limit = 1)
        .map { albums -> albums.firstOrNull() }
        .distinctUntilChanged()
        .flatMapLatest { album: Album? ->
            flow {
                // The empty model first, so the card draws its placeholder and its copy immediately
                // rather than sitting on the loading layout while a cover comes off a server that may
                // not be reachable at all. REQUIREMENTS.md "Widgets": render sensibly with no network.
                emit(RecentlyAddedModel.of(album, cover = null))
                val cover = artwork.load(album?.artwork, coverPx, cornerPx)
                if (cover != null) emit(RecentlyAddedModel.of(album, cover = cover))
            }
        }
}

/**
 * The card: a cover and a Play disc over three lines, as screens 15 and 18 draw it.
 *
 * `appWidgetBackground` is what tells Android 12 and later that this view *is* the widget's
 * background, so the launcher clips it to the system's own widget corner radius instead of letting the
 * card's 22dp corners sit inside a square of card colour.
 *
 * The pack's `justify-content: space-between` is a weighted `Spacer` here, because Glance has no
 * such alignment: the two blocks are pinned to the top and the bottom of whatever height the launcher
 * gave the card, and the slack goes between them.
 */
@Composable
private fun RecentlyAddedCard(model: RecentlyAddedModel, open: Action) {
    val context = LocalContext.current
    val size = LocalSize.current
    val showPlay: Boolean = model.canPlay && size.width >= WidgetSquareDimensions.playButtonMinWidth
    // The third line carries the artist when there is an album and the empty state's second line when
    // there is not, so it is dropped on height alone. It is never dropped for want of content: an
    // album the mirror has no artist for is named rather than left blank, below.
    val showThirdLine: Boolean = size.height >= WidgetSquareDimensions.subtitleMinHeight

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_card))
            .padding(WidgetDimensions.cardPadding)
            .clickable(open),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(model)
            Spacer(GlanceModifier.defaultWeight())
            if (showPlay) PlayDisc(model)
        }

        Spacer(GlanceModifier.defaultWeight())

        Text(
            text = context.getString(R.string.widget_recently_added_eyebrow),
            style = WidgetText.eyebrow,
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(WidgetSquareDimensions.lineGap))
        Text(
            // Never blank. An album the mirror holds no title for reads "Untitled album" rather than
            // drawing an empty line, which on a home screen is indistinguishable from a broken widget;
            // see RecentlyAddedModel.title for where that guard comes from.
            text = when {
                !model.hasAlbum -> context.getString(R.string.widget_recently_added_empty)
                model.hasTitle -> model.title
                else -> context.getString(R.string.widget_untitled_album)
            },
            style = WidgetText.cardTitle,
            maxLines = 1,
        )
        if (showThirdLine) {
            Spacer(GlanceModifier.height(WidgetSquareDimensions.lineGap))
            Text(
                // Never blank either. `LibraryFormat.UNKNOWN_ARTIST` is what the rest of the app calls
                // an artist the server did not name, and the home screen must call it the same thing.
                text = when {
                    !model.hasAlbum -> context.getString(R.string.widget_recently_added_empty_detail)
                    model.hasArtist -> model.artistName
                    else -> context.getString(R.string.widget_unknown_artist)
                },
                style = WidgetText.cardSubtitle,
                maxLines = 1,
            )
        }
    }
}

/**
 * The 56dp cover.
 *
 * The placeholder tint is the `Box`'s background rather than a branch, so it is already painted when
 * the bitmap is null - no artwork, no network, or nothing in the mirror yet - and the card never shows
 * a hole.
 */
@Composable
private fun Cover(model: RecentlyAddedModel) {
    Box(
        modifier = GlanceModifier
            .size(WidgetSquareDimensions.artwork)
            .background(ImageProvider(R.drawable.widget_artwork_placeholder)),
        contentAlignment = Alignment.Center,
    ) {
        val cover = model.cover
        if (cover != null) {
            Image(
                provider = ImageProvider(cover),
                contentDescription = model.artworkDescription,
                modifier = GlanceModifier.size(WidgetSquareDimensions.artwork),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * The 40dp accent disc: play this album.
 *
 * Its own `clickable` inside the card's, which is what RemoteViews supports and what the pack draws -
 * the card is a link to the album and the disc is a command, so the two cannot be the same tap. The
 * MBID travels in the action's parameters rather than being read again inside the callback, so the
 * disc plays the album the user was looking at even if a newer one arrived between the draw and the
 * press.
 *
 * The description names the record, because "Play" on a home screen full of widgets says nothing
 * about which one.
 */
@Composable
private fun PlayDisc(model: RecentlyAddedModel) {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier
            .size(WidgetSquareDimensions.playButton)
            .background(ImageProvider(R.drawable.widget_transport_primary))
            .clickable(
                actionRunCallback<PlayRecentAlbumAction>(
                    actionParametersOf(PlayRecentAlbumAction.AlbumMbidKey to model.releaseGroupMbid),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_ic_play),
            contentDescription = context.getString(
                R.string.widget_action_play_album,
                // "Play this album" rather than "Play " with nothing after it: a content description
                // read inside a phrase must not end on a dangling word, so the absent title takes the
                // lower-case phrase rather than the title slot's "Untitled album".
                if (model.hasTitle) model.title else context.getString(R.string.widget_this_album),
            ),
            modifier = GlanceModifier.size(WidgetSquareDimensions.playIcon),
        )
    }
}

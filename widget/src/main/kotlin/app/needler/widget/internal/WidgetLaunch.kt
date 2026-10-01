package app.needler.widget.internal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity

/**
 * Where a tap on a widget card goes: into Needler, at the screen the card was about.
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Widgets" draws three cards on screens 15 and 18 and
 * every one of them is a link - the now-playing card opens the app, the recently added card opens
 * that album, the pulls card opens Pulls. This object is the one place that knows how to say so.
 *
 * ## Why the launcher intent and not a component
 *
 * It resolves the launch intent through `PackageManager` rather than naming `MainActivity` directly,
 * for two reasons. `:app` depends on `:widget`, so this module cannot see that class without
 * inverting the module graph. And the launch intent carries `ACTION_MAIN` and `CATEGORY_LAUNCHER`,
 * which is what makes Android *resume the existing task* instead of starting a second copy of a
 * single-activity app - a bare `ComponentName` would leave a user with two Needlers in their recents.
 *
 * The literal fallback is the component `:app`'s manifest declares, `app.needler/.MainActivity`, and
 * it should never be reached: `getLaunchIntentForPackage` only returns null when the package has no
 * launcher activity at all.
 *
 * ## Why the destination travels as two intent extras
 *
 * Because that mechanism already exists and already works. `:core:data` posts the three
 * notifications REQUIREMENTS.md "Background work and notifications" specifies, and each carries its
 * tap target as `NotificationDestination` encoded into two string extras on exactly this launch
 * intent; `MainActivity` decodes them and `:app`'s navigation graph turns them into a route. A
 * widget tap wants precisely the same thing, so it uses the same envelope rather than asking `:app`
 * for a second one.
 *
 * The keys and kind tokens below are therefore **restated literals, not a dependency**.
 * REQUIREMENTS.md "Widgets" forbids this module from seeing `:core:data`, where
 * `NotificationDestination` is declared, and `WidgetFormat` and `WidgetTheme` already carry the same
 * kind of deliberate copy for the same layering reason. The alternative - hoisting the destination
 * type into `:core:domain` so both callers share it - is the better long-term shape and is written
 * down as such, because it is a change in a module this one may not touch. Until then these four
 * strings must stay byte-identical to `NotificationDestination`'s, and a new kind token added there
 * means nothing here until it is added here too.
 *
 * ## Why every destination carries a throwaway URI
 *
 * This is the part that is silently broken if it is left out. Glance turns an `actionStartActivity`
 * into `PendingIntent.getActivity(context, 0, intent, FLAG_UPDATE_CURRENT ...)` - request code zero,
 * for every action in every widget. `PendingIntent` identity is `Intent.filterEquals`, which
 * compares action, component, categories, data and type and **ignores extras entirely**. Three
 * Needler widgets each building a `PendingIntent` from the same `ACTION_MAIN` launch intent are
 * therefore the *same* pending intent as far as the system is concerned, and `FLAG_UPDATE_CURRENT`
 * means the last one built wins: tapping the recently added card would open Pulls, or the
 * now-playing card would open an album, depending on which composed last.
 *
 * [DESTINATION_SCHEME] is what separates them. The URI is never resolved and never parsed - the
 * intent has an explicit component, so intent-filter matching does not happen and `:app` needs no
 * `<data>` element for it - it exists solely to give each destination its own identity under
 * `filterEquals`. Dropping it is a bug that only appears once a second widget is placed, which is
 * why it is written down here rather than left as a curiosity.
 */
internal object WidgetLaunch {

    /**
     * Open Needler wherever the user left it.
     *
     * No destination and no `FLAG_ACTIVITY_CLEAR_TOP`: this is the now-playing card's tap target,
     * and it means "bring the app back", not "go somewhere". Resuming the task as it stands is the
     * friendlier answer for a person who was two screens deep before they went to the home screen.
     */
    fun openApp(context: Context): Action = actionStartActivity(launchIntent(context))

    /**
     * Open one album's screen, by bare release-group MBID.
     *
     * The same route a "pull finished" notification opens, for the same reason: an album the user
     * asked to see is a change of content inside the navigation chrome, not a new context.
     */
    fun openAlbum(context: Context, releaseGroupMbid: String): Action =
        actionStartActivity(destinationIntent(context, KIND_ALBUM, releaseGroupMbid))

    /** Open the Pulls tab: the pulls card's tap target, and where a failed pull's notification goes. */
    fun openPulls(context: Context): Action =
        actionStartActivity(destinationIntent(context, KIND_PULLS, id = null))

    /**
     * Open the Library tab.
     *
     * The fallback when a card has nothing of its own to open - an empty recently added card on a
     * library that has never synced. REQUIREMENTS.md "Widgets" requires the cards to render sensibly
     * with no network, and a card whose tap does nothing is not sensible.
     */
    fun openLibrary(context: Context): Action =
        actionStartActivity(destinationIntent(context, KIND_LIBRARY, id = null))

    /**
     * The launch intent, with the destination attached and the app brought to the front on top of it.
     *
     * `FLAG_ACTIVITY_CLEAR_TOP` is what makes the extras arrive at all. `MainActivity` is a standard
     * launch mode, so on an already-running task a bare `ACTION_MAIN` intent resumes it and the
     * extras are dropped on the floor; `CLEAR_TOP` delivers the intent instead. `:core:data`'s
     * notifier sets the same flag on the same intent for the same reason.
     */
    private fun destinationIntent(context: Context, kind: String, id: String?): Intent =
        launchIntent(context)
            .setPackage(context.packageName)
            .setData(destinationUri(kind, id))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_ID, id)

    /** `needler-widget://album/1a2b-...`, or `needler-widget://pulls` where there is no id. */
    private fun destinationUri(kind: String, id: String?): Uri {
        val path: String = if (id.isNullOrBlank()) kind else kind + "/" + Uri.encode(id)
        return Uri.parse(DESTINATION_SCHEME + "://" + path)
    }

    private fun launchIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(context.packageName, FALLBACK_MAIN_ACTIVITY)
            }

    /** `:app`'s one activity, as `app/src/main/AndroidManifest.xml` declares it. */
    private const val FALLBACK_MAIN_ACTIVITY: String = "app.needler.MainActivity"

    /**
     * The scheme that keeps the three cards' pending intents apart. Registered nowhere, resolved
     * never; see this object's KDoc.
     */
    private const val DESTINATION_SCHEME: String = "needler-widget"

    /** `NotificationDestination.EXTRA_KIND`. Restated; see this object's KDoc. */
    private const val EXTRA_KIND: String = "app.needler.notification.KIND"

    /** `NotificationDestination.EXTRA_ID`. */
    private const val EXTRA_ID: String = "app.needler.notification.ID"

    /** `NotificationDestination.KIND_ALBUM`, which `:app` turns into the route `album/{albumId}`. */
    private const val KIND_ALBUM: String = "album"

    /** `NotificationDestination.KIND_PULLS`, which `:app` turns into the Pulls tab. */
    private const val KIND_PULLS: String = "pulls"

    /** `NotificationDestination.KIND_LIBRARY`, which `:app` turns into the Library tab. */
    private const val KIND_LIBRARY: String = "library"
}

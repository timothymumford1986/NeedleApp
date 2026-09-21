package app.needler.core.data.background

/**
 * What the runtime notification permission is, as far as any decision in this app cares.
 *
 * `POST_NOTIFICATIONS` only exists from Android 13 (API 33). Below that a notification posts
 * without asking, which is [NOT_REQUIRED] rather than [GRANTED] - the distinction matters because
 * "not required" must never make the app show a permission rationale on a phone that has no such
 * permission to grant.
 */
public enum class NotificationPermissionState {

    /** Android 12L and below: nothing to ask for. */
    NOT_REQUIRED,

    /** Android 13+, never asked. The only state in which asking is allowed. */
    NOT_YET_ASKED,

    /** Android 13+, granted. */
    GRANTED,

    /**
     * Android 13+, refused - or granted once and revoked in system settings.
     *
     * This is a normal state, not a failure. The user said no to notifications, not to the app.
     */
    DENIED,
    ;

    /** True when a notification posted now would actually be seen. */
    public val canPost: Boolean get() = this == NOT_REQUIRED || this == GRANTED
}

/** The moments at which asking for the permission is reasonable. */
public enum class NotificationPermissionTrigger {

    /** The user turned on one of the three notification switches on screen 12. */
    ENABLED_A_NOTIFICATION,

    /**
     * The user placed their first pull.
     *
     * The first pull is the first moment the app has something to tell them later, which is what
     * makes the request answerable: "allow notifications" right after "get me this album" means
     * something, where the same dialog on first launch means nothing.
     */
    PLACED_FIRST_PULL,

    /** App launch. Deliberately never a reason to ask. */
    APP_LAUNCH,
}

/**
 * When to ask for `POST_NOTIFICATIONS`, and what to do when the answer is no.
 *
 * REQUIREMENTS.md never says "ask on launch", and screen 12 draws the three notifications as
 * switches rather than as a prompt, so the permission is requested at the first moment the app has
 * a notification to deliver: the user turning a switch on, or placing their first pull.
 *
 * **Refusal changes nothing about correctness.** REQUIREMENTS.md "Delivery is best-effort" already
 * treats notifications as a convenience that Doze, standby buckets and OEM battery managers drop
 * anyway, and names the Pulls tab badge - `active_count` plus unseen completions, refreshed
 * whenever the app is opened - as the reliable channel. So a denied permission suppresses posting
 * and nothing else: the poller still runs, the mirror is still refreshed, the badge is still right.
 * [shouldSuppressPolling] exists to say that in code, and returns false.
 */
public object NotificationPermissionPolicy {

    /**
     * Whether to show the system permission dialog now.
     *
     * Only once. Android itself stops showing the dialog after two refusals, and an app that asks
     * on every switch flip trains the user to dismiss it, so the second ask is the app's own job to
     * suppress: after [NotificationPermissionState.DENIED] the correct affordance is a line in
     * settings pointing at the system screen, not another dialog.
     */
    public fun shouldAsk(
        state: NotificationPermissionState,
        trigger: NotificationPermissionTrigger,
        alreadyAsked: Boolean,
    ): Boolean {
        if (trigger == NotificationPermissionTrigger.APP_LAUNCH) return false
        if (state != NotificationPermissionState.NOT_YET_ASKED) return false
        return !alreadyAsked
    }

    /** True when posting is worth attempting at all. */
    public fun canPost(state: NotificationPermissionState): Boolean = state.canPost

    /**
     * False, always, and that is the point.
     *
     * A refused permission must not stop the background work: the badge, the mirror and the pull
     * table are all fed by the same poll, and they are the channel the product actually relies on.
     */
    public fun shouldSuppressPolling(state: NotificationPermissionState): Boolean = false

    /**
     * Whether the settings screen should explain that notifications are off at the system level.
     *
     * The switches on screen 12 stay usable when the permission is denied - they record what the
     * user wants - but a switch that is on while the system silently drops every notification is a
     * lie, so the screen needs to be able to say so.
     */
    public fun shouldShowSystemSettingsHint(
        state: NotificationPermissionState,
        anyNotificationEnabled: Boolean,
    ): Boolean = state == NotificationPermissionState.DENIED && anyNotificationEnabled
}

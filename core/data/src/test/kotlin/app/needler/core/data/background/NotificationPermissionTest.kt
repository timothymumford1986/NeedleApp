package app.needler.core.data.background

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app may ask for `POST_NOTIFICATIONS`, and what a refusal is allowed to change.
 *
 * The second half is the one worth a test. REQUIREMENTS.md "Delivery is best-effort" makes the
 * Pulls badge the reliable channel precisely because notifications are dropped by Doze, standby
 * buckets and OEM battery managers anyway - so a refused permission has to suppress posting and
 * nothing else. A poller that stopped when the permission was denied would silently stop keeping
 * the badge right, which is the one thing the user would actually notice.
 */
public class NotificationPermissionTest {

    @Test
    public fun `launch is never a reason to ask`() {
        assertFalse(
            NotificationPermissionPolicy.shouldAsk(
                state = NotificationPermissionState.NOT_YET_ASKED,
                trigger = NotificationPermissionTrigger.APP_LAUNCH,
                alreadyAsked = false,
            ),
        )
    }

    @Test
    public fun `the first pull and a switched-on notification both are`() {
        listOf(
            NotificationPermissionTrigger.PLACED_FIRST_PULL,
            NotificationPermissionTrigger.ENABLED_A_NOTIFICATION,
        ).forEach { trigger ->
            assertTrue(
                NotificationPermissionPolicy.shouldAsk(
                    state = NotificationPermissionState.NOT_YET_ASKED,
                    trigger = trigger,
                    alreadyAsked = false,
                ),
            )
        }
    }

    @Test
    public fun `the app asks once`() {
        assertFalse(
            "Android stops showing the dialog after two refusals; asking again only trains the " +
                "user to dismiss it",
            NotificationPermissionPolicy.shouldAsk(
                state = NotificationPermissionState.NOT_YET_ASKED,
                trigger = NotificationPermissionTrigger.PLACED_FIRST_PULL,
                alreadyAsked = true,
            ),
        )
    }

    @Test
    public fun `a device with no such permission is never asked`() {
        assertFalse(
            "a rationale on Android 12 would refer to a dialog that cannot appear",
            NotificationPermissionPolicy.shouldAsk(
                state = NotificationPermissionState.NOT_REQUIRED,
                trigger = NotificationPermissionTrigger.PLACED_FIRST_PULL,
                alreadyAsked = false,
            ),
        )
        assertTrue(NotificationPermissionPolicy.canPost(NotificationPermissionState.NOT_REQUIRED))
    }

    @Test
    public fun `a granted permission is not asked for again`() {
        assertFalse(
            NotificationPermissionPolicy.shouldAsk(
                state = NotificationPermissionState.GRANTED,
                trigger = NotificationPermissionTrigger.ENABLED_A_NOTIFICATION,
                alreadyAsked = false,
            ),
        )
        assertTrue(NotificationPermissionPolicy.canPost(NotificationPermissionState.GRANTED))
    }

    @Test
    public fun `a refusal suppresses posting and nothing else`() {
        assertFalse(NotificationPermissionPolicy.canPost(NotificationPermissionState.DENIED))
        assertFalse(
            "the badge, the mirror and the pull table all come from the same poll, and the badge " +
                "is the channel the product relies on",
            NotificationPermissionPolicy.shouldSuppressPolling(NotificationPermissionState.DENIED),
        )
    }

    @Test
    public fun `a switch that is on while the system silently drops everything must be explained`() {
        assertTrue(
            NotificationPermissionPolicy.shouldShowSystemSettingsHint(
                state = NotificationPermissionState.DENIED,
                anyNotificationEnabled = true,
            ),
        )
        assertFalse(
            "nothing to explain when the user wanted no notifications anyway",
            NotificationPermissionPolicy.shouldShowSystemSettingsHint(
                state = NotificationPermissionState.DENIED,
                anyNotificationEnabled = false,
            ),
        )
        assertFalse(
            NotificationPermissionPolicy.shouldShowSystemSettingsHint(
                state = NotificationPermissionState.GRANTED,
                anyNotificationEnabled = true,
            ),
        )
    }
}

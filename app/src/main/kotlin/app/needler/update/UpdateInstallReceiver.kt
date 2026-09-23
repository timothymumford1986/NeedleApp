package app.needler.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The other end of [ApkInstaller]'s [android.content.IntentSender]: where the platform reports what
 * happened to an install session.
 *
 * ## Why a manifest receiver rather than a runtime-registered one
 *
 * `PackageInstaller.Session.commit` is asynchronous and the app it is installing is *this* app, so
 * the process cannot be assumed to survive long enough to hear the answer on a receiver registered
 * in memory. A manifest entry is the only form that is still there after the process has been
 * killed and brought back — which, on a successful install, is exactly what has happened.
 *
 * It is declared `android:exported="false"` and carries no `<intent-filter>`, because nothing
 * outside this app has any business addressing it. [ApkInstaller] builds an **explicit** intent
 * naming this class, and the platform delivers it under this app's own identity, so the broadcast
 * arrives without the receiver being reachable by anyone else. Exporting it, or giving it a public
 * action, would let any app on the device fabricate an install result.
 *
 * ## Hilt
 *
 * `@AndroidEntryPoint` on a receiver means the generated superclass performs field injection inside
 * `super.onReceive`, so that call must come first — before [installer] is touched. This is why
 * [ApkInstaller] is a `@Singleton`: the receiver and the view model behind the banner have to be
 * looking at the same `StateFlow`, or the confirmation dialogue would appear while the banner went
 * on claiming the download was still staging.
 *
 * ## What this class deliberately does not do
 *
 * It does not decide anything. Everything — which statuses mean failure, what to show, when to
 * abandon a session — is in [ApkInstaller] next to the code that created the session, so that the
 * install state machine can be read in one file. In particular it does not, and must never, respond
 * to a failure by offering to uninstall: see the rule at the top of [ApkInstaller].
 */
@AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {

    @Inject
    lateinit var installer: ApkInstaller

    override fun onReceive(context: Context, intent: Intent) {
        // Must be first: this is what injects `installer`.
        super.onReceive(context, intent)
        installer.onStatusBroadcast(context, intent)
    }
}

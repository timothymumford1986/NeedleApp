package app.needler.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

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
 * ## Hilt, and why this pulls rather than being injected
 *
 * `@AndroidEntryPoint` is the usual answer and does not work here. Hilt injects a receiver from a
 * generated superclass inside `super.onReceive`, and the Gradle plugin rewrites the superclass
 * *after* compilation — so Kotlin still sees `BroadcastReceiver.onReceive`, which is abstract, and
 * `super.onReceive` will not compile. An `@EntryPoint` pulled from the application graph does the
 * same job without the transform, and `:widget` already reaches the graph this way for the same
 * underlying reason: a receiver is only injectable for the length of one `onReceive`.
 *
 * [ApkInstaller] is a `@Singleton` so that this receiver and the view model behind the banner are
 * looking at the same `StateFlow`; otherwise the confirmation dialogue would appear while the
 * banner went on claiming the download was still staging.
 *
 * ## What this class deliberately does not do
 *
 * It does not decide anything. Everything — which statuses mean failure, what to show, when to
 * abandon a session — is in [ApkInstaller] next to the code that created the session, so that the
 * install state machine can be read in one file. In particular it does not, and must never, respond
 * to a failure by offering to uninstall: see the rule at the top of [ApkInstaller].
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Pulled per broadcast rather than held: the process this receiver runs
        // in may have been created to deliver exactly this one intent.
        EntryPointAccessors
            .fromApplication(context.applicationContext, UpdateInstallEntryPoint::class.java)
            .apkInstaller()
            .onStatusBroadcast(context, intent)
    }

    /** How the receiver reaches the one [ApkInstaller] the banner is also watching. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UpdateInstallEntryPoint {
        fun apkInstaller(): ApkInstaller
    }
}

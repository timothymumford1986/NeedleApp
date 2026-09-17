package app.needler

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt's application entry point.
 *
 * This is the root of the dependency graph: :core:data's repository
 * implementations are bound to the :core:domain interfaces that every feature
 * module, the Glance widgets and the Media3 service ask for. Those bindings go
 * in Hilt modules under :app, not here.
 *
 * Two things are expected to be added to this class, by whoever writes them:
 *  - `Configuration.Provider` returning a configuration with the injected
 *    `HiltWorkerFactory`, so the polling and download Workers in :core:data can
 *    be constructed by Hilt. Removing WorkManager's default initializer in
 *    AndroidManifest.xml is only correct once that exists.
 *  - notification channel creation for the three switchable notifications
 *    (pull finished, pull failed, new release from a followed artist).
 *
 * Nothing that touches the network, the database or the credential store should
 * run in `onCreate`: REQUIREMENTS.md budgets cold start to library content at
 * under 1.2 s on a mid-range 2022 phone.
 */
@HiltAndroidApp
class NeedlerApplication : Application()

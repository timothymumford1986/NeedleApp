// :app — navigation, DI wiring, Connect and Settings.
// REQUIREMENTS.md "Architecture > Modules".
//
// This is the only module that sees everything. It is where Hilt binds the
// :core:data implementations to the :core:domain interfaces the features and
// the widgets ask for, and where the OkHttp client from :core:network (with its
// certificate-pinning policy) is constructed once and shared with Coil and with
// the Media3 datasource.
//
// It also owns the two screens that are not features: Connect (screens 01/16)
// and Settings (screen 12).

plugins {
    id("needler.android.application")
    id("needler.hilt")
}

android {
    namespace = "app.needler"

    defaultConfig {
        applicationId = "app.needler"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    // ---- every module in the project ----
    implementation(project(":core:design"))
    implementation(project(":core:domain"))
    // :app is the only module allowed to see the implementations, because it is
    // the only module that binds them.
    implementation(project(":core:data"))
    implementation(project(":core:network"))

    implementation(project(":feature:library"))
    implementation(project(":feature:search"))
    implementation(project(":feature:pulls"))
    implementation(project(":feature:player"))

    implementation(project(":player:service"))
    implementation(project(":widget"))

    // ---- navigation and the activity host ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // REQUIREMENTS.md "Motion"/"Performance budgets": the 2.1 s record spin-up
    // has to overlay a screen that is already loaded and be skippable by a tap.
    implementation(libs.androidx.core.splashscreen)

    // WorkManager is configured here (HiltWorkerFactory via
    // Configuration.Provider on the Application); the Workers themselves live
    // in :core:data.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    // Binding a MediaController to the session owned by :player:service.
    implementation(libs.media3.session)

    implementation(libs.kotlinx.coroutines.android)

    // Hilt instrumented testing is intentionally not wired up yet. When the
    // first @HiltAndroidTest arrives, add:
    //     androidTestImplementation(libs.hilt.android.testing)
    //     add("kspAndroidTest", libs.hilt.compiler)
    // (libs.hilt.android.testing is already in the version catalogue.)
}

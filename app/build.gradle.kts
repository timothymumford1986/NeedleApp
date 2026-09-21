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

// ---------------------------------------------------------------------------
// Screenshots
// ---------------------------------------------------------------------------
// Every screen in this module renders to a PNG on the JVM - no emulator, no
// device, no connected phone. Regenerate the whole set with:
//
//     ./gradlew :app:recordScreenshots
//
// The images land in `screenshots/` at the repository root, which is committed,
// so they can be opened directly rather than dug out of build output. Add
// `-Pneedler.screenshots.verify` to compare against the committed images and
// fail on a difference instead of overwriting them.
//
// The rendering itself is Roborazzi over Robolectric's native graphics mode;
// how that is wired, and why the Roborazzi Gradle plugin is deliberately not
// applied, is in build-logic/.../ScreenshotConventionPlugin.kt. The test helper
// that names the device sizes is
// app/src/test/kotlin/app/needler/screenshot/NeedlerScreenshots.kt.
// ---------------------------------------------------------------------------

plugins {
    id("needler.android.application")
    id("needler.hilt")
    id("needler.screenshots")
}

android {
    namespace = "app.needler"

    defaultConfig {
        applicationId = "app.needler"
        // Android decides what counts as an update by comparing versionCode, so a
        // constant here means no release can ever install over another: the system
        // sees the same version and refuses. The release workflow derives both from
        // the git tag and passes them in; a local build keeps the defaults, which is
        // what you want for something you are only ever installing over itself.
        //
        // v1.2.3 becomes 10203, so the number always sorts the way the tag reads.
        versionCode = providers.gradleProperty("needler.versionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("needler.versionName").orNull ?: "0.0.0-dev"
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
    // Material 3 itself arrives transitively through :core:design's `api`, but
    // :app names it too: the navigation host uses Material's Scaffold insets
    // and window size classes directly, and a module should declare what it
    // compiles against.
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.windowSizeClass)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Artwork. :app owns the ImageLoader because turning a domain ArtworkRef into a URL needs the
    // saved server address and the session, which no feature module may reach.
    implementation(libs.coil.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

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

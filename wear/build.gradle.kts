// :wear — the Wear OS companion.
// REQUIREMENTS.md "Architecture > Modules", "Surfaces beyond the app > Wear OS".
//
// v1 scope is transport controls, the crate, and playback of on-device audio
// synced from the phone over the data layer. No search, no pulling.
//
// This is its own APK, so it is an application module, not a library. The
// applicationId matches the phone app because Wear pairing is keyed on it.
//
// Two things to know:
//  * No Hilt. Hilt needs an @HiltAndroidApp Application class and the watch app
//    does not have one yet. Add `id("needler.hilt")` at the same time as that
//    class, not before.
//  * minSdk comes from the catalogue (26), matching REQUIREMENTS.md. In practice
//    Wear OS 3+ wants minSdk 30, and Play requires it for a watch listing; that
//    is tied up with open question 4 (whether Wear ships via Play at all), so
//    the override is deliberately not applied yet.

plugins {
    id("needler.android.application")
}

android {
    namespace = "app.needler.wear"

    defaultConfig {
        // Must equal :app's applicationId for the watch app to pair with it.
        applicationId = "app.needler"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    // Shared entities only. The watch talks to the phone over the data layer,
    // not to the server, so it needs neither :core:data nor :core:network.
    implementation(project(":core:domain"))

    // Wear's own Compose stack. Note this is androidx.wear.compose:compose-material3,
    // not the phone/tablet androidx.compose.material3 — the convention plugin
    // deliberately does not add the latter.
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.toolingPreview)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    // The data layer, for syncing on-device audio and session state from the phone.
    implementation(libs.playServices.wearable)

    implementation(libs.kotlinx.coroutines.android)
}

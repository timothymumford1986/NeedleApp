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

    // Not a dependency - a floor on one that arrives through the line above.
    //
    // play-services-wearable pulls play-services-base, which asks for
    // androidx.fragment 1.0.0; Gradle settles on 1.1.0. NeedlerWearActivity uses
    // registerForActivityResult to ask for POST_NOTIFICATIONS, and
    // androidx.activity's InvalidFragmentVersionForActivityResult is a *fatal*
    // lint check below fragment 1.3.0: a pre-1.3 FragmentActivity does not call
    // super.onRequestPermissionsResult, so the result never arrives. It fails
    // :wear:lintVitalRelease, which means it fails the release build rather than
    // merely warning.
    //
    // A constraint rather than an implementation dependency, because this module
    // is Wear Compose and wants no fragment code of its own - only a version
    // floor on the copy Play services drags in.
    constraints {
        implementation(libs.androidx.fragment) {
            because("androidx.activity requires fragment 1.3.0+ for registerForActivityResult")
        }
    }

    // The watch's own playback. REQUIREMENTS.md "Surfaces beyond the app > Wear
    // OS" ends Wear's v1 scope with "playback of on-device audio synced from the
    // phone over the data layer", and a watch that plays with its screen off
    // needs a media session in a foreground service — which is what
    // media3-session provides and what the platform's own media controls drive.
    //
    // This is a much smaller slice of Media3 than :player:service takes, and the
    // omissions are deliberate:
    //   * no media3-datasource-okhttp and no okhttp, because the watch reads
    //     files out of its own store and never a URL. It has no server
    //     credentials and no route to DroppedNeedle, by design.
    //   * no media3-cast: there is no output picker on a watch.
    //   * no media3-database, for the reason :player:service gives — it exists
    //     to back SimpleCache's index, which REQUIREMENTS.md rules out.
    // media3-common is named as well as depended on transitively because
    // MediaItem, MediaMetadata and Player are used directly.
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // MediaSessionService extends LifecycleService. It arrives transitively with
    // media3-session; named here for the same reason :player:service names it —
    // the service class depends on it directly and a transitive compile
    // dependency is not a contract.
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.kotlinx.coroutines.android)
}

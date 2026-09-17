// :feature:player — now playing, the crate, EQ, crossfade and output picker.
// REQUIREMENTS.md "Architecture > Modules", "Playback".
//
// Layering: :core:domain + :core:design only, like every other feature.
//
// Note on Media3: this module deliberately has NO Media3 dependency, even
// though it draws the transport. REQUIREMENTS.md "Playback" is explicit that
// "The app's UI is one more client of that session, not the owner of the
// player" — so the screens here drive a playback abstraction declared in
// :core:domain (transport commands, queue state, EQ bands, crossfade settings,
// current output), and :player:service is the only module that knows Media3
// exists. If a MediaController ever needs to be constructed, it is constructed
// there or in :app and handed across as that domain interface; do not add
// androidx.media3 to this module.

plugins {
    id("needler.android.library.compose")
    id("needler.hilt")
}

android {
    namespace = "app.needler.feature.player"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:design"))

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}

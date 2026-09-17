// :widget — the three Glance home-screen widgets.
// REQUIREMENTS.md "Architecture > Modules", "Surfaces beyond the app > Widgets".
//
// Now playing, recently added, and the active pull with its percentage
// (screens 15 and 18). REQUIREMENTS.md requires all three to render sensibly
// with no network and no active playback, since that is their most common
// state — so they read the mirror through domain repositories injected by Hilt,
// exactly like a feature module does.
//
// Glance composables are not Compose UI composables (different runtime, no
// androidx.compose.material3), so this module does not depend on :core:design.
// Colour and type values from the design system have to be restated in Glance
// terms; that duplication is Glance's, not ours to avoid.
//
// It still needs the Compose *compiler*, which is why it uses the compose
// library convention plugin.

plugins {
    id("needler.android.library.compose")
    id("needler.hilt")
}

android {
    namespace = "app.needler.widget"
}

dependencies {
    implementation(project(":core:domain"))

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.kotlinx.coroutines.android)
}

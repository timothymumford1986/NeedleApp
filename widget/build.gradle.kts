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

    defaultConfig {
        // Glance builds a PendingIntent that names an ActionCallback by class
        // name, so R8 sees the transport buttons' classes as unreachable and
        // strips them from :app's minified release. The rules ship with the
        // module because the module is what knows why they are needed.
        // (AndroidLibraryConventionPlugin deliberately does not set this for
        // every module: AGP 9 fails the build when a named file is missing.)
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(project(":core:domain"))

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Artwork. This does NOT build an ImageLoader: :app already builds the one
    // the whole app shares (ArtworkRefMapper, the cert-pinned mediaClient, the
    // 256 MB artwork disk cache) and publishes it as Coil's singleton, and the
    // widgets compose in :app's own process, so SingletonImageLoader.get hands
    // them that exact loader. The dependency is here only to name it.
    //
    // A widget must not open its own HTTP connection: REQUIREMENTS.md
    // "Libraries" allows the project one OkHttp client and one certificate
    // -pinning policy, and a second client here would fetch covers straight
    // past the pin. See WidgetArtwork.kt.
    implementation(libs.coil.core)

    implementation(libs.kotlinx.coroutines.android)
}

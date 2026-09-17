// :core:data — Room mirror, sync, offline write queue, repository implementations.
// REQUIREMENTS.md "Architecture > Modules", "Local persistence", "Offline and caching".
//
// This is the one module that sees both the domain model and the wire model, so
// it owns every DTO -> domain mapping, including the two places REQUIREMENTS.md
// calls out ("Where the two lanes meet"): AlbumRepository joining owned albums
// from the mirror with the /api/v1 discography, and SearchRepository merging
// local FTS results with catalogue results on the release-group MBID.
//
// It implements the interfaces declared in :core:domain; nothing here is visible
// to a feature module. Hilt in :app binds these implementations to those
// interfaces.

plugins {
    id("needler.android.library")
    id("needler.hilt")
    // Room's Gradle plugin, so exported schemas land in a known directory.
    alias(libs.plugins.room)
    // Write-queue payloads and cached capability sets are stored as JSON.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.needler.core.data"
}

room {
    // REQUIREMENTS.md "Secrets and migrations": migrations are hand-written from
    // the first release, because a destructive fallback would discard a
    // multi-gigabyte audio cache and force a full re-sync. Hand-written
    // migrations need the exported schemas committed, so they go in the module.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // `api`, because the repository interfaces and entities this module
    // implements are part of what a consumer of :core:data works with.
    api(project(":core:domain"))
    // `implementation`: the DTOs stop here. See the note in :core:network.
    implementation(project(":core:network"))

    // Room with FTS4 over the mirror. REQUIREMENTS.md "Libraries".
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Settings and secrets. REQUIREMENTS.md "Libraries": DataStore preferences
    // for settings, EncryptedSharedPreferences under a Keystore master key for
    // the companion bearer and the app-password.
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Background polling, metadata sync and per-track Range downloads.
    // REQUIREMENTS.md "Background work and notifications".
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.work.testing)
}

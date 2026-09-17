// :player:service — the Media3 MediaLibraryService, audio processors, audio
// cache and the Android Auto browse tree.
// REQUIREMENTS.md "Architecture > Modules", "Playback", "Offline and caching".
//
// Everything that touches Media3 lives here and nowhere else. One
// MediaLibraryService backs the app UI, the lock screen, the widgets, Android
// Auto, Bluetooth controls and Wear, so this is also where:
//   * the custom audio processor chain for the 10-band EQ and crossfade goes
//     (the platform Equalizer effect cannot do it)
//   * CacheDataSource/SimpleCache over an OkHttp datasource does byte-range
//     caching for both pinned and LRU-cached audio
//   * the Auto browse tree and voice search are served
//
// It depends on :core:data — the exception to the "features see only domain"
// rule — because the audio cache index is a Room table (`audio_cache`) that the
// player reads and writes directly on every buffer and eviction. Going through
// a domain repository for that would add a hop on the hot path.

plugins {
    id("needler.android.library")
    id("needler.hilt")
}

android {
    namespace = "app.needler.player.service"
}

dependencies {
    implementation(project(":core:domain"))
    // The cache index (`audio_cache`: track key, byte size, last played, pinned
    // flag, file path, source file_id) and the staleness check on sync.
    implementation(project(":core:data"))

    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // REQUIREMENTS.md "Libraries": one OkHttp client, one cert-pinning policy —
    // including for audio, which must be Range-capable.
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp.core)
    // SimpleCache's index is backed by media3-database.
    implementation(libs.media3.database)
    // REQUIREMENTS.md "Output"/"Cast, and where it breaks": Cast is best-effort
    // and probed for reachability, but the receiver support lives here so the
    // one session owns every output.
    implementation(libs.media3.cast)

    implementation(libs.kotlinx.coroutines.android)
}

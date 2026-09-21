// :player:service — the Media3 MediaLibraryService, the audio processor chain, the
// write-through audio cache datasource and (next) the Android Auto browse tree.
// REQUIREMENTS.md "Architecture > Modules", "Playback", "Offline and caching".
//
// Everything that touches Media3 lives here and nowhere else. One
// MediaLibraryService backs the app UI, the lock screen, the widgets, Android
// Auto, Bluetooth controls and Wear, so this is also where:
//   * the custom audio processor chain for the 10-band EQ goes (the platform
//     Equalizer effect cannot express the fixed 31 Hz - 16 kHz band set, has no
//     preamp, and cannot be composed with the fade ramps)
//   * a write-through DataSource copies streamed bytes into the project's own
//     `audio_cache` store as they are read - NOT CacheDataSource/SimpleCache,
//     which REQUIREMENTS.md rules out for four separate reasons
//   * the Auto browse tree and voice search will be served
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
    // A second, narrower exception, and it is one file: PlayerServiceModule
    // adapts SubsonicMediaUrls to the module's own AudioUrls port, and hands
    // NeedlerHttpClient.mediaClient to Media3's OkHttp datasource. Media3
    // fetches audio bytes itself, so it needs a self-contained URL and the one
    // client that carries the certificate pin — neither of which a repository
    // can hand it. No DTO, no API call and no wire type crosses this boundary;
    // everything else in the module is written against the AudioUrls port so it
    // can be exercised without a server.
    implementation(project(":core:network"))

    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // REQUIREMENTS.md "Libraries": one OkHttp client, one cert-pinning policy —
    // including for audio, which must be Range-capable.
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp.core)
    // MediaSessionService extends LifecycleService. It arrives transitively with
    // media3-session; named here because the service class depends on it
    // directly and a transitive compile dependency is not a contract.
    implementation(libs.androidx.lifecycle.service)
    // REQUIREMENTS.md "Output"/"Cast, and where it breaks": Cast is best-effort
    // and probed for reachability, but the receiver support lives here so the
    // one session owns every output. Not wired yet — see the module report.
    implementation(libs.media3.cast)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    // media3-database is deliberately NOT a dependency. It exists to back
    // SimpleCache's index, and REQUIREMENTS.md "Why Media3's cache is not used"
    // rules SimpleCache out: its evictor is a fixed byte cap (the storage budget
    // this product removed), it cannot exempt a downloaded album from eviction,
    // it would retain transcodes, and it has nowhere to hold the per-track
    // fingerprint the staleness check compares against.
}

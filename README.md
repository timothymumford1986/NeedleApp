# Needler

An Android music player for a self-hosted [DroppedNeedle](https://github.com/DroppedNeedle/DroppedNeedle) server.

Browse and play your library, search the MusicBrainz catalogue, ask your server to pull albums you
don't own yet, and keep what you want playable offline. Phone and tablet, with lock-screen controls,
home-screen widgets, Android Auto and a Wear OS companion.

## Status

The foundation layers are built and tested. There is no user interface yet, so the app
assembles and installs but does not yet show you anything.

| Module | State |
| --- | --- |
| `:core:domain` — models, repository interfaces, use cases | Built |
| `:core:design` — palette, type, motion, shared components | Built |
| `:core:network` — both server APIs, TLS pinning, capability probe | Built |
| `:core:data` — database, offline store, settings, credentials | Built |
| Build setup — 12 modules, version catalog, CI | Built |
| Screens — library, search, pulls, player | Not started |
| `:player:service` — Media3 playback | Not started |
| `:widget`, `:wear` | Not started |

Roughly 20,000 lines of Kotlin, 101 tests passing, `app-debug.apk` and `wear-debug.apk`
both assemble.

| | |
| --- | --- |
| [REQUIREMENTS.md](REQUIREMENTS.md) | Requirements and architecture, and the canonical spec — kept in step with the code |
| [design/](design/) | 21 screens as HTML and PNG, plus a PDF — the UI source of truth |

## Building

Needs JDK 21 and the Android SDK with `android-37.x` installed. `compileSdk` is 37 because
`okhttp-android` and the androidx libraries require it; `targetSdk` is 36 and `minSdk` is 26.

```
./gradlew test assembleDebug
```

If your checkout lives in a synced folder (OneDrive, Dropbox), the sync client will hold
handles on Gradle's output and the build will fail with "Unable to delete directory". Put
`needler.buildDir=/some/path/outside/the/sync` in `~/.gradle/gradle.properties` to move the
build output elsewhere.

## How it works

Needler talks to two surfaces on your own DroppedNeedle server:

- **OpenSubsonic** (`/subsonic/rest/*`) for library browsing, playback, playlists and favourites
- **The native API** (`/api/v1/*`) for catalogue search, pull requests and the download queue

Both are joined on the MusicBrainz release-group MBID, so an album you own and an album you could
pull are the same thing in two states. See [REQUIREMENTS.md](REQUIREMENTS.md) for detail.

## Stack

Kotlin 2.4.20, Jetpack Compose with adaptive layouts, Media3/ExoPlayer, Room, WorkManager, Hilt,
OkHttp, Coil. Built with AGP 9.4 and Gradle 9.6.

## Disclaimer

Needler is an independent client for a DroppedNeedle server that you install, configure and operate
yourself. It is not affiliated with, endorsed by or sponsored by DroppedNeedle, slskd, Soulseek,
MusicBrainz, ListenBrainz or any other service it talks to.

Needler does not host, store, index, search for or transmit any music. Every search, download and
stream is performed by your own server and the services you have connected to it, under your control
and your accounts.

You are solely responsible for what you search for, download and play, for holding the rights to do
so, and for complying with copyright law and the terms of every service you use.

# Needler

An Android music player for a self-hosted [DroppedNeedle](https://github.com/DroppedNeedle/DroppedNeedle) server.

Your music lives on your server. Needler is the app that plays it from your phone — your library,
your artwork, your queue. You can search the MusicBrainz catalogue for records you don't own yet and
ask your server to go and find them, and keep whatever you like on the phone for when there's no
signal.

It's for people who already run their own music server and want a player that treats it as the real
thing rather than an afterthought.

## What works today

Connect it to your server and you get your whole library — albums, artists and songs, with artwork,
sorting, and a list or grid view. Open an album, play it, shuffle it, jump to any track. There's a
mini-player above the tabs and a full player behind it with your queue.

Playback behaves the way a music app should. It keeps going when you leave the app, it's on your
lock screen and in your notification shade with artwork and controls, and it survives rotating the
phone. Turn the phone sideways, or open it on a tablet, and the player moves into a panel beside
your library instead of hiding behind it.

The library is mirrored on the phone, so it loads instantly and works with no connection at all.

**Not there yet.** Search, the pulls screen and settings are still placeholders, and the
home-screen widget and Wear OS app haven't been started. Downloading albums for offline listening
starts but doesn't yet hold on to what it fetches, so treat offline playback as unfinished.

## Get it

Grab the newest `needler-vX.Y.Z.apk` from
[Releases](https://github.com/timothymumford1986/NeedleApp/releases) and open it on your phone.
Android will ask whether to allow installs from whatever you downloaded it with — that's a per-app
permission and you can take it back afterwards. You'll need Android 8.0 or newer.

There's a separate `needler-wear-vX.Y.Z.apk` for a Wear OS watch, which has to go on over adb.

Every release is signed with the same key, so a new one installs over the old one and keeps your
server and your library.

## What's next

Roughly in the order I'll get to them:

1. Make downloads actually stick, so offline listening works.
2. Add some logging. Two bugs so far have been almost impossible to chase on a device, because the
   app says nothing at all when something fails.
3. Work out why artist discographies come back empty from the catalogue.
4. Build search. It's the biggest thing missing, and the placeholder is already wired up.
5. Make the Songs tab show your whole library rather than a sample of it.
6. Then the pulls screen, the widget and the watch app.

## Build it yourself

You'll need JDK 21 and the Android SDK with `android-37.x`. `compileSdk` is 37 because
`okhttp-android` and the androidx libraries want it; `targetSdk` is 36 and `minSdk` is 26.

```
./gradlew test assembleDebug
```

`./gradlew test` rewrites the committed screenshots unless you pass
`-Pneedler.screenshots.verify`, which is what CI does and what turns them into regression tests. So
record deliberate changes with the flag off, and commit the result.

If your checkout sits in a synced folder (OneDrive, Dropbox), the sync client holds handles on
Gradle's output and the build fails with "Unable to delete directory". Put
`needler.buildDir=/some/path/outside/the/sync` in `~/.gradle/gradle.properties` to move the build
output somewhere else.

One thing worth knowing: debug builds are signed with whatever debug keystore the machine building
them happens to have, so a build from a second computer won't install over one from the first.
Uninstalling to get around that also wipes the key protecting your saved credentials, and you'll
have to connect to your server again. Releases don't have that problem.

## How it works

Needler talks to two surfaces on your own DroppedNeedle server:

- **OpenSubsonic** (`/subsonic/rest/*`) for library browsing, playback, playlists and favourites
- **The native API** (`/api/v1/*`) for catalogue search, pull requests and the download queue

Both are joined on the MusicBrainz release-group MBID, so an album you own and an album you could
pull are the same thing in two states.

| | |
| --- | --- |
| [REQUIREMENTS.md](REQUIREMENTS.md) | The full spec and architecture, kept in step with the code |
| [design/](design/) | 21 screens as HTML and PNG, plus a PDF — the UI source of truth |

## Stack

Kotlin 2.4.20, Jetpack Compose with adaptive layouts, Media3/ExoPlayer, Room, WorkManager, Hilt,
OkHttp, Coil. Built with AGP 9.4 and Gradle 9.6.

## Licence

[Apache-2.0](LICENSE). You may use, modify and redistribute this, including commercially,
provided you keep the notice and state your changes.

Needler talks to DroppedNeedle over HTTP and links none of its code, so DroppedNeedle's
AGPL-3.0 does not extend to this project.

## Disclaimer

Needler is an independent client for a DroppedNeedle server that you install, configure and operate
yourself. It is not affiliated with, endorsed by or sponsored by DroppedNeedle, slskd, Soulseek,
MusicBrainz, ListenBrainz or any other service it talks to.

Needler does not host, store, index, search for or transmit any music. Every search, download and
stream is performed by your own server and the services you have connected to it, under your control
and your accounts.

You are solely responsible for what you search for, download and play, for holding the rights to do
so, and for complying with copyright law and the terms of every service you use.

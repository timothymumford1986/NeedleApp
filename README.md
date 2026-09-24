# Needler

An Android music player for a self-hosted [DroppedNeedle](https://github.com/DroppedNeedle/DroppedNeedle) server.

Play your library, search the MusicBrainz catalogue, ask your server to pull albums you don't own, and keep what you want on the phone for when you're offline.

Phone and tablet.

<img src="docs/screenshots/library.png" width="320" alt="The library screen on a phone">

## What works

Your library in albums, artists and songs, with artwork and sorting. Open an album, play it, shuffle it, jump to any track.

Search your library and the MusicBrainz catalogue at the same time. The pulls queue, with progress, cancel and retry. Settings, the equaliser and crossfade.

The player keeps going in the background, on the lock screen and in the notification shade. Turn the phone sideways, or open it on a tablet, and the player moves to a panel next to the library.

Your library is mirrored on the phone, so it opens straight away and works with no connection.

## What's not done

Offline downloads work in the code but I haven't tested them on a phone yet.

The Wear app runs but can't talk to the phone. The phone side of that isn't built.

Artist discographies come back empty from the catalogue.

No Android Auto.

## How to get it

### Use a release

Download the latest `needler-vX.Y.Z.apk` from [Releases](https://github.com/timothymumford1986/NeedleApp/releases) and open it on your phone. You'll need Android 8.0 or newer.

Android will ask whether to allow installs from wherever you downloaded it. That's per app, and you can turn it back off afterwards.

There's a separate `needler-wear-vX.Y.Z.apk` for a watch, which goes on over adb.

Every release is signed with the same key, so a new one installs over the old one and keeps your server and your library.

### Build it yourself

You'll need JDK 21 and the Android SDK with `android-37.x`.

```bash
./gradlew test assembleDebug
```

`compileSdk` is 37 because `okhttp-android` and the androidx libraries need it. `targetSdk` is 36, `minSdk` is 26.

`test` rewrites the committed screenshots unless you pass `-Pneedler.screenshots.verify`, which is what CI runs. Record changes with the flag off, then commit the result.

In a synced folder (OneDrive, Dropbox) the sync client holds Gradle's output open and the build fails with "Unable to delete directory". Put `needler.buildDir=/some/path/outside/the/sync` in `~/.gradle/gradle.properties`.

Debug builds are signed with each machine's own debug key, so a build from a second computer won't install over one from the first. Releases don't have that problem.

## How it works

Needler talks to two things on your DroppedNeedle server:

- OpenSubsonic (`/subsonic/rest/*`) for the library, playback, playlists and favourites.
- The native API (`/api/v1/*`) for catalogue search, pull requests and the download queue.

Both are joined on the MusicBrainz release-group MBID, so an album you own and one you could pull are the same album in two states.

## On AI

Yes, AI has been used to build parts of this.

If something you find is off, or could be improved, you're more than welcome to submit an issue, or a PR.

## Stack

Kotlin 2.4.20, Jetpack Compose, Media3/ExoPlayer, Room, WorkManager, Hilt, OkHttp, Coil. Built with AGP 9.4 and Gradle 9.6.

## Licence

[Apache-2.0](LICENSE). You can use, change and share it, including commercially, as long as you keep the notice and say what you changed.

Needler talks to DroppedNeedle over HTTP and links none of its code, so DroppedNeedle's AGPL-3.0 doesn't apply here.

## Disclaimer

Needler is an independent client. It isn't affiliated with, endorsed by, or connected to DroppedNeedle, slskd, Soulseek, MusicBrainz, ListenBrainz, or anything else it talks to. All names and trademarks belong to their owners.

Needler hosts, stores, indexes and transmits no music of its own. It plays and manages what is already on a DroppedNeedle server that you install, run and control. Every search, request, download and stream is carried out by your server and the accounts you have connected to it, on your instructions.

The software is provided as is, with no warranty, under the Apache-2.0 licence. The author isn't liable for how it is used, or for anything that follows from using it.

You are responsible for what you search for, request, download, keep and play through it. That means holding the rights to do so, and following copyright law and the terms of every service you use, in your own country. If you don't have the right to a track, Needler doesn't give it to you.

It's meant for lawful, personal use with music you're entitled to. Anything past that is on you.

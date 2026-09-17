# Needler

An Android music player for a self-hosted [DroppedNeedle](https://github.com/DroppedNeedle/DroppedNeedle) server.

Browse and play your library, search the MusicBrainz catalogue, ask your server to pull albums you
don't own yet, and keep what you want playable offline. Phone and tablet, with lock-screen controls,
home-screen widgets, Android Auto and a Wear OS companion.

## Status

Pre-implementation. The requirements and architecture are settled; no app code yet.

| | |
| --- | --- |
| [REQUIREMENTS.md](REQUIREMENTS.md) | Requirements and architecture, including the DroppedNeedle API surface Needler consumes |
| [design/](design/) | 21 screens as HTML and PNG, plus a PDF — the UI source of truth |

## How it works

Needler talks to two surfaces on your own DroppedNeedle server:

- **OpenSubsonic** (`/subsonic/rest/*`) for library browsing, playback, playlists and favourites
- **The native API** (`/api/v1/*`) for catalogue search, pull requests and the download queue

Both are joined on the MusicBrainz release-group MBID, so an album you own and an album you could
pull are the same thing in two states. See [REQUIREMENTS.md](REQUIREMENTS.md) for detail.

## Stack

Kotlin, Jetpack Compose with adaptive layouts, Media3/ExoPlayer, Room, WorkManager, Hilt.
Minimum SDK 26, target SDK 36.

## Disclaimer

Needler is an independent client for a DroppedNeedle server that you install, configure and operate
yourself. It is not affiliated with, endorsed by or sponsored by DroppedNeedle, slskd, Soulseek,
MusicBrainz, ListenBrainz or any other service it talks to.

Needler does not host, store, index, search for or transmit any music. Every search, download and
stream is performed by your own server and the services you have connected to it, under your control
and your accounts.

You are solely responsible for what you search for, download and play, for holding the rights to do
so, and for complying with copyright law and the terms of every service you use.

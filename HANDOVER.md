# Handover — 2026-09-24

Scratch file for the next session. Untracked, and safe to delete once the branch builds.

**State: ~230 files changed, uncommitted, on `main`.** ~26,300 new lines of Kotlin across 127 new
files, 41 of them tests, plus 13,822 insertions into existing files. Nothing is committed, so
`git status` and `git diff` are the source of truth for all of it.

**Only `:core:domain` has ever been compiled.** There is no Android SDK on this machine
(`ANDROID_HOME` and `ANDROID_SDK_ROOT` unset, no `local.properties`,
`./gradlew :core:design:compileDebugKotlin` → "SDK location not found"). `./gradlew :core:domain:test`
passes: **100 tests, 0 failures**. Everything else — Compose, Room, Media3, Hilt, Play services — is
hand-checked only.

## Do these first, in order

1. **Build on a machine with an SDK.** Expect a substantial compile round. Do not judge the work
   before this; the errors that got through are the ones no scripted check catches — type inference,
   Compose scope rules, KSP validation, Hilt graph errors.
2. **Two breaks are already known:**
   - **Paged repository reads are half-applied.** That agent was stopped mid-edit. It had modified
     `ArtistDao`, `FavouriteDao`, `PinDao`, `PlaylistDao` and five domain repository interfaces, and
     was partway through `DefaultPlaylistRepository` / `DefaultPinRepository`. The interfaces may
     declare members the implementations do not have.
   - **Room schema v2 JSON is missing on purpose.** `NeedlerDatabase` is at version 2 with a
     hand-written `MIGRATION_1_2` for a stream-override table, but `schemas/.../2.json` was
     deliberately not hand-written — its `identityHash` cannot be computed by hand and a
     plausible-looking wrong one is worse than an absent one. Room writes it at compile time;
     regenerate and commit it.
3. **Record screenshot baselines** — roughly 60 new, and most existing ones now differ (artwork draws
   a letter on a derived tint, list rows gained a play control, the format label gained a chip,
   Settings gained rows, Connect lost four placeholders).
4. **Reconcile `REQUIREMENTS.md`.** Untouched by all of this and now wrong in three known places: its
   "Remaining" table, the §Streaming wording for the two-option control that no longer exists, and
   the retention paragraph ("Fixed in code and verified by CI; not yet exercised on a device") that
   the device run falsified.

## Landed (16 workstreams)

Pulls blank titles · search relevance/dedupe/paging/IME · library (9 items) · player (7 items incl.
the landscape P0) · playlists + genres · licences + diagnostics · Android Auto browse tree · Wear
phone bridge + crate · Wear on-device audio · two widgets + refresh wiring · transcode resolver ·
audio-cache write path · three-mode stream quality + per-item overrides · scaffold insets ·
cache-refusal diagnostics · false low-space warning.

## Not done

- Cast / Bluetooth output enumeration — agent stopped before writing anything. The picker still
  lists only "This device".
- `requestHistory` / `wantedRequests` lanes — agent stopped mid-edit; `PullsFormat`, `PullsScreen`,
  the V1 DTOs and four test files are partially changed.
- `:app` wiring, ~85%: the artist route needs optional name/subtitle args, and `onOpenArtist` is
  still unwired for Now Playing (the outer graph cannot reach the inner `artist/{id}` — it needs the
  pop-and-hand-off shape the notification destinations already use) and for the tablet sidebar.

## Top three unverified assumptions, per the agents that made them

1. Room composite primary key whose first column is a **converted enum** (stream overrides). Enum
   *parameters* in `@Query` have precedent here; an enum in a composite PK does not.
2. `Asset.createFromFd` descriptor lifetime on Wear — the descriptor is closed after `putDataItem`
   resolves. If Play services needs it open until the *transfer* completes, watch tracks silently
   never arrive.
3. `MIGRATION_1_2` DDL matching Room's generated schema byte-for-byte — column order, `NOT NULL`s,
   backtick quoting, `PRIMARY KEY(...)` placement.

## One device experiment still worth running

Independent of all the above, and it settles the zero-bytes cache report: play a track **on Wi-Fi**
and watch `adb shell run-as app.needler ls -l files/audio/`.

- nothing appears → refusal at open (transcode flag, missing mirror size, or the free-space floor)
- a `.streaming` file grows then vanishes with no `.audio` → refusal at commit; that defect is fixed
- an `.audio` file appears → the write path works

The leading explanation is a bug now fixed: on v0.0.8 an MP3-320 library on the MP3-320 rung over
mobile data resolved to `Transcoded`, and transcoded bytes are correctly never retained.

## Two environment gotchas, both cost time today

- Heredocs in this shell silently collapse `\\` to `\`. Use the Write tool for anything with escapes.
- The Kotlin block-comment trap runs **both** ways. `/*` inside a KDoc opens a nested comment that
  swallows the rest of the file; `*/` — as in a literal `**Play**/**Shuffle**` — closes one early so
  the remainder parses as code. One of each was found in this codebase today.

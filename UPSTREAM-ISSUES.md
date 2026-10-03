# Issues for DroppedNeedle

Things Needler cannot fix on the client, found while building and testing against a real server
(`music.mumfordhome.com`, user `tmumford`). Each entry says what was observed, how, and what Needler
does about it meanwhile.

Evidence is tagged: **Observed** on a device or against the live server, **Read from source** in
Needler's own code, or **Documented** already in REQUIREMENTS.md.

---

## 1. The server returns different release-group IDs for music it already holds

**Severity: high.** This is the root of the most visible defect in the app.

**Observed**, 2026-10-03, on device. Searching "dido" returns four albums the user owns, listed from
the OpenSubsonic mirror as `In library`, and then returns **the same four albums again** from
`/api/v1/search` — each carrying `in_library: false` and so each offered with a Pull button.

The two lanes disagree about identity. `/subsonic` reports one release-group MBID for an album and
`/api/v1/search` reports a different ID for what is evidently the same record, then answers its own
`in_library` question against the ID it just sent — and says no. The server is telling the client it
does not hold an album it is simultaneously serving.

Likely cause, not confirmed: the catalogue lane is returning a **release** MBID where the library
holds a **release group**, or MusicBrainz search is picking a different release group from the one
the import matched.

**Not repairable on the client by key normalisation** — both sides are bare, trimmed MBIDs and the
join is sound. Needler now falls back to matching on case-folded title plus artist to suppress the
duplicate, which is a heuristic and will fail on legitimately same-titled records.

**What would fix it upstream:** have `/api/v1/search` resolve `in_library` against the same identity
the library import stored, or return both the release and release-group MBIDs so a client can join on
whichever it holds.

## 2. Artists are matched by name rather than to MusicBrainz

**Severity: high.** Removes a whole feature for affected artists.

**Observed**, 2026-10-03, on device. The artist page for Dido — a well-known artist with an
unambiguous MusicBrainz entry — renders:

> Your server matched this artist by name rather than to MusicBrainz, so there is no full
> discography to look up. Everything you own by them is listed above.

With no artist MBID there is no key to request a discography with, so "show me this artist's other
records" is impossible for that artist no matter what the client does.

**What would fix it upstream:** resolve artists to MusicBrainz IDs at import, or expose an endpoint
that resolves a name to candidate artist MBIDs so a client can offer the user a choice.

## 3. Items held for review can only be released from the web interface

**Severity: medium.** Blocks the app's main loop in the state the user is actually in.

**Observed**, 2026-10-03. The server reports **490 items held for review**, and all 35 of the user's
active pulls read "a source needs picking on the server". There is no API to list those items, see
what is holding them, or pick a source — so the app can only report the count and send the user to
DroppedNeedle's own web interface.

The practical effect is that requesting music through Needler does not complete. Every pull stops at
the same place and the user must leave the app to continue.

**What would fix it upstream:** an endpoint to list held items and select a candidate source, even a
minimal one. Needler already models `AWAITING_SOURCE_REVIEW` and draws it.

## 4. Pull counts are not reconcilable across endpoints

**Severity: low, but it makes the app look broken.**

**Observed**, 2026-10-03, within two minutes on one device: the app's badge and header said **35 in
progress**, the held-for-review banner said **490**, and an aggregate notification said **360 pulls
need attention**. These are plausibly four different populations, but nothing in the API names which
population each count covers, so a client cannot label them unambiguously.

**What would help upstream:** document what each count includes, or return counts that partition
cleanly.

---

## Already documented, listed for completeness

These are recorded in REQUIREMENTS.md and Needler works around them. They are noted here in case
upstream wants them.

| Behaviour | Needler's workaround |
|---|---|
| **`setRating` validates and persists nothing.** A rating UI would report success and silently lose input. | Only binary favourites via `star`/`unstar` are offered. No star-rating control exists anywhere. |
| **`getArtists` accepts `ifModifiedSince` and ignores it**, returning the full artist list every time. A delta built on it is a full sync in disguise. | Delta sync uses `getIndexes` specifically. `getArtists` is kept only for the Artists browse screen, where the whole list is wanted. |
| **`GET /api/v1/downloads` has no `total` and no `total_pages`**, so paging is blind — a full page means there *may* be another. | Infinite scroll rather than a page count. Pruning only deletes a row when the walk ended on a short page **and** `requests/active` answered, so one timeout cannot delete every parked approval. |
| **`/api/v1/requests/active` and `/requests/wanted` do not page at all**; only `/requests/history` takes `page`, `page_size`, `status`, `sort`. | Handled per endpoint; history is the only one with a page control. |
| **Compat endpoints marked `partial`** in the server's capability matrix — `getAlbumList2`, `getRandomSongs`, `star`, `scrobble` — may deviate in edge cases. | Tested against the real server rather than trusted from the protocol. |

## Observations, not complaints

- **`ArtistReleasesDto.warming`** is genuinely useful and Needler was misusing it: an empty response
  while `warming` is true means "still resolving upstream", not "this artist has nothing". That was
  a client bug, now fixed. The flag being there is what made the fix possible.
- **The transcoding ceiling** (1 per user, 2 global) is clearly documented and Needler is built
  around it: rungs are ceilings rather than requests, nothing transcodes upward, and a re-encode
  that would save no bytes is skipped so it does not burn a scarce slot.

# Dr Animo — Android anime client (APK)

**Dr Animo APK** — a native Android anime client: offline-first catalogue plus **native HLS playback**
(no WebView). *Only Dr Animo can be hidden under the black hole.*

[**Download the latest APK**](https://github.com/1HP2DIE/Dr-Animo/releases/latest) ·
[Website](https://1hp2die.github.io/Dr-Animo/) · v1.9.0 · 26.9 MB · Android 7.0+ · MIT

```
anikotoapi.site ──► OkHttp (2.2 s throttle) ──► Room (source of truth) ──► Compose UI
                                                     │
episode embed URL ──► StreamResolver (AES) ──► master.m3u8 ──► Media3/ExoPlayer (Referer on every request)
```

## Status

| Piece | State |
|---|---|
| Build | `./gradlew assembleDebug` → **BUILD SUCCESSFUL**, AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35 / minSdk 24 |
| Unit tests | 23 tests, 0 failures (`NormalizeTest`, `DtoMappingTest`) |
| Device smoke test | Emulator API 36: catalog sync → home grid → detail → **video playing** (verified frame + subtitle) |
| APK | `app/build/outputs/apk/debug/app-debug.apk` (~24 MB debug) |

## Download

Signed APK: [Releases](https://github.com/1HP2DIE/Dr-Animo/releases) — grab the latest
`Animo-<version>.apk` and sideload it (`adb install -r Animo-<version>.apk`). Requires Android 7.0+
(minSdk 24).

Dr Animo is distributed **APK-only** — there is no Play Store listing, no advertising SDK and no
telemetry. The package id is `com.animo.app`; the current build is 1.9.0 (versionCode 13),
SHA-256 `4cb2acfd9beb02bcda8ac23c20c3b00125a3ce25183eec0599ddc98ac4d737e6`.

## Install

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## How it works

1. **Sync** — `CatalogSyncer` walks `/recent-anime?per_page=100` (95 pages), upserts into Room,
   re-fetches only series whose `updated_at` moved. Rate gate: ≥2.2 s between requests
   (API allows 60 / 120 s), abort-and-sleep when `x-ratelimit-remaining < 5`.
2. **Read** — every screen reads Room `Flow`s. Cold start renders with zero network.
3. **Play** — `MegaplayStreamResolver`:
   embed page (referer) → parse `data-id` → `getSources` (`X-Requested-With`) → AES-256-CBC decrypt →
   `master.m3u8` → ExoPlayer with `OkHttpDataSource` carrying
   `Referer: https://megaplay.buzz/` on the playlist, the variant **and every segment**.

Measured facts that shaped this (see `research/RESOLVER_SPEC.md`):

- The CDN accepts **only** `Referer: https://megaplay.buzz/`; `anikototv.bz` / `anikoto.cz` get 403 there
  (they only pass the embed-page allowlist). Device-verified.
- The `?token=` HMAC signature is **not required** — referer alone returns 200/206.
- Segment extensions rotate (`.jpg`, `.html`, `.js`, `.css`) but are always MPEG-TS. Never infer.
- Soft-410 page (HTTP 200 + "Error Code: 410") means *referer rejected*, not *deleted*.

## Layout

```
app/src/main/java/com/animo/app/
  domain/{model,repo}      contracts (Anime, Episode, PlayableStream, repositories)
  data/remote              OkHttp API, DTOs, mappers, provider chain
  data/local               Room entities/DAOs, FTS4 search, schema export
  data/sync                catalog syncer, episode backfill, WorkManager
  data/repo                repository implementations
  player/                  resolver + Media3 engine + media session service
  ui/                      Compose screens (home, search, detail, player)
di/ServiceLocator          manual DI (no Hilt)
research/                  verified protocol docs, provider matrix, crawler, probes
```

## Known limitations

- Search is local-only (the API has no search endpoint); first sync fills it.
- ReAnime / AnimEgg providers are documented stubs — Anikoto is the only live source.
- Dub is offered only where `embed_url.dub` exists (the API omits it for most episodes).
- This app consumes a third-party API that redistributes copyrighted streams. That is a legal risk
  outside the code: expect domain rotation, dead mirrors and takedowns.

## Docs

- `docs/ARCHITECTURE.md` — layers, ownership, sync model
- `research/DATA_CONTRACT.md` — field-by-field API contract + Room DDL
- `research/RESOLVER_SPEC.md` — measured 4-way header matrix, TTL probe, Kotlin resolver
- `research/PROVIDER_MATRIX.md` — which scrapers are alive, which to port next
- `research/EXTRACTOR_SPECS.md` — megaplay/vidtube/streamwish/kwik recipes and traps
- `research/STACK.md` — locked versions and build traps

## FAQ

**What is Dr Animo APK?** A native Android anime client shipped as an APK — offline-first catalogue
in Room, native HLS playback on Media3/ExoPlayer, no WebView renderer.

**Is Dr Animo free?** Yes: free APK, MIT-licensed source, no ads and no telemetry.

**Which Android versions?** Android 7.0 (API 24) and newer; the manifest compiles against API 35.

**How do I install it?** Download `Animo-1.9.0.apk` from Releases and sideload it
(`adb install -r Animo-1.9.0.apk`), or open the APK on the device with "install unknown apps" allowed.

**Does it work offline?** Browsing, search and progress are local; video playback needs a connection.

**Is it on the Play Store?** No — APK-only distribution from this repository's releases.

**Why the tagline?** *Only Dr Animo can be hidden under the black hole.*

## Publishing a release (version tracking)

The app polls `https://1hp2die.github.io/Dr-Animo/update.json`; the landing page renders the same
document as its version history. Both are generated from GitHub releases, so a release reaches
installed builds without shipping new code first.

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts` and add a `Release(...)` entry to
   `app/src/main/java/com/animo/app/ui/changelog/Changelog.kt` (that file is the user-facing copy).
2. Tag and publish — the release body is generated from the changelog, not retyped:

   ```bash
   git tag v1.10.0 && git push origin v1.10.0
   gh release create v1.10.0 release/Animo-1.10.0.apk \
     --notes-file <(python3 tools/changelog_to_release_notes.py 1.10.0)
   ```

3. `.github/workflows/update-feed.yml` runs on `release: published`, regenerates
   `docs/update.json` and the `<!-- versions -->` block in `docs/index.html`, and commits them.

A release body is parsed for `## Headline`, `## Released`, and `- NEW: … / - IMPROVED: … /
- FIXED: …` bullets; anything else (install snippets, hashes) is ignored. `versionCode` is read from
  `app/build.gradle.kts` **at the tag**, never guessed from the tag text.

In the app, `UpdateCenter` fetches the feed (6 h cache), compares `versionCode`, and `UpdatePrompt`
shows the in-app notice once per version: headline, changelog, and a download link. "Later" silences
that version; the changelog screen merges feed entries above the bundled ones.

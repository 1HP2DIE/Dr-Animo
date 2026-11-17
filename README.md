# Animo

Native Android anime client — offline-first catalog + **native HLS playback** (no WebView).

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

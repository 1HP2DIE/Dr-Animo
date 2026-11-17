# Animo — Native Android Anime App

Offline-first native Android client for the Anikoto API with a **native stream resolver**
(no WebView, no iframe) feeding ExoPlayer/Media3.

Source API: `https://anikotoapi.site`
Playback chain: `embed page → getSources → AES-256-CBC → master.m3u8 → HLS`

---

## Non-negotiables

1. **The app never trusts `embed_url` as a playable URL.** It is an HTML page. The app must
   run the resolver chain and play the resulting `.m3u8` itself.
2. **Rate limit 60 req / 120s per IP** (`x-ratelimit-*`). All catalog traffic goes through the
   sync layer with throttling + backoff. UI never hits the network directly for lists — it reads Room.
3. **Every media request needs a `Referer`.** Playlist *and* every segment. Missing referer =
   Cloudflare 403 / soft-410 page.
4. `per_page=100` is the bulk sync unit. 9,404 series ≈ 95 pages.
5. Cache-or-crash: any screen must render from local DB when offline.

---

## Layered architecture

```
ui/  (Compose)  ──►  domain/usecase  ──►  data/repo  ──►  data/local (Room, source of truth)
                                             │
                                             └──►  data/remote (OkHttp + kotlinx.serialization, throttled)
                                                        ▲
                                          data/sync (WorkManager)
player/  ──►  StreamResolver (OkHttp + AES)  ──►  Media3 ExoPlayer (OkHttpDataSource w/ Referer)
```

---

## File ownership (build agents — no overlap)

### Agent A — Build & Data Core
```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml
app/build.gradle.kts, app/proguard-rules.pro, local.properties
app/src/main/AndroidManifest.xml
app/src/main/res/values/{strings,themes}.xml, app/src/main/res/xml/*.xml
app/src/main/java/com/animo/app/AnimoApp.kt, MainActivity.kt
app/src/main/java/com/animo/app/di/ServiceLocator.kt
app/src/main/java/com/animo/app/data/remote/**
app/src/main/java/com/animo/app/data/local/**
app/src/main/java/com/animo/app/data/sync/**
app/src/main/java/com/animo/app/data/repo/**
```
Owns the only place that knows the JSON field names and the Room schema.

### Agent B — UI
```
app/src/main/java/com/animo/app/ui/**
app/src/main/java/com/animo/app/ui/theme/**
```
Consumes repository + resolver interfaces only. Never imports `data.remote` or `data.local`.

### Agent C — Player & Resolver
```
app/src/main/java/com/animo/app/player/**
app/src/main/java/com/animo/app/ui/player/**   (player screen only)
```
Consumes `StreamResolver` + `PlayableStream`; owns Media3 wiring and all referer headers.

### Orchestrator (pre-written contracts — do not modify without asking)
```
app/src/main/java/com/animo/app/domain/model/*.kt      Anime, Episode, PlayableStream, SyncState
app/src/main/java/com/animo/app/domain/repo/*.kt       AnimeRepository, StreamRepository interfaces
app/src/main/java/com/animo/app/player/StreamResolver.kt  (interface only)
```

---

## Screen flow

1. **Home** — recent/popular grid from Room, infinite scroll, pull-to-refresh triggers sync.
2. **Search** — instant local FTS over title/alternative/titles/native + genre/studio/year/type filters.
3. **Detail** — poster, meta, genres, synopsis, episode grid (sub/dub badges), continue-watching.
4. **Player** — ExoPlayer, auto-resolve, quality selector from master playlist, resume position,
   error → re-resolve once → mark episode unavailable.

---

## Sync model

| Job | Trigger | Requests |
|---|---|---|
| Catalog page sync | nightly / manual | ≤95 (per_page=100) |
| Detail sync | page sync sees `updated_at` newer than local | 1 per changed series |
| Episode backfill | on detail open, or background, budgeted | 1 per series |

Throttle: ≥1.2 s between requests, abort if `x-ratelimit-remaining < 5` and sleep 90 s.
Backoff on 429/403 with exponential jitter. Room is the source of truth.

---

## Resolver contract

`StreamResolver.resolve(embedUrl: String): Result<PlayableStream>`

Steps (see `research/RESOLVER_SPEC.md` for the verified protocol):
1. GET embed page with `Referer` → parse `data-id` (file id).
2. GET `getSources` with `X-Requested-With: XMLHttpRequest` + `Referer` + cachebuster.
3. AES-256-CBC decrypt `enc` → `{"file": ".../master.m3u8"}`.
4. Return master URL + the exact headers needed by ExoPlayer.

Errors: `Dead` (soft-410 / 404), `Blocked` (403), `Network`, `Parse`.

---

## Build

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug
```
JDK 21 · Gradle 8.11 · AGP 8.7.3 · Kotlin 2.0.21 · compileSdk per `research/STACK.md`.

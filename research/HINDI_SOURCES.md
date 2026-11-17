# Hindi-dub sources — reverse-engineered (verified 2026-11-17)

The user's memory was right: the Hindi-dub source is **`toono.app`**, and the working recipe lives in
`/home/grandpa/me/code/mixed/toon.app/` (not `python/`). Everything below was re-verified live today.

---

## 1. toono.app — P0 (Hindi dub, 1256 of 1299 titles)

**What it is:** WordPress site ("TOONo") indexing toons/anime with Indian language dubs.
The local catalog `site/data.json` (1299 titles) has a `c` category array containing language tags:

| tag | titles (of 1299) |
|---|---|
| **Hindi** | **1256** |
| Telugu | 42 |
| Tamil | 41 |
| dub (word) | 3 |

Example row (`site/data.json[0]`):
```json
{"t":"#1 Happy Family USA","y":"series","s":"1-happy-family-usa",
 "p":"https://image.tmdb.org/t/p/w342/…jpg",
 "u":"https://toono.app/series/1-happy-family-usa/",
 "c":["Animation","Comedy","Hindi"],"g":["Animation","Comedy"],"yr":"2025","dur":"m"}
```
Series page confirms: `Hindi – Tamil – Telugu`, `hindi/">Hindi`, `Hindi Watch/Download`.

### 1.1 Catalog enumeration (from `site/tools/sync-catalog.js`)
`/series/page/N/` **is not really paginated** (repeats page 1); `sitemap.xml` only carries the newest
250. The complete enumeration is the theme's "Load more" AJAX:

```
POST https://toono.app/wp-admin/admin-ajax.php
  action=action_search
  vars={"page":N,"post_type":"series"…}   + nonce (from any page's inline settings)
→ 10 titles per page, every page a distinct slice
```
Fallback: HTML harvest of `/series/` and `/movies/`.

Title page parse (`tools/lib/toono.js`): poster, synopsis, rating, `c` categories, `g` genres,
`firstEpisodeUrl()` → first `/episode/<slug>/` link.

### 1.2 Stream chain (MEASURED today, 2026-11-17)

```
[1] GET https://toono.app/episode/1-happy-family-usa-1x1/          → 200 (24,871 B)
      contains data-episode-id="39519" (+ data-lmt JWT, 300 s expiry, data-url base64)
[2] GET https://toono.app/?trembed=1&trid=39519&trtype=2
      Referer: <episode page>                                      → 200 (422 B)
      body contains → https://argon.razorshell.space/embed/U2vgc4LiRSp0lBM
[3] GET https://argon.razorshell.space/embed/U2vgc4LiRSp0lBM       → 200 (5,689 B)
      contains  _juicycodes("…"+"…"+"…")   (string-concat obfuscated playlist)
                /assets/players/jwplayer/player.js?cb=…
[4] decode: concatenate the quoted args of _juicycodes(...), run player.js in a JS sandbox with
      jwplayer/XHR/DOM stubs, capture jwplayer().setup({playlist|sources}).file  (see site/server.js
      decodePlaylist(), lines ~77–147)
[5] GET {master}.m3u8                                              → 200, 3 variants
      360p 667 kbps · 720p 1.58 Mbps · 1080p 3.89 Mbps   (#EXT-X-VERSION:4)
[6] GET {variant}.m3u8                                             → 200, 167 segments
[7] GET {segment}                                                  → 206 video/mp4 (real MPEG-TS, 47 40 11 10)
```

CDN host: `wfbrcpgcgcwjrqh.groovy.monster` (`*.groovy.monster`, juicy.codes infra). Playlist says
`#POWERED-BY:JUICYCODES <https://juicy.codes>`.

### 1.3 The CDN gate — headers only (Android-friendly)

| request | result |
|---|---|
| plain curl (no headers) | **403** |
| Referer only | **403** |
| UA + `Accept-Language` + Referer (full set) | **200 master / 200 variant / 206 segment** |
| saved master URL from 2026-10-28, full headers | **403** ← URL had expired |

Required on **every** hop including segments:

```
User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/156.0.0.0 Safari/537.36
Accept-Language: en-US,en;q=0.6
Referer: https://argon.razorshell.space/
Accept: */*
```

No cookies, no auth, no TLS/JA3 impersonation needed → **OkHttp `OkHttpDataSource` with
`setDefaultRequestProperties` is sufficient** (same pattern as the megaplay source).

⚠️ **Master URLs are time-limited.** A URL resolved 20 days ago returns 403 while a fresh one is 200.
Always resolve at play time; cache ≤15 min (see `research/RESOLVER_SPEC.md` §5).

### 1.4 Local tooling already built (reuse, don't rewrite)
`/home/grandpa/me/code/mixed/toon.app/site/server.js` (575 lines) already implements
`resolveStream(episodeUrl)` + `/api/stream?u=<episode page>`, `/api/episodes?u=<title page>`,
`/proxy?u=<cdn url>`, a 5-minute stream cache, and an hourly catalog sync. Verified working today:
`GET localhost:8899/api/stream?u=https://toono.app/episode/1-happy-family-usa-1x1/` → 200 JSON with
`m3u8` + `proxied`.

### 1.5 Android port notes
- Resolver needs a JS engine for `_juicycodes` + `player.js`. Options, cheapest first:
  1. **Port the decode to Kotlin**: the payload is a base64-ish string split into quoted chunks —
     concatenate, then the jwplayer setup object is recoverable with a small parser (the playlist
     `file` appears in the decoded string). Try this first; verify against a live payload.
  2. Fallback: **QuickJS/`quickjs-android`** or `WebView.evaluateJavascript` with a stubbed
     `jwplayer` (heavier, but exact).
  3. Fallback: run the existing Node resolver on a tiny VPS and call it (adds a hop, avoids JS in app).
- Playlist/segment headers go on one `OkHttpDataSource.Factory` per playback (same rule as megaplay).
- Catalog: enumerate via the admin-ajax `action_search` + nonce; 10 items/page is slow, so sync
  once into Room and diff (same pattern as the Anikoto syncer).
- IDs: toono slug (`1-happy-family-usa`) + `data-episode-id` (numeric term id). Join to our catalog by
  **title/slug**; no MAL/AniList id on the site, but `poster` is a TMDB path which can be reverse-mapped.

---

## 2. AnivoFlix — P1 (code-verified, liveness NOT run)

Path: `/home/grandpa/me/code/python/anivoflix/`. Streaming PWA + Telegram bots, **Firestore** backed.

- Config (`src/anivoflix/config.py:32-44`): Firebase project `anivoflix`, public API key, db `(default)`.
- Access: REST quota is dead, so the code reimplements the web SDK's Firestore
  **`Listen/channel`** webchannel with stdlib only:
  `https://firestore.googleapis.com/google.firestore.v1.Firestore/Listen/channel`
  (POST handshake `VER=8&RID=1&CVER=22`, then `GET …&RID=rpc&SID=…&AID=0&CI=0&TYPE=xmlhttp`).
  Headers: Chrome 126 UA + `X-Goog-Api-Client: gl-js/ fire/10.12.2`.
- Collections: `movies` (public), `ads_settings`; `series/anime/users/hidden_links` are rules-denied.
- Docs fields: `title, type, genre, year, quality, poster_url, backdrop_url, video_url, download_url,
  telegram_url, languages[], …`; episodes at `movies/{id}/seasons/{sid}/episodes`.
- **Hindi is real**: 316 of 555 titles carry `"Hindi"` in `languages[]` (catalog on disk in
  `osint_dump/`). Tamil/Telugu/Malayalam/Bengali also present (counts not measured).
- Playback: **not** HLS — short-link players `https://dramawatch.strp2p.com/#<key>` (+`&dl=1`),
  Telegram bot deep links, some `pixeldrain.com/u/…`.
- Anime slice is tiny (5 titles) → useful as a **Hindi movie/drama** source, not for anime.
- No local media library (`downloads/` has only a log).

---

## 3. Other python projects (checked)

| project | what it is | Hindi-dub relevance |
|---|---|---|
| `cyber-dl-api` | yt-dlp + FastAPI wrapper (`POST /api/download`) | none — generic downloader |
| `zishan`, `test`, `tg-bots/*` | bots; "hindi" appears only as language labels | none |

---

## 4. Verified status codes (today)

| URL | status |
|---|---|
| `https://toono.app/series/1-happy-family-usa/` | 200 (28,649 B) |
| `https://toono.app/episode/1-happy-family-usa-1x1/` | 200 (24,871 B) |
| `https://toono.app/?trembed=1&trid=39519&trtype=2` | 200 (422 B → embed URL) |
| `https://argon.razorshell.space/embed/U2vgc4LiRSp0lBM` | 200 (5,689 B, `_juicycodes`) |
| fresh master `.m3u8` (fingerprint headers) | 200 (2,253 B, 3 variants) |
| fresh variant `.m3u8` | 200 (109,288 B, 167 segments) |
| segment (Range 0-2047) | **206** `video/mp4` |
| segment without Referer | 403 |
| segment without browser UA | 403 |
| stale master (Oct 28 URL) | 403 |

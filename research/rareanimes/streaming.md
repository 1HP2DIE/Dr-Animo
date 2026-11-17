# rareanimes.foo — Streaming chain (measured 2026-11-17)

**Bottom line: the CDN is *not* signature-locked to a browser.** A freshly resolved URL works from
plain `curl` with three headers. The 403s seen earlier were **expired URLs** (the blob is
time-limited). Same host and same player stack as `toono.app` — one decoder covers both.

## 1. rareanimes.foo itself has no player

| probe | result |
|---|---|
| `GET /` | 200 — SEO gateway, no listings |
| `GET /home/` | 200 — 420 links, all season posts |
| `GET /sitemap.xml` | 200 — 273 URLs |
| `GET /naruto-season-1-hindi-dubbed-episodes-download-hd/` | 200 (438 KB) — **no `<iframe>`, no `<video>`, no `.m3u8`, no `jwplayer`, no `_juicycodes`** |
| `/wp-json/wp/v2/types` | only `post/page/attachment/...` — no `episode`/`video` CPT |
| `watch|play|player|stream|cdn|api.rareanimes.foo` | **NXDOMAIN** |

Episodes are inline links to a link-protector; the player lives on a third-party gate.

## 2. The chain (4 hops + 1 interstitial)

```
[1] GET https://www.rareanimes.foo/<season-slug>/                     200
      episode rows → <a href="https://codedew.com/zipper/?url=<opaque>">
      (one row per language/quality: HubCloud, pixeldrain, mega.nz, argon …/downlead/<id>)

[2] GET https://codedew.com/zipper/?url=<opaque>                     200  "Step 1 of 3 — Scanning Link Safety…"
      5-second countdown, then the button (data-href) navigates:
        GET …/zipper/?url=<opaque>&ad_done=1                         200  "Step 2 of 3"
      → next hop to /multiquality/

[3] GET https://codedew.com/multiquality/?url=<opaque>                200
      <iframe id="playerIframe" src="https://argon.razorshell.space/embed/<videoId>">
      language chip: "Hindi" · quality chips: 1080p FHD / 720p HD / 360p SD

[4] GET https://argon.razorshell.space/embed/<videoId>                200 (~115 KB)
      <script>_juicycodes("…"+"…"+"…")                               8.4 KB, string-concat obfuscated
      window.juicyData = {"data":{"token":"…","video":"<videoId>",
                                  "routes":{"ping":"/api/videos/<blob>/ping"},…}}
      scripts: jwplayer 8.23.2 · player.js?cb=… · provider.hlsjs.js?cb=…
      → jwplayer().getPlaylist()[0].sources[0].file  =  master .m3u8

[5] GET https://wfbrcpgcgcwjrqh.groovy.monster/stream/<448-char blob>.m3u8     200
      #EXTM3U · 3 variants (360 SD / 720 HD / 1080 FHD) · tracks: thumbnails sprite .vtt only

[6] GET …/stream/variant/<blob>.m3u8                                  200
[7] GET …/stream/<blob>  (Range)                                      206  MPEG-TS
```

Playlist host = segment host (`wfbrcpgcgcwjrqh.groovy.monster`) — unlike megaplay's rotating
segment hosts. No `.mpd`, no direct `.mp4`, no subtitle tracks (sprite VTT only).

## 3. CDN gate — headers, not signatures (measured)

| probe on a **fresh** master URL | headers | result |
|---|---|---|
| (a) bare | none | **403** |
| (b) Referer only | `Referer: https://argon.razorshell.space/` | **403** |
| (c) UA + AL + Referer | Chrome UA + `Accept-Language: en-US,en;q=0.6` + Referer | **200** |
| segment | same three headers + `Range` | **206** `video/mp4` |
| segment without Referer | UA only | **403** |
| stale URL (resolved > hours ago) | full header set | **403** `Invalid signature` |

403 body is the JUICY NGINX page:
```
server: JUICY NGINX · x-powered-by: JUICY CODES · x-contact-email: help@juicy.codes
body: "Forbidden | RareAnimesIndia — 403 - Forbidden — Invalid signature."
```
`access-control-allow-origin: https://argon.razorshell.space` and a `laravel_session` cookie are set,
but **neither is required** — the request that returns 200 sends no cookie.

**Conclusion:** the "Invalid signature" text is about the **URL blob's own expiry**, not a
browser-bound signature. Verified on the same host with a URL minted seconds earlier from Node:
`t+0 → 200`, `t+61 s → 200`, `t+182 s → 200`. A URL captured 20 days earlier → 403 with the same
header set. So: time-limited blob, header-gated CDN, portable to OkHttp.

## 4. Exact header set for Android (OkHttp)

```kotlin
private val HLS_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) HeadlessChrome/156.0.0.0 Safari/537.36",
    "Accept-Language" to "en-US,en;q=0.6",
    "Referer" to "https://argon.razorshell.space/",
    "Accept" to "*/*",
)
// one factory per playback — same rule as megaplay (research/RESOLVER_SPEC.md §3)
val ds = OkHttpDataSource.Factory(client).setDefaultRequestProperties(HLS_HEADERS)
```
`Origin` is **not** required (CORS header is permissive; no preflight for media).

## 5. Refresh policy

Measured lifetime: **≥ 182 s** (3 min) for a blob minted server-side; a 20-day-old blob is 403.
Exact ceiling not probed (would need a 1–60 min sweep).

The blob is minted per embed load, so:
- resolve **immediately before** playback (never at catalogue-sync time),
- cache ≤ 10 min,
- on any 403/410 during playback → re-run hop 3→7 once (cheap: 2 requests + decode),
- never persist a master URL in the database.

## 6. What the app must implement (two extra pieces vs Anikoto)

1. **Interstitial handler** for `codedew.com`: a 5 s timer + `data-href` jump; replicate as
   `delay(5_000)` then follow the href (or read `data-href` and skip the delay — it is client-side only).
2. **`_juicycodes` decoder**: identical to toono.app. Reuse `decodePlaylist()` from
   `/home/grandpa/me/code/mixed/toon.app/site/server.js:77` as the reference implementation —
   the embed pages are byte-structurally the same (same `_juicycodes`, same `player.js?cb=`,
   same `juicyData`, same CDN host). Port it to Kotlin once and both Hindi providers work.

## 7. Fragility ranking

| what | likelihood | detection |
|---|---|---|
| blob TTL shortened | medium | first 403 mid-playback → re-resolve |
| `_juicycodes` payload format change | medium | decode fails → alert, keep old build working |
| CDN host rename (`*.groovy.monster`) | medium | master 403 while page 200 → re-resolve once, then error |
| new interstitial step on codedew | medium | `Step N of 3` text changes |
| UA allowlist tightened (HeadlessChrome) | low-medium | 403 on all header sets → try full Chrome UA variants |
| moving to TLS/JA3 gating | low | all header sets 403 even fresh → only then consider WebView/impersonation |

## 8. Not verified

- Exact blob TTL (only ≥ 61 s measured; longer window in progress)
- Whether an English/original sample differs (agent sampled only the Hindi Naruto episode)
- Mirror/server tabs on the episode page (one iframe observed per episode)
- Subtitle tracks (none found; sprite thumbnails only)

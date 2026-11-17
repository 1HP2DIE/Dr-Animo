# VidNest — surface recon (verified 2026-11-17)

`https://vidnest.fun/` is **not** a streaming site. It is an embed-API product: the homepage *is* the
documentation (no catalogue grid, one demo iframe, no `/embed` route — the iframe path **is** the
player path).

| Fact | Evidence |
|---|---|
| HTTP/2 200, Cloudflare, `x-powered-by: Next.js`, `x-nextjs-prerender: 1`, `x-nextjs-cache: HIT` | `vidnest_home.headers` |
| `vary: rsc, next-router-state-tree, next-router-prefetch, next-router-segment-prefetch` | same |
| 43,106 B homepage, 1 iframe: `<iframe src="/movie/324857" class="player-iframe glass-box">` | `vidnest_home.html` |
| Stated catalogue: 115K+ movies · 79K+ episodes · 5.3K+ animes | homepage copy |
| Backend is a **Go service** (`404 page not found` = net/http default) behind Cloudflare at `new.vidnest.fun` | API probes |

## 1. Route map — every player route returns 200, even for a bogus id

| URL | Status | Bytes |
|---|---|---|
| `/` (docs) | 200 | 43,106 |
| `/movie/324857` | 200 | 15,963 |
| `/tv/94997/1/1` | 200 | 16,988 |
| `/anime/154587/1/{sub,dub,hindi}` | 200 | ≈16,600 |
| `/animepahe/16498/1/sub` | 200 | 16,250 |
| `/movie/999999999`, `/movie/abc`, `/tv/94997/99/99` | **200** | ≈16,000 |

The shell is static per route *shape*: no per-title metadata, no JSON-LD, no `<title>` override. An
invalid id is only detectable **after** the backend call, so a provider implementation must treat the
API response as the source of truth and ignore the HTML entirely.

404s (Next.js 404 page, 404 status): `/movie`, `/tv`, `/anime`, `/animepahe`, `/tv/<id>` (missing
season/ep), `/embed/*`, `/sitemap.xml`, `/manifest.json`, `/docs`, `/api`.

**RSC is not a data channel**: `GET /movie/324857?_rsc=abc12` with header `RSC: 1` → 200
`text/x-component`, 9,586 B, containing only route params (`"c":["","movie","324857"]`) + generic
site JSON-LD.

## 2. Identity model — TMDB for film/TV, AniList for anime

```
/movie/{TMDB_ID}
/tv/{TMDB_ID}/{SEASON}/{EPISODE}
/anime/{ANILIST_ID}/{EPISODE}/{lang}
/animepahe/{ANILIST_ID}/{EPISODE}/{lang}
```

`lang` is a free-form path segment, lowercased client-side with **no allowlist**
(`(lang || "sub").toLowerCase()`), and `hindi` is an accepted token in the docs and reaches the API
identically to `sub`/`dub`. Whether a provider honours it is unverified (all anime probes 502 during
the test window).

Documented examples: `/movie/324857` (Spider-Verse), `/tv/94997/1/1` (HotD S1E1),
`/anime/154587/1/hindi`, `/animepahe/16498/1/sub` (AoT).

## 3. There is NO lookup/search API

Probed `new.vidnest.fun/`, `/search`, `/api/search` → 404. Across 11 downloaded JS chunks (~1.3 MB)
there is **zero** occurrence of `/api/`, `?q=`, `search`, `/trending`, `/popular`, `_next/data`, or
any list endpoint. The only egress hosts are metadata/subtitle services:
`api.themoviedb.org/3` (movie/tv metadata, public key `61e2290429798c561450eb56b26de19b`),
`api.ani.zip/mappings?anilist_id=`, `sub.wyzie.io/search`, `sub.vdrk.site/v2/{movie,tv}`.

**Consequence:** VidNest is id-in / stream-out. Animo must own the title→TMDB/AniList resolution
(same slot Anikoto occupies today, minus the search API).

## 4. The real API — `https://new.vidnest.fun`

### Nonce gate: client-generated, unsigned, trivially reproducible

Every path (including `/`) returns `403 {"error":"missing or invalid nonce"}` without `_n`.

```js
const KEY = "K3p8vQ2mX9rL5wZ0fN7bG1sH4tJ6dC9_y8S3wM1nQ5pL2", BASE = "https://new.vidnest.fun";
// append to any BASE url:
_n = base64url(crypto.getRandomValues(new Uint8Array(16)))
```

16 random bytes, base64url, **nothing signed, no timestamp, no HMAC**. A locally minted
`_n=gKtxdtK_ZXTWkHNUMP8Ccg` (22 chars) was accepted immediately.

### CORS — fully open (present even on 404s)

```
access-control-allow-origin: *
access-control-allow-headers: Content-Type, Authorization, X-Requested-With, X-Stream-Nonce, X-Client-Version
access-control-allow-methods: GET, POST, PUT, PATCH, DELETE, OPTIONS
access-control-max-age: 86400
```

### Provider paths

| Provider key | Movie | TV |
|---|---|---|
| catflix | `/yflix/movie/{id}` | `/yflix/tv/{id}/{s}/{e}` |
| filxer | `/rogflix/movie/{id}` | `/rogflix/tv/…` |
| gama | `/vidzee/movie/{id}` | `/vidzee/tv/…` |
| lamda | `/ophim/movie/{id}` | `/ophim/tv/…` |
| beta | `/flixhq/movie/{id}` | `/flixhq/tv/…` |
| alfa | `/onehd/movie/{id}` | `/onehd/tv/…` |
| prime | `/rpmvid/movie/{id}` | `/rpmvid/tv/…` |
| zeta | `/nextgencloudfabric/movie/{id}` | `/nextgencloudfabric/tv/…` |
| ophim | `/klikxxi/movie/{id}` | `/klikxxi/tv/…` |
| sigma | `/hollymoviehd/movie/{id}` (double `/movie`) | `/hollymoviehd/tv/{id}/{s}/{e}` |
| hexa | `/vidlink/movie/{id}` | `/vidlink/tv/…` |
| delta | `/allmovies/movie/{id}` | `/allmovies/tv/…` |

Anime (`{anitaku, aniwave, megaplay}`, default order `aniwave, megaplay, anitaku`):

| Provider | Path |
|---|---|
| megaplay | `/animehub/{anilistId}/{ep}/{lang}` |
| aniwave | `/aniwave_hls/{anilistId}/{ep}/{lang}` |
| anitaku | `/hianime/anime/{anilistId}/{ep}/{lang}/hd-2` |

### Response envelope — versioned AES-GCM (`x-encrypted: v2`)

```
{"d":"<base64url>","n":"<nonce>","v":2}
key = SHA-256( n + "|" + KEY )      // KEY is the JS constant above
AES-GCM: iv = d[0..12), ciphertext||tag = rest
```

Unencrypted/legacy responses pass through unchanged. **Verified live**: `/ophim/tv/94997/1/1?_n=…`
→ 200, 3,095 B, decrypted to

```json
{"streams":[{"language":"Original Audio","quality":"1080p","type":"hls",
  "url":"https://m5f-cf.money-7.workers.dev/hls/eyJoIjp7IkNvb2tpZSI6IkNsb3VkRnJvbnQtUG9saWN5…"}]}
```

That `url` is a **proxy-wrapped HLS URL carrying its own base64 JSON header blob** — `Cookie`
(full `CloudFront-Policy`/`Signature`/`Key-Pair-Id`), `Referer`, `User-Agent`
(`com.community.oneroom/… (Android 13; Redmi)`), `x-mb-token`. Own headers ship with the payload, so
playback may not need any extra request headers at all — *unverified, that is the play-chain agent's
job*.

Per-provider shapes differ (`streams[]` / `sources[]` / `url` / `data.stream.playlist` /
`status:"success"`), so a parser must be tolerant.

## 5. Provider health (single sweep, one nonce, same minute)

| Endpoint | Result |
|---|---|
| `/ophim/tv/94997/1/1` | **200**, encrypted, valid |
| `/yflix/movie/324857` | 502 |
| `/animehub/154587/1/sub` | 502 |
| `/animehub/154587/1/hindi` | 502 |

502 is the backend's generic upstream-failure page: the scraper/CDN failed, not a rejection. This is
a **flaky mirror farm** — usable only behind fallback (the UI does exactly that: `trySwitchServer`
walks the provider map until one answers). Per-provider success rate is unmeasured.

## 6. Access model

No Cloudflare challenge on any path (`cf-mitigated` absent, no `cf_clearance`, no JS challenge).
No rate-limit headers observed, no 429 provoked (low volume — unproven). `/robots.txt` is
Cloudflare's managed content-signals boilerplate with **no `User-agent:` block, no `Disallow:` and no
`Content-Signal:` value** — by its own rule (c) the operator "neither grants nor restricts".

## 7. Verdict

- **(c) rejected — not iframe-only.** Nothing is JS-gated: the nonce is random bytes and the payload
  is AES-GCM with a key derived from the response's own nonce and a constant in the public bundle.
  Pure Kotlin/OkHttp is enough (`MessageDigest("SHA-256")` + `AES/GCM/NoPadding`), no WebView.
- **(b) partially verified.** One full chain (TMDB TV id → decrypted JSON → 1080p HLS) proven, but
  3 of 4 sampled providers 502'd in the same minute → treat as a **fallback tier**, not a primary.
- **(a) no.** No title→id lookup. Animo must map titles itself.

**Recommended slot:** `embed_url`-supplier tier (like Anikoto, minus search), feature-flagged, with
the 12-provider fallback loop and a ~15-minute cache, and only after a per-provider hit-rate probe
clears ~50%.

### Open questions for the next agent

1. Per-provider hit rate over a few minutes (decides primary vs fallback).
2. `hindi` vs `sub` vs `dub` on a provider that is up — compare decrypted `streams[].language`.
   **This decides whether VidNest matters for the Hindi track.**
3. Whether the signed CloudFront URL plays with plain OkHttp using the payload's own header blob.

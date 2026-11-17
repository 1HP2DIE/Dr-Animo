# Source matrix — 2 Hindi + English/original

Target shape for Animo: **two Hindi-dub sources** and the rest **English/original**.

| # | Source | Language | Hindi coverage | Alive | Crypto/JS | Stream | Priority |
|---|---|---|---|---|---|---|---|
| 1 | **toono.app** | **Hindi** (+Tamil/Telugu) | **1256 / 1299 titles** | ✅ verified today | needs JS decode (`_juicycodes` + player.js) | HLS 3 variants, header-gated CDN | **P0 — Hindi #1** |
| 2 | **rareanimes.foo** ("Rare Toons India") | **Hindi** | being measured by 3 crawl agents | ✅ 200 (Cloudflare, WP edge cache) | same juicy.codes family per CDN error page | HLS, same header gate (to confirm) | **P0 — Hindi #2** |
| 3 | **anikotoapi.site** | English/original | none | ✅ | AES (solved) | HLS via megaplay | P0 — already shipped |
| 4 | **reanime.cz** | English/original | none | ✅ JSON API, no crypto | direct/JSON | **P1 next** |
| 5 | **animegg.org** | English/original | none | ✅ JSON search, no crypto | direct mp4 | P1 |
| 6 | anizone.to | English/original | none | ✅ | none | single multi-audio | P2 |
| 7 | animeonsen.xyz | English/original | none | ✅ | none | DASH only | P2 |
| — | animepahe.pw | — | — | ❌ CF 403 | — | — | skip |
| — | animetsu.* | — | — | ❌ DNS/API dead | — | — | skip |
| — | anivoflix (python) | Hindi movies/dramas | 316/555 titles | not probed | Firestore webchannel | short-link players | P2 (not anime) |

## Hindi #1 — toono.app (verified today)

```
GET /series/<slug>/                            200
GET /episode/<slug>/                           200   → data-episode-id
GET /?trembed=1&trid=<id>&trtype=2             200   → https://argon.razorshell.space/embed/<vid>
GET <embed>                                    200   → _juicycodes("…") + player.js
decode → master.m3u8                           200   360p / 720p / 1080p
GET variant.m3u8                               200   167 segments
GET segment (Range)                            206   video/mp4 (MPEG-TS)
```
Required on **every** hop incl. segments:
`User-Agent: …HeadlessChrome/156.0.0.0 Safari/537.36` · `Accept-Language: en-US,en;q=0.6` · `Referer: https://argon.razorshell.space/`
Master URLs are **time-limited** → resolve at play time, cache ≤15 min.

## Hindi #2 — rareanimes.foo (in progress)

```
curl -I https://www.rareanimes.foo/
HTTP/2 200 · server: cloudflare · cf-cache-status: HIT
cf-edge-cache: cache,platform=wordpress
<title>Rare Toons India - All Animes and Toons Hindi Download
generator: Redux 4.5.18
```
Same `RareAnimesIndia` CDN brand seen on the groovy.monster 403 page → almost certainly the same
player chain. Three agents are measuring listing / streaming / download+risk now
(`research/rareanimes/{listing,streaming,download_and_risk}.md`).

## Integration shape in the app

```kotlin
interface AnimeProvider { fun search(q); fun episodes(id); suspend fun resolve(ep); }

// language tag on Anime so the UI can filter Hindi vs original
enum class AudioLanguage { HINDI, ENGLISH, ORIGINAL, MULTI }

providerChain =
  if (wantHindi) listOf(ToonoProvider, RareAnimesProvider)      // 2 Hindi
  else           listOf(AnikotoProvider, ReAnimeProvider, AnimeEggProvider)  // English/original
```
Catalog stays in one Room table; a `languages` column (CSV) plus `hasHindi` index lets the UI offer a
"Hindi only" switch without a second database.

## Rules

1. Metadata sync is cheap; **resolve streams at play time** (both Hindi CDNs expire URLs).
2. Never bulk-crawl download endpoints — catalog + episode pages only.
3. Hindi sources need a JS decode step; keep it isolated behind one resolver class so a payload
   change breaks one file, not the app.
4. Respect the crawl budgets in `research/rareanimes/download_and_risk.md`.

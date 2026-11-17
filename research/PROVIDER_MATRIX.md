# Animo — Provider Matrix (live liveness verified 2026-11-17)

Source: `research/vendor/animestream` (Flutter) + live `curl` probes (browser UA, `-L`, 20 s,
1.5 s spacing, ≤2 requests/host).

## Liveness

| Host | Status | Final host | Verdict |
|---|---|---|---|
| `anikototv.to` | 200 | → `anikototv.bz` | DOMAIN-CHANGED |
| `anikototv.bz` | 200 (CF) | — | ALIVE |
| `anikoto.cz` | 200 | — | ALIVE |
| `anikotoapi.site` | 200 | — | ALIVE (our base) |
| `megaplay.buzz` | 200 | — | ALIVE (player/CDN) |
| `reanime.to` | 200 | → `reanime.cz` | DOMAIN-CHANGED |
| `reanime.cz /api/v1/search` | 200 JSON, real data | — | ALIVE |
| `flixcloud.cc` | 200 | — | ALIVE |
| `anizone.to` | 200 (71 KB) | — | ALIVE |
| `anizone.to /anime?search=` | 200 (csrf token) | — | ALIVE |
| `www.animegg.org` | 200 (56 KB) | — | ALIVE |
| `www.animegg.org /search/auto/?q=` | 200 **JSON** | — | ALIVE |
| `kwik.cx` | 200 | — | ALIVE |
| `vidtube.site` | 200 | — | ALIVE |
| `pahe.nekostream.site` | 200 | — | ALIVE |
| `mapper.nekostream.site /api/mal/1/1/<ts>` | 200 JSON | — | ALIVE |
| `vidcache.net:8161` | 200 JSON | — | ALIVE (thumbs) |
| `animepahe.pw` | **403** "Just a moment…" | — | **CLOUDFLARE-BLOCKED** |
| `animepahe.pw /api?m=search` | **403** | — | BLOCKED |
| `auth.animeonsen.xyz` POST `/oauth/token` | 200 (JWT works) | — | ALIVE |
| `api.animeonsen.xyz /v4/search/…` | 404 = no match | — | ALIVE (DASH only) |
| `animetsu.live` / `.cc` | DNS NXDOMAIN | — | DEAD |
| `animetsu.to` | domain for sale | — | DEAD |
| `animetsu.lol` | 200 but new SPA, `/v2/api/*` returns HTML | — | **API GONE** |
| `swiftstream.top` | DNS NXDOMAIN | — | DEAD |

## Decision matrix

| Provider | Alive | Real dub split | Crypto needed | Coverage | **Priority** |
|---|---|---|---|---|---|
| **Anikoto** (`anikotoapi.site` → megaplay) | ✅ | yes (`embed_url.dub`) | AES only (solved) | largest catalog (9,404) | **P0 — base** |
| **ReAnime** (`reanime.cz`) | ✅ | **yes, `dataType: "dub"` servers** | none (JSON + JWT regex) | full TV + **AniList id on every series** + filler flag | **P0** (2nd source) |
| **AnimEgg** (`www.animegg.org`) | ✅ | **yes, `.btn-dubbed` / `data-version=dubbed`** | none (regex `var videoSources=[…]`) | long-running classics, direct per-quality mp4 | **P0** (fallback) |
| **AniZone** (`anizone.to`) | ✅ | claimed (`hasDub:true` hardcoded — unverified) | none (`x-data` JSON.parse) | multi-audio single stream | **P1** |
| **AnimeOnsen** (`api.animeonsen.xyz`) | ✅ | no dub branch | none (**DASH** `manifest.mpd`) | small catalog | **P2** — needs `media3-exoplayer-dash` |
| **AnimePahe** | ❌ CF 403 | yes | Kwik RC4-ish | moot | **skip** |
| **Gojo / Animetsu** | ❌ dead | — | — | moot | **skip** |

## Request recipes (from Dart source, `file:line`)

### ReAnime — port first
```
GET {base}/api/v1/search?limit=20&typeahead=1&q={q}         // reanime.dart
GET {base}/api/flix/{anilistId}/{ep}                        // reanime.dart:175-179
    Referer: {base}/watch/{animeId}?ep={n}
    Origin:  {base}
    → servers[] with dataType: "dub" | "sub", dataLink
GET {dataLink}  → /e/{aid}
GET https://flixcloud.cc/d/{aid}/__data.json → fileId(UUID), token(JWT), fetch{N}.flixcloud.cc base, resolution
GET {base}/download/{fileId}/progress?token=   // poll ≤3× @500ms until status=="ready"
GET {base}/download/{fileId}?token=&file=…mkv
```
Dub subtitle preference: Signs/Songs/Forced. Requires **MKV** support in the player.

### AnimEgg — port second
```
GET https://www.animegg.org/search/auto/?q={q}   // JSON: name, url, thumbnailUrl (//vidcache.net:8161/…)
GET {seriesUrl}                                  // div.newmanga children reversed → episodes
GET {watchUrl}                                   // a[data-version=subbed|dubbed][data-id]
GET {embedUrl}                                   // regex: var videoSources = (\[[\s\S]*?\]);  → mp4 `file`
    then baseUrl + e['file'], with Referer: {embedUrl}
```
No crypto, explicit sub/dub flag, per-quality direct files → **best resilience**.

### AniZone
```
GET https://anizone.to/anime?search={q}      // HTML, csrf-token
GET https://anizone.to/anime/{slug}          // Alpine x-data JSON.parse('...') → episodes
```
Single multi-audio stream; `hasDub` is hardcoded true in the Dart — **do not trust it**.

### AnimeOnsen (DASH only)
```
POST https://auth.animeonsen.xyz/oauth/token   → access_token (JWT)
GET  https://api.animeonsen.xyz/v4/search/{q}  Authorization: Bearer …
GET  …/{id} → manifest.mpd + ASS subtitles
```
Requires `androidx.media3:media3-exoplayer-dash`; ASS subtitle rendering needs a custom renderer.

## Data-quality wins these providers give us

1. **ReAnime carries `anilist_id` per series** → joins straight into our Anikoto-backed Room catalog
   (`ani_id` from Anikoto is a mixed MAL/AniList id space, so this is the clean join key).
2. **ReAnime has a real `dubbed` boolean + `is_filler` per episode** (Anikoto: `is_dub` is a nullable
   episode *count*, absent on 73.7% of rows).
3. **AnimEgg gives per-quality direct mp4 URLs** — no AES step, no `getSources` round-trip, immune to
   crypto rotation.
4. **ReAnime returns episode thumbnails**; Anikoto returns `thumbnail: null` for every episode.
5. **Bug to fix if we port Anikoto's Kiwi fallback**: Dart reads `['Kiwi-Stream-']` but the live
   `mapper.nekostream.site` JSON key is `"Kiwi"` (`anikoto.dart:360`) — silent dead fallback.

## Integration rule

`AnimeProvider` interface in the app:

```kotlin
interface AnimeProvider {
    val id: String                      // "anikoto" | "reanime" | "animegg"
    suspend fun search(q: String, page: Int = 1): List<ProviderHit>
    suspend fun episodes(seriesId: String): List<ProviderEpisode>
    suspend fun resolve(episode: ProviderEpisode): PlaybackSource
}
```

Priority chain per episode: `anikoto → reanime (if anilist_id known) → animegg`. First non-`dead`
result wins; cache which provider worked per series so later episodes skip the chain.

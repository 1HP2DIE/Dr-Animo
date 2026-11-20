# MovieLinkBD source — reverse-engineering notes

Status: **fixture-locked** (captures taken 2026-11-20). This document and the fixtures in
`app/src/test/resources/movielink/` are the contract for the Kotlin resolver. Real hostnames are
**not** in this file or in the fixtures: they live in `secrets/providers.properties` (git-ignored).
All examples below use:

| Placeholder | Meaning |
|---|---|
| `https://example.invalid` | rotating HTML/content host (catalogue, detail, `/p/` shell) |
| `https://watch.example.invalid` | HLS host (`/m/…`, `/hls/…`, `/s/…`, `/vseg/…`) |
| `https://files.example.invalid` | progressive MP4 host (`/p/…`) |
| `https://cdn.example.invalid` | poster/image bucket |

---

## 1. Pipeline

```
catalogue (/page/N, /search)
        │  server-rendered card HTML
        ▼
detail  /movie/<id> | /series/<id> | /anime/<id> | /drama/<id>
        │  <iframe id="mlbdHdFrame" src="/p/<token>">
        ▼
player  /p/<token>            (same origin as detail; text/html)
        │  inline  window.__PD2 = { … }
        ├── master : https://watch.example.invalid/m/<token>   (HLS master, VOD)
        │        └── https://watch.example.invalid/hls/<token> (media playlist)
        │                 ├── #EXT-X-MAP      … /s/<token>.jpg  (init, fMP4)
        │                 └── #EXT-X-BYTERANGE… /s/<token>.jpg  (segments, fMP4)
        └── mp4    : https://files.example.invalid/p/<token>    (progressive, Range)
```

The player shell is not a separate origin: the iframe `src` is relative, so the HTML host serving
`/p/<token>` is the same rotating host that served the detail page. Only the media URLs point at
`watch.*` / `files.*`.

`<a data-dl>` download buttons on the detail page decode to **relative** `/file/<token>` paths on
the content host. Their redirect target (direct file vs another gate) is not resolved yet.

---

## 2. Endpoints observed

| Method | URL | Status | Content-Type | Notes |
|---|---|---|---|---|
| GET | `/` | 302 → rotating host, then 200 | `text/html` | home is a listing (`/page/1`); 456 KB |
| GET | `/page/1` … `/page/N` | 200 | `text/html` | ~30 cards/page; pages are server-rendered |
| GET | `/search?q=` | 200 | `text/html` | form results, same card markup |
| GET | `/movie/<id>` | 200 | `text/html` | 280 KB capture; `ld+json` + iframe |
| GET | `/series/<id>` | 200 | `text/html` | 468 KB capture; details + episode grids |
| GET | `/p/<token>` | 200 | `text/html; charset=utf-8` | 13 KB (movie) / 102 KB (80 eps) shell |
| GET | `watch…/m/<token>` | 200 | **`image/jpeg`** | HLS master playlist, 1,088 B movie / 313 B series |
| GET | `watch…/hls/<token>` | 200 | **`image/jpeg`** | media playlist, 131,057 B (movie 1080p) |
| GET | `watch…/s/<token>.jpg` | 200/206 | `image/jpeg` | fMP4 init + media, addressed by `BYTERANGE` |
| GET | `files…/p/<token>` | **206** | `video/mp4` | `Content-Range: bytes 0-1023/4017446458` |

IDs are opaque: `/movie/mKs_fch7eans37owkjaffuam55f634`, `/series/mKs_gregvwgeclfg5y3c2mqworsrly`
(`mKs_` + 25 base32-ish chars). Detail pages also carry a readable canonical/og:url
(`/movie/gondhal-2026`) that is **not** the href used by listings — resolve the href, not the
canonical.

---

## 3. Header matrix

The site sits behind Cloudflare. A desktop Chrome UA is sufficient for the content host; the
`.one` marketing hub is WAF-blocked from at least one datacenter IP (403 “Sorry, you have been
blocked”, Ray ID returned) even with a full Chrome header set. Treat the hub as unavailability,
not as a parser failure.

| Target | UA | Referer | Cookies | Result |
|---|---|---|---|---|
| content host `/`, `/page/N`, `/movie`, `/series` | desktop Chrome | not required | not required | 200 |
| content host `/p/<token>` | desktop Chrome | detail page (sent; not A/B tested) | not required | 200 |
| `watch` host `/m`, `/hls`, `/s` | desktop Chrome | **required**, any `example.invalid`-class site origin | not required | 200 |
| `watch` host, no Referer | desktop Chrome | — | — | **403** |
| `watch` host, foreign Referer | desktop Chrome | `https://evil.example/` | — | **403** |
| `files` host `/p` | desktop Chrome | site Referer (tested) | not required | 206 |

Minimal request that works for media:

```http
GET /m/<token> HTTP/2
Host: watch.example.invalid
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36
Referer: https://example.invalid/
```

The failure body is a **playlist**, not an HTML error page:

```
HTTP/2 403
content-type: image/jpeg
content-length: 31

#EXTM3U
#EXT-X-ERROR:forbidden
```

Both “no Referer” and a wrong-origin Referer produced exactly these 31 bytes (including the
trailing LF). The Referer
allowlist is therefore checked by origin/host suffix, not by a per-token signed header. Because the
403 still advertises `image/jpeg`, a client must check the status **and** reject an
`#EXT-X-ERROR:` playlist; do not rely on Content-Type.

Both tested site origins worked as Referer: the marketing hub and the rotating content host. Use a
configurable slot (see `secrets/providers.properties`), defaulting to the host that served the
detail page.

---

## 4. `window.__PD2`

Inline, single assignment, inside the `/p/` HTML:

```js
window.__PD2 = { … };
```

### Keys

| Key | Type | Value |
|---|---|---|
| `master` | string | HLS master URL for the default episode |
| `title` | string | display title, e.g. `"Gondhal (2026)"` |
| `year` | string | usually `""` |
| `langs` | string | comma-ish label, e.g. `"Hindi"`, `"Bangla"` |
| `poster` | string | `https://cdn.example.invalid/poster_….jpg` |
| `subs` | array | top-level subtitles (empty in both captures) |
| `audio` | array | top-level audio tracks (empty) |
| `episodes` | array | **always present**, movie = 1 entry |
| `startEp` | number | index of default episode (`0` movie, `79` for the last of 80) |
| `id` | string | internal numeric id (`"16880"`, `"13085"`) |
| `mp4` | string | top-level MP4; `""` in both captures (use episode `mp4`) |
| `share` | string | empty |
| `home` | string | empty |

Episode object:

| Key | Type | Notes |
|---|---|---|
| `label` | string | `"Movie"`, `"Episode 1221"`, `"Episode 1263-1268"` |
| `number` | number | start number; movie = `0` |
| `num_end` | number | end number for grouped episodes, else same as `number` |
| `master` | string | per-episode HLS master |
| `mp4` | string | per-episode progressive MP4 |
| `season` | number | `0` for movies |
| `audio` | array | empty in captures |
| `subs` | array | `{label, lang, url}`; `url` ends in `.jpg` (same disguise as segments) |

### Redacted sample (shape preserved, tokens elided)

```json
{
  "master": "https://watch.example.invalid/m/<token:232>",
  "title": "Gopal Bhar (2026)",
  "year": "",
  "langs": "Bangla",
  "poster": "https://cdn.example.invalid/poster_<hash>.jpg",
  "subs": [],
  "audio": [],
  "episodes": [
    {
      "label": "Episode 1221",
      "number": 1221,
      "num_end": 1221,
      "master": "https://watch.example.invalid/m/<token:232>",
      "mp4": "https://files.example.invalid/p/<token:216>",
      "season": 13,
      "audio": [],
      "subs": []
    },
    {
      "label": "Episode 1326",
      "number": 1326,
      "num_end": 1326,
      "master": "https://watch.example.invalid/m/<token:798>",
      "mp4": "https://files.example.invalid/p/<token:216>",
      "season": 14,
      "audio": [],
      "subs": [
        {
          "label": "English",
          "lang": "en",
          "url": "https://watch.example.invalid/vseg/<token>.jpg"
        }
      ]
    }
  ],
  "startEp": 79,
  "id": "13085",
  "mp4": "",
  "share": "",
  "home": ""
}
```

Token lengths are **not** fixed (`/m/` observed at 232, 714 and 798 chars; `/p/` at 216). Do not validate
by length; treat everything after the last `/` as opaque base64url (no padding).

For a movie, `episodes` has exactly one element with `label:"Movie"`, `number:0`, `num_end:0`,
`season:0`; top-level `mp4` is empty and `episodes[0].mp4` holds the file.

The series **detail page** grid is not 1:1 with `__PD2`: the capture has 81 cards but only 80
unique `data-player-episode-id` values (episode 1305 appears twice), while `__PD2.episodes` has 80
entries. De-duplicate by `number`/id rather than trusting the card count.

---

## 5. Stream details

### HLS master (`/m/<token>`)

```
#EXTM3U
#EXT-X-VERSION:7
#EXT-X-INDEPENDENT-SEGMENTS
#EXT-X-STREAM-INF:BANDWIDTH=2900000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
https://watch.example.invalid/hls/<token>
#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720,CODECS="avc1.640028,mp4a.40.2"
… (two 720p entries, different tokens, same bandwidth/resolution)
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480,CODECS="avc1.640028,mp4a.40.2"
…
```

- Served as `image/jpeg`; body is plain UTF-8 M3U8.
- **No `EXT-X-KEY`** in the master or in either media playlist tested (movie + series).
- The two 720p variants are byte-distinct tokens with identical codecs/resolution; pick the first
  or de-duplicate by resolution.

### Media playlist (`/hls/<token>`)

```
#EXTM3U
#EXT-X-VERSION:7
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-INDEPENDENT-SEGMENTS
#EXT-X-TARGETDURATION:20
#EXT-X-MAP:URI="https://watch.example.invalid/s/<token>.jpg",BYTERANGE="1467@0"
#EXTINF:8.917,
#EXT-X-BYTERANGE:4492712@32291
https://watch.example.invalid/s/<token>.jpg
#EXTINF:10.000,
#EXT-X-BYTERANGE:6718067@4525003
https://watch.example.invalid/s/<token>.jpg
…
```

- fMP4 (init segment + `#EXT-X-BYTERANGE`), **one URL reused** for every segment via byte ranges.
  The segment and init files are `.jpg` and return `image/jpeg`; bytes are MP4 boxes.
- Movie 1080p playlist: 131,057 B; series 1080p: 23,007 B / 102 segments.
- No `EXT-X-KEY`; no `EXT-X-DISCONTINUITY` observed.

### Progressive MP4 (`/p/<token>`)

```
HTTP/2 206
content-type: video/mp4
content-range: bytes 0-1023/4017446458
accept-ranges: bytes

00000000: 0000 001c 6674 7970 6973 6f35 0000 0200  ....ftypiso5....
```

- `ftyp` brand `iso5`; total length ~3.74 GB for the movie capture, hence Range is mandatory.
- Range support is advertised by `accept-ranges: bytes`; do not download the whole file to probe
  it. (HEAD was not tested; use a 1–16 byte ranged GET.)
- Whether this endpoint 302s to another CDN was **not** tested (see open questions).

### `mp4` vs HLS

| | progressive MP4 | HLS |
|---|---|---|
| URL | `files…/p/<token>` | `watch…/m/<token>` |
| Content-Type | real `video/mp4` | `image/jpeg` (both playlist and segments) |
| Adaptive | no | 1080p/720p/480p |
| Seeking | HTTP Range | `EXT-X-BYTERANGE` + playlist index |
| Startup | offset of moov (unknown; may be at end) | init segment + first segment |
| Best use | offline/download, single-quality playback | streaming, quality switching, resume |
| Risks | 3.7 GB, Range must be honoured, moov position | content-type disguise, no `.m3u8` extension, referer gate |

Both are gated the same way (site Referer). A resolver should expose both and let the UI choose:
HLS for streaming, MP4 for “download”.

---

## 6. Download anchors (`data-dl`)

Detail pages render download buttons as:

```html
<a href="#" data-dl="<base64>" data-ds="<hex>" data-dr="300"
   class="btn btn-dark btn-user mb-2 mx-2" role="button" rel="nofollow noopener">
  … Download [1080p • 3.7 GB] …
</a>
```

`data-dl` is **not** a URL. The page's own inline script decodes it with an LCG-XOR stream:

```js
function dec(b, s, r) {
  try {
    var x = parseInt(s, 16) >>> 0, i;
    for (i = 0; i < r; i++) x = (Math.imul(x, 1103515245) + 12345) >>> 0;
    var raw = atob(b), o = "", j;
    for (j = 0; j < raw.length; j++) {
      x = (Math.imul(x, 1103515245) + 12345) >>> 0;
      o += String.fromCharCode(raw.charCodeAt(j) ^ ((x >>> 16) & 255));
    }
    return o;
  } catch (e) { return ""; }
}
// url = dec(data-dl, data-ds, parseInt(data-dr, 10) || 0)
```

Kotlin port (same arithmetic, 32-bit wrap):

```kotlin
fun decodeDl(b64: String, seedHex: String, rounds: Int): String {
    var x: Int = seedHex.toLong(16).toInt()          // parseInt(...,16) >>> 0
    repeat(rounds) { x = x * 1103515245 + 12345 }     // Int arithmetic wraps
    val raw = Base64.getDecoder().decode(b64)
    val out = CharArray(raw.size)
    for (i in raw.indices) {
        x = x * 1103515245 + 12345
        out[i] = ((raw[i].toInt() and 0xFF) xor ((x ushr 16) and 0xFF)).toChar()
    }
    return String(out)
}
```

Decoded value is a path such as `/file/<token>` on the content host, e.g.
`/file/BNYPG6pe5x04awuB-<…>`. Build `https://<content-host>/file/<token>`; the redirect/file
behaviour is unresolved.

Anchor counts in the captures:

- **Movie (`Gondhal`): 4 anchors**, all with the full `data-dl` + `data-ds` + `data-dr` triad
  (480p, 720p, 1080p, “Best Quality”). `data-dr` was `300` in all four.
- **Series (`Gopal Bhar`): 147 anchors** in the full page, all with the full triad. The fixture
  keeps 131 after dropping the combined-season ZIP grid.
- **No anchor missing `data-dr` was observed.** An earlier raw count of `data-dl` (10 movie /
  153 series) included occurrences inside JS selector strings (`a[data-dl]`, `[data-dl]`), which
  inflated the numbers and produced a false “4 anchors lack data-dr” reading. Still, a parser
  should skip anchors without all three attributes rather than throw.

---

## 7. What is still unknown

1. **Token lifetime.** No expiry field is visible in `__PD2` or in the playlist. The same master
   token worked across the capture window. Whether `/m/`, `/hls/`, `/s/`, `/p/` tokens rotate on a
   schedule, per IP, or never is untested. Plan for 403 `#EXT-X-ERROR:forbidden` to mean
   “refresh detail → player → new token”, not only “bad Referer”.
2. **`/file/<token>` redirects.** Decoded download links are relative paths; status, redirect
   chain, final host, and Range support are untested.
3. **MP4 redirects.** The `files…/p` request answered 206 directly in this capture, but a
   302/303 to another CDN is plausible. Follow redirects and re-apply the Referer only to the
   first hop (do not forward it cross-origin blindly).
4. **Referer allowlist breadth.** Two site origins were accepted. Whether any `example.invalid`
   host, the exact hub, or only a fixed list is accepted is unknown. Make the slot configurable.
5. **Player page without Referer.** `/p/<token>` was fetched with a detail-page Referer; no
   no-Referer control was run.
6. **Subtitle/CDN host split.** Some `subs[].url` values sit on the `watch` host with a `.jpg`
   suffix; their real format (WebVTT) and CORS headers are untested.
7. **Hub availability.** The marketing hub returned Cloudflare 403 from this network. The rotating
   content host worked. A resolver should start from a cached/known content host and follow the
   302, not assume the hub is reachable.

---

## 8. How a Kotlin resolver maps to this

```
ProviderKey slots (secrets/providers.properties)
  catalog/base host  ──► OkHttp, desktop UA, follow redirects
        │
        ├─ listing: GET {base}/page/{n} ──► Jsoup
        │     div.movie-card  →  a[data-mlbd-click-ad="movie-card"][href]
        │     title from a.title, quality/language/type from span.*
        │
        ├─ detail: GET {base}{href} ──► Jsoup
        │     h1                            (display title)
        │     script[type=application/ld+json]  (schema.org Movie/TVSeries)
        │     iframe#mlbdHdFrame[src]       → player path
        │     a[data-dl][data-ds][data-dr]  → decodeDl() → /file/<token>
        │     series: #mlbdHdEps button[data-episode-number]
        │             #mlbdEpisodeDownloadGrid .ep-card[data-episode-number]
        │
        ├─ player: GET {base}{iframeSrc} ──► regex
        │     window\.__PD2\s*=\s*(\{.*?\})\s*;?\s*</script>
        │     kotlinx.serialization → Pd2(master,title,…,episodes)
        │     choose episode: startEp or user selection (number/num_end/season)
        │
        ├─ HLS: master → pick variant (1080p first, then 720p, then 480p)
        │     ExoPlayer HlsMediaSource + custom DataSource.Factory
        │     add UA + Referer to every request (playlist, init, segment)
        │     accept image/jpeg bodies; do not branch on Content-Type
        │
        └─ MP4: files…/p/<token> with Range, UA + Referer
              progressive MediaSource; do not buffer 3.7 GB
```

Implementation notes:

- **Parse the href, not the canonical.** Listings use opaque `/movie/mKs_…`; `og:url` may show a
  readable slug that returns 404 or a different route.
- **HTML is server-rendered.** No JS execution is needed for listing/detail. `/p/` is a shell plus
  one JSON assignment; a regex or a small script-tag scan is enough, but anchor the regex to
  `window.__PD2` and the closing `</script>`.
- **`episodes` is always an array.** Movies have one pseudo-episode (`number: 0`), so the same UI
  path works for both. `startEp` is an index, not an episode number.
- **Use `num_end` for grouped labels** (`"1263-1268"`); display `label`, but key playback by
  `number`.
- **Referer is mandatory for media.** Send a configurable site-origin Referer on `watch` and
  `files` requests; without it the server returns 403 with a 31-byte `#EXT-X-ERROR:forbidden`
  body. Treat any `#EXT-X-ERROR` as a failed resolve and refresh the player token.
- **Content-Type lies.** Playlists and `.jpg` segments are `image/jpeg`; MP4 is `video/mp4`.
  Detect playlists by `#EXTM3U`, segments by URL/`EXT-X-BYTERANGE`, not by MIME.
- **Range/seek.** HLS seeking is playlist-driven; MP4 needs `Range` and the server advertises
  `accept-ranges: bytes`.
- **Tokens are opaque.** No length/format validation; keep `_`/`-`, no padding assumptions.
- **Host rotation.** The content host changes (302 from an older host). Pin the final URL per
  session, but re-resolve on 403/404.

---

## 9. Capture evidence (working files, not committed)

Raw captures were saved under `/tmp/opencode/ml/`. The **`image/jpeg` playlist disguise is visible
in `m1.hdr` + `m1.body` (movie master) and `c1.hdr` + `c1.body` (movie 1080p media playlist)**:
the headers say `content-type: image/jpeg` while the body starts with `#EXTM3U`. Series equivalents
are `sm.hdr/ sm.body` and `sc.hdr/ sc.body`.

| File | Content | Observed |
|---|---|---|
| `movie.html` | movie detail | 200 `text/html`, 280,125 B |
| `series.html` | series detail | 200 `text/html`, 468,323 B |
| `player_movie.html` | movie `/p/` shell | 200 `text/html; charset=utf-8`, 13,317 B |
| `player_series.html` | series `/p/` shell | 200, 102,048 B, 80 episodes |
| `m1.hdr`, `m1.body` | movie HLS master | 200 `image/jpeg`, 1,088 B, `#EXTM3U`, 0 × `EXT-X-KEY` |
| `c1.hdr`, `c1.body` | movie 1080p playlist | 200 `image/jpeg`, 131,057 B, fMP4 `EXT-X-MAP`, 0 × `EXT-X-KEY` |
| `sm.hdr`, `sm.body` | series HLS master | 200 `image/jpeg`, 313 B |
| `sc.hdr`, `sc.body` | series 1080p playlist | 200 `image/jpeg`, 23,007 B / 102 segments |
| `mp4.hdr`, `mp4.bin` | movie MP4 range | 206 `video/mp4`, `bytes 0-1023/4017446458`, first 16 B `0000 001c 6674 7970 6973 6f35` |
| `m2.hdr`, `m2.body` | master, no Referer | 403 `image/jpeg`, 31 B `#EXTM3U\n#EXT-X-ERROR:forbidden\n` |
| `w1.hdr`, `w1.body` | master, wrong Referer | same 403 / 31 B |

These are working captures and are intentionally outside the repo; the committed fixtures contain
HTML/JSON only, with all hosts replaced.

## 10. Fixtures

`app/src/test/resources/movielink/`:

| File | Source | Trim | Host scrub |
|---|---|---|---|
| `detail_movie.html` | `Gondhal (2026)` movie detail | head (meta/canonical/ld+json, CSS+analytics removed) + `<main>` only; 4 `data-dl` anchors, iframe, h1 | all |
| `detail_series.html` | `Gopal Bhar (2026) S13` series detail | head + details + `#mlbdHdEps`/`#mlbdHdGrid` + `#mlbdEpisodeDownloads` (80 cards); ZIP grid/screenshots removed | all |
| `player_pd2_movie.html` | `/p/<token>` movie shell | full shell (13 KB) | all |
| `player_pd2_series.html` | `/p/<token>` series shell | full shell, complete 80-entry `episodes` incl. `subs` | all |
| `listing_page.html` | home listing | head + first 6 `.movie-card` blocks | all |

Attribute names, class names, `data-*` values, path shapes and token characters are unchanged
except for the host substitutions. The `image/jpeg` disguise is intentionally **not** represented
in fixtures (fixtures are HTML); the evidence is in §2/§3/§5 above.

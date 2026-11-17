# RareAnimes.foo — Download Flow & Scrape-Risk Assessment

**Target:** `https://www.rareanimes.foo/` ("Rare Toons India — All Animes and Toons Hindi Download")
**Date of observation:** 2026-11-17, 05:51–05:56 UTC
**Agent:** crawl-agent 3 (rendered Chrome via CDP + 22 deliberate top-level fetches)
**Scope:** one end-to-end download flow, host/stack mapping, and a defensible crawl plan.
**Nothing was downloaded, no account created, no survey/shortener completed, no ad link clicked.**

> Evidence rule: everything below is quoted from a tool result in this session. Claims that could not
> be observed are explicitly marked **not verified**. This file intentionally redacts working
> download keys (Mega fragment, gamerxyt token) — the links are copyrighted works.

---

## TL;DR

1. The site is a **WordPress blog (Herald theme + Redux)** that *does not host files*. It stores
   **opaque, server-side-encrypted tokens** in post HTML.
2. Every "download" link is `https://codedew.com/zipper/?url=<opaque-base64>` → a **2-step, 5-second-timer
   ad interstitial** ("Step 1 of 3", "Step 2 of 3") → final destination on a rotating third-party file
   host (`hubcloud.ist` / `hubcloud.mov` / `mega.nz`) or a second-level streaming portal
   (`codedew.com/multiquality|watchbeta`).
3. A parallel "WatchMultiQuality" link opens **their own Netflix-style player**
   (`codedew.com/multiquality/?url=<16-char-id>`) that iframes `https://argon.razorshell.space/embed/<id>`
   and offers **1080p FHD / 720p HD / 360p SD** tabs.
4. Post HTML alone is **not enough** to resolve a download; the token is decrypted **server-side** by
   codedew.com. There is no `/?trdownload=`, no base64-in-JS, no AJAX link API on rareanimes.
5. Catalog is **1,942 posts**, fully enumerable via `/category/animes/page/1..231/` (edge-cached), and
   via `wp-json/wp/v2/posts` (**`x-robots-tag: noindex`**, **`no-store`**, bypasses Cloudflare cache).
6. **Verdict:** ingest *catalog metadata only*, resolve nothing at scrape time, deep-link out for
   playback. Do **not** mirror files or automate the download chain. See §C5.

---

# A. How downloads work

## A1. The flow I walked (title → file)

Post used: `/naruto-shippuden-season-18-episodes-hindi-dubbed-download-hd/` (post ID **79113**),
Episode 01 (373) → **HubCloud** button, because HubCloud is the canonical "download" target and the
same chain serves every episode.

| Step | URL | What happens |
|---|---|---|
| 0 | `https://www.rareanimes.foo/naruto-shippuden-season-18-.../` | Static WP post. Episode list with 5 buttons per language row. All buttons are `target="_blank"` links to `codedew.com/zipper/?url=…`. |
| 1 | `https://codedew.com/zipper/?url=9a%2BLxBUzsw08…%3D%3D` | Page title **"Step 1 of 3 — Scanning Link Safety..."**. Visible: `SECURITY SCAN 1/3`, `Scanning Link Safety...`, `⚠ Please minimize / ignore any pop-up ads and press button below.` Button is `javascript:void(0)` and **disabled for 5s** (`var timeLeft = 5;`) then becomes `Continue to Link Generation →`. |
| 2 | `…/zipper/?url=…&ad_done=1` | Title **"Step 2 of 3 — Generating Secure Link..."**. Text: `Generating Secure Link... Allocating high-speed server node`. Another 5s timer, button `Get Secure Link →`. `data-href` in the HTML is already the **final destination**. |
| 3 | `https://hubcloud.mov/drive/rd61sq1chfygjde` (same page also served as `hubcloud.ist`) | File host landing page. Header text: `Naruto Shippuden S18E01 373 [RareAnimes].mkv`, `File Size 208 MB`, `File Type video/matroska`, `Share Date 25-Oct-2026 16:05:56`. |
| 4 | `https://gamerxyt.com/hubcloud.php?host=hubcloud&id=rd61sq1chfygjde&token=Ykpx…` | The "Generate Direct Download Link" button. **`content-type: text/html`**, `x-turbo-charged-by: LiteSpeed`, `cache-control: no-store`, `cf-cache-status: MISS` → an HTML/JS redirector, **not** the file and **not** an OkHttp-friendly direct URL. |

Timers are real but trivial: the button is gated only by client JS, and step 2's `data-href` reveals the
final URL in the HTML source. There is no captcha, no account, no shortener with personal data, and
**no survey** in this flow.

### Download-flow diagram

```
                          rareanimes.foo (WordPress, Cloudflare)
  ┌───────────────────────────────────────────────────────────────────────────┐
  │ /naruto-shippuden-season-18-episodes-hindi-dubbed-download-hd/            │
  │ <span style="color:#99cc00">Hindi</span> –                                │
  │   [WatchMultiQuality] [HubCloud] [WatchNow] [DLBeta] [Mega]               │
  │        │                   │          │         │        │                │
  │        └──────────┬────────┴──────────┴─────────┴────────┘                │
  │                   ▼  href="https://codedew.com/zipper/?url=<OPAQUE>"      │
  └───────────────────────────────────────────────────────────────────────────┘
                          │  (opaque token = encrypted server-side; not decodable)
                          ▼
        ┌──────────────────────────────────────────────────────────┐
        │ codedew.com/zipper  — "Step 1 of 3  Scanning…"  [5s]     │
        │   ads: ek.hastatesimnel.com, llvpn.com/tag.min.js        │
        └───────────────────────────┬──────────────────────────────┘
                                    │ data-href: same URL + &ad_done=1
                                    ▼
        ┌──────────────────────────────────────────────────────────┐
        │ codedew.com/zipper?…&ad_done=1 — "Step 2 of 3 Generating" │
        │   [5s]  data-href  = FINAL URL (server-decrypted)        │
        └───────┬───────────────┬───────────────┬──────────────────┘
                │               │               │
       download │        stream │        stream │  (labels are just names —
                ▼               ▼               ▼   every one is a zipper hop)
   ┌────────────────────┐ ┌──────────────────┐ ┌──────────────────────┐
   │ hubcloud.ist/.mov  │ │ mega.nz/file/…   │ │ codedew.com/watchbeta│
   │ /drive/<12-char>   │ │ #<key>           │ │  /?url=<8-char>      │
   │ 208 MB .mkv        │ │ (client-side AES)│ │                      │
   └─────────┬──────────┘ └──────────────────┘ └──────────────────────┘
             │ "Generate Direct Download Link"
             ▼
   ┌────────────────────────────────────────────┐
   │ gamerxyt.com/hubcloud.php?host=&id=&token= │  text/html, no-store
   │  → JS redirect → CDN file (host not shown) │
   └────────────────────────────────────────────┘
```

The **WatchMultiQuality** branch is different and more interesting:

```
[WatchMultiQuality] → https://codedew.com/multiquality/?url=<16-char-id>
        │  (own player UI: 1080p FHD / 720p HD / 360p SD, season list, watchlist)
        ├─ AJAX  /multiquality/?url=…&ajax=1&preload=1   → episode payload
        ├─ iframe https://argon.razorshell.space/embed/<id>  (actual stream embed)
        ├─ beacon /multiquality/?beacon=1&fid=…&sid=…&is_view=1
        └─ fetch  https://vinnyvower.cfd/cuid/?f=https%3A%2F%2Fcodedew.com  (fingerprint/ad)
```

## A2. File hosts actually observed

| Host | Role | Evidence |
|---|---|---|
| `codedew.com` | Owns the token gate (`/zipper/`), the player (`/multiquality/`), the alt player (`/watchbeta/`) | `data-href="https://hubcloud.ist/drive/rd61sq1chfygjde"` after step 2; page titles/URLs above |
| `hubcloud.ist`, `hubcloud.mov`, `hubcloud.fans` | Primary download host (rotating TLDs: **.ist** in step-2 `data-href`, **.mov** when browsed; `.fans` linked as "Create An Account") | `https://hubcloud.ist/drive/…` vs `location.href = https://hubcloud.mov/drive/rd61sq1chfygjde` |
| `gamerxyt.com` | Direct-link generator for hubcloud files (`?host=&id=&token=`) | HEAD → `text/html`, `x-turbo-charged-by: LiteSpeed`, `cf-cache-status: MISS` |
| `mega.nz` | Mirror download (label "Mega") | `data-href="https://mega.nz/file/IBIzma5K#<key redacted>"` |
| `argon.razorshell.space` | Stream embed iframe host (`/embed/<id>`) | `performance` entry `iframe → https://argon.razorshell.space/embed/srCZj4T7ft9YFr3` |
| `wsrv.nl` + `image.tmdb.org` | Poster CDN/proxy (posters are TMDB images resized by wsrv) | `https://wsrv.nl/?url=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Fw500%2F%2F…&w=400&output=webp&q=80` |
| `rareanimes.mov` | Second domain used for brand assets (`/wp-content/uploads/2023/11/Rare-Animes-India.png`) | image request from the player page |

Labels `WatchNow` and `DLBeta` both decode to a **streaming page**, not a file host:
`DLBeta → data-href="/watchbeta/?url=aRyaLyMT"` (verified). `WatchNow` was not individually decoded
(**not verified**, structurally identical).

## A3. How links are stored

* **Not** post meta exposed to the API, **not** `/?trdownload=` (string absent from the rendered post),
  **not** base64-in-JS, **not** an AJAX endpoint on rareanimes.
* They are **plain `<a>` tags in the post body** whose `href` is
  `https://codedew.com/zipper/?url=<opaque>`.
* The `url=` value is **long, URL-encoded base64 that does not decode to readable text** (it decodes to
  binary). Step 2's HTML already contains the plaintext destination, so **decryption happens server-side**.
* Player links use a much shorter opaque ID (`?url=srCZj4T7ft9YFr3`, 16 chars; watchbeta 8 chars) — a
  different, non-derivable token space.
* Granularity: **per episode**, not per season/batch. Naruto S18E01 has its own hubcloud file
  (`S18E01 373 [RareAnimes].mkv`). No batch/zip was offered on this post (**a batch post type may exist;
  not verified**).

## A4. Quality / language tagging — can you tell files apart *before* downloading?

**Language: yes, reliably.** Each language row is a coloured `<span>` immediately before the links:

```html
<p><span style="color: #99cc00;">Hindi </span>– [<a href="https://codedew.com/zipper/?url=cXg7…"
   target="_blank" rel="noopener noreferrer">WatchMultiQuality</a>] [<a …>HubCloud</a>] [<a …>WatchNow</a>]
   [<a …>DLBeta</a>] [<a …>Mega</a>]</p>
<p><span style="color: #ff00ff;">Tamil </span>– [<a …>HubCloud</a>] [<a …>WatchNow</a>]</p>
<p><span style="color: #00ccff;">Telugu </span>– [<a …>HubCloud</a>] [<a …>WatchNow</a>]</p>
<p><span style="color: #ff0000;">Bengali </span>– [<a …>HubCloud</a>] [<a …>WatchNow</a>]</p>
```

Colour→language map observed: Hindi `#99cc00`, Tamil `#ff00ff`, Telugu `#00ccff`, Bengali `#ff0000`
(also present elsewhere: `#00ff00`, `#0000ff`, `#339966`, `#333399`, `#ffff00`). Parse the span text,
not the colour.

**Quality: no.** Quality exists only as prose and as a button *name*:

* Prose: `🎬Quality: (360p, 480p, 720p HD, 1080p HD)`
* Button label: `WatchMultiQuality` (one link for all qualities)
* Player tabs (post-click): `1080p FHD`, `720p HD`, `360p SD`
* The actual file is **one** `.mkv` with **no quality token in the filename**:
  `Naruto Shippuden S18E01 373 [RareAnimes].mkv` / `video/matroska` / `208 MB`

⇒ **You cannot distinguish a 480p Hindi file from a 1080p English one from the rareanimes HTML.**
You get *language* + *"multi-quality"*, and the resolution choice only appears inside codedew's player.
Any catalog field called `quality` would have to be `["360p","480p","720p","1080p"]` (declared set), not a
per-file attribute.

Episode numbering **is** parseable but only from body text, not schema:
`Episode 01 (373) – Team 7 Assemble!` (`episode 1`, `absolute 373`, `title`). The post's JSON-LD is only
`WebSite` + `WebPage` — **no `TVEpisode` schema** (`grep 'application/ld+json'` → 1 block, graph types
`['WebSite','WebPage']`). By contrast the codedew player *does* emit `TVEpisode` JSON-LD with
`episodeNumber`, `partOfSeason.seasonNumber`, `description`, `image`.

## A5. Direct (OkHttp-able) or browser-only?

**Browser-only, in practice.** Minimal reproduction of just the download hop:

1. `GET` the post page → regex `<span style="color:…">Lang </span>.*?<a href="(https://codedew\.com/zipper/\?url=[^"]+)">HubCloud</a>`
2. `GET` that zipper URL → wait 5 s → `GET` same URL + `&ad_done=1`
3. Parse `data-href="(https?://[^"]+)"` from step 2 → that is the **file-host landing page**
4. `GET` that landing page → scrape filename/size + the `gamerxyt.com/...token=…` href
5. `GET` gamerxyt → it returns **HTML** that JS-redirects to a CDN; OkHttp must follow a script, not a 302.

So: no cookies are *required* (I never accepted one deliberately), but **JS execution is required** at
steps 1–2 (5 s gate) and at step 5 (HTML redirector). The chain also rotates host TLDs, so any
hard-coded `hubcloud.mov` breaks. Every hop is monetised by ads — automating it is both fragile and
directly hostile to the operator.

---

# B. How the site works

## B1. Stack (from rendered HTML + response headers)

| Layer | Evidence |
|---|---|
| CMS | WordPress. `link: <https://www.rareanimes.foo/wp-json/>; rel="https://api.w.org/"`; `wp-content/`, `wp-includes/`, `wp-embed-responsive` |
| Theme | **Herald 2.6.2.1** (Meks) — `/wp-content/themes/herald/assets/css/min.css?ver=2.6.2.1`; body class `wp-theme-herald herald-v_2_6_2_1` |
| Options framework | `meta name="generator" content="Redux 4.5.18"` |
| Plugins | `search-in-place` 1.5.8 (CodePeople), `gs-logo-slider` 3.9.2, `announcer` 6.5, `contact-form-7` 6.2.5, `meks-easy-social-share` 1.3 |
| jQuery | 3.7.1 + migrate 3.4.1 |
| CDN / WAF | **Cloudflare** (`server: cloudflare`, `cf-ray`, `cf-cache-status`, `nel`, `alt-svc: h3`) + **Cloudflare Rocket Loader** on codedew (`rocket-loader.min.js`) |
| Edge cache | `cf-edge-cache: cache,platform=wordpress`; HTML `cf-cache-status: **HIT**`, `age: 2166`, `cdn-cache-control: public, max-age=0, s-maxage=6040` |
| Analytics | Cloudflare Insights beacon only (`static.cloudflareinsights.com/beacon.min.js`). No GA/GTM in the 40 scripts inspected (**not exhaustive**) |
| Ads | `https://zb.impallatwist.com/rxFVdwQ6fHojzfV/134893` (rareanimes), `https://ek.hastatesimnel.com/rB5o9p5Xbe3hWxsq/135799`, `https://llvpn.com/tag.min.js` (codedew), `https://vinnyvower.cfd/cuid/?f=…` (fingerprint fetch) |
| Download/stream subsystem | `codedew.com` (separate app, also Cloudflare) — `/zipper/`, `/multiquality/`, `/watchbeta/` |
| Membership | **None observed.** Nav is only `HOME · DISCLAIMER · DMCA · CONTACT US`. Herald ships a login popup, but no paywall, no premium tier, no gated content |
| Search | WordPress `?s=` **plus** the `search-in-place` AJAX plugin (`char_number: 3`, `result_number: 10`, `operator: or`, endpoint `admin-ajax.php`) — live-as-you-type via `POST /wp-admin/admin-ajax.php` |
| Content model | **Human-posted blog.** 1,942 posts, hand-written emoji metadata blocks, `👉 CLICK HERE`, coloured spans, typos, inconsistent quality claims. Not API-driven. ~2 posts/day (78626 @ 2026-11-16T22:00, 78651 @ 21:27) |

## B2. Catalogue surface (useful for crawl design)

| Surface | Observation |
|---|---|
| `wp-json/wp/v2/posts` | Works, returns `id, slug, date, modified, link, title, categories, featured_media`. **`x-wp-total: 1942`**, `x-wp-totalpages: 1942`. **`x-robots-tag: noindex`**, `cache-control: private, no-store`, `cf-cache-status: BYPASS` |
| `sitemap.xml` | 200, `text/xml`, 45,425 bytes, **273 `<loc>`**, per-URL `<lastmod>`, generated `2026-11-16 17:02:05 GMT`. Includes pages (`/terms-and-conditions/`, `/persons/`, `/videos/`). **Partial vs 1,942 posts** |
| `/category/animes/` | 200, **231 paginated pages**, 8–9 posts per page, `cdn-cache-control: s-maxage=6040` (edge-cacheable after first MISS) |
| Post identity | `link: <https://www.rareanimes.foo/?p=79113>; rel=shortlink` and body class `post-79113` → **post ID is stable and exposed twice**. Slug is derived from title and can change |
| Categories | IDs in markup: `herald-cat-180` = HINDI DUB, `-1` = ANIMES, `-2362` TAMIL, `-2363` TELUGU, `-7377` MALAYALAM |

## B3. Terms / DMCA posture (quoted)

`https://www.rareanimes.foo/robots.txt` (complete file):

```
User-agent: *
Disallow: /wp-admin/
Allow: /wp-admin/admin-ajax.php

Sitemap: https://www.rareanimes.foo/sitemap.xml
```

`/dmca/` — "We do not store any copyright-protected content on our websites/servers… any linked content
is stored only in third-party websites… This site merely indexes of other sites's contents… You should
DELETE IT(data) within 24 hours… Kindly mail us at (alonepakamon@proton.me)". (A boilerplate
piracy-disclaimer page; the uploads are in fact third-party, which matches the observed host chain.)

`/terms-and-conditions/` — generic template (references "prevailing law of Netherlands"), and explicitly:

> **You must not:** Republish material from Rare Animes · Sell, rent or sub-license material from Rare
> Animes · **Reproduce, duplicate or copy material from Rare Animes** · **Redistribute content from Rare
> Animes**

⇒ `robots.txt` is permissive, but the ToS forbids copying **content**. Catalog *facts* (titles, episode
numbers) are not the site's IP; their **descriptions, editorial text, and files are**. See §C1.

---

# C. How to scrape WITHOUT risk

## C1. Legal / ToS posture (blunt)

**This is a piracy-adjacent site.** It indexes Hindi/Tamil/Telugu dubs and full episodes of licensed
anime (`Naruto Shippuden`, `Black Clover`, `Mushoku Tensei`, `Re:ZERO`) with no licence, and monetises the
hops with ad networks. The DMCA page is an admission-and-disclaimer, not a licence.

Three distinct risks, often conflated:

| Act | Legal character | Risk |
|---|---|---|
| Reading the public catalog (titles, episode numbers, poster URLs) | Robots.txt allows it; ToS forbids "reproducing content". Facts are generally not copyrightable; *their prose and posters are* | **Low** (ToS breach possible; no realistic damages) |
| Scraping the download/stream endpoints to resolve playable links | Circumventing an ad gate (the zipper exists solely to show ads); contract/ToS breach; possible anti-circumvention argument | **Medium–High** |
| Mirroring / redistributing the `.mkv` files | Straight copyright infringement (India Copyright Act 1957 §51/63; US 17 USC §501); criminal exposure in some jurisdictions | **High — do not** |
| Shipping an app that plays those files to users | Same as mirroring, *plus* you become the distributor, not an indexer | **Highest — do not** |

Also note the operator's own ToS grants you "personal use" only, and the site's disclaimer requires the
user to delete content in 24h — you cannot inherit that fig leaf for a distributed app.

## C2. Technical risk (measured, not guessed)

* **Cloudflare is in front of every host** (`server: cloudflare` on rareanimes, codedew, gamerxyt,
  hubcloud). Cloudflare Bot Management JS was injected on codedew:
  `/cdn-cgi/challenge-platform/scripts/jsd/main.js?ray=…` with `window.__CF$cv$params` — that is CF's
  JS-detection beacon, i.e. bot scoring is active on the download subsystem.
* **No challenge or 403/429 was ever served to me**: 22 top-level fetches over ~5 minutes at ~0.4 req/s with
  a normal Chrome UA, all `200`. **The actual rate threshold is not verified** — absence of a block at
  0.4 req/s is not evidence about 5 req/s.
* **HTML is edge-cached**, so repeat GETs mostly do not reach origin:
  `cf-cache-status: HIT`, `age: 2166`, `cdn-cache-control: public, max-age=0, s-maxage=6040`.
  A polite crawler mainly spends *Cloudflare's* bandwidth, not the origin's — but it still accrues against
  per-IP bot score.
* **The uncached endpoints are the dangerous ones**: `wp-json` (`BYPASS`, `no-store`, `noindex`),
  `admin-ajax.php` (live search → PHP + DB per keystroke), and every `codedew`/`hubcloud` hop (ad
  revenue + bot management). These are where a crawler becomes a cost and where a block will start.
* **`/category/animes/` page 1 was `MISS`** (first hit, then cached) — so archive enumeration is cheap
  after the first pass, whereas 1,942 individual posts always cost 1,942 origin misses on first crawl.

## C3. Safe crawl design (concrete)

1. **Budget:** **≤ 250 requests/night**, hard-capped in code. Cadence **1 request / 3.0 s** with jitter
   `3.0–5.5 s` (random), single-threaded, no parallel connections, no HTTP/2 multiplexing tricks. Nightly
   window 02:00–05:00 IST (lowest human traffic; origin load is what matters, not your convenience).
2. **Enumerate cheaply, detail rarely.** Nightly: fetch `/category/animes/page/1/` and `/page/2/` only
   (2 requests) → new post IDs. Weekly (Sunday): walk `/category/animes/page/1..231/` (231 requests) to
   catch edits/insertions. **Never** walk 1,942 detail pages in one night.
3. **Conditional requests.** Send `If-None-Match` (ETag) and `If-Modified-Since` (Last-Modified) for every
   known URL. WordPress + this Cloudflare setup returns `last-modified` and honours conditionals; a `304`
   costs no origin render and no body transfer. This is the single biggest load reducer: an unchanged
   catalogue becomes 231 tiny `304`s weekly instead of 231 full renders.
4. **Cache locally, key by post ID.** Store raw HTML + extracted record + `etag` + `last_modified` +
   `fetched_at`. Re-crawl only when `modified` (REST/`lastmod`) is newer than `fetched_at`. A page already
   in cache and unchanged is **never** fetched again that night.
5. **Identifiers:** primary key = **post ID** (`?p=79113`, class `post-79113`, REST `id`). Secondary =
   slug (may change on title edits — treat a slug change on the same ID as a rename, not a new title).
   Episode key = `(post_id, language, absolute_episode_number)`, parsed from `Episode 01 (373) – Title`.
6. **Avoid entirely** (see §C4): `/?s=`, `admin-ajax.php`, `wp-json` (routine), every `codedew.com`,
   `hubcloud.*`, `gamerxyt.com`, `mega.nz`, `argon.razorshell.space`.
7. **Error handling:** `403`, `429`, `503` → **exponential backoff 30 s → 2 min → 10 min**, max 3
   attempts per URL, then **abort the whole run** (not just the URL). Never retry in a loop; a retry storm
   is how a soft block becomes an IP ban. `5xx` ≠ `429` — treat both as stop signals at night level.
8. **User-Agent:** honest and contactable:
   `AnimoCatalog/0.1 (+https://<your-domain>/bot; contact@<your-domain>)`. Trade-off: an honest UA may be
   scored lower than Chrome and could be challenged sooner. That is acceptable — the correct response to a
   challenge is to **stop**, not to spoof. Never randomise UAs to evade detection; that converts a ToS
   issue into an evasion issue.
9. **Kill switch (check every response):**
   * status `403` / `429` / `503`
   * header `cf-mitigated: challenge`
   * body contains any of `Just a moment...`, `Attention Required!`, `__cf_chl`, `cf-challenge`, `Ray ID`,
     `challenge-platform/scripts/jsd`
   * `server: cloudflare` + `content-type: text/html` + no `wp-content` in body (challenge page shape)
   On any hit: persist state, log the URL, **exit 0 without retry**, alert a human. Add a 24 h cooldown.
10. **Bandwidth:** never fetch images. Posters are TMDB originals behind `wsrv.nl` — fetch the *identifier*
    (`image.tmdb.org/t/p/w500/<hash>.jpg`) and either rehost from TMDB under its terms or hotlink the
    site's `wp-content` thumbnail. No image crawling.
11. **Instrument:** log `status, bytes, cf-cache-status, age, duration` per request so a future block is
    diagnosable (a rising `MISS` rate or falling `age` = you are hitting origin too often).

## C4. What NOT to do (explicit)

1. **No parallel hammering.** No concurrency > 1, no 10-worker scraper, no `xargs -P`, no retry storms.
2. **No scraping of download/shortener/stream endpoints** — `codedew.com/zipper|multiquality|watchbeta`,
   `hubcloud.*`, `gamerxyt.com`, `mega.nz`, `argon.razorshell.space`. These are ad-gated and
   bot-managed; touching them is both a ToS breach and the fastest route to a block.
3. **No bypassing paywalls, ad gates, timers, or Cloudflare challenges.** If you see a challenge, stop.
4. **No redistributing files.** Do not download `.mkv`s, do not rehost them, do not proxy them.
5. **No ignoring robots on admin paths.** `/wp-admin/` is `Disallow`; `admin-ajax.php` is technically
   *allowed* but is an origin-cost endpoint — treat "allowed" as "still don't".
6. **No spoofed/scraped browser UAs or cookie replay** to look like a human.
7. **No use of `wp-json` as a bulk harvesting channel.** It is explicitly `x-robots-tag: noindex` and
   `no-store`; hammering it is the clearest possible "I am a bot" signal.
8. **No copying of their prose, episode descriptions, or hosted posters** into the app; those are the
   copyrightable parts.
9. **No account creation, no comments, no contact form submissions** as part of crawling.

## C5. Recommendation — metadata-only, resolve nothing

**Ingest catalog metadata only** (title, season, episode number, absolute episode number, language tags,
declared quality *set*, TMDB poster id, post ID/slug for attribution), and **do not mirror or pre-resolve
anything**. For playback, deep-link the user out to the source page in a browser (`Intent`/`Custom Tab`),
or resolve at *user tap time* through the site's own UI — never in a background job, never cached as a
playable URL. This keeps your nightly cost at ~2–250 edge-cached requests, keeps you inside robots.txt,
and keeps the copyright act (distribution) on the operator, where it currently sits. The trade-off is real:
metadata-only gives you a **worse UX** (extra taps, ads visible, no offline downloads) and your catalog
will drift from theirs by hours, not seconds; and it does **not** make the *app* lawful — a catalogue whose
only purpose is to route users to unlicensed dubs is still contributory-risk territory, so ship it as a
generic "where to watch" index with legitimate sources prioritised and a takedown path, not as a
RareAnimes mirror. Mirroring downloads would be technically easy (one `.mkv`, 208 MB) and is precisely the
line that turns a metadata-scraping complaint into a criminal copyright case — don't.

---

## Risk table

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Cloudflare bot block / managed challenge on the crawler IP | Medium (0 blocks seen at 0.4 req/s, threshold **not verified**) | Medium — crawl stops, IP flagged | 1 req/3 s + jitter, honest UA, kill switch, 24 h cooldown after any 403/429 |
| Origin overload from uncached endpoints (`wp-json`, `admin-ajax`, post pages) | Medium–High if REST is used in bulk | Medium — operator notices, blocks, or site degrades | Use edge-cached archives; conditional GETs; never bulk `wp-json`; 250 req/night cap |
| ToS breach claim (copy/reproduce/redistribute) | High (their ToS forbids copying) | Low–Medium (civil, weak damages for facts) | Take facts only; attribute + link; honour takedowns; no prose/poster copying |
| Copyright infringement via mirrored `.mkv` files | High **if** you mirror | **High — criminal exposure** | Never download/rehost files; deep-link only |
| Contributory infringement by shipping the catalogue in-app | Medium | **High** — app store removal, DMCA, legal notice | Metadata + outbound links; add DMCA/contact flow; prioritise licensed sources |
| Link rot: host TLD rotation (`hubcloud.ist/.mov/.fans`), expiring gamerxyt tokens | Certain over time | Low for metadata, High if you store resolved URLs | Never persist resolved URLs; re-resolve at tap time only (or don't resolve) |
| Ad/malware exposure if you automate the download chain | High | High (drive-by ads, fingerprinting via `vinnyvower.cfd`) | Never automate the chain; never render it in a WebView with JS enabled for downloads |
| Scraping personal data (comments, emails) | Low (comments open, but not needed) | Medium (privacy law) | Collect no user-generated content; no comment scraping |
| WP REST `noindex` signal ignored → perceived hostile crawling | Medium | Medium | Treat `noindex` as opt-out for bulk API use; prefer sitemap/archives |

## Crawl budget table

| Endpoint | Requests / night | Cacheable? | Notes |
|---|---|---|---|
| `GET /category/animes/page/1..2/` | **2** | Yes (`s-maxage=6040`; first hit MISS) | New-post discovery. 8–9 posts/page |
| `GET /category/animes/page/1..231/` (weekly) | **231** (1 night/week) | Yes after first pass | Full enumeration; 231 pages ≈ 1,942 posts |
| `GET /<post-slug>/` (new/changed only) | **≤ 15** | Yes (`HIT`, `age` up to 2166) | Conditional `If-Modified-Since`/`If-None-Match`; expect `304` |
| `GET /<post-slug>/` (full re-check) | **0** (avoid) | Yes | Would be 1,942 — only if a full audit is ever needed, split over 8+ nights |
| `GET /sitemap.xml` | **1** | Yes | 273 `<loc>` only — partial, but good `lastmod` signal |
| `GET /wp-json/wp/v2/posts?per_page=100` | **0** (20 if explicitly approved) | **No** — `BYPASS`, `no-store`, `x-robots-tag: noindex` | Most efficient shape (20 req for 1,942 posts) but uncached, origin-cost, and opted out. Default: don't |
| `GET /?s=<query>` | **0** | No | Origin search; also a bot-shaped pattern |
| `POST /wp-admin/admin-ajax.php` (search-in-place) | **0** | No | Allowed by robots but expensive; never touch |
| `GET /wp-admin/*` | **0** | No | `Disallow` — hard no |
| `GET codedew.com/zipper|multiquality|watchbeta` | **0** | No | Ad gate + CF bot management |
| `GET hubcloud.*`, `gamerxyt.com`, `mega.nz`, `argon.razorshell.space` | **0** | No | File hosts / stream embeds — out of scope by design |
| `GET /wp-content/uploads/*` (images) | **0** | Yes | Never fetch; use TMDB id or hotlink thumbnail only |
| **Nightly total (normal)** | **≈ 18** | | 2 archive + ≤15 changed posts + 1 sitemap |
| **Weekly peak (full enum)** | **≈ 247** | | Still under the 250 cap |

---

## What I could NOT verify

* The true Cloudflare rate threshold / block page — **no 403, 429 or challenge was ever served** during
  the 22 fetches at ~0.4 req/s.
* `WatchNow` label's final host (structurally identical to `DLBeta` → `/watchbeta/`; not decoded).
* Whether a per-season/batch ZIP post type exists (no batch link on the Naruto S18 post).
* The final CDN host behind the `gamerxyt.com` HTML redirector (I did not follow it — that is the file).
* Whether `wp-json` is intentionally open or accidentally left enabled (only that it is `noindex`).
* WP core version (not exposed; no `wp-includes/js/wp-embed.min.js?ver=` on the inspected pages).

## Appendix — raw evidence

```
# robots.txt (verbatim)
User-agent: *
Disallow: /wp-admin/
Allow: /wp-admin/admin-ajax.php

Sitemap: https://www.rareanimes.foo/sitemap.xml
```

```
# HEAD /naruto-shippuden-season-18-.../
HTTP/2 200
content-type: text/html; charset=UTF-8
server: cloudflare
cf-edge-cache: cache,platform=wordpress
cache-control: public, max-age=0, must-revalidate
cdn-cache-control: public, max-age=0, s-maxage=6040, must-revalidate
last-modified: Tue, 17 Nov 2026 05:15:09 GMT
age: 2166
cf-cache-status: HIT
link: <https://www.rareanimes.foo/wp-json/>; rel="https://api.w.org/"
link: <https://www.rareanimes.foo/wp-json/wp/v2/posts/79113>; rel="alternate"; title="JSON"; type="application/json"
link: <https://www.rareanimes.foo/?p=79113>; rel=shortlink
strict-transport-security: max-age=31536000
```

```
# HEAD /wp-json/wp/v2/posts?per_page=1
x-robots-tag: noindex
x-wp-total: 1942
x-wp-totalpages: 1942
cache-control: private, no-store, no-cache, must-revalidate, max-age=0
cdn-cache-control: private, no-store
cf-cache-status: BYPASS
```

```
# HEAD gamerxyt.com/hubcloud.php?host=hubcloud&id=rd61sq1chfygjde&token=<redacted>
HTTP/2 200
content-type: text/html; charset=UTF-8
server: cloudflare
x-turbo-charged-by: LiteSpeed
cache-control: no-store
cf-cache-status: MISS
accept-ranges: bytes
```

```
# zipper step 1 inline JS (verbatim excerpt)
var timeLeft = 5; ... var ready = 'Continue to Link Generation →';
setTimeout(function() { window.location.href = t; }, 200);
# step-1 data-href:
/zipper/?url=9a%2BLxBUzsw08…%3D%3D&amp;ad_done=1
# step-2 data-href (final destination, server-decrypted):
https://hubcloud.ist/drive/rd61sq1chfygjde
```

```
# hubcloud landing page (rendered text)
Naruto Shippuden S18E01 373 [RareAnimes].mkv
File Size  208 MB
File Type  video/matroska
Share Date 25-Oct-2026 16:05:56
Note: Always Use Updated Chrome Browser With Cookies Enabled .| Refresh this Page & click Download again …
https://hubcloud.mov/drive/rd61sq1chfygjde
```

# Poster Audit — why 633 Hindi rows have no poster

Measured 2026-11-17 against a copy of the live Room DB (`/tmp/opencode/animo3.db`) and the live
toono.app / rareanimes.foo endpoints. Everything below is labelled **measured** or **assumed**.

## Verdict

**One regex, zero extra requests.** `ToonoProvider.CARD_POSTER` requires `src` to be the attribute
directly after `<img `, but toono's **movie** card template emits
`<img class="trs" src="…">`. 620 of 620 movie cards therefore parse to `poster = null`, while
640/640 series cards parse fine. A `[^>]*?\s` between `<img` and `src` fixes 618 of the 633 rows on
the next forced sync — no per-title fetch, no new endpoint, no rate-limit risk.

| | measured |
|---|---|
| listing cards crawled (all 126 pages, hindi category `term=197`, `type=mixed`) | **1,260** |
| cards the current `CARD_POSTER` matches | **640** (all `series/`) |
| cards it misses | **620** (all `movies/`, 100 %) |
| cards an attribute-tolerant regex matches | **1,260** (100 %) |
| DB rows the 620 misses explain | **618** of the 633 empty-poster rows |
| rows left over | **15** rareanimes rows (pre-existing, separate cause, see §5) |

## 1. Counts by bucket (`anime` table, 12,377 rows)

| bucket | id range | rows | empty `poster` | empty `backgroundImage` | `score` NULL | empty `description` |
|---|---|---|---|---|---|---|
| originals | `id > 0` | 9,404 | **0** | 6,738 (71.6 %) | 1,240 (13.2 %) | 193 (2.1 %) |
| toono | `-999999999 … -1` | 1,258 | **618 (49.1 %)** | 1,258 (100 %) | 1,258 (100 %) | 1,258 (100 %) |
| rareanimes | `-1999999999 … -1000000000` | 1,715 | **15 (0.9 %)** | 1,715 (100 %) | 1,715 (100 %) | 0 |
| **total** | | **12,377** | **633** | 9,711 | 4,213 | 1,451 |

The 633 breaks down exactly as **618 toono + 15 rareanimes**.

The toono split is perfectly correlated with route and with `type`:

| toono subset | rows | empty poster |
|---|---|---|
| `originUrl` = `/series/…`, `type = TV` | 640 | **0** |
| `originUrl` = `/movies/…`, `type = Movie` | 618 | **618 (100 %)** |

So: *every* toono movie row is poster-less, *no* toono series row is.

## 2. Root cause — the card template, not the data

Raw movie card (page 4 of the AJAX listing, verbatim, **data only**):

```html
<article class="post dfx fcl movies">
  <header class="entry-header"><h2 class="entry-title">Bee Movie</h2> … </header>
  <div class="post-thumbnail or-1">
    <figure>
      <img class="trs" src="https://image.tmdb.org/t/p/w342/tX2mgKFUkzFMZingSVD4vioZNz2.jpg" loading="lazy" alt="Bee Movie" />
    </figure>
    …
```

Raw series card for comparison: `<img src="https://image.tmdb.org/t/p/w342/…jpg" loading="lazy" alt="…"/>` — no `class`, which is the only reason it works.

`ToonoProvider.kt:467`

```kotlin
val CARD_POSTER = Regex("<img src=\"([^\"]+)\"")      // misses  <img class="trs" src="…">
```

**The listing already carries the poster for all 618.** No second request is needed: the tolerant
match returned a fully-qualified `https://image.tmdb.org/t/p/w342/….jpg` for **620/620** movie cards,
0 relative URLs, 0 `http://`, 0 logo/avatar false positives.

The title page also carries the same poster and the *existing* `POSTER` regex already parses it
(verified on 7 missing titles: `dragon-ball-z-broly-second-coming`, `pinocchio-a-true-story`,
`tom-and-jerry-spy-quest`, `space-jam`, `toy-story-5`, `bee-movie`, `delhi-safari`,
`mononoke-…-serpent` → 7/7 hits). That matters only as the fallback path; see §6.

## 3. Recommended change

**File:** `app/src/main/java/com/animo/app/data/hindi/ToonoProvider.kt`
**Field:** `CARD_POSTER` (line 467)

```kotlin
// was: val CARD_POSTER = Regex("<img src=\"([^\"]+)\"")
val CARD_POSTER = Regex("<img[^>]*?\\ssrc=\"([^\"]+)\"")
```

Note the mandatory `\s`: without it the pattern would also match `data-src="…"` (a lazy-load
placeholder) on any card that ever grows one. With `\s` it only accepts a real `src` attribute.

This is the same shape the title-page `POSTER` regex already uses
(`<img[^>]*?\ssrc="([^"]+)"`), so it aligns the two parsers instead of inventing a third convention.

**Operational precondition (important):** `HindiCatalogImpl.syncInternal` skips a provider whose rows
are already present (`hindiOriginsPresent()`), so a plain re-sync will *not* rewalk toono.
The fix must ship with `sync(force = true)` or an equivalent one-shot re-walk. With `force=true`
the whole pass is **103.6 s measured** (126 pages at the existing 250 ms gap) and rewrites
`poster` for all 1,258 toono rows in place.

## 4. Option cost — measured, not estimated

Latency sampled 5× each from this machine, single connection, no pacing: AJAX listing page
**0.527 s median** (0.463–0.603), title page **1.407 s median** (1.406–2.205).

| option | calls needed to fix 633 | extra wall time | risk | verdict |
|---|---|---|---|---|
| **(a)** attribute-tolerant `CARD_POSTER` | **0** | **0 s** (full pass already 103.6 s) | none — same call the app already makes | **recommended**, fixes 618 |
| **(a) + forced sync for rare** | 20 listing pages (3 s each, provider-paced) | ~60 s | none | fixes ≤15 more |
| **(b)** per-title fetch of the 633 missing | 633 × 1.407 s = 890 s **≈ 14.8 min** (966 s / 16.1 min with the existing 120 ms gap) | ≈ 15–16 min | 633 sequential GETs against one small WP host; `FETCH_ATTEMPTS=3` × 400 ms backoff already exists if it starts 429-ing | not needed |
| **(c)** not available at all | **0 rows proven unavailable** | — | — | refuted |

For (c): 7/7 sampled title pages carried the poster and 2/2 sampled rareanimes posts returned a
`source_url` from the live REST API, so the data exists for every row checked. Sample, not a proof
for all 633 — but the listing fix removes the question for the 618.

Cost estimate for the fix as a whole: **one regex line + one forced sync (~104 s of network), 0
additional requests, 0 new failure modes.**

## 5. The 15 rareanimes rows

`RareAnimesProvider` is not the problem. Replaying the provider's exact request
(`?_fields=…,_links,_embedded&_embed=wp:featuredmedia`) for `young-justice-season-2-…` returns
`_embedded["wp:featuredmedia"][0].source_url = https://www.rareanimes.foo/wp-content/uploads/2021/03/Young-Justice-Season-2-Hindi-Episodes-Download-FHD.jpg`.
Listing page 18 also returned `source_url` for **100/100** posts.

So the 15 are stale rows, not missing data (their posts are ids 13533–13569, i.e. the tail of the
catalogue that the pre-fix pass wrote). A forced rareanimes sync should clear them. **Assumed**
(not proven) that this is a listing-embed miss on the day they were written — the timestamps cannot
settle it because `rareRow` stores the *post date* in `updatedAtEpoch`, not the sync time.

Priority: lowest. 15 rows, 0.9 % of the source.

## 6. Second, quieter gaps (asks “is the poster problem really only Hindi?”)

- **Poster: yes, only Hindi.** 9,404/9,404 originals have a poster; 2,340/2,973 Hindi rows do after
  the earlier fix. No second poster gap.
- **`backgroundImage`: a real, much larger gap, and it is global.**
  6,738/9,404 originals (71.6 %) and 100 % of Hindi rows have none. Hindi never sets it by design
  (`toonoRow`/`rareRow` hardcode `backgroundImage = ""`). For originals it is a genuine hole, an
  order of magnitude bigger than the poster problem, and invisible because the UI falls back to a
  gradient.
- **`score`: 100 % of Hindi rows are NULL (2,973)** — yet the listing card *does* carry
  `<span class="rating fa-star"><span>7.867</span></span>`: **1,154/1,260 cards have a rating**
  (597 of the 618 poster-less rows). `parseCard` ignores it, and
  `ToonoProvider`'s KDoc (“the AJAX cards do not expose a stable rating”) is contradicted by the
  measured payload.
- **`description`: 100 % of toono rows are empty (1,258)** — the same card carries
  `<div class="entry-content"><p>…</p></div>` for **1,256/1,260 cards** (617 of the 618). A second
  free win available in the same `parseCard`, no extra requests: the existing `RATING` and
  `SYNOPSIS` regexes already match the card shape.
- Originals otherwise look healthy: `description` missing on 193 (2.1 %), `year` on 844 (9.0 %).

## 7. Can a stored poster fail to load?

All 11,744 stored poster URLs were audited structurally; 42 were fetched.

| check | result |
|---|---|
| non-`http(s)` URLs (relative, `data:`, protocol-less) | **0** |
| `http://` URLs | **0** |
| URLs with no file extension | **0** |
| distinct duplicate URLs | 4 groups / 4 surplus rows (image reuse, not a bug) |
| odd extensions | 4 `.jpe`, 1 `.avif`, 1 `images.plex.tv/photo?url=…` — sloppy but valid |

Per-host load check (`curl` with a range request, no special headers unless noted):

| host | rows | sampled | status | content-type |
|---|---|---|---|---|
| cdn.anipixcdn.co | 9,402 | 13 | 200 ×13 | image/jpeg, 58–117 KB (referer not required) |
| www.rareanimes.foo | 1,700 | 10 | 200/206 ×10 | image/jpeg, image/webp |
| image.tmdb.org | 576 | 10 | 200/206 ×10 | image/jpeg |
| i.imgur.com | 38 | 2 | 200 ×2 | image/jpeg, image/png |
| i.postimg.cc | 17 | 2 | 200 ×2 | image/jpeg |
| cdn.phototourl.com | 8 | 2 | 200 ×2 | image/jpeg, image/png |
| cdn.myanimelist.net | 2 | 2 | 206 ×2 | image/jpeg |
| images.plex.tv | 1 | 1 | 200 ×1 | image/jpeg |

**42/42 sampled URLs load.** No Cloudflare challenge, no 403-without-Referer, no dead host, no
http→https redirect trap. **Assumed** by extrapolation: the untested remainder behaves the same;
a full sweep of 11,744 URLs was out of scope for a research pass.

## 8. Verified vs assumed

| claim | status |
|---|---|
| 618 empty-poster toono rows are all `movies/` / `type=Movie` | **verified** (SQL: 618 of 618) |
| 640 toono `series/` rows all have a poster | **verified** (SQL: 0 of 640 empty) |
| the AJAX listing has 1,260 cards over 126 pages and stops (`next=false`) | **verified** (4 independent crawls) |
| the current `CARD_POSTER` misses 620 of them, entirely movie cards | **verified** (regex replayed over all 1,260 cards) |
| `<img[^>]*?\ssrc="…` recovers 1,260/1,260 | **verified** |
| 618 of those 620 slugs = the DB's empty-poster slugs (set intersection) | **verified** |
| every recovered URL is absolute `https://`, no logo/avatar false positive | **verified** |
| title pages carry the same poster, parsed by the existing `POSTER` regex | **verified, 7/7 sampled** |
| rareanimes REST returns `source_url` for the 15 empty rows | **verified, 2/2 by slug + 100/100 on the listing page** |
| the 15 rare rows are stale rather than absent | **assumed** — the request replays correctly today; timestamps cannot prove history |
| forcing a toono sync rewrites all 1,258 rows in place | **assumed** — follows from `writeToonoPage`'s unconditional `upsertAll`, not executed |
| a plain (non-forced) sync will not fix anything | **verified by code** — `hindiOriginsPresent()` short-circuits `syncInternal` |
| the other ~11,700 poster URLs load | **assumed** — 0.4 % sample (42 URLs), all 200/206 |
| toono's card rating/synopsis are stable enough to store | **assumed** — present on 1,154 and 1,256 of 1,260 cards today; not tracked across syncs |

## 9. Out-of-scope observation

`toono-aladdin` and `toono-cloudy-with-a-chance-of-meatballs` already have posters but are served as
**movie** cards today while their DB `originUrl` still says `/series/…`. Same for the item moved
between routes; their ids are slug hashes so they will silently re-point on the next forced sync.
Harmless for posters, worth knowing for deep links.

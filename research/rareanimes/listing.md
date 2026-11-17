# rareanimes.foo — Listing & catalogue (measured 2026-11-17)

Site: **Rare Toons India** — `https://www.rareanimes.foo/`
Stack: WordPress + theme `herald v2.6.2.1` + `Redux 4.5.18` + Elementor + CF7 · Cloudflare (`cf-edge-cache: cache,platform=wordpress`).
Mirror observed: `rareanimes.fans` (301 target for shared short links, renders the same site).

23 live requests, 2.5–3 s spacing, **no 429/403** encountered.

## 1. robots.txt

```
User-agent: *
Disallow: /wp-admin/
Allow: /wp-admin/admin-ajax.php
Sitemap: https://www.rareanimes.foo/sitemap.xml
```
`/wp-json/` is **not** disallowed → the REST API is fair game.

## 2. URL map (all verified 200)

| Kind | Template | Notes |
|---|---|---|
| Landing | `/` | static SEO gateway, no listings (only `?s=` shortcuts) |
| Real home | `/home/` | latest + language rails, `article.herald-lay-b` cards |
| Title (post) | `/<slug>/` | **flat permalink**, no `/anime/` or `/series/` prefix |
| Category | `/category/<slug>/` | e.g. `/category/hindi-dub/` |
| Category page N | `/category/<slug>/page/N/` | `/page/2/`, `/page/339/` both 200 |
| Search | `/?s=<q>` | `?s=naruto` → 200, 9 results page 1 |
| Series index | `/all-anime-cartoon-series-list/` | **1540 unique title URLs**, single page, A–Z |
| Movies index | `/all-anime-cartoon-movies-list/` | **1040 unique title URLs**, single page |
| RSS | `/category/hindi-dub/feed/` | only 6 items — useless for enumeration |
| REST | `/wp-json/wp/v2/posts` | open · `X-WP-Total: 1942` · `X-WP-TotalPages: 20` |

## 3. Enumeration — only REST is complete

| method | coverage | cost |
|---|---|---|
| `sitemap.xml` | **273 `<loc>`** = 14 % (no `/category/` entries) | 1 req |
| **`/wp-json/wp/v2/posts?per_page=100`** | **1942 posts / 20 pages — complete** | **20 req** |
| HTML category archive | 339 pages × ~5–8 posts | 339 req |
| `?s=`, RSS, admin-ajax | partial / useless | — |

`_fields` and `_embed=wp:featuredmedia` both work. Poster requires `_links` in `_fields` **or** the
`_embedded` block is omitted — include both.

## 4. Language taxonomy — the important part

There is **no `hreflang`, no language meta, no `english` category**. Language is a **WP category**
(`category-<slug>` in post classes / `class_list`):

| category | id | posts |
|---|---|---|
| **`hindi-dub`** | **180** | **1693** |
| **`hindi-sub`** | **297** | **34** |
| tamil | — | 316 |
| telugu | — | 311 |
| urdu | — | 15 |
| bengali | — | 9 |
| malayalam | — | 5 |

Proof of completeness:
```
?categories=180,297          → X-WP-Total: 1715
?categories_exclude=180,297  → X-WP-Total: 227
1715 + 227 = 1942 ✓
```
→ **88.3 % Hindi / 11.7 % English-or-original.** Sample of newest 100: 97 Hindi / 3 non-Hindi.

Per-post the authoritative line is `🔊Language:` inside the content, e.g.
`Hindi – Original | Multiaudio: Track 1 – Tamil, Track 2 – Telugu, Track 3 – Bengali`.

**Tags are junk for this** (8783 tags; `hindi`=16, `hindi-dubbed`=13) — use categories only.

## 5. Per-title data (single `post` type, no episode CPT)

REST fields: `id, slug, link, date, title, categories, tags, featured_media, content.rendered`.
No `episode`/`series`/`video` post type exists.

Content HTML carries (emoji-labelled):
```
📰 Full Name · 🍂 Season · ⌛ RunTime · 🎞 Episodes: <n> · 🌐 Network
📅 Year/Release Year · 🔊 Language · 🎭 Genre · 🌟 Rating · 🎬 Quality · synopsis
```
**No TMDB / MAL / IMDb ids** (0 of all three in the sample and in `_fields` output).

Type detection: `category-movies` (613 posts) ⇒ movie, else series;
`category-animes` (1154) ⇒ anime vs cartoon.

## 6. Episodes are inline — there are no episode pages

Zero `/episode/` URLs anywhere. One season post holds the whole episode list:

```html
<p>Episode 01 (373) – Team 7 Assemble!</p>
  <span style="color:…">…</span>   ← one coloured row per language/quality
    hosts: hubcloud.ist · yournewdomain.com · pixeldrain.com · mega.nz
           argon.razorshell.space/downlead/<id>  ← streaming embed
```
So **catalogue + episodes arrive in one request per season** (content field), which makes the
nightly job very cheap.

## 7. Pagination / cache behaviour

| endpoint | `cf-cache-status` |
|---|---|
| `sitemap.xml` | HIT |
| `/`, `/category/...`, `/?s=` | MISS / EXPIRED |
| `/wp-json/*` | BYPASS |

Server-rendered: the browser's `innerText` contained the same `Episode 01` rows as curl —
**no JS needed for listing or episodes**.

## 8. Cheapest safe ingestion plan (20 requests/night)

```
1. GET /wp-json/wp/v2/categories?per_page=100                 (id → name/slug map, cached for weeks)
2. for page in 1..20:
     GET /wp-json/wp/v2/posts?per_page=100&page=N
         &_fields=id,slug,link,date,title,categories,featured_media,content,_links,_embedded
         &_embed=wp:featuredmedia
   → upsert by `id`; skip posts whose `date` is unchanged
3. Hindi flag = categories ∋ {180, 297};  movie = categories ∋ movies-cat;  anime = categories ∋ animes-cat
4. Episode list parsed from `content.rendered` only when the post is new/changed
```
- 20 requests + 1 = **21 requests** for the full 1942-post catalogue.
- Never touch `/wp-admin/`, the download hosts, or `codedew.com` during sync.
- Cadence 3 s + jitter; stop on 403/429 rather than retrying.

## 9. What was not verified

- `?paged=` on search, `/home/page/N/`
- `wp-admin/admin-ajax.php` (skipped deliberately — robots allows it, but it is a search endpoint)
- Exact category ids for tamil/telugu/urdu/bengali/malayalam (only counts measured)
- Whether `content.rendered` pagination exists for seasons with 100+ episodes

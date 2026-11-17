# rareanimes.foo — provider implementation notes

Companion to `listing.md`, `streaming.md`, `download_and_risk.md`. Those were written before
`RareAnimesProvider.kt` existed; this file records what the **implementation** proved, what changed
on the site since, and which claims are verified vs inherited.

Code: `app/src/main/java/com/animo/app/data/hindi/RareAnimesProvider.kt`
(package `com.animo.app.data.hindi`, no `android.*`, no Jsoup, no Retrofit).

---

## 1. Working request shapes

All with `User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/156.0.0.0 Safari/537.36`
and no cookies. Cloudflare never served a challenge to any of these.

| call | request | status | notes |
|---|---|---|---|
| catalogue page | `GET /wp-json/wp/v2/posts?per_page=100&page=N&_fields=id,slug,title,link,date,categories,featured_media,excerpt` | 200 | `x-wp-total: 1942`, `x-wp-totalpages: 20`, 79,611 B for 100 rows |
| Hindi slice | `…&categories=180,297` | 200 | `x-wp-total: 1715`, `x-wp-totalpages: 18` — **OR**, not AND |
| detail | `GET /wp-json/wp/v2/posts/{id}?_fields=id,slug,title,link,date,categories,featured_media,content,excerpt,_links,_embedded&_embed=wp:featuredmedia` | 200 | 33,225 B for post 78626; poster + content in one call |
| page past end | `…&page=21` | **400** | `rest_post_invalid_page_number` — not an empty 200 |

`categories=180,297` → 1,715 ≠ 1,693 + 34 = 1,727: twelve posts carry both categories. The comma form
is `IN`; the provider still re-fetches 180 and 297 separately if a row arrives with neither, so an
install change degrades to 2 extra requests instead of a wrong language flag.

`_embedded` is dropped when `_fields` omits `_links`, which is why [TITLE_QUERY] asks for both.

---

## 2. DRIFT: the `codedew.com/zipper` gate is gone from current posts

`streaming.md` describes every episode row as
`<a href="https://codedew.com/zipper/?url=<opaque>">`. That is **no longer how new posts are written**.

Sampled posts (2026-11, 2026-01, 2024-12, 2020): **0** `codedew.com/zipper/?url=` occurrences.
Rows now point straight at final hosts:

```
argon.razorshell.space/downlead/<id>     ← the player, same id as /embed/<id>
swift.multiquality.click/embed/<id>
swift.multiquality.click/downlead/<id>
hubcloud.ist | .pw | .mov /drive/<id>
yournewdomain.com/drive/<id>
pixeldrain.com/u/<id> · mega.nz/file/<id> · gofile.io/d/<id> · mediafire.com/file/…
codedew.com/zipcloud/?<id>               ← batch archive, 302 → hubcloud.mov/drive/
```

`codedew.com/zipper/` still exists (a valid old token answers 200) but is now only reachable from
old/batch posts, so the provider treats it as a **legacy branch**, not the main path.

---

## 3. The `/downlead/<id>` → `/embed/<id>` identity (new, verified)

| request | status | bytes | title |
|---|---|---|---|
| `argon.razorshell.space/downlead/NRf7nNo5GJ2eBqO` | 200 | 5,101 | `Download Rich Girl Caretaker S01E01 [Hindi]` |
| `argon.razorshell.space/embed/NRf7nNo5GJ2eBqO` | 200 | 10,301 | `Embed Rich Girl Caretaker S01E01 [Hindi]` |
| `codedew.com/multiquality/?url=NRf7nNo5GJ2eBqO` | 200 | 70,234 | contains `<iframe id="playerIframe" src="https://argon.razorshell.space/embed/NRf7nNo5GJ2eBqO" …>` |

The id is shared across `downlead`, `multiquality?url=` and `embed`, so a row can be turned into a
playable embed with a **string rewrite and zero requests**.

## 4. `swift.multiquality.click` is NXDOMAIN, and its ids still work on argon

* `dig`/`getent` → NXDOMAIN; apex `multiquality.click` parks at `192.64.119.51`.
* 82 of ~119 `WatchMultiQuality`/`WatchMultQuality` links in the sample point at that host, so a
  blind implementation would have had a >60 % dead-stream rate.
* Verified repair: `argon.razorshell.space/embed/XM6WRpw1UCmeuw7` → 200, `Embed Tokyo Revengers S01E12`;
  `…/embed/8OJMBpWajBYSFTc` → 200, `Embed [RAI] Detective Conan S2E19 (047) in Hindi`.

Rule implemented: any `*.multiquality.click` URL is rebuilt as
`https://argon.razorshell.space/embed/<last path segment>`.

---

## 5. Legacy zipper chain, re-measured (5 s is client-side only)

| hop | request | status | bytes | finding |
|---|---|---|---|---|
| step 1 | `GET codedew.com/zipper/?url=<opaque>` | 200 | 26,771 | `Step 1 of 3 — Scanning Link Safety...`, `timeLeft = 5`, `data-href="/zipper/?url=<rotated>&ad_done=1"`; sets `PHPSESSID`, `zipper_client_id` |
| step 2 | `GET …&ad_done=1` | 200 | 26,638 | `Step 2 of 3 — Generating Secure Link...`, `timeLeft = 5`, `data-href` = final destination |

* **The 5 s wait is skippable.** Both steps publish the next hop in `data-href` on arrival; the timer
  only gates the button's click handler. The provider follows `data-href` immediately and never sleeps.
* **No cookie jar needed.** Step 2 returned the identical 26,638 B with no cookies at all. (An earlier
  400 was self-inflicted: the opaque token had been truncated in a terminal copy-paste. A full
  221-char token, zero cookies → 200.)
* A token re-issued by step 1 is the only thing step 2 requires; `&ad_done=1` alone is not enough.
* A batch ("ZIP – GoFile") token's step 2 landed on a `rareanimes.foo` post, i.e. the gate is generic
  over whatever the token encodes, not a stream-specific hop.

---

## 6. Two post layouts the parser must handle

**Series** (78626, 79113):

```html
<p>Episode 01 (373) – Team 7 Assemble!</p>
<p><span style="color: #99cc00;">Hindi </span>– [<a href="…/downlead/<id>">WatchMultiQuality</a>] [HubCloud] [WatchNow] [DLBeta] [Mega]</p>
<p><span style="color: #ff00ff;">Tamil </span>– [<a href="…">HubCloud</a>] [<a href="…">WatchNow</a>]</p>
```

**Movie** (69702, 52817) — no episode marker at all:

```html
<h4><span style="color:#…">Hindi – Download</span></h4>
<h3><a href="…">WatchMultiQuality</a></h3>
<h3><a href="…">HubCloud</a></h3>
```

Consequences baked into the parser:

1. **Merge by episode number.** 79113 repeats the heading for an "Untouched (Multi Audio)" row; naive
   append gave **39** episodes instead of 21.
2. **Separate null-episode bucket.** Movie posts have no `Episode N` at all, so their rows land in
   `RareTitle.batchLinks` (`episode = null`) instead of being dropped.
3. **Language** comes from the coloured `<span>` (parse the text, never the colour) or from the
   `<h4>Hindi – Download</h4>` heading; the heading's `– Download` suffix is stripped before storing.

Measured counts after parsing (JVM run of `RareAnimesParser` on the saved bodies):

| post | kind | episodes | episodeLinks | batchLinks |
|---|---|---|---|---|
| 78626 | series, 12 eps | **12** | **62** | 0 |
| 79113 | series, 21 eps | **21** | **276** | 0 |
| 69702 | movie | 0 | 0 | **8** |
| 52817 | movie | 0 | 0 | **3** |

Poster fallback (all from `_embedded.wp:featuredmedia.media_details.sizes`):
`medium_large` (768×432) → `large` → `herald-lay-b` (470×264) → `medium` → `full` (2560×1440) →
`thumbnail`, then `source_url`, then the first inline `<img>`.

---

## 7. Verified vs assumed

**Verified live (2026-11-17):** every status/byte/title in §1, §3, §4, §5; the parser counts in §6;
poster/synopsis extraction from the real post 78626 payload.

**Inherited from the earlier crawl, not re-probed:** the HLS/CDN layer behind the embed
(`_juicycodes` decode, `*.groovy.monster` master playlist, header-gated segments). That belongs to the
`JuicyDecoder` in this module, and the embed page still exposes `_juicycodes` + `juicyData` + `jwplayer`.

**Not verified, and the provider does not depend on it:** whether `codedew.com/zipper` will be retired
entirely; the exact TTL of a zipper token; whether a `WatchNow`-labelled row ever resolves to a player
(it is a file host in every sample).

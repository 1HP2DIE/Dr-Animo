# Multi-season data audit — how one franchise is stored today

Measured against the live Room database (`/tmp/opencode/animo3.db`, 2026-11-17). Every number below is
`verified` unless the row is tagged otherwise. Analysis scripts: `/tmp/opencode/ms{6,7,8,9,10}.py`.

## 0. Schema facts that change the design

| Fact | Value |
|---|---|
| `anime` rows | 12,377 — 9,404 `id>0` (anikoto) + 2,973 `id<0` (toono 1,258 · rareanimes 1,715) |
| `season` / `seasonNorm` | the **broadcast** season (Fall/Spring…), **not** a sequel number; 11 spellings, incl. `&nbsp` and `SPRING` |
| Sequel number | exists **only inside `title`** (and sometimes only in `alternative`) |
| `malId` / `aniId` / `sId` | per-season, per-MAL-entry. 9,247 distinct malIds over 9,261 non-blank rows (max 2 rows per malId) → **cannot join seasons** |
| Hindi rows | 100 % have `malId=''`, `aniId=''`, `sId=0` → **no cross-space join key exists today** |
| `episode` table | 4,355 rows covering only **195 anime** (194 EN + 1 HI) |

## 1. Franchise keys — measured on all 12,377 rows

| key | groups | singleton | multi-row | 2–3 | 4+ | max group |
|---|---|---|---|---|---|---|
| K0 raw `titleNorm` | 11,981 | 11,598 | 383 | 383 | 0 | 3 |
| K1 title base¹ | 9,859 | 8,395 | **1,464** | 1,276 | 188 | 32 |
| K2 = K1 + 5 aliases² | 9,857 | 8,394 | **1,463** | 1,276 | 187 | 34 |
| K2, English only | 8,594 | 8,013 | 581 | 536 | 45 | 9 |
| K2, Hindi only | 1,632 | 726 | 906 | 817 | 89 | 33 |

¹ strip `Season N` / `Part N` / `Nth Season` / `Final Season` / roman tail / year / scraper noise, accent-fold.
² `shinchan→shin chan`, `Detective Conan→Case Closed`, `Boku no Hero→My Hero Academia`, `Shingeki→Attack on Titan`, `Pokemon→Pokémon`.

Composition of the 1,463 groups: 898 have ≥2 TV/ONA rows · 113 are "1 TV + movies" · 384 movies-only ·
54 "1 TV + other" · 14 other. **280 groups have ≥2 distinct explicit season numbers** (1,297 rows).
TV-row counts: 4,960 singles · 462 pairs · 267 triples · 59×4 · 53×5 · 23×6 · 10×8 · 2×20 · 1×32 · 1×34.

### Precision vs recall — the decisive finding

- **Precision ~97 %**: a 85-group sample (40 largest + 45 random, seed 7) looked like genuine
  same-franchise groupings; 2 debatable (`Devil May Cry` 2007 vs 2025, `Area 88` OVA 1985 vs TV 2004).
- **Recall is the problem.** Largest-key capture per family:

| family | capture | family | capture |
|---|---|---|---|
| Pokémon | **3 %** (148 rows → 127 keys) | One Piece | 21 % |
| Gundam | 3 % | Conan | 21 % |
| Monogatari | 4 % | Bleach | 20 % |
| Precure | 6 % | JoJo | 20 % |
| Dragon Ball | 8 % | Naruto | 32 % |
| Digimon | 8 % | Fairy Tail | 53 % |
| Saint Seiya | 6 % | Hunter × Hunter | 60 % |

**Across those 16 families: 143 of 966 rows = 15 %.**
Reason: EN rows are MAL entries titled by subtitle (`Pokémon: XYZ`, `Dr. Stone: Stone Wars`,
`Bleach: Thousand-Year Blood War`) with **no season marker** — only **697/5,862 (12 %) of EN TV rows
carry a numeric season marker**, versus **1,087/1,827 (59 %) of Hindi TV rows**.

## 2. Hard cases (all verified)

**Attack on Titan** — 11 EN rows under one key: `1631` (25 ep) · `1385 Season 2` (12) · `1585 Season 3`
(22) · `5687 Final Season Part 1` (16) · `6694 Final Season Part 2` (12) · `6476 The Final Season
Part 3` (2). Movies live in *separate* keys (`233`, `507`, `1788`, `7624`), OVAs in three more (`1024`,
`1367`, `473`), plus a spin-off (`2916 Junior High`). Hindi side: `-980043140` plus `Season 1–4`
(only 2 of 4 have `year`, **all `episodeCount=0`**). HI "Season 4" ≈ EN Final Season Parts 1+2+3 —
three MAL entries, one Hindi page.

**Season marker only in `alternative`/`native`** — **287 rows**. Examples: `21 Sengoku Youko`
(alt "…Part 2"), `54 Code Geass … R2`, `55 Owarimonogatari Second Season`, `72 High School DxD BorN`,
`84 Full-Time Magister 3`, `106 Rurouni Kenshin -Kyoto Disturbance-`, `143 Baki: Dai Raitaisai-hen`,
`137 Amagami SS+ plus`. Only **14** titles spell out "Second/Third… Season"; suffixes (`II`, `BorN`,
`R2`, `3`) are invisible to any numeric regex.

**Movies / OVAs** — **1,594** Movie/Special/OVA rows begin with an existing franchise key
(`-1000078275 Doraemon New Nobita … (Movie)`, `-1000077479 Death Note Relight 2`). 23 groups mix
seasons and movies (One Piece 5 TV + 2 movies; Re:ZERO 9 + 3; Ghost in the Shell 12 + 15).

**Duplicates inside one id space** — EN **105 groups / 111 extra rows**. 80 differ by year (reboots:
`68` vs `1334` Hunter × Hunter 1999/2011, `1639`/`4175` Berserk), **25 same year** (`21` + `6207`
Sengoku Youko both TV 2024; `336` TV + `1975` Special). Same-`malId` duplicate groups: **14 groups /
28 rows**, mostly "(Uncensored)" twins (mal 14719 `1485`/`6845`, mal 25429 `4728`/`7040`, …).

**Same franchise in both spaces** — 369 exact key overlaps; **310 keys have TV rows in both spaces**
(465 EN + 785 HI rows).

| franchise | EN | HI |
|---|---|---|
| Attack on Titan S1 | `1631` / 25 ep | `-1000053499` / 0 |
| Apothecary Diaries S2 | `7491` / 24 | `-1000054607` / 0 |
| One Piece S22 | `1642` / 1,180 (all arcs in one row) | `-1000071477` / 0 |

> Correction to an earlier assumption: "Pokémon Season 9 Battle Frontier" is duplicated **inside the
> Hindi space** (`-1000000088` toono / `-10053952` rareanimes), not across spaces. Hindi
> cross-provider duplicate groups overall: **1**.

**Same-season collisions across spaces** — **89 groups / 141 instances** where two rows map to the
same franchise + season number; **87 are EN+HI pairs**. Only **87 of 1,098** Hindi season-marked rows
(8 %) can be matched to an EN row by base+season — 1,011 cannot.

**Long-runners**

| franchise | EN rows / TV | HI rows / TV | shape |
|---|---|---|---|
| Pokémon | 58 / 24 | 90 / 57 | EN split by MAL sub-series (`387` 276 ep, `2699` 192, `2334` 191, `3087` 145, `2082` 93, `4337` 48, `3879` 49, `421` 48) → **127 keys for 148 rows** |
| Detective Conan | 47 / 5 | 56 / 35 | EN = one row `1458` **1,220 eps**; HI = Seasons 01–18 as 18 rows |
| Doraemon | 36 / 2 | 93 / 39 | 32-row group (seasons 1–22) + `155 Doraemon (2005)` |
| Naruto | 26 / 5 | 37 / 33 | two MAL franchises: Naruto (11) / Shippuden (20) |
| One Piece | 58 / 5 | 10 / 7 | `1642` 1,180 eps + 34 movies/specials; HI only Seasons 01/20/22 |
| Shin Chan | **0 / 0** | 20 / 20 | EN has none at all |

## 3. Episode integrity

- 195 anime have episodes. **`episodeCount` vs `episode` count mismatch: 0.**
- Gaps (max number ≠ count): **5** — `6414 BEYBLADE X` 138/139 · `7619 Koupen-chan` 73/84 ·
  `8194 Digimon Beatbreak` 50/49 · `9131` 3/9 · `9169` 40/51.
- Duplicate episode number: **1** — `8194` number 11 twice (sub-only + sub+dub re-publish).
- `episodesDeclared>0 && episodeCount>0 && differ`: 63 rows.
- Episode titles carry no information (`"Episode 11"`), so "an episode belonging to the wrong season"
  is unverifiable in principle — and irrelevant, episodes are FK'd per `animeId`.
- **A season with 0 episodes is the norm:** 2,005 of 7,689 TV/ONA rows have both counters at 0 and
  **all 1,827 Hindi TV rows report 0**. The picker cannot gate on episode availability.
- `progress` = 5 rows, keyed on `animeId` → progress survives a season split naturally.

## 4. Smallest correct model

A single `franchiseKey` column + `GROUP BY` is **not enough**: 1,463 groups at ~97 % precision covers
3,983 rows, but it recovers only **15 %** of the rows in the 16 largest families.

Measured residual manual work → **~450–500 franchise families need an explicit mapping record**:
the 27 mega-groups (316 rows), **290 orphan season rows** (season ≥2 with no season-1 sibling, e.g.
`-1000069072 Power Rangers Ninja Steel Season 24`, `-1000077015 JoJo Season 3`), **287** rows whose
marker lives only in `alternative`, **1,594** movie/special orphans, **87** EN+HI same-season pairs and
**1,011** unmatchable Hindi season rows.

```sql
-- migration v3 -> v4
ALTER TABLE anime ADD COLUMN franchiseKey TEXT NOT NULL DEFAULT '';  -- Normalize.franchiseKey(title)
ALTER TABLE anime ADD COLUMN seasonNumber INTEGER;                   -- NULL = unknown
ALTER TABLE anime ADD COLUMN seasonLabel  TEXT NOT NULL DEFAULT '';  -- "Final Season Part 2", "Season 4"
CREATE INDEX index_anime_franchiseKey ON anime(franchiseKey);

CREATE TABLE franchise (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  key TEXT NOT NULL UNIQUE,            -- canonical slug, e.g. 'pokemon'
  displayTitle TEXT NOT NULL,
  year INTEGER
);
CREATE TABLE franchise_alias (
  alias TEXT PRIMARY KEY,              -- title-derived key OR raw titleNorm
  franchiseId INTEGER NOT NULL REFERENCES franchise(id),
  seasonNumber INTEGER,                -- forced season index for this alias
  seasonLabel TEXT NOT NULL DEFAULT ''
);
CREATE TABLE franchise_member (        -- only for rows the key gets wrong
  animeId INTEGER PRIMARY KEY REFERENCES anime(id),
  franchiseId INTEGER NOT NULL REFERENCES franchise(id),
  seasonNumber INTEGER,
  kind TEXT NOT NULL DEFAULT 'season'  -- season|movie|ova|special|spinoff|adaptation
);
```

Kotlin key (pure, testable, mirrors `Normalize.titleSortKey`):

```kotlin
object FranchiseKey {
    private val strongNoise = Regex(
        """\b(?:hindi|tamil|telugu|malayalam|dubbed|subbed|dub|sub|episodes?|downloads?|blu-?ray|web-?dl|[0-9]{3,4}p|4k)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val markers = listOf(
        Regex("""\b(?:the\s+)?final\s+season\b""", RegexOption.IGNORE_CASE),
        Regex("""\bseason\s*([0-9]{1,3})\b""", RegexOption.IGNORE_CASE),
        Regex("""\b([0-9]{1,3})(?:st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\s+season\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpart\s*([0-9]{1,3})\b""", RegexOption.IGNORE_CASE),
        Regex("""\bseason\s+([ivx]{1,6})\b""", RegexOption.IGNORE_CASE),
    )
    private val romanTail = Regex("""^(.*\S)\s+([ivx]{1,6})$""")

    fun key(title: String): String {
        var s = Normalize.foldAccents(title)                    // Pokémon -> Pokemon
        strongNoise.find(s)?.let { s = s.substring(0, it.range.first) }
        markers.forEach { s = it.replace(s, " ") }
        romanTail.matchEntire(s.trim())?.let { m ->
            if (m.groupValues[2].lowercase() in ROMAN && m.groupValues[1].length >= 4) s = m.groupValues[1]
        }
        return s.lowercase().replace(Regex("""\s+"""), " ").trim(' ', '-', ':', ',', '.', '\'')
    }

    fun seasonNumber(title: String): Int? = /* same regexes, 1-based; "final season" -> 99 */ null
}
```

Screen model: `franchise -> List<SeasonRow>` where `SeasonRow(number, label, editions: List<AnimeEntity>)`
— `editions` holds the EN row and/or the HI row for the same season, which is exactly what the 87
EN+HI pairs and the 28 "(Uncensored)" twins need. Backfill `franchiseKey` + `seasonNumber` in the
v3→v4 migration in Kotlin (same pattern as `titleNorm`), then apply `franchise_member` overrides at
read time — never let the ingest path overwrite a manual mapping.

## 5. Traps to record

1. `season`/`seasonNorm` ≠ sequel number — it is the airing season; 11 spellings incl. `&nbsp`.
2. `malId`/`aniId` are per-season; `sId` is unique per row (9,403/9,404). None is a franchise key.
   Hindi rows have **none of them**.
3. `Season 2` vs `2nd Season` vs `Season II` vs `Second Season` vs `II` suffix vs `BorN`/`R2`/`3`.
4. Accents: `Pokémon` vs `Pokemon` — fold NFKD or the same franchise lands in two keys.
5. Scraper tails: `– Episodes Hindi – Tamil – Telugu Dubbed Download HD`, `(Sony Yay Dub)`, `[Raw]`,
   `(Uncensored)`, `(TV)`, `(REMAKE)`. Truncate at the wrong stopword and you destroy titles
   (`Chillin' in My 30s` → `chillin'`; titles starting with `In`/`All` → empty keys).
6. Movie titles starting with the franchise name (1,594 rows) must not become a season.
7. Duplicate same-season rows (87 EN+HI pairs, 28 malId twins, 25 same-year dupes, 1 Hindi
   cross-provider dupe) must render as **one season with two editions**, not two seasons.
8. Zero-episode seasons are normal — the picker cannot gate on episode availability.
9. `episodeCount` is populated on **2 %** of EN rows (`episodesDeclared` on 77 %); use
   `max(episodeCount, episodesDeclared, count(episode))` for the "24 episodes" label (63 rows disagree).
10. Reboots share a title (Hunter × Hunter 1999/2011, Berserk 1997/2016): same key but **not**
    seasons 1 and 2 of one show — needs `kind='adaptation'` + year, or the picker lies about
    continuity.

## 6. Assumed (not measured)

- That a manual `franchise_member` table of ~500 rows is maintainable — plausible by volume, unproven
  by workflow.
- That the 97 % sample precision holds across the unsampled 1,378 groups.
- That grouping improves browsing for this catalogue — a UX claim, not a data claim.

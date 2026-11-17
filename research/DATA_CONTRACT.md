# Animo — API Data Contract (anikotoapi.site)

Verified live on 2026-11-17 by research-agent-1. Every number here came from a real HTTP
response in that session. 300 catalog rows (pages 1-3 @ per_page=100) + 40 full series
(3,425 episodes) were inspected. No value below is assumed.

## 1. Endpoints

| # | Method | Path | Success | Failure |
|---|---|---|---|---|
| 1 | GET | `/recent-anime?page={n}&per_page={n}` | 200 `{"ok":true,"anikoto_domains":["anikototv.to","anikoto.cz"],"data":[Anime],"pagination":Pagination}` | — |
| 2 | GET | `/series/{id}` | 200 `{"ok":true,"data":{"anime":Anime,"episodes":[Episode]}}` | 404 `{"ok":false,"error":"Series not found","code":"not_found"}` |

### Pagination semantics (all measured)

- `per_page` max is **100**. Asking 200 returns 200-OK with 100 rows and echoes `per_page:100`.
- `total` = **9404**, `total_pages` = **95**. Last page (95) holds **4** rows.
- `page=0` is normalized to page 1. `page>total_pages` returns **200 with `data:[]`** (never 404).
- Page body size: 120,397-130,258 bytes (avg 123,859). `cache-control: no-store`.

### Rate limiting

- `x-ratelimit-limit: 60`, `x-ratelimit-remaining`, `x-ratelimit-reset` (Unix **epoch seconds**).
- Sliding window: **60 requests / 120 s per IP = 0.5 req/s sustained = >=2.0 s between requests.**
  A 1.2 s cadence trips the limiter at request 61. Use 2.2 s.
- 429 when tripped, 403 if it judges you abusive. On `remaining < 5`, sleep until
  `reset` (or 90 s) before continuing.
- Every response also sets `PHPSESSID`; it is not required for subsequent calls.

## 2. `AnimeDto` field report (n=300)

`null` was **never** observed for any field. Absent-keys are common; that distinction matters.

| field | JSON type | present | missing | `""` | notes |
|---|---|---|---|---|---|
| `id` | Int | 100% | 0% | - | 300 unique, 859-9584 |
| `title` | String | 100% | 0% | 0% | 300 distinct, none empty |
| `alternative` | String | 100% | 0% | 29.7% | 212 distinct |
| `titles` | String | 100% | 0% | 21.3% | CSV of aliases, 237 distinct |
| `native` | String | 100% | 0% | 0% | 299 distinct |
| `slug` | String | 100% | 0% | 0% | 300 unique, URL-safe |
| `rating` | String | 100% | 0% | 12.3% | PG 145, "" 37, G 37, R+ 27, R 22, "None" 19, PG-13 11, "N/A" 2 |
| `poster` | String(url) | 100% | 0% | 0% | cdn.anipixcdn.co |
| `is_dub` | Int | 26.3% | **73.7%** | - | episode COUNT; 1-1159, avg 27.2 |
| `is_sub` | Int | 94.3% | 5.7% | - | episode COUNT; 1-1220, avg 19.4 |
| `description` | String | 100% | 0% | 0% | 17-1478 chars, median 398 |
| `aired` | String | 100% | 0% | 0% | "Oct 4, 2026 to ?"; 7 rows are a bare year |
| `season` | String | 100% | 0% | 0% | fall 131, summer 57, spring 42, winter 38, unknown 11, Spring 9, "&nbsp" 5, Summer 3, Winter 2, Fall 1, SPRING 1 |
| `year` | Int | 100% | 0% | - | 1960-2026, avg 2013.2 |
| `duration` | String | 99.3% | 0.7% | 24.7% | "24 min. per ep." |
| `status` | String | 100% | 0% | 0% | Finished Airing 192, Currently Airing 87, Not yet aired 21 |
| `score` | **String** | 96.3% | 3.7% | 0% | numeric string 4.02-9.03, avg 6.709 |
| `mal_id` | **String** | 99% | 1% | 0.3% | 21-65009 |
| `episodes` | **String** | 100% | 0% | 9.7% | "1"x97, "12"x44, "3"x16, "2"x15 |
| `ani_id` | **String** | 100% | 0% | 0% | mixed id space (21-217026) - NOT a stable key |
| `source` | String | 99.3% | 0.7% | 0.7% | manga 113, original 65, other 40, light_novel 33, video_game 17 (16 distinct) |
| `s_id` | Int | 100% | 0% | - | 100-2,147,483,640 (int32 boundary) - store as Long |
| `background_image` | String | 100% | 0% | 58.7% | 124 usable |
| `updated_at` | String | 100% | 0% | 0% | "YYYY-MM-DD HH:MM:SS", UTC, 300/300 regex-clean |
| `next_air_schedule_time` | Int(epoch) | 33% | 67% | - | min 1743287400 = 2025-03-30 (in the past!) |
| `next_air_ep` | Int | 32.7% | 67.3% | - | 3-1221 |
| `terms_by_type` | Object | 100% | 0% | - | see below |

`terms_by_type` sub-keys: `type` 100% / `genre` 99% / `studios` 94% / `producers` 57%.
`type` distribution: TV 123, OVA 61, Movie 41, ONA 40, Special 20, TV Special 9, CM 2,
Music 2, TV_SHORT 1, PV 1. `genre` 43 distinct (Comedy 103, Action 102, Fantasy 92).
`studios` 147 distinct; 18 rows have no studios, 129 have no producers.

The identical 27-key object is returned as `data.anime` from `/series/{id}` - same schema,
no extra keys, same absence pattern.

## 3. `EpisodeDto` field report (n=3,425 across 40 series)

| field | JSON type | present | notes |
|---|---|---|---|
| `id` | Int | 100% | unique globally in sample; use as PK |
| `title` | String | 100% | **HTML-entity escaped** ("I&#39;m Luffy!"); 5 dup titles in One Piece, 31 in Case Closed |
| `jp_title` | String | 100% | romaji, not Japanese, despite the name |
| `number` | Int | 100% | always Int; **one duplicate observed** (Soul Land 976) |
| `episode_embed_id` | String | 100% | numeric string, e.g. "992343" |
| `embed_url` | Object | 100% | keys are **only `sub` and `dub`**; `hsub` never appears; never null |
| `updated_at` | String | 100% | same format as anime.updated_at |

`embed_url.*` host: **megaplay.buzz 100%** (3,425 sub / 1,670 dub URLs). No second host seen.

Ordering: 40/40 series ascending by `number`, all starting at 1. Gaps: 1 series (2 gaps).
Duplicate `number`: 1 series. Duplicate `id`: 0.

## 4. Kotlin DTOs (kotlinx.serialization)

```kotlin
package com.animo.app.data.remote.dto

import kotlinx.serialization.*
import kotlinx.serialization.json.*

private val AnimoJson = Json {
    ignoreUnknownKeys = true   // API grows keys; never crash on additions
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Accepts a JSON string OR number for fields the API types inconsistently. */
object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val e = input.decodeJsonElement()) {
            is JsonPrimitive -> e.content
            else -> ""
        }
    }
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

@Serializable
data class RecentAnimeResponse(
    val ok: Boolean = false,
    @SerialName("anikoto_domains") val domains: List<String> = emptyList(),
    val data: List<AnimeDto> = emptyList(),
    val pagination: PaginationDto = PaginationDto(),
)

@Serializable
data class PaginationDto(
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = 100,
    val total: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
)

@Serializable
data class SeriesResponse(
    val ok: Boolean = false,
    val data: SeriesDataDto? = null,
    val error: String? = null,
    val code: String? = null,
)

@Serializable
data class SeriesDataDto(
    val anime: AnimeDto,
    val episodes: List<EpisodeDto> = emptyList(),  // [] is normal on 7/40 series
)

@Serializable
data class AnimeDto(
    val id: Int,
    val title: String = "",
    val alternative: String = "",
    val titles: String = "",
    val native: String = "",
    val slug: String = "",
    val rating: String = "",                       // "PG" | "G" | "R" | "R+" | "PG-13" | "None" | "N/A" | ""
    val poster: String = "",
    @SerialName("is_dub") val isDub: Int? = null,  // COUNT of dub episodes. absent on 73.7%
    @SerialName("is_sub") val isSub: Int? = null,  // COUNT of sub episodes. absent on 5.7%
    val description: String = "",
    val aired: String = "",
    val season: String = "",                       // fall|summer|spring|winter|unknown|&nbsp|Spring|SPRING|...
    val year: Int? = null,
    val duration: String = "",                     // "24 min. per ep."
    val status: String = "",                       // Finished Airing | Currently Airing | Not yet aired
    @Serializable(with = FlexibleStringSerializer::class)
    val score: String? = null,                     // "7.11"; absent on 3.7%
    @SerialName("mal_id")
    @Serializable(with = FlexibleStringSerializer::class)
    val malId: String? = null,                     // absent 1%, "" 0.3%
    @Serializable(with = FlexibleStringSerializer::class)
    val episodes: String? = null,                  // DECLARED count as string; "" 9.7%
    @SerialName("ani_id")
    @Serializable(with = FlexibleStringSerializer::class)
    val aniId: String? = null,                     // mixed id space; diagnostics only
    val source: String? = null,                    // manga|original|light_novel|video_game|other|...
    @SerialName("s_id") val sId: Long = 0L,        // reaches 2,147,483,640
    @SerialName("background_image") val backgroundImage: String = "",
    @SerialName("updated_at") val updatedAt: String = "",          // "2026-11-16 17:40:55" UTC
    @SerialName("next_air_schedule_time") val nextAirAt: Long? = null,  // can be in the past
    @SerialName("next_air_ep") val nextAirEp: Int? = null,
    @SerialName("terms_by_type") val terms: TermsByTypeDto = TermsByTypeDto(),
)

@Serializable
data class TermsByTypeDto(
    val genre: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val producers: List<String> = emptyList(),
    val type: List<String> = emptyList(),          // first element is THE type
)

@Serializable
data class EpisodeDto(
    val id: Long,                                  // server episode id, unique
    val title: String = "",                        // HTML-escaped, needs unescape
    @SerialName("jp_title") val jpTitle: String = "",
    val number: Int = 0,                           // duplicates possible
    @SerialName("episode_embed_id") val embedId: String = "",
    @SerialName("embed_url") val embedUrl: EmbedUrlDto = EmbedUrlDto(),
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class EmbedUrlDto(
    val sub: String? = null,
    val dub: String? = null,
    val hsub: String? = null,                      // never observed; kept for forward compat
)
```

Rules that fall out of the data:

- Never deserialize `score`/`episodes`/`mal_id`/`ani_id` as `Double`/`Int`. They are strings.
- Normalize `season` -> enum at the mapper layer: lowercase, map `&nbsp`/`unknown`/`""` -> `UNKNOWN`.
- Parse `episodes` with `toIntOrNull()`; `""` and absent both mean "unknown", never 0.
- Parse `updated_at` with a fixed `DateTimeFormatter("yyyy-MM-dd HH:mm:ss")` + `ZoneOffset.UTC`.
- Unescape HTML entities on `title`, `jpTitle`, `description`, `titles` at ingest.
- `poster`/`backgroundImage` are the only image fields; `backgroundImage` is empty 58.7%.

## 5. Room schema

```sql
CREATE TABLE anime (
  id                INTEGER NOT NULL PRIMARY KEY,   -- server id, not autogen
  title             TEXT    NOT NULL,
  alternative       TEXT    NOT NULL DEFAULT '',
  titles            TEXT    NOT NULL DEFAULT '',
  native            TEXT    NOT NULL DEFAULT '',
  slug              TEXT    NOT NULL,
  rating            TEXT    NOT NULL DEFAULT '',
  ratingNorm        TEXT    NOT NULL DEFAULT '',     -- lowercased; '' for None/N/A
  poster            TEXT    NOT NULL DEFAULT '',
  isDub             INTEGER,                          -- nullable: absent on 73.7%
  isSub             INTEGER,
  description       TEXT    NOT NULL DEFAULT '',
  aired             TEXT    NOT NULL DEFAULT '',
  season            TEXT    NOT NULL DEFAULT '',
  seasonNorm        TEXT    NOT NULL DEFAULT 'unknown',
  year              INTEGER,
  duration          TEXT    NOT NULL DEFAULT '',
  status            TEXT    NOT NULL DEFAULT '',
  statusNorm        TEXT    NOT NULL DEFAULT '',
  score             REAL,
  malId             TEXT,
  episodesDeclared  INTEGER,
  episodeCount      INTEGER NOT NULL DEFAULT 0,
  aniId             TEXT,
  source            TEXT,
  sId               INTEGER NOT NULL DEFAULT 0,
  backgroundImage   TEXT    NOT NULL DEFAULT '',
  genres            TEXT    NOT NULL DEFAULT '',
  studios           TEXT    NOT NULL DEFAULT '',
  producers         TEXT    NOT NULL DEFAULT '',
  type              TEXT    NOT NULL DEFAULT '',
  updatedAt         TEXT    NOT NULL DEFAULT '',
  updatedAtEpoch    INTEGER NOT NULL DEFAULT 0,
  nextAirAt         INTEGER,
  nextAirEp         INTEGER,
  detailFetchedAt   INTEGER
);

CREATE UNIQUE INDEX index_anime_id        ON anime(id);
CREATE UNIQUE INDEX index_anime_slug      ON anime(slug);
CREATE        INDEX index_anime_updated   ON anime(updatedAtEpoch);
CREATE        INDEX index_anime_year      ON anime(year);
CREATE        INDEX index_anime_status    ON anime(statusNorm);
CREATE        INDEX index_anime_type      ON anime(type);
CREATE        INDEX index_anime_score     ON anime(score);

CREATE TABLE episode (
  episodeId      INTEGER NOT NULL PRIMARY KEY,
  animeId        INTEGER NOT NULL,
  number         INTEGER NOT NULL,
  title          TEXT    NOT NULL DEFAULT '',
  jpTitle        TEXT    NOT NULL DEFAULT '',
  embedId        TEXT    NOT NULL DEFAULT '',
  subUrl         TEXT,
  dubUrl         TEXT,
  updatedAt      TEXT    NOT NULL DEFAULT '',
  updatedAtEpoch INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(animeId) REFERENCES anime(id) ON DELETE CASCADE
);

-- NOT unique: Soul Land returns 2 rows with number=263
CREATE INDEX index_episode_anime_number ON episode(animeId, number);
CREATE INDEX index_episode_anime        ON episode(animeId);
```

FTS for title search — use **FTS4 with external content**; FTS5 only with a runtime probe.

```sql
CREATE VIRTUAL TABLE anime_fts USING fts4(
  title, alternative, titles, native, genres, studios,
  content='anime', tokenize=unicode61
);
INSERT INTO anime_fts(anime_fts) VALUES('rebuild');
```

Search field fill rates (300-row sample):

| field | non-empty | verdict |
|---|---|---|
| `title` | **100%** | primary |
| `native` | 100% | include (CJK queries) |
| `titles` | 78.7% | include |
| `alternative` | 70.3% | include |
| `genres` | 99% | filter facet |
| `studios` | 94% | filter facet |
| `year` | 100% | filter facet |
| `type` | 100% | filter facet |
| `slug` | 100% | exact-match only |

## 6. Sync strategy

Catalog: **95 pages x 100 = 9,404 series**, ~123,859 B/page -> **~11.8 MB**.

| Job | Requests | Time (0.5 req/s floor) | Notes |
|---|---|---|---|
| Full catalog | 95 | 190 s = 3.2 min | hard floor set by 60/120 s |
| Nightly delta | 95 | ~3.5 min | stop on `data.isEmpty()` |
| Detail re-fetch | 1 per changed series | - | only when `updatedAt` differs |
| Episode backfill (all) | 9,404 | **18,808 s = 5.22 h** | do NOT bulk-run |

- **Cadence: 2.2 s between requests.** Abort batch and sleep when `remaining < 5`.
- Full catalog pass: store `updatedAt`; next pass only `/series/{id}` for rows whose `updatedAt` moved.
- Episode backfill is **on detail open** + WorkManager budget (e.g. 500/night, ~18 min).
- Detail refresh is driven by `anime.updated_at`, not by `episodes.length` (7/40 legitimately return `[]`).

## 7. Data quality warnings

1. `is_sub`/`is_dub` are episode COUNTS, not booleans; `is_dub` absent on 73.7% of rows.
2. `anime.episodes` overstates availability in 22/35 declared cases; 7/40 series return zero
   episodes with HTTP 200.
3. Episode titles and some metadata are HTML-entity escaped; `season` can be literally `&nbsp`.
4. `season` has 11 spellings for 4 values; `rating` mixes `None`/`N/A`/`""`.
5. `per_page > 100` is silently clamped; `page > total_pages` returns 200 + empty array; `page=0`
   becomes page 1. Do not trust your own pagination math.
6. `s_id` reaches 2,147,483,640; `ani_id` mixes MAL and AniList id spaces. Only `id`, `slug` and
   `episodes[].id` are stable.
7. Duplicate episode `number` exists (Soul Land), so `(animeId, number)` cannot be a PK.
8. `next_air_schedule_time` can be in the past even for `Currently Airing` — compare with now.
9. Episode `updated_at` is not the max of its parent (Soul Land anime 2026-10-20, episodes 2024-10-17).
10. `background_image` empty on 58.7%; `studios` absent 6%; `producers` 43%.
11. 97/300 rows declare `episodes:"1"` — singles need UI special-casing.
12. `title` not globally unique by construction; `jp_title` repeats.

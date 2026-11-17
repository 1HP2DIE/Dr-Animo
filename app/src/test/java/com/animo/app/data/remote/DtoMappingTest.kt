package com.animo.app.data.remote

import com.animo.app.data.remote.dto.AnimoJson
import com.animo.app.data.remote.dto.RecentAnimeResponse
import com.animo.app.data.remote.dto.SeriesResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests against payloads shaped like the live API (research/DATA_CONTRACT.md §2, §3).
 *
 * These exist because the DTOs use `@Serializable(with = FlexibleStringSerializer::class)` on
 * *nullable* properties, and because the API mixes strings and numbers for `score`, `mal_id`,
 * `episodes` and `ani_id`. A silent regression here would corrupt the catalog on the next sync.
 */
class DtoMappingTest {

    private val catalogJson = """
        {
          "ok": true,
          "anikoto_domains": ["anikototv.to", "anikoto.cz"],
          "data": [
            {
              "id": 42,
              "title": "One Piece",
              "alternative": "",
              "titles": "OP, One Piece",
              "native": "ワンピース",
              "slug": "one-piece",
              "rating": "PG-13",
              "poster": "https://cdn.anipixcdn.co/p.jpg",
              "is_sub": 1220,
              "description": "A&#39;pirate &amp; crew",
              "aired": "Oct 4, 2026 to ?",
              "season": "Fall",
              "year": 1999,
              "duration": "24 min. per ep.",
              "status": "Currently Airing",
              "score": "9.03",
              "mal_id": "21",
              "episodes": "",
              "ani_id": "21",
              "source": "manga",
              "s_id": 2147483640,
              "background_image": "",
              "updated_at": "2026-11-16 17:40:55",
              "next_air_schedule_time": 1743287400,
              "next_air_ep": 1120,
              "terms_by_type": {
                "genre": ["Action", "Adventure"],
                "studios": ["Toei Animation"],
                "producers": [],
                "type": ["TV"]
              },
              "brand_new_field": {"ignored": true}
            },
            {
              "id": 77,
              "title": "Solo Leveling",
              "alternative": "Ore dake Level Up na Ken",
              "titles": "",
              "native": "俺だけレベルアップな件",
              "slug": "solo-leveling",
              "rating": "None",
              "poster": "https://cdn.anipixcdn.co/s.jpg",
              "description": "Gates open.",
              "aired": "2024",
              "season": "&nbsp",
              "year": 2024,
              "duration": "",
              "status": "Finished Airing",
              "score": 8.5,
              "mal_id": 58567,
              "episodes": "12",
              "ani_id": 151807,
              "s_id": 7401,
              "background_image": "https://cdn.anipixcdn.co/sb.jpg",
              "updated_at": "2026-11-15 09:00:00",
              "terms_by_type": {
                "genre": ["Action"],
                "studios": ["A-1 Pictures"],
                "type": ["TV"]
              }
            }
          ],
          "pagination": {"page": 1, "per_page": 100, "total": 9404, "total_pages": 95}
        }
    """.trimIndent()

    private fun catalog(): RecentAnimeResponse =
        AnimoJson.decodeFromString(RecentAnimeResponse.serializer(), catalogJson)

    @Test
    fun `pagination is read from snake_case`() {
        val pagination = catalog().pagination
        assertEquals(100, pagination.perPage)
        assertEquals(9404, pagination.total)
        assertEquals(95, pagination.totalPages)
    }

    @Test
    fun `counts are counts not booleans and absent means null`() {
        val rows = catalog().data
        assertNull(rows[0].isDub)
        assertEquals(1220, rows[0].isSub)
        assertNull(rows[1].isDub)
        assertNull(rows[1].isSub)
    }

    @Test
    fun `string typed numeric fields survive both wire shapes`() {
        val rows = catalog().data
        assertEquals("9.03", rows[0].score)
        assertEquals("21", rows[0].malId)
        // score arrived as a JSON number for this row — FlexibleStringSerializer must coerce it.
        assertEquals("8.5", rows[1].score)
        assertEquals("58567", rows[1].malId)
    }

    @Test
    fun `s_id beyond int range is preserved as long`() {
        assertEquals(2_147_483_640L, catalog().data[0].sId)
    }

    @Test
    fun `entity keeps unknown episode count as null and normalises season and rating`() {
        val first = catalog().data[0].toEntity()
        assertEquals(42, first.id)
        assertEquals("one-piece", first.slug)
        assertNull(first.episodesDeclared)
        assertEquals("fall", first.seasonNorm)
        assertEquals("Fall", first.season)
        assertEquals("pg-13", first.ratingNorm)
        assertEquals("currently_airing", first.statusNorm)
        assertEquals("Action, Adventure", first.genres)
        assertEquals("Toei Animation", first.studios)
        assertEquals("TV", first.type)
        assertEquals(9.03, first.score!!, 0.0001)
        assertEquals(1_794_850_855L, first.updatedAtEpoch)
        // 1_743_287_400 is 2025-03-30 — in the past, so it is dropped (DATA_CONTRACT §7.8).
        assertNull(first.nextAirAt)
        assertEquals(0, first.episodeCount)
        assertNull(first.detailFetchedAt)
    }

    @Test
    fun `entity decodes html entities and empty episodes`() {
        val rows = catalog().data
        assertEquals("A'pirate & crew", rows[0].toEntity().description)
        assertEquals("", rows[1].toEntity().ratingNorm)
        assertEquals("unknown", rows[1].toEntity().seasonNorm)
        assertEquals(12, rows[1].toEntity().episodesDeclared)
        assertEquals("finished_airing", rows[1].toEntity().statusNorm)
    }

    @Test
    fun `previous detail metadata is carried across a catalog upsert`() {
        val previous = com.animo.app.data.local.dao.AnimeStamp(
            id = 42,
            updatedAtEpoch = 1L,
            detailFetchedAt = 999L,
            episodeCount = 1_120,
        )
        val patched = catalog().data[0].toEntity(previous)
        assertEquals(1_120, patched.episodeCount)
        assertEquals(999L, patched.detailFetchedAt)
    }

    // ---------------------------------------------------------------- series detail

    private val seriesJson = """
        {
          "ok": true,
          "data": {
            "anime": {
              "id": 42,
              "title": "One Piece",
              "slug": "one-piece",
              "updated_at": "2026-11-16 17:40:55"
            },
            "episodes": [
              {
                "id": 992343,
                "title": "I&#39;m Luffy!",
                "jp_title": "I&#39;m Luffy!",
                "number": 1,
                "episode_embed_id": "992343",
                "embed_url": {
                  "sub": "https://megaplay.buzz/stream/s-2/992343/sub",
                  "dub": "https://megaplay.buzz/stream/s-2/992343/dub"
                },
                "updated_at": "2024-10-17 00:00:00"
              },
              {
                "id": 992344,
                "title": "Enter Zoro",
                "jp_title": "Enter Zoro",
                "number": 2,
                "episode_embed_id": "992344",
                "embed_url": {"hsub": "https://megaplay.buzz/stream/s-2/992344/hsub"},
                "updated_at": "2024-10-17 00:00:00"
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `episode titles are unescaped and both audio tracks are kept`() {
        val series = AnimoJson.decodeFromString(SeriesResponse.serializer(), seriesJson)
        assertTrue(series.ok)

        val entities = series.data!!.toEpisodeEntities()
        assertEquals(2, entities.size)

        val first = entities[0]
        assertEquals(992_343L, first.episodeId)
        assertEquals(42, first.animeId)
        assertEquals("I'm Luffy!", first.title)
        assertEquals("https://megaplay.buzz/stream/s-2/992343/sub", first.subUrl)
        assertEquals("https://megaplay.buzz/stream/s-2/992343/dub", first.dubUrl)
        assertTrue(first.updatedAtEpoch > 0L)

        // `hsub` never appears live but must degrade to a sub URL rather than being dropped.
        val second = entities[1]
        assertEquals("https://megaplay.buzz/stream/s-2/992344/hsub", second.subUrl)
        assertNull(second.dubUrl)
    }

    @Test
    fun `missing series data is not treated as success`() {
        val failed = AnimoJson.decodeFromString(
            SeriesResponse.serializer(),
            """{"ok":false,"error":"Series not found","code":"not_found"}""",
        )
        assertNull(failed.data)
        assertEquals("not_found", failed.code)
    }
}

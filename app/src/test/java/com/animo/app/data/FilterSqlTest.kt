package com.animo.app.data

import com.animo.app.data.local.FilterQuery
import com.animo.app.data.local.FilterSql
import com.animo.app.domain.model.Filters
import com.animo.app.domain.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the single browse/search statement.
 *
 * These deliberately assert the generated SQL text as well as the bind arguments: the whole point of
 * [FilterSql] is that a genre never reaches the statement as a literal, so "the args list changed"
 * and "the SQL changed" are both real failures.
 */
class FilterSqlTest {

    private fun build(
        match: String? = null,
        filters: Filters = Filters(),
        limit: Int = 40,
        offset: Int = 0,
    ): FilterQuery = FilterSql.build(match, filters, limit, offset)

    private fun String.occurrences(needle: String): Int {
        var index = indexOf(needle)
        var count = 0
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    // ---------------------------------------------------------------- baseline

    @Test
    fun `no filters is a plain table scan without the fts join`() {
        val query = build()

        assertEquals("SELECT * FROM anime AS a WHERE 1 = 1", query.sql.substringBefore("\nORDER BY"))
        assertFalse(query.sql.contains("anime_fts MATCH"))
        assertTrue(query.sql.endsWith("ORDER BY a.updatedAtEpoch DESC, a.id DESC\nLIMIT ? OFFSET ?"))
        assertEquals(listOf<Any?>(40, 0), query.args)
    }

    @Test
    fun `a search term joins fts and binds the raw query`() {
        val query = build(match = "naru*")

        assertTrue(query.sql.contains("a.id IN (SELECT rowid FROM anime_fts WHERE anime_fts MATCH ?)"))
        assertEquals("naru*", query.args.first())
    }

    // ---------------------------------------------------------------- genres

    @Test
    fun `two genres become two AND predicates in sorted order`() {
        val query = build(filters = Filters(genres = setOf("Comedy", "action")))

        assertEquals(
            listOf<Any?>("action", "comedy", 40, 0),
            query.args,
        )
        assertEquals(2, query.sql.occurrences("AND LOWER(a.genres) LIKE '%' || ? || '%'"))
    }

    @Test
    fun `genres are trimmed, lowercased and de-duplicated`() {
        assertEquals(
            listOf("action", "sci-fi"),
            FilterSql.genreArgs(setOf("Action", "action", "  SCI-FI ", "   ")),
        )
    }

    @Test
    fun `a blank genre adds no predicate`() {
        val query = build(filters = Filters(genres = setOf("  ", "")))

        assertEquals(listOf<Any?>(40, 0), query.args)
        assertFalse(query.sql.contains("a.genres"))
    }

    @Test
    fun `the statement text is stable for a reordered selection`() {
        val first = build(filters = Filters(genres = setOf("Action", "Comedy")))
        val second = build(filters = Filters(genres = setOf("Comedy", "Action")))

        assertEquals(first.sql, second.sql)
        assertEquals(first.args, second.args)
    }

    // ---------------------------------------------------------------- scalars

    @Test
    fun `year window binds both bounds`() {
        val query = build(filters = Filters(yearFrom = 2010, yearTo = 2019))

        assertTrue(query.sql.contains("AND a.year >= ?"))
        assertTrue(query.sql.contains("AND a.year <= ?"))
        assertEquals(listOf<Any?>(2010, 2019, 40, 0), query.args)
    }

    @Test
    fun `a one-sided year window binds one bound`() {
        val query = build(filters = Filters(yearFrom = 2020))

        assertEquals(listOf<Any?>(2020, 40, 0), query.args)
        assertFalse(query.sql.contains("a.year <= ?"))
    }

    @Test
    fun `type is canonicalised and matched across stored spellings`() {
        val query = build(filters = Filters(type = " Movie "))

        assertTrue(query.sql.contains("LOWER(REPLACE(a.type, '_', ' ')) = ?"))
        assertEquals(listOf<Any?>("movie", 40, 0), query.args)
        // TV_SHORT and TV share one label for the user, but different match keys.
        assertEquals(listOf<Any?>("tv short", 40, 0), build(filters = Filters(type = "TV_SHORT")).args)
        assertEquals(listOf<Any?>("tv", 40, 0), build(filters = Filters(type = "TV")).args)
    }

    @Test
    fun `blank and unknown types add no predicate`() {
        listOf(null, "  ", "conspiracy").forEach { type ->
            val query = build(filters = Filters(type = type))
            assertFalse(query.sql.contains("a.type"))
            assertEquals(listOf<Any?>(40, 0), query.args)
        }
    }

    @Test
    fun `domain status keys normalise to the stored statusNorm`() {
        assertEquals(listOf<Any?>("currently_airing", 40, 0), build(filters = Filters(status = "airing")).args)
        assertEquals(listOf<Any?>("finished_airing", 40, 0), build(filters = Filters(status = "finished")).args)
        assertEquals(listOf<Any?>("not_yet_aired", 40, 0), build(filters = Filters(status = "upcoming")).args)
    }

    @Test
    fun `raw api status spellings are accepted too`() {
        assertEquals(
            listOf<Any?>("currently_airing", 40, 0),
            build(filters = Filters(status = "Currently Airing")).args,
        )
    }

    @Test
    fun `an unrecognised status adds no predicate`() {
        val query = build(filters = Filters(status = "banana"))

        assertFalse(query.sql.contains("a.statusNorm"))
        assertEquals(listOf<Any?>(40, 0), query.args)
    }

    // ---------------------------------------------------------------- A-Z

    @Test
    fun `the browse letter is folded to the titleNorm form`() {
        val query = build(filters = Filters(letter = 'A'))

        assertTrue(query.sql.contains("AND a.titleNorm LIKE ? || '%'"))
        assertEquals(listOf<Any?>("a", 40, 0), query.args)
    }

    @Test
    fun `digits are valid browse keys`() {
        assertEquals(listOf<Any?>("8", 40, 0), build(filters = Filters(letter = '8')).args)
    }

    @Test
    fun `the hash bucket matches every digit-led title`() {
        val query = build(filters = Filters(letter = '#'))

        assertTrue(query.sql.contains("AND a.titleNorm GLOB '[0-9]*'"))
        assertEquals(listOf<Any?>(40, 0), query.args)
    }

    @Test
    fun `punctuation is not a browse key`() {
        val query = build(filters = Filters(letter = '-'))

        assertFalse(query.sql.contains("a.titleNorm LIKE"))
        assertEquals(listOf<Any?>(40, 0), query.args)
    }

    // ---------------------------------------------------------------- dub

    @Test
    fun `hasDub requires a positive dub episode count`() {
        val query = build(filters = Filters(hasDub = true))

        assertTrue(query.sql.contains("AND (a.isDub IS NOT NULL AND a.isDub > 0)"))
        assertEquals(listOf<Any?>(40, 0), query.args)
    }

    @Test
    fun `hasDub false does not add the clause`() {
        assertFalse(build(filters = Filters(hasDub = false)).sql.contains("a.isDub"))
    }

    // ---------------------------------------------------------------- paging

    @Test
    fun `limit and offset are clamped`() {
        assertEquals(listOf<Any?>(1, 0), build(limit = 0, offset = -5).args)
        assertEquals(listOf<Any?>(1, 0), build(limit = -20).args)
        assertEquals(listOf<Any?>(FilterSql.MAX_LIMIT, 500), build(limit = 99_999, offset = 500).args)
    }

    // ---------------------------------------------------------------- sorts

    @Test
    fun `every sort ends with an id tiebreaker and they are all distinct`() {
        val clauses = SortOrder.entries.map(FilterSql::orderBy)

        clauses.forEach { assertTrue(it, it.trimEnd().endsWith("a.id DESC")) }
        assertEquals(SortOrder.entries.size, clauses.distinct().size)
    }

    @Test
    fun `year and score sorts push nulls last in both directions`() {
        assertTrue(FilterSql.orderBy(SortOrder.YEAR_DESC).startsWith("(a.year IS NULL) ASC"))
        assertTrue(FilterSql.orderBy(SortOrder.YEAR_ASC).startsWith("(a.year IS NULL) ASC"))
        assertTrue(FilterSql.orderBy(SortOrder.SCORE_DESC).startsWith("(a.score IS NULL) ASC"))
    }

    @Test
    fun `the chosen sort reaches the statement`() {
        assertEquals(
            "SELECT * FROM anime AS a WHERE 1 = 1\nORDER BY a.titleNorm ASC, a.id DESC\nLIMIT ? OFFSET ?",
            build(filters = Filters(sort = SortOrder.TITLE_ASC)).sql,
        )
    }

    // ---------------------------------------------------------------- everything at once

    @Test
    fun `a full filter set binds every value in clause order`() {
        val query = build(
            match = "one piece*",
            filters = Filters(
                genres = setOf("Action", "Adventure"),
                yearFrom = 2000,
                yearTo = 2025,
                type = "TV",
                status = "airing",
                letter = 'O',
                hasDub = true,
                sort = SortOrder.YEAR_DESC,
            ),
            limit = 20,
            offset = 40,
        )

        assertEquals(
            listOf<Any?>("one piece*", "action", "adventure", 2000, 2025, "tv", "currently_airing", "o", 20, 40),
            query.args,
        )
        listOf(
            "anime_fts MATCH ?",
            "LOWER(a.genres)",
            "LOWER(a.genres)",
            "a.year >= ?",
            "a.year <= ?",
            "LOWER(REPLACE(a.type, '_', ' ')) = ?",
            "a.statusNorm = ?",
            "a.titleNorm LIKE ? || '%'",
            "(a.isDub IS NOT NULL AND a.isDub > 0)",
            "ORDER BY (a.year IS NULL) ASC, a.year DESC, a.id DESC",
        ).forEach { fragment -> assertTrue(fragment, query.sql.contains(fragment)) }
    }

    @Test
    fun `no user value is ever interpolated into the sql`() {
        val injection = "'; DROP TABLE anime;--"
        val query = build(filters = Filters(genres = setOf(injection), type = injection, status = injection))

        assertFalse(query.sql.contains(injection))
        assertFalse(query.sql.contains("DROP TABLE"))
        assertEquals("'; drop table anime;--", query.args.first())
    }
}

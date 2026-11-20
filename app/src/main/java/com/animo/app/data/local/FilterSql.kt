package com.animo.app.data.local

import com.animo.app.data.remote.Normalize
import com.animo.app.domain.model.ContentKind
import com.animo.app.domain.model.Filters
import com.animo.app.domain.model.SortOrder

/**
 * One ready-to-execute statement: [sql] plus its positional [args], in clause order.
 * Kept free of `androidx.sqlite` so the SQL can be asserted in plain JVM tests.
 */
data class FilterQuery(val sql: String, val args: List<Any?>)

/**
 * Builds the single browse/search statement behind [com.animo.app.domain.repo.AnimeRepository.search].
 *
 * Why one statement instead of a `@Query` per sort: a multi-genre filter needs **one `LIKE` per
 * selected genre with AND semantics**, and the genre count is only known at call time. Room's
 * `@Query` cannot expand a variable number of predicates, so the AND chain is appended here and
 * every value still travels as a `?` bind argument — nothing is ever interpolated into the SQL, so
 * a genre named `'; DROP TABLE anime;--` is just a string that matches nothing.
 *
 * The six sorts stay explicit in [orderBy] (an exhaustive `when`, so adding a [SortOrder] without a
 * clause is a compile error) and every one ends in `a.id DESC`: `titleNorm`, `year` and `score` all
 * have duplicates, and without a tiebreaker LIMIT/OFFSET paging can repeat or skip rows.
 *
 * Ordering rules worth knowing:
 *  - `year`/`score` are nullable and SQLite sorts NULL **first** in `ASC`, so the null-last guard
 *    is only needed where it would otherwise surface (both directions are written symmetrically).
 *  - Every text comparison is case-folded: `LOWER(a.genres)` against lowercased args, and
 *    `titleNorm` is already lowercased at ingest. `LIKE` on an un-folded column would depend on the
 *    `case_sensitive_like` pragma, which we do not control.
 */
object FilterSql {

    /** Upper bound on a page — a corrupt limit from the UI must not try to inflate the whole table. */
    const val MAX_LIMIT = 1_000

    /** Ceiling on one `IN (?,…,?)` list — see [appendInClause]. */
    const val MAX_IN_VALUES = 200

    fun build(match: String?, filters: Filters, limit: Int, offset: Int): FilterQuery {
        val sql = StringBuilder(SELECT)
        val args = ArrayList<Any?>(8)

        // An empty query never reaches FTS: `MATCH ''` is a syntax error in FTS4, and the join is
        // skipped entirely so "browse everything" is a plain table scan.
        if (match != null) {
            sql.append(AND_FTS)
            args += match
        }

        for (genre in genreArgs(filters.genres)) {
            sql.append(AND_GENRE)
            args += genre
        }

        filters.yearFrom?.let { from ->
            sql.append("\n  AND a.year >= ?")
            args += from
        }
        filters.yearTo?.let { to ->
            sql.append("\n  AND a.year <= ?")
            args += to
        }
        filters.type?.let(Normalize::typeMatchKey)?.let { type ->
            sql.append("\n  AND LOWER(REPLACE(a.type, '_', ' ')) = ?")
            args += type
        }
        // Accepts both the domain values (airing/finished/upcoming) and the raw API spellings
        // ("Currently Airing"), because the same helper maps both onto the stored statusNorm.
        Normalize.normalizeStatus(filters.status.orEmpty()).takeIf { it.isNotEmpty() }?.let { status ->
            sql.append("\n  AND a.statusNorm = ?")
            args += status
        }
        // The A–Z rail's `#` bucket means "starts with a digit"; `normalizeLetter` rejects it, so it
        // is expanded here instead of turning into a no-op.
        if (filters.letter == '#') {
            sql.append(AND_DIGITS)
        } else {
            Normalize.normalizeLetter(filters.letter)?.let { letter ->
                sql.append(AND_LETTER)
                args += letter.toString()
            }
        }
        if (filters.hasDub) sql.append(AND_DUB)
        // Hindi rows live in the negative id space; the primary key index covers this as a range scan.
        if (filters.hindiOnly) sql.append(AND_HINDI)

        // The three v6 owner columns. Each is one `AND <col> IN (?,?,…)` with a placeholder per
        // selected value; the vocabularies are small and closed (a kind enum, ISO-639-1 tags,
        // provider ids), so the count is only ever a handful. All three are skipped when empty, and
        // every value still travels as a bind argument — same rule as the genres above.
        appendInClause(sql, args, "a.contentKind", kindArgs(filters.kinds))
        appendInClause(sql, args, "a.language", languageArgs(filters.languages))
        appendInClause(sql, args, "a.providerId", providerArgs(filters.providers))

        sql.append("\nORDER BY ").append(orderBy(filters.sort))
        sql.append("\nLIMIT ? OFFSET ?")
        args += limit.coerceIn(1, MAX_LIMIT)
        args += offset.coerceAtLeast(0)

        return FilterQuery(sql.toString(), args)
    }

    /**
     * Each selected genre becomes its own `LIKE` predicate, therefore a title must carry **all** of
     * them — the AND is what makes multi-select narrowing instead of widening. Values are trimmed,
     * lowercased and de-duplicated so `{Action, action}` costs one predicate, and sorted so the same
     * selection always produces the same SQL text (Room's statement cache keys on it).
     */
    fun genreArgs(genres: Set<String>): List<String> =
        genres.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()

    /** Stored spelling of each selected kind ([ContentKind.name]), in enum order and de-duplicated. */
    fun kindArgs(kinds: Set<ContentKind>): List<String> =
        kinds.asSequence()
            .distinct()
            .sortedBy { it.ordinal }
            .map { it.name }
            .toList()

    /**
     * [Filters.languages] is an ISO-639-1 set the UI can spell as `JA`; the column stores lowercase,
     * so the same trim/lowercase/de-duplicate/sort rule as [genreArgs] is what keeps the SQL text —
     * and therefore Room's statement cache — stable.
     */
    fun languageArgs(languages: Set<String>): List<String> =
        languages.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()

    /** Provider ids are already part of the database contract, so they are only trimmed and sorted. */
    fun providerArgs(providers: Set<String>): List<String> =
        providers.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()

    /**
     * Appends `AND <column> IN (?,?,…)` for a non-empty [values], binding one argument per value.
     *
     * [MAX_IN_VALUES] is a statement-size guard, not a behaviour: the vocabularies this is used for
     * are a handful of values, and a set larger than the ceiling is a caller bug that must not turn
     * into a 999-argument statement.
     */
    private fun appendInClause(
        sql: StringBuilder,
        args: MutableList<Any?>,
        column: String,
        values: List<String>,
    ) {
        val bounded = values.take(MAX_IN_VALUES)
        if (bounded.isEmpty()) return

        sql.append("\n  AND ").append(column).append(" IN (")
        bounded.forEachIndexed { index, value ->
            if (index > 0) sql.append(',')
            sql.append('?')
            args += value
        }
        sql.append(')')
    }

    fun orderBy(sort: SortOrder): String = when (sort) {
        SortOrder.RECENT -> "a.updatedAtEpoch DESC, a.id DESC"
        SortOrder.TITLE_ASC -> "a.titleNorm ASC, a.id DESC"
        SortOrder.TITLE_DESC -> "a.titleNorm DESC, a.id DESC"
        SortOrder.YEAR_DESC -> "(a.year IS NULL) ASC, a.year DESC, a.id DESC"
        SortOrder.YEAR_ASC -> "(a.year IS NULL) ASC, a.year ASC, a.id DESC"
        SortOrder.SCORE_DESC -> "(a.score IS NULL) ASC, a.score DESC, a.id DESC"
    }

    private const val SELECT = "SELECT * FROM anime AS a WHERE 1 = 1"

    private const val AND_FTS =
        "\n  AND a.id IN (SELECT rowid FROM anime_fts WHERE anime_fts MATCH ?)"

    private const val AND_GENRE = "\n  AND LOWER(a.genres) LIKE '%' || ? || '%'"

    private const val AND_LETTER = "\n  AND a.titleNorm LIKE ? || '%'"

    /** `#` on the rail: `titleNorm` already starts with the digit for titles like `86 Eighty Six`. */
    private const val AND_DIGITS = "\n  AND a.titleNorm GLOB '[0-9]*'"

    /** `isDub` is an episode **count**, and NULL on 73.7% of rows — presence, not truthiness. */
    private const val AND_DUB = "\n  AND (a.isDub IS NOT NULL AND a.isDub > 0)"

    /**
     * Hindi-dub providers write negative ids, so this needs no extra column — but it must be the
     * **Hindi bands**, not `id < 0`: MovieLink rows also live below zero (see `CatalogIds`), and a
     * sign test would fold a third catalogue into the Hindi rail. Bounds are the two Hindi ranges,
     * adjacent by design; `-2_000_000_000` is deliberately outside both.
     */
    private const val AND_HINDI = "\n  AND a.id BETWEEN -1999999999 AND -1"
}

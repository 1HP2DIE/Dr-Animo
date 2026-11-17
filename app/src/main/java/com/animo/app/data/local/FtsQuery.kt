package com.animo.app.data.local

/**
 * Turns free text into a safe FTS4 MATCH expression.
 *
 * Every character that carries FTS syntax (`" * : ^ ( ) -`) is replaced with a space and the result
 * is split on whitespace, so a user typing `Re:Zero"` can never produce a syntax error. Each token
 * becomes a prefix term (`naru*`), which is what makes search feel instant while the user types.
 *
 * Returns `null` when nothing searchable remains — callers then fall back to the filter-only query
 * instead of running `MATCH ''` (which FTS4 rejects).
 */
object FtsQuery {

    private val NON_TOKEN_CHARS = Regex("[^\\p{L}\\p{N}\\s]")
    private val WHITESPACE = Regex("\\s+")

    private const val MAX_TOKENS = 8

    fun build(raw: String): String? {
        val tokens = raw.replace(NON_TOKEN_CHARS, " ")
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .take(MAX_TOKENS)

        if (tokens.isEmpty()) return null
        return tokens.joinToString(separator = " ") { "$it*" }
    }
}

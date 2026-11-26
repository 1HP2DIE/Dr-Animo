package com.animo.app.data.remote.provider.movielink

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `/p/<token>` lookup, which is what makes a stored link refreshable.
 *
 * A saved row carries a token that the index rotates away from — measured 2026-11-26: a token from
 * six days earlier answers `400 bad config` while the same title page's current token answers 200.
 * Recovery therefore depends entirely on this scan finding the current token, so each layout that
 * has been seen gets its own assertion rather than one "happy path" plus hope.
 */
class MovieLinkParserPlayerPathTest {

    @Test
    fun `the measured detail page yields the player frame source`() {
        assertEquals(SERIES_TOKEN, MovieLinkParser.playerPath(fixture("detail_series.html")))
        assertEquals(MOVIE_TOKEN, MovieLinkParser.playerPath(fixture("detail_movie.html")))
    }

    @Test
    fun `a frame with another id is still found by its path`() {
        val html = """
            <div class="player"><iframe id="somethingElse" src="/p/anotherToken1234567890"></iframe></div>
        """.trimIndent()

        assertEquals("/p/anotherToken1234567890", MovieLinkParser.playerPath(html))
    }

    @Test
    fun `a lazy frame is read from data-src`() {
        val html = """<iframe class="lazy" data-src="/p/lazyToken1234567890abc"></iframe>"""

        assertEquals("/p/lazyToken1234567890abc", MovieLinkParser.playerPath(html))
    }

    @Test
    fun `a player built by script is read from an attribute or a literal`() {
        val attribute = """<div class="stage" data-player="/p/attrToken1234567890xyz"></div>"""
        assertEquals("/p/attrToken1234567890xyz", MovieLinkParser.playerPath(attribute))

        // No attribute context at all: the only remaining signal is the literal in the inline script.
        val literal = """<script>var cfg = {url: "/p/scriptToken1234567890q"};</script>"""
        assertEquals("/p/scriptToken1234567890q", MovieLinkParser.playerPath(literal))
    }

    @Test
    fun `a page with no player answers empty instead of a guess`() {
        assertEquals("", MovieLinkParser.playerPath("<html><body><h1>404</h1></body></html>"))
        assertEquals("", MovieLinkParser.playerPath(""))
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/movielink/$name")) { "missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }

    private companion object {
        const val SERIES_TOKEN = "/p/Cq-UsTjaK_shtx726KA7iyINM-MXW_YCcieKRNLchdYPivQdZIidNgbwnd_cAF651Rm_ZMzq"
        const val MOVIE_TOKEN = "/p/cc8eQPqfdaVO3NVrRuKEIlFTtdLMT9TEgDyF0FSSZwhLSy_JF-K-35wyf2EtNkssed0cGefe"
    }
}

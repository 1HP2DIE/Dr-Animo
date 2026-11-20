package com.animo.app.player.movielink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fixtures are trimmed copies of the real `/p/<token>` shell: one `window.__PD2 = {...}` line
 * followed, on the same line, by unrelated JS that itself contains `}`. That trailing JS is the
 * reason the parser brace-matches with string awareness instead of cutting at the last `}`.
 */
class MovieLinkPd2Test {

    @Test
    fun `a movie payload yields its progressive mp4 as the primary stream`() {
        val payload = payloadOf(MovieLinkPd2.extract(MOVIE_SHELL))

        assertEquals("https://fsl.movielink.tv/p/tokMovie", payload.topLevel.mp4Url)
        assertEquals(listOf("Movie"), payload.episodes.map { it.label })

        val choice = choiceOf(MovieLinkPd2.choose(payload, episodeNumber = null))
        assertEquals("https://fsl.movielink.tv/p/tokMovie", choice.url)
        assertFalse(choice.hls)
    }

    @Test
    fun `a series payload carries every episode and the episode number picks one`() {
        val payload = payloadOf(MovieLinkPd2.extract(SERIES_SHELL))
        assertEquals(3, payload.episodes.size)
        assertEquals(listOf("EP 1", "EP 2", "EP 3"), payload.episodes.map { it.label })

        // 1-based, from the embed URL; out-of-range values clamp instead of failing a deep link.
        assertEquals("https://fsl.movielink.tv/p/tokE1", MovieLinkPd2.choose(payload, 1)?.url)
        assertEquals("https://fsl.movielink.tv/p/tokE2", MovieLinkPd2.choose(payload, 2)?.url)
        assertEquals("https://fsl.movielink.tv/p/tokE3", MovieLinkPd2.choose(payload, 99)?.url)
        assertEquals("https://fsl.movielink.tv/p/tokE1", MovieLinkPd2.choose(payload, 0)?.url)
        assertEquals("https://fsl.movielink.tv/p/tokE1", MovieLinkPd2.choose(payload, null)?.url)
    }

    @Test
    fun `a page without a usable payload yields null`() {
        assertNull(MovieLinkPd2.extract("<html><body>just a moment</body></html>"))

        // Marker present but the object never closes: a truncated shell must not half-parse.
        assertNull(MovieLinkPd2.extract("""<script>window.__PD2 = {"master":"https://x/m/1"</script>"""))

        // Marker present, object closed, but not this provider's config.
        val empty = payloadOf(MovieLinkPd2.extract("""<script>window.__PD2 = {"hash":"z"}</script>"""))
        assertNull(MovieLinkPd2.choose(empty, null))
    }

    private companion object {
        /**
         * The `hash` value holds a `}` on purpose: it is inside a string, and a naive scan would
         * treat it as the end of the config object.
         */
        val MOVIE_SHELL = """
            <!DOCTYPE html><html><head><title>Movie</title></head><body>
            <iframe id="mlbdHdFrame" src="/p/tokMovie"></iframe>
            <script>
            window.__PD2 = {"master":"https://watch.movielink.tv/m/tokMovie","mp4":"https://fsl.movielink.tv/p/tokMovie","episodes":[{"label":"Movie","master":"https://watch.movielink.tv/m/tokMovie","mp4":"https://fsl.movielink.tv/p/tokMovie"}],"hash":"a}b"};window.__CF = {"x":"}"};
            </script></body></html>
        """.trimIndent()

        val SERIES_SHELL = """
            <!DOCTYPE html><html><head><title>Series</title></head><body>
            <script>window.__PD2 = {"master":"https://watch.movielink.tv/m/tokE1","mp4":"https://fsl.movielink.tv/p/tokE1","episodes":[
              {"label":"EP 1","master":"https://watch.movielink.tv/m/tokE1","mp4":"https://fsl.movielink.tv/p/tokE1"},
              {"label":"EP 2","master":"https://watch.movielink.tv/m/tokE2","mp4":"https://fsl.movielink.tv/p/tokE2"},
              {"label":"EP 3","master":"https://watch.movielink.tv/m/tokE3","mp4":"https://fsl.movielink.tv/p/tokE3"}
            ]};</script></body></html>
        """.trimIndent()

        private fun payloadOf(value: MovieLinkPd2.Payload?): MovieLinkPd2.Payload =
            value ?: error("expected a parsed payload")

        private fun choiceOf(value: MovieLinkPd2.Choice?): MovieLinkPd2.Choice =
            value ?: error("expected a chosen stream")
    }
}

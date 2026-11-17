package com.animo.app.data.hindi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The fixtures are the live ones: `embed.html` is the real `argon.razorshell.space` embed page and
 * `payload.txt` is the concatenation of its `_juicycodes(...)` arguments (7,785 chars).
 *
 * [MASTER_URL] was captured independently by running the host's own `player.js` in a node `vm` with
 * `jwplayer` stubs and reading the `file` out of `jwplayer().setup({...})` — so it is ground truth for
 * the port, not a value produced by it.
 */
class JuicyDecoderTest {

    @Test
    fun `the live payload decodes to the master playlist`() {
        assertEquals(MASTER_URL, JuicyDecoder.decode(payload()))
    }

    @Test
    fun `the live embed page yields its juicycodes arguments as the payload`() {
        assertEquals(payload(), JuicyDecoder.extractPayload(embedHtml()))
    }

    @Test
    fun `extracting from the page and decoding lands on the same url`() {
        val extracted = JuicyDecoder.extractPayload(embedHtml())
        assertEquals(MASTER_URL, JuicyDecoder.decode(requireNotNull(extracted)))
    }

    @Test
    fun `a payload too short to carry a salt is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { JuicyDecoder.decode("ab") }
    }

    @Test
    fun `a salt that is not digit-encoded is rejected`() {
        // The tail must be three chars each exactly 100 above a digit; 'abc' sits 97..99.
        assertThrows(IllegalArgumentException::class.java) { JuicyDecoder.decode("QUJDabc") }
    }

    @Test
    fun `a body that is not base64 is rejected`() {
        // '!' is in no base64 alphabet, so the body cannot be decoded at all.
        assertThrows(IllegalArgumentException::class.java) { JuicyDecoder.decode("!!!fhi") }
    }

    @Test
    fun `a body that decodes to characters outside the symbol map is rejected`() {
        // "QUJD" is "ABC", and 'A' is not one of the ten substitutes.
        assertThrows(IllegalArgumentException::class.java) { JuicyDecoder.decode("QUJDfhi") }
    }

    @Test
    fun `a body with a symbol count that is not a multiple of four is rejected`() {
        // "YCsl" is "`+%": three symbols, so no complete four-symbol group exists.
        assertThrows(IllegalArgumentException::class.java) { JuicyDecoder.decode("YCslfhi") }
    }

    @Test
    fun `a playlist that names no m3u8 is rejected`() {
        // Symbols "`+%`" are the digits 0310; 0310 % 1000 - 245 = 65 = 'A', so this decodes to "A".
        assertThrows(IllegalArgumentException::class.java) { JuicyDecoder.decode("YCslYAfhi") }
    }

    @Test
    fun `extractPayload returns null when the page has no call`() {
        assertNull(JuicyDecoder.extractPayload("<html><body>nothing here</body></html>"))
    }

    @Test
    fun `extractPayload returns null when the call carries no arguments`() {
        assertNull(JuicyDecoder.extractPayload("<script>_juicycodes();</script>"))
    }

    @Test
    fun `extractPayload tolerates escaped quotes and newlines between chunks`() {
        val html = "<script>_juicycodes(\n  \"AA\\\"BB\" +\n  \"CC\"\n);</script>"
        assertEquals("AA\"BBCC", JuicyDecoder.extractPayload(html))
    }

    @Test
    fun `extractPayload ignores a closing paren inside a chunk`() {
        assertEquals("a)bc", JuicyDecoder.extractPayload("_juicycodes(\"a)b\"+\"c\");"))
    }

    private fun payload(): String = resource("payload.txt").trimEnd('\n', '\r')

    private fun embedHtml(): String = resource("embed.html")

    private fun resource(name: String): String =
        JuicyDecoderTest::class.java.getResourceAsStream("/juicy/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("missing test resource /juicy/$name")

    private companion object {
        const val MASTER_URL =
            "https://wfbrcpgcgcwjrqh.groovy.monster/stream/AGZ5ZTH4LmSzZJRjZ2Z3MGAzLJExLGx1MwqxLmAyLmVkATZkLGL5BTExZ2VkBQL0BQAvLJRjZwuuAwV4L2D2ZP5JryO2Z1qwo3MJp2cipxAmMJ5uoaMOYxqXEQqmJH15AaSnBSEapKt2ASO6HxM1rv1EEGIApH9cp3q6A0HmImMnGaqDZQuUYHyFHGOjMaIDA1SlrxcsAGx/AzDlATV5ATVkZTMvBQL1AmIxAGxjAQV1BQMzMQH2Z2Z1ZTHmZGDjAmuxMwD2AJExZmZkAzMwAGN5BTH1LwxmAF56JTqmFxHjF2SEBQqXM3RmrHWdqzkEYaOMBRqxIF1yD0WznQWcDmWFZTuSJycbGR5JDz8kDxSyDyWTIKOFFTq0HQRkFJuKMUb1YGx4oT9KAmV1qHAGqGV.m3u8"
    }
}

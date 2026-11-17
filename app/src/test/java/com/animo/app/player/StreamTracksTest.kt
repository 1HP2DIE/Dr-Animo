package com.animo.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTracksTest {

    private val base = "https://subs.example/anime/a/b"

    /** Shape captured from a live getSources answer: every language twice (ASS + VTT). */
    private val payload = """
        {"tracks":[
          {"file":"$base/subtitles/track_0_CR_English_eng.ass","label":"English (CR English)","kind":"captions","default":true,"styled":true,"fonts":["x.ttf"]},
          {"file":"$base/subtitles/track_1_CR_Spanish_spa.ass","label":"Spanish (CR Spanish)","kind":"captions"},
          {"file":"$base/subtitles/track_0_CR_English_eng.vtt","label":"English (CR English)","kind":"captions"},
          {"file":"$base/subtitles/track_1_CR_Spanish_spa.vtt","label":"Spanish (CR Spanish)","kind":"captions"},
          {"file":"$base/preview/thumbnails.vtt","kind":"thumbnails"}
        ],"intro":{"start":0,"end":118},"enc":"x"}
    """.trimIndent()

    @Test
    fun `duplicates collapse to the vtt copy and keep the default flag`() {
        val parsed = StreamTracks.parse(payload)
        assertEquals(2, parsed.subtitles.size)
        val english = parsed.subtitles[0]
        assertTrue(english.url.endsWith("_eng.vtt"))
        assertTrue(english.isDefault)
        assertEquals("eng", english.language)
        assertEquals("$base/preview/thumbnails.vtt", parsed.previewVtt)
    }

    @Test
    fun `sub picks the default, dub picks only a forced english track`() {
        val subs = StreamTracks.parse(payload).subtitles
        assertEquals("English (CR English)", StreamTracks.autoPick(subs, dub = false)?.label)
        assertNull(StreamTracks.autoPick(subs, dub = true))

        val forced = subs + subs[0].copy(label = "English (Forced)", forced = true, isDefault = false)
        assertEquals("English (Forced)", StreamTracks.autoPick(forced, dub = true)?.label)
    }

    @Test
    fun `garbage and missing tracks are empty, not a crash`() {
        assertTrue(StreamTracks.parse("not json").subtitles.isEmpty())
        assertTrue(StreamTracks.parse("""{"enc":"x"}""").subtitles.isEmpty())
    }

    @Test
    fun `preview vtt resolves relative sprites and finds cells by time`() {
        val vtt = """
            WEBVTT

            NOTE This is a thumbnail VTT file

            00:00:00.000 --> 00:00:05.000
            thumbnails/sprite_0.webp#xywh=0,0,160,90

            00:00:05.000 --> 00:00:10.000
            thumbnails/sprite_0.webp#xywh=160,0,160,90
        """.trimIndent()
        val cells = PreviewVtt.parse(vtt, "$base/preview/thumbnails.vtt")
        assertEquals(2, cells.size)
        assertEquals("$base/preview/thumbnails/sprite_0.webp", cells[1].imageUrl)
        assertEquals(160, PreviewVtt.at(cells, 7_000)?.x)
        assertEquals(0, PreviewVtt.at(cells, 0)?.x)
    }
}

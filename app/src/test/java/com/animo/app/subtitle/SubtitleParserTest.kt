package com.animo.app.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleParserTest {

    @Test
    fun `parses a plain srt`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,500
            Hello there.

            2
            00:00:04,000 --> 00:00:06,250
            Second line.
        """.trimIndent()

        val cues = SubtitleParser.parse("episode.srt", srt)

        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(3_500L, cues[0].endMs)
        assertEquals("Hello there.", cues[0].text)
        assertEquals(6_250L, cues[1].endMs)
        assertEquals("Second line.", cues[1].text)
    }

    @Test
    fun `srt keeps line breaks and strips markup`() {
        val srt = "\uFEFF1\r\n00:00:00,000 --> 00:00:02,000\r\n<i>First</i> line\r\n<font color=\"#fff\">second</font>\r\n"

        val cues = SubtitleParser.parse("x.SRT", srt)

        assertEquals(1, cues.size)
        assertEquals("First line\nsecond", cues[0].text)
    }

    @Test
    fun `webvtt header, cue ids and align settings are handled`() {
        val vtt = """
            WEBVTT

            intro
            00:01.000 --> 00:03.000 align:start
            Left-aligned line

            00:04.000 --> 00:05.500
            Centred by default
        """.trimIndent()

        val cues = SubtitleParser.parse("episode.vtt", vtt)

        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(CueAlignment.START, cues[0].alignment)
        assertEquals("Left-aligned line", cues[0].text)
        assertEquals(CueAlignment.CENTER, cues[1].alignment)
    }

    @Test
    fun `webvtt note blocks are skipped`() {
        val vtt = """
            WEBVTT

            NOTE this is a comment
            spanning two lines

            00:01.000 --> 00:02.000
            Real cue
        """.trimIndent()

        val cues = SubtitleParser.parse("note.vtt", vtt)

        assertEquals(1, cues.size)
        assertEquals("Real cue", cues[0].text)
    }

    @Test
    fun `ass dialogue is parsed and overrides are removed`() {
        val ass = """
            [Script Info]
            Title: test

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,{\an8}Top line\Nand a second line
            Dialogue: 0,0:00:04.00,0:00:05.00,Default,,0,0,0,,Bottom right{\an3}
            Comment: 0,0:00:06.00,0:00:07.00,Default,,0,0,0,,ignored
        """.trimIndent()

        val cues = SubtitleParser.parse("episode.ass", ass)

        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        // `.cc` is centiseconds in ASS.
        assertEquals(3_500L, cues[0].endMs)
        assertEquals("Top line\nand a second line", cues[0].text)
        assertEquals(CueAlignment.END, cues[1].alignment)
        assertEquals("Bottom right", cues[1].text)
    }

    @Test
    fun `format is detected from content when the name lies`() {
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:02.00,0:00:03.00,Default,,0,0,0,,Detected
        """.trimIndent()

        val cues = SubtitleParser.parse("download.bin", ass)

        assertEquals(1, cues.size)
        assertEquals(2_000L, cues[0].startMs)
    }

    @Test
    fun `unreadable input yields no cues instead of throwing`() {
        assertTrue(SubtitleParser.parse("random.txt", "just some prose").isEmpty())
        assertTrue(SubtitleParser.parse("empty.srt", "").isEmpty())
    }

    @Test
    fun `timecodes of every shape`() {
        assertEquals(20_000L, SubtitleParser.parseTimecode("00:00:20,000"))
        assertEquals(90_500L, SubtitleParser.parseTimecode("01:30.500"))
        assertEquals(1_500L, SubtitleParser.parseTimecode("0:00:01.5"))
        assertEquals(3_723_456L, SubtitleParser.parseTimecode("1:02:03,456"))
        assertEquals(null, SubtitleParser.parseTimecode("not a time"))
    }

    @Test
    fun `activeAt picks every overlapping cue`() {
        val cues = listOf(
            SubtitleCue(0, 5_000, "sign"),
            SubtitleCue(3_000, 8_000, "dialogue"),
            SubtitleCue(9_000, 10_000, "later"),
        )

        assertEquals(listOf("sign", "dialogue"), cues.activeAt(4_000).map { it.text })
        assertEquals(listOf("dialogue"), cues.activeAt(7_999).map { it.text })
        assertEquals(emptyList<String>(), cues.activeAt(8_000).map { it.text })
        assertEquals(listOf("later"), cues.activeAt(9_500).map { it.text })
        assertEquals(emptyList<String>(), cues.activeAt(-1).map { it.text })
    }

    @Test
    fun `ass file with unknown format line falls back to the default field order`() {
        val ass = """
            [Events]
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,No format line
        """.trimIndent()

        val cues = SubtitleParser.parse("no-format.ass", ass)

        assertEquals(1, cues.size)
        assertEquals("No format line", cues[0].text)
    }
}

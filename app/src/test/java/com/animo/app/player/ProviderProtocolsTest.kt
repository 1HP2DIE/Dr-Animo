package com.animo.app.player

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.repo.wantsDub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsers for servers 2–4 and the skip-times fallback, against payloads shaped like the live ones. */
class ProviderProtocolsTest {

    // ---------------------------------------------------------------- server 3 (mirror)

    private val flix = """
        {"success":true,"servers":[
          {"serverName":"HD-1","dataLink":"https://player.invalid/e/abc123?v=1","dataType":"sub","hardsub":false},
          {"serverName":"HD-1","dataLink":"https://player.invalid/e/abc123?v=1","dataType":"dub","hardsub":false},
          {"serverName":"H-SUB","dataLink":"https://player.invalid/e/abc123?v=3","dataType":"sub","hardsub":true}
        ]}
    """.trimIndent()

    @Test
    fun `mirror servers prefer the soft-subbed file and read the embed id`() {
        val servers = MirrorProtocol.servers(flix)
        assertEquals(3, servers.size)
        val sub = MirrorProtocol.pick(servers, dub = false)!!
        assertFalse(sub.hardsub)
        assertEquals("abc123" to "1", MirrorProtocol.embedId(sub.link))
        assertEquals("dub", MirrorProtocol.pick(servers, dub = true)?.dataType)
        assertTrue(MirrorProtocol.servers("<html>").isEmpty())
    }

    /** Devalue: objects hold indices into one flat array (shape of the live `/d/` payload). */
    private val ticketJson = """
        {"type":"data","nodes":[null,{"type":"data","data":[
          {"video":1,"download":5},
          {"fileId":2,"filename":3,"sizeBytes":4},
          "60aa-file","Show - S01E01 [x].mkv",1124052844,
          {"base":6,"token":7},
          "https://fetch8.invalid","test-token"
        ]}]}
    """.trimIndent()

    @Test
    fun `devalue payload yields the download ticket`() {
        val ticket = MirrorProtocol.ticket(ticketJson)!!
        assertEquals("60aa-file", ticket.fileId)
        assertEquals("Show - S01E01 [x].mkv", ticket.filename)
        assertEquals("https://fetch8.invalid", ticket.base)
        assertEquals("test-token", ticket.token)
        assertNull(MirrorProtocol.ticket("""{"type":"data","nodes":[]}"""))
    }

    private val embedJson = """
        {"type":"data","nodes":[null,{"type":"data","data":[
          {"chapters":1,"outro_chapter":12,"intro_chapter":-1,"subtitles":13,"thumbnails_vtt":22},
          [2,6,10],
          {"start":3,"end":4,"title":5},0,90,"Opening",
          {"start":4,"end":7,"title":8},1136,"Part A",0,
          {"start":7,"end":11,"title":9},
          1549,
          {"start":20,"end":11,"title":21},
          [14,18],
          {"url":15,"language":16,"default":17},
          "https://subs.invalid/eng_3.ass","English (Full Subtitles [9volt])",true,
          {"url":19,"language":21,"default":false},
          "https://subs.invalid/eng_4.ass",
          1459,"Ending",
          "https://thumbs.invalid/thumbnails_vtt/60aa"
        ]}]}
    """.trimIndent()

    @Test
    fun `mirror embed data gives chapters, subtitles and the preview index`() {
        val extras = MirrorProtocol.extras(embedJson)
        assertEquals(0L until 90_000L, extras.intro)
        assertEquals(1_459_000L until 1_549_000L, extras.outro)
        assertEquals(2, extras.subtitles.size)
        assertTrue(extras.subtitles[0].isDefault)
        assertEquals("eng", extras.subtitles[0].language)
        assertEquals("https://thumbs.invalid/thumbnails_vtt/60aa", extras.previewVtt)
    }

    @Test
    fun `progress events are read for the ready state`() {
        assertTrue(MirrorProtocol.isReady("""data: {"percent":100,"status":"ready"}"""))
        assertFalse(MirrorProtocol.isReady("""data: {"percent":40,"status":"processing"}"""))
    }

    @Test
    fun `signs and songs counts as the forced track a dub wants`() {
        assertTrue(StreamTracks.isForcedLabel("English (Signs & Songs)"))
        assertTrue(StreamTracks.isForcedLabel("English (Forced)"))
        assertFalse(StreamTracks.isForcedLabel("English (Full Subtitles [9volt])"))
    }

    @Test
    fun `preview index without an extension is the sprites folder`() {
        val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:05.000\nthumbnails/sprite_0.webp#xywh=0,0,160,90\n"
        val cell = PreviewVtt.parse(vtt, "https://thumbs.invalid/thumbnails_vtt/60aa").single()
        assertEquals("https://thumbs.invalid/thumbnails_vtt/60aa/thumbnails/sprite_0.webp", cell.imageUrl)
    }

    // ---------------------------------------------------------------- server 4 (archive)

    @Test
    fun `archive matches titles exactly, never a sequel`() {
        val hits = ArchiveProtocol.searchHits(
            """[{"id":1,"name":"Sousou no Frieren","url":"/series/sousou-no-frieren"},
               {"id":2,"name":"Frieren beyond journeys end season 2","url":"/series/frieren-s2"}]""",
        )
        assertEquals("/series/sousou-no-frieren", ArchiveProtocol.match(hits, listOf("Frieren: Beyond Journey's End", "Sousou no Frieren"))?.path)
        assertNull(ArchiveProtocol.match(hits, listOf("Frieren")))
    }

    @Test
    fun `archive pages yield the episode, its versions and the files`() {
        val series = """<a href="/sousou-no-frieren-episode-10" class="anm_det_pop"></a>
            <a href="/sousou-no-frieren-episode-1" class="anm_det_pop"></a>"""
        assertEquals("/sousou-no-frieren-episode-1", ArchiveProtocol.episodePath(series, 1))
        assertNull(ArchiveProtocol.episodePath(series, 2))

        val page = """<a href="#subbed-Animegg" data-toggle="tab" data-id='131519' data-mirror="Animegg" data-version="subbed">
            <a href="#dubbed-Animegg" data-toggle="tab" data-id='131769' data-mirror="Animegg" data-version="dubbed">"""
        assertEquals(mapOf("subbed" to "131519", "dubbed" to "131769"), ArchiveProtocol.versions(page))

        val embed = """var videoSources = [{file: "/play/1/video.mp4?for=1", label: "360p", bk: "x", isBk: false },{file: "/play/3/video.mp4?for=1", label: "1080p", bk: "x", isBk: false }];"""
        val files = ArchiveProtocol.sources(embed)
        assertEquals(listOf(360, 1080), files.map { it.height })
        assertEquals("/play/3/video.mp4?for=1", files[1].path)
    }

    // ---------------------------------------------------------------- server 2 renditions

    @Test
    fun `server 2 renditions are the picked language at each real height`() {
        fun entry(q: String, a: String) = AltStreamEntry(quality = q, audio = a, fallbackProxyUrl = "/p/$q/$a")
        val payload = AltStreamPayload(
            sources = listOf(entry("Auto", "ja"), entry("Auto", "en"), entry("720p", "ja"), entry("720p", "en"), entry("480p", "ja")),
        )
        val picked = AltStreamProtocol.pick(payload)!!
        val renditions = AltStreamProtocol.alternates(payload, picked)
        assertEquals(listOf("720p", "480p"), renditions.map { it.quality })
        assertTrue(renditions.all { it.audio == "ja" })
        assertNull(AltStreamProtocol.heightOf("Auto"))
    }

    // ---------------------------------------------------------------- skip times & languages

    @Test
    fun `skip times parse opening and ending`() {
        val body = """{"found":true,"results":[
            {"interval":{"startTime":0,"endTime":96.252},"skipType":"op"},
            {"interval":{"startTime":1330.583,"endTime":1421.21},"skipType":"ed"}],"statusCode":200}"""
        val markers = SkipTimes.parse(body)!!
        assertEquals(0L until 96_252L, markers.intro)
        assertEquals(1_330_583L until 1_421_210L, markers.outro)
        assertNull(SkipTimes.parse("""{"found":false,"results":[]}"""))
    }

    @Test
    fun `language ids map to sub, dub or not here`() {
        assertEquals(false, wantsDub("sub", AudioTrack.DUB))
        assertEquals(true, wantsDub("en", AudioTrack.SUB))
        assertEquals(true, wantsDub(null, AudioTrack.DUB))
        assertNull(wantsDub("hi", AudioTrack.DUB))
    }
}

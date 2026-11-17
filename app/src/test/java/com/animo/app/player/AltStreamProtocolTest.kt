package com.animo.app.player

import com.animo.app.domain.model.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AltStreamProtocolTest {

    private val fixture = """
        {
          "sources": [
            {
              "id": "castle-1-hi-auto",
              "url": "https://img.example.invalid/hls/720/index_1.m3u8",
              "permanent_m3u8_url": "https://img.example.invalid/hls/720/index_1.m3u8",
              "fallbackProxyUrl": "/api/core/proxy?data=%7B%22url%22%3A%22https%3A%2F%2Fimg.example.invalid%2Fhls%2F720%2Findex_1.m3u8%22%7D&defaultAudio=hi&defaultAudioIndex=0",
              "type": "hls",
              "quality": "Auto",
              "audio": "hi",
              "audioLabel": "Hindi",
              "audioIndex": 0
            },
            {
              "id": "castle-1-ja-720p",
              "url": "https://img.example.invalid/hls/720/index_2.m3u8",
              "type": "hls",
              "quality": "720p",
              "audio": "ja"
            }
          ],
          "audioTracks": [
            {"language": "hi", "label": "Hindi", "index": 0, "trackIndex": 0},
            {"language": "en", "label": "English", "index": 5},
            {"language": "ja", "label": "Japanese", "index": 6}
          ],
          "qualities": ["1080p", "720p", "480p", "Auto"],
          "providers": ["Castle"],
          "subtitles": [],
          "_diag": {"targetMeta": {"title": "Frieren: Beyond Journey's End", "year": "2023"}}
        }
    """.trimIndent()

    @Test
    fun `parses sources and audio tracks`() {
        val payload = AltStreamProtocol.parse(fixture)!!
        assertEquals(2, payload.sources.size)
        assertEquals(listOf("hi", "en", "ja"), payload.audioTracks.map { it.language })
        assertEquals("Frieren: Beyond Journey's End", payload.diagnostics?.targetMeta?.title)
        assertEquals("Hindi", payload.sources[0].audioLabel)
    }

    @Test
    fun `picks the first hls source that has a proxy path`() {
        val source = AltStreamProtocol.pick(AltStreamProtocol.parse(fixture)!!)!!
        assertEquals("castle-1-hi-auto", source.id)
        assertTrue(source.proxyPath().startsWith("/api/core/proxy?data="))
    }

    @Test
    fun `a payload with no sources picks nothing`() {
        assertNull(AltStreamProtocol.pick(AltStreamProtocol.parse("""{"sources":[]}""")!!))
    }

    @Test
    fun `tolerates a truncated or unknown payload`() {
        assertNull(AltStreamProtocol.parse("not json"))
        assertTrue(AltStreamProtocol.parse("""{"sources":[],"futureField":1}""")!!.sources.isEmpty())
    }

    @Test
    fun `proxy path is joined onto the shard`() {
        val payload = AltStreamProtocol.parse(fixture)!!
        val url = AltStreamProtocol.playlistUrl("https://shard.example.invalid", payload.sources[0])
        assertTrue(url.startsWith("https://shard.example.invalid/api/core/proxy?data="))
    }

    @Test
    fun `an absolute proxy url is left alone`() {
        val source = AltStreamEntry(
            fallbackProxyUrl = "https://shard.example.invalid/api/core/proxy?data=x",
        )
        assertEquals(
            "https://shard.example.invalid/api/core/proxy?data=x",
            AltStreamProtocol.playlistUrl("https://ignored.invalid", source),
        )
    }

    @Test
    fun `shard hashing is deterministic and bounded`() {
        val seed = AltStreamProtocol.seed("anime", "21", 1, 1)
        val first = AltStreamProtocol.shardIndex(seed, 100)
        val second = AltStreamProtocol.shardIndex(seed, 100)
        assertEquals(first, second)
        assertTrue(first in 0 until 100)
        assertTrue(AltStreamProtocol.shardIndex(seed, 3) in 0..2)
    }

    @Test
    fun `the seed follows the site's shape`() {
        assertEquals("anime-154587-1-5", AltStreamProtocol.seed("anime", "154587", 1, 5))
    }

    @Test
    fun `the streams url carries every required parameter`() {
        val url = AltStreamProtocol.streamsUrl(
            shard = "https://shard.example.invalid/",
            type = "anime",
            id = "21",
            season = 1,
            episode = 5,
            audio = "hi",
            timestampMs = 1234L,
        )
        assertEquals(
            "https://shard.example.invalid/api/core/streams?type=anime&id=21&season=1&episode=5&audio=hi&_t=1234",
            url,
        )
        assertTrue(
            AltStreamProtocol.streamsUrl("https://s.invalid", "anime", "21", 1, 5, null, 1L)
                .endsWith("&_t=1"),
        )
    }

    @Test
    fun `sub prefers the original, dub prefers hindi`() {
        assertEquals("ja", AltStreamProtocol.preferredAudio(AudioTrack.SUB))
        assertEquals("hi", AltStreamProtocol.preferredAudio(AudioTrack.DUB))
    }
}

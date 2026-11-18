package com.animo.app.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistTest {

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",URI="audio/en.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
        360/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,AUDIO="aud"
        https://cdn.example/720/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
        1080/index.m3u8
    """.trimIndent()

    @Test
    fun `master lists variants with resolved urls and heights`() {
        assertTrue(HlsPlaylist.isMaster(master))
        val parsed = HlsPlaylist.parseMaster(master, "https://host.example/anime/master.m3u8")
        assertEquals(listOf(360, 720, 1080), parsed.variants.map { it.height })
        assertEquals("https://host.example/anime/360/index.m3u8", parsed.variants[0].uri)
        assertEquals("https://cdn.example/720/index.m3u8", parsed.variants[1].uri)
        assertEquals("aud", parsed.variants[1].audioGroup)
        assertEquals("https://host.example/anime/audio/en.m3u8", parsed.renditions.single().uri)
    }

    @Test
    fun `quoted attribute values keep their commas`() {
        val attrs = HlsPlaylist.attributes("""BANDWIDTH=1,CODECS="a,b",RESOLUTION=2x3""")
        assertEquals("a,b", attrs["CODECS"])
        assertEquals("2x3", attrs["RESOLUTION"])
    }

    @Test
    fun `media playlist collects segments keys and init`() {
        val media = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg-0.jpg
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x000102030405060708090a0b0c0d0e0f
            #EXTINF:4.0,
            seg-1.jpg
            #EXT-X-KEY:METHOD=NONE
            #EXT-X-BYTERANGE:100@50
            #EXTINF:4.0,
            seg-2.jpg
            #EXT-X-BYTERANGE:20
            #EXTINF:4.0,
            seg-2.jpg
            #EXT-X-ENDLIST
        """.trimIndent()
        val parsed = HlsPlaylist.parseMedia(media, "https://h.example/a/b.m3u8")
        assertEquals("https://h.example/a/init.mp4", parsed.initUri)
        assertEquals(4, parsed.segments.size)
        assertEquals(listOf(7L, 8L, 9L, 10L), parsed.segments.map { it.sequence })
        assertNull(parsed.segments[0].key)
        assertEquals("https://h.example/a/key.bin", parsed.segments[1].key?.uri)
        assertArrayEquals(ByteArray(16) { it.toByte() }, parsed.segments[1].key?.iv)
        assertNull(parsed.segments[2].key)
        assertEquals(50L..149L, parsed.segments[2].byteRange)
        assertEquals(150L..169L, parsed.segments[3].byteRange)
    }

    @Test
    fun `plain media playlist is not a master`() {
        assertFalse(HlsPlaylist.isMaster("#EXTM3U\n#EXTINF:4,\na.ts\n"))
    }

    @Test
    fun `sequence iv is big endian`() {
        val iv = HlsPlaylist.sequenceIv(258L)
        assertEquals(1.toByte(), iv[14])
        assertEquals(2.toByte(), iv[15])
    }

    @Test
    fun `quality pick prefers the highest at or below the target`() {
        val heights = listOf(360, 1080, 720, 480)
        assertEquals(720, DownloadEngine.pickByHeight(heights, 720) { it })
        assertEquals(1080, DownloadEngine.pickByHeight(heights, Int.MAX_VALUE) { it })
        assertEquals(480, DownloadEngine.pickByHeight(heights, 600) { it })
        assertEquals(360, DownloadEngine.pickByHeight(heights, 240) { it })
        assertNull(DownloadEngine.pickByHeight(emptyList<Int>(), 720) { it })
    }

    @Test
    fun `file names are safe`() {
        assertEquals("E01 Who are you", DownloadEngine.sanitize("E01 Who: are/you?"))
    }
}

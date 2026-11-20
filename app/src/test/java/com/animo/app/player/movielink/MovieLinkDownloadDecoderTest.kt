package com.animo.app.player.movielink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The vectors are cross-checked against the site's own `dec()` running in node 24 on the same
 * payload/seed/rounds — not against this port. The *payloads* were produced by the inverse of that
 * JS with `data-ds=bc01741b` and `data-dr=300` (the values measured on the live download button),
 * so the seed and the round count are real even though the file names are synthetic.
 *
 * The live page itself could not be fetched from this build host: movielinkbd.one answers
 * `403` with a Cloudflare "Just a moment…" interstitial to a datacentre IP, so a genuine
 * `data-dl` attribute could not be captured.
 */
class MovieLinkDownloadDecoderTest {

    @Test
    fun `the seeded keystream decodes a download path`() {
        val decoded = MovieLinkDownloadDecoder.decode(MKV_PAYLOAD, SEED, ROUNDS)
        assertEquals("/file/6a1f2c9d3b/Movie.Name.2024.1080p.WEB-DL.x264.mkv", decoded)

        val mp4 = MovieLinkDownloadDecoder.decode(MP4_PAYLOAD, SEED, ROUNDS)
        assertEquals("/file/9f3c1a/Another.Title.2019.720p.mp4", mp4)

        // The 300 pre-roll steps are the part a naive port drops; one step fewer must not decode.
        assertNotEquals(decoded, MovieLinkDownloadDecoder.decode(MKV_PAYLOAD, SEED, ROUNDS - 1))
    }

    @Test
    fun `attributes that cannot be decoded are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MovieLinkDownloadDecoder.decode("", SEED, ROUNDS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MovieLinkDownloadDecoder.decode("!!!", SEED, ROUNDS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MovieLinkDownloadDecoder.decode(MKV_PAYLOAD, "not-hex", ROUNDS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MovieLinkDownloadDecoder.decode(MKV_PAYLOAD, SEED, -1)
        }
    }

    private companion object {
        const val SEED = "bc01741b"
        const val ROUNDS = 300
        const val MKV_PAYLOAD =
            "lS4NUQQPBd0dlU9l6RgKmPNV9VTOIPrBlrbLXEQ8/k/7LIrKjZGWwT+4ZXwr4QAlIXjBlyMr"
        const val MP4_PAYLOAD =
            "lS4NUQQPCtofkExn/z1Xlahw/1CJEb37m76AQEY99VXiL4qCk4zIog=="
    }
}

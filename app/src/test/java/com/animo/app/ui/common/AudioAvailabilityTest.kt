package com.animo.app.ui.common

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAvailabilityTest {

    private fun episode(sub: String?, dub: String?) = Episode(
        id = 1L,
        animeId = 1,
        number = 1,
        title = "",
        subUrl = sub,
        dubUrl = dub,
    )

    @Test
    fun `both chips are offered when the episode has both tracks`() {
        val e = episode(sub = "https://example.invalid/s", dub = "https://example.invalid/d")
        assertTrue(e.supportsAudio(AudioTrack.SUB))
        assertTrue(e.supportsAudio(AudioTrack.DUB))
    }

    @Test
    fun `a sub-only episode never offers dub`() {
        val e = episode(sub = "https://example.invalid/s", dub = null)
        assertTrue(e.supportsAudio(AudioTrack.SUB))
        assertFalse(e.supportsAudio(AudioTrack.DUB))
    }

    @Test
    fun `a blank url is not a track`() {
        val e = episode(sub = " ", dub = null)
        assertFalse(e.supportsAudio(AudioTrack.SUB))
    }
}

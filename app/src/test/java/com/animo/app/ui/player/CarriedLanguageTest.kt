package com.animo.app.ui.player

import com.animo.app.domain.model.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarriedLanguageTest {

    @Test
    fun `the language survives when the episode has that track`() {
        assertEquals(
            "dub",
            carriedLanguageId("dub", requested = AudioTrack.DUB, resolved = AudioTrack.DUB),
        )
    }

    @Test
    fun `the language is dropped when playback fell back to the other track`() {
        assertNull(carriedLanguageId("dub", requested = AudioTrack.DUB, resolved = AudioTrack.SUB))
    }

    @Test
    fun `no language stays no language`() {
        assertNull(carriedLanguageId(null, requested = AudioTrack.SUB, resolved = AudioTrack.SUB))
    }
}

package com.animo.app.data.repo

import com.animo.app.domain.model.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimaryAudioTest {

    @Test
    fun `dub with no explicit language asks for the dub track`() {
        assertEquals(AudioTrack.DUB, primaryAudioFor(AudioTrack.DUB, languageId = null))
    }

    @Test
    fun `sub with no explicit language asks for the sub track`() {
        assertEquals(AudioTrack.SUB, primaryAudioFor(AudioTrack.SUB, languageId = null))
    }

    @Test
    fun `an explicit language wins over the audio request`() {
        assertEquals(AudioTrack.SUB, primaryAudioFor(AudioTrack.DUB, languageId = "sub"))
        assertEquals(AudioTrack.DUB, primaryAudioFor(AudioTrack.SUB, languageId = "en"))
    }

    @Test
    fun `a language this server does not carry is refused, not quietly swapped`() {
        assertNull(primaryAudioFor(AudioTrack.DUB, languageId = "hi"))
    }
}

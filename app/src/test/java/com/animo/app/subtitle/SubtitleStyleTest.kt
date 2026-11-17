package com.animo.app.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleStyleTest {

    @Test
    fun `sanitize clamps out of range values`() {
        val wild = SubtitleStyle(
            sizeScale = 9f,
            outlineWidth = -4f,
            backgroundOpacity = 3f,
            bottomPaddingPercent = 400f,
            maxLines = 42,
            letterSpacing = 4f,
        )

        val clean = SubtitleStyleLimits.sanitize(wild)

        assertEquals(SubtitleStyleLimits.SIZE_MAX, clean.sizeScale, 0.001f)
        assertEquals(SubtitleStyleLimits.OUTLINE_MIN, clean.outlineWidth, 0.001f)
        assertEquals(SubtitleStyleLimits.OPACITY_MAX, clean.backgroundOpacity, 0.001f)
        assertEquals(SubtitleStyleLimits.BOTTOM_MAX, clean.bottomPaddingPercent, 0.001f)
        assertEquals(SubtitleStyleLimits.MAX_LINES_MAX, clean.maxLines)
        assertEquals(SubtitleStyleLimits.LETTER_SPACING_MAX, clean.letterSpacing, 0.001f)
    }

    @Test
    fun `defaults are inside their own limits`() {
        assertEquals(SubtitleStyles.Default, SubtitleStyleLimits.sanitize(SubtitleStyles.Default))
    }

    @Test
    fun `every built-in preset is valid and uniquely named`() {
        val presets = SubtitleStyles.builtInPresets
        assertTrue(presets.isNotEmpty())
        presets.forEach { preset ->
            assertEquals(preset.style, SubtitleStyleLimits.sanitize(preset.style))
            assertTrue(preset.builtIn)
            assertTrue(preset.name.isNotBlank())
        }
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        assertEquals(presets.size, presets.map { it.name }.toSet().size)
    }

    @Test
    fun `presets stay distinguishable from each other`() {
        val presets = SubtitleStyles.builtInPresets
        assertEquals(presets.size, presets.map { it.style }.toSet().size)
    }
}

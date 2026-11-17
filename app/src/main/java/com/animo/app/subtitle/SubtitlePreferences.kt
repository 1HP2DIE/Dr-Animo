package com.animo.app.subtitle

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.animo.app.ui.theme.animoUiPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Subtitle look + saved presets, persisted in the shared `animo_ui` DataStore.
 *
 * The style lives here rather than inside the player screen so it survives leaving the player,
 * changing episode and restarting the app. Presets are stored as JSON: a preferences key is a flat
 * map, and a list of named looks does not fit one.
 *
 * Reads are defensive — a value written by an older build, or a half-written string, falls back to
 * the default look instead of crashing the player.
 */
class SubtitlePreferences(context: Context) {

    private val store = context.applicationContext.animoUiPreferences
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The look currently in use. Emits the default when nothing has been saved yet. */
    val style: Flow<SubtitleStyle> = store.data
        .map { prefs -> decodeStyle(prefs[StyleKey]) }
        .distinctUntilChanged()

    /** The viewer's own presets, newest first. Built-ins are not stored — they live in code. */
    val presets: Flow<List<SubtitlePreset>> = store.data
        .map { prefs -> decodePresets(prefs[PresetsKey]) }
        .distinctUntilChanged()

    suspend fun setStyle(style: SubtitleStyle) {
        val sanitized = SubtitleStyleLimits.sanitize(style)
        store.edit { prefs -> prefs[StyleKey] = json.encodeToString(sanitized) }
    }

    suspend fun setPresets(presets: List<SubtitlePreset>) {
        store.edit { prefs -> prefs[PresetsKey] = json.encodeToString(presets) }
    }

    private fun decodeStyle(raw: String?): SubtitleStyle {
        if (raw.isNullOrBlank()) return SubtitleStyles.Default
        return runCatching { json.decodeFromString<SubtitleStyle>(raw) }
            .map(SubtitleStyleLimits::sanitize)
            .getOrDefault(SubtitleStyles.Default)
    }

    private fun decodePresets(raw: String?): List<SubtitlePreset> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<SubtitlePreset>>(raw) }
            .map { list -> list.map { it.copy(style = SubtitleStyleLimits.sanitize(it.style)) } }
            .getOrDefault(emptyList())
    }

    private companion object {
        val StyleKey = stringPreferencesKey("ui_subtitle_style")
        val PresetsKey = stringPreferencesKey("ui_subtitle_presets")
    }
}

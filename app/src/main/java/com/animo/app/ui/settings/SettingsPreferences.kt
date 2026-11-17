package com.animo.app.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.animo.app.domain.model.AudioTrack
import com.animo.app.ui.theme.animoUiPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Playback + catalogue preferences, persisted in the shared `animo_ui` DataStore.
 *
 * The player is owned by another agent, so these keys are written here and are ready to be read
 * wherever playback starts (they are not consumed by the player screen yet).
 */
class SettingsPreferences(private val context: Context) {

    private val store = context.animoUiPreferences

    val autoplayNext: Flow<Boolean> = store.data
        .map { prefs -> prefs[AutoplayNextKey] ?: true }
        .distinctUntilChanged()

    val defaultAudio: Flow<AudioTrack> = store.data
        .map { prefs ->
            prefs[DefaultAudioKey]?.let { name -> AudioTrack.entries.firstOrNull { it.name == name } }
                ?: AudioTrack.SUB
        }
        .distinctUntilChanged()

    /** Wall-clock time of the last *finished* sync; `null` = this install has never completed one. */
    val lastSyncEpoch: Flow<Long?> = store.data
        .map { prefs -> prefs[LastSyncEpochKey] }
        .distinctUntilChanged()

    /**
     * The app version whose release notes the user has already opened. `null` on a first install,
     * which is deliberately treated as "unseen" — those notes really are new to them.
     */
    val changelogSeenVersion: Flow<String?> = store.data
        .map { prefs -> prefs[ChangelogSeenKey] }
        .distinctUntilChanged()

    suspend fun setAutoplayNext(enabled: Boolean) {
        store.edit { prefs -> prefs[AutoplayNextKey] = enabled }
    }

    suspend fun setDefaultAudio(audio: AudioTrack) {
        store.edit { prefs -> prefs[DefaultAudioKey] = audio.name }
    }

    /**
     * Stamps "Last sync". Called from every completed sync path (catalogue, Home-triggered, Hindi),
     * so callers must only pass a time for a run that actually finished — not for a no-op or error.
     */
    suspend fun recordSync(epochMs: Long) {
        store.edit { prefs -> prefs[LastSyncEpochKey] = epochMs }
    }

    suspend fun markChangelogSeen(version: String) {
        store.edit { prefs -> prefs[ChangelogSeenKey] = version }
    }

    /**
     * Size of the Room database files on disk. Cheap enough to call when Settings opens: it is a
     * handful of `stat` calls, no full scan, and it needs no knowledge of the schema.
     */
    suspend fun catalogueBytes(): Long = withContext(Dispatchers.IO) {
        context.databaseList().sumOf { name ->
            runCatching { context.getDatabasePath(name).length() }.getOrDefault(0L)
        }
    }

    companion object {
        val AutoplayNextKey = booleanPreferencesKey("ui_autoplay_next")
        val DefaultAudioKey = stringPreferencesKey("ui_default_audio")
        val LastSyncEpochKey = longPreferencesKey("ui_last_sync_epoch")
        val ChangelogSeenKey = stringPreferencesKey("ui_changelog_seen_version")
    }
}

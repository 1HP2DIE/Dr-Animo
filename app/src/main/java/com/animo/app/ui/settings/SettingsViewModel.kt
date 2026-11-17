package com.animo.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.SyncProgress
import com.animo.app.domain.model.SyncState
import com.animo.app.domain.repo.AnimeRepository
import com.animo.app.ui.changelog.Changelog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val catalogueSize: Int = 0,
    val catalogueBytes: Long = 0L,
    val lastSyncEpoch: Long? = null,
    val autoplayNext: Boolean = true,
    val defaultAudio: AudioTrack = AudioTrack.SUB,
    val sync: SyncProgress = SyncProgress(done = 0, total = 0, state = SyncState.IDLE),
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    /** Highest app version whose release notes were opened; `null` = never opened. */
    val changelogSeenVersion: String? = null,
) {
    val progressFraction: Float
        get() = if (sync.total > 0) {
            (sync.done.toFloat() / sync.total.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

fun settingsViewModelFactory(repository: AnimeRepository, context: Context): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { SettingsViewModel(repository, SettingsPreferences(context.applicationContext)) }
    }

/**
 * Settings owns no navigation: it reads repository stats + persisted preferences and writes the
 * playback defaults back. Theme lives in `ThemePreference` so the drawer and this screen stay in
 * sync.
 */
class SettingsViewModel(
    private val repository: AnimeRepository,
    private val preferences: SettingsPreferences,
) : ViewModel() {

    private val catalogueSize = MutableStateFlow(0)
    private val catalogueBytes = MutableStateFlow(0L)
    private val isSyncing = MutableStateFlow(false)
    private val syncMessage = MutableStateFlow<String?>(null)

    private data class Prefs(
        val autoplayNext: Boolean,
        val defaultAudio: AudioTrack,
        val lastSyncEpoch: Long?,
        val changelogSeen: String?,
    )

    private val playback = combine(
        preferences.autoplayNext,
        preferences.defaultAudio,
        preferences.lastSyncEpoch,
        preferences.changelogSeenVersion,
    ) { autoplay, audio, lastSync, seen -> Prefs(autoplay, audio, lastSync, seen) }

    private val catalogue = combine(catalogueSize, catalogueBytes) { size, bytes -> size to bytes }

    val state: StateFlow<SettingsUiState> = combine(
        playback,
        catalogue,
        repository.sync,
        isSyncing,
        syncMessage,
    ) { playbackPrefs, catalogueStats, sync, syncing, message ->
        SettingsUiState(
            catalogueSize = catalogueStats.first,
            catalogueBytes = catalogueStats.second,
            lastSyncEpoch = playbackPrefs.lastSyncEpoch,
            autoplayNext = playbackPrefs.autoplayNext,
            defaultAudio = playbackPrefs.defaultAudio,
            changelogSeenVersion = playbackPrefs.changelogSeen,
            sync = sync,
            isSyncing = syncing,
            syncMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    init {
        viewModelScope.launch {
            refreshCatalogueStats()
            var wasSyncing = false
            repository.sync.collect { progress ->
                val syncing = progress.state == SyncState.RUNNING || progress.state == SyncState.THROTTLED
                if (wasSyncing && !syncing) refreshCatalogueStats()
                wasSyncing = syncing
            }
        }
    }

    fun syncNow() {
        if (isSyncing.value) return
        viewModelScope.launch {
            isSyncing.value = true
            syncMessage.value = null
            val result = repository.refreshCatalog(force = true)
            val failure = result.exceptionOrNull()
            if (failure != null) {
                syncMessage.value = failure.message ?: failure::class.simpleName
            } else {
                preferences.recordSync(System.currentTimeMillis())
            }
            isSyncing.value = false
            refreshCatalogueStats()
        }
    }

    fun setAutoplayNext(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoplayNext(enabled) }
    }

    fun setDefaultAudio(audio: AudioTrack) {
        viewModelScope.launch { preferences.setDefaultAudio(audio) }
    }

    /** Clears the "New" tag on the About row once the release notes have actually been opened. */
    fun markChangelogSeen() {
        val marker = Changelog.seenMarker
        viewModelScope.launch { preferences.markChangelogSeen(marker) }
    }

    private suspend fun refreshCatalogueStats() {
        catalogueSize.value = runCatching { repository.catalogSize() }.getOrDefault(catalogueSize.value)
        catalogueBytes.value = runCatching { preferences.catalogueBytes() }.getOrDefault(catalogueBytes.value)
    }
}

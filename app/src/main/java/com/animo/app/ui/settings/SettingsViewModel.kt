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
import com.animo.app.domain.repo.HindiCatalog
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
    /** False in builds/previews without a registered [HindiCatalog] — the block is then hidden. */
    val hindiAvailable: Boolean = false,
    val hindiCount: Int = 0,
    val hindiSyncing: Boolean = false,
    val hindiDone: Int = 0,
    val hindiTotal: Int = 0,
    /** Titles written by the last finished Hindi sync; `null` until one succeeds. */
    val hindiResultCount: Int? = null,
    /** Failure text from the last Hindi sync; `null` = clean. */
    val hindiMessage: String? = null,
) {
    val progressFraction: Float
        get() = if (sync.total > 0) {
            (sync.done.toFloat() / sync.total.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val hindiProgressFraction: Float
        get() = if (hindiTotal > 0) {
            (hindiDone.toFloat() / hindiTotal.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

fun settingsViewModelFactory(
    repository: AnimeRepository,
    context: Context,
    hindiCatalog: HindiCatalog? = null,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        SettingsViewModel(
            repository = repository,
            preferences = SettingsPreferences(context.applicationContext),
            hindiCatalog = hindiCatalog,
        )
    }
}

/**
 * Settings owns no navigation: it reads repository stats + persisted preferences and writes the
 * playback defaults back. Theme lives in `ThemePreference` so the drawer and this screen stay in
 * sync.
 *
 * Hindi is a second, independent catalogue with its own providers and rate budget, so it gets its
 * own count/sync state instead of being folded into the main sync rows. A `null` [hindiCatalog]
 * means "this build has no Hindi source" — every Hindi field stays at its default and the screen
 * hides the block.
 */
class SettingsViewModel(
    private val repository: AnimeRepository,
    private val preferences: SettingsPreferences,
    private val hindiCatalog: HindiCatalog? = null,
) : ViewModel() {

    private val catalogueSize = MutableStateFlow(0)
    private val catalogueBytes = MutableStateFlow(0L)
    private val isSyncing = MutableStateFlow(false)
    private val syncMessage = MutableStateFlow<String?>(null)

    /** Everything the Hindi block needs, kept in one flow so the state combine stays at five inputs. */
    private data class HindiStats(
        val count: Int = 0,
        val syncing: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val resultCount: Int? = null,
        val message: String? = null,
    )

    private val hindi = MutableStateFlow(HindiStats())

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

    private val playbackAndCatalogue = combine(playback, catalogue) { prefs, stats -> prefs to stats }

    val state: StateFlow<SettingsUiState> = combine(
        playbackAndCatalogue,
        repository.sync,
        isSyncing,
        syncMessage,
        hindi,
    ) { core, sync, syncing, message, hindiStats ->
        val (playbackPrefs, catalogueStats) = core
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
            hindiAvailable = hindiCatalog != null,
            hindiCount = hindiStats.count,
            hindiSyncing = hindiStats.syncing,
            hindiDone = hindiStats.done,
            hindiTotal = hindiStats.total,
            hindiResultCount = hindiStats.resultCount,
            hindiMessage = hindiStats.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(hindiAvailable = hindiCatalog != null),
    )

    init {
        viewModelScope.launch {
            refreshCatalogueStats()
            refreshHindiCount()
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

    /**
     * Hindi sync. Runs on its own flag so a main-catalogue sync in flight never disables this button
     * and vice versa; the provider rate budget is the only shared resource.
     */
    fun syncHindiNow() {
        val catalog = hindiCatalog ?: return
        if (hindi.value.syncing) return
        viewModelScope.launch {
            hindi.value = hindi.value.copy(
                syncing = true,
                done = 0,
                total = 0,
                resultCount = null,
                message = null,
            )
            val result = catalog.sync(force = true) { done, total ->
                hindi.value = hindi.value.copy(done = done, total = total)
            }
            val failure = result.exceptionOrNull()
            val count = runCatching { catalog.catalogCount() }.getOrDefault(hindi.value.count)
            hindi.value = if (failure != null) {
                hindi.value.copy(
                    syncing = false,
                    count = count,
                    message = failure.message ?: failure::class.simpleName,
                )
            } else {
                hindi.value.copy(
                    syncing = false,
                    count = count,
                    resultCount = result.getOrNull() ?: 0,
                    message = null,
                )
            }
            // Hindi rows are ordinary catalogue rows, so the main "Total titles" moved too.
            if (failure == null) refreshCatalogueStats()
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

    private suspend fun refreshHindiCount() {
        val catalog = hindiCatalog ?: return
        val count = runCatching { catalog.catalogCount() }.getOrNull() ?: return
        hindi.value = hindi.value.copy(count = count)
    }
}

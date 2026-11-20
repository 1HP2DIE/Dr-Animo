package com.animo.app.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.animo.app.data.sync.ProviderSync
import com.animo.app.data.sync.SyncDisabledException
import com.animo.app.di.ServiceLocator
import com.animo.app.domain.model.ContentKind
import com.animo.app.domain.model.ProviderServer
import com.animo.app.domain.model.ProviderSpec
import com.animo.app.domain.model.Providers
import com.animo.app.domain.model.SyncMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One provider as the page renders it: the compiled-in metadata from [ProviderSpec] plus the three
 * things that are only known at runtime — how many rows it owns, whether it may sync itself, and
 * how a running sync is going.
 */
data class ProviderRow(
    val id: String,
    val displayName: String,
    val summary: String,
    /** ISO-639-1 tags as compiled in; the screen turns them into language names. */
    val languages: List<String>,
    val kinds: List<ContentKind>,
    /** `null` = not read yet (no wire, or the first read failed) — the page shows a dash. */
    val count: Int? = null,
    val syncMode: SyncMode = SyncMode.MANUAL,
    val syncing: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** Failure text from this provider's last sync or mode write; `null` = clean. */
    val message: String? = null,
    val serversByKind: Map<ContentKind, List<ProviderServer>> = emptyMap(),
) {
    /** 0 until the first page lands; the screen renders the bar indeterminate while it is 0. */
    val progressFraction: Float
        get() = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    /**
     * Kinds to render, in [ContentKind.ALL] order. A kind the spec does not carry, or one with no
     * servers, is absent rather than an empty section.
     */
    val visibleKinds: List<ContentKind>
        get() = ContentKind.ALL.filter { kind -> kind in kinds && !serversByKind[kind].isNullOrEmpty() }
}

data class ProvidersUiState(
    val rows: List<ProviderRow> = emptyList(),
    /** True when `ServiceLocator.providerSync` is missing: counts stay blank and writes fail soft. */
    val unavailable: Boolean = false,
)

/**
 * The provider page: static metadata from [Providers] crossed with the per-provider runtime facts
 * (`countFor`, `syncMode`) and the two actions a viewer has — pick a [SyncMode], or sync once now.
 *
 * Every field is per row. A sync running on one provider must not touch another provider's button,
 * so [ProviderRow.syncing] is owned by its own row instead of being one screen-wide flag.
 *
 * The facade is read through [wireOrNull] on purpose: this screen ships before the sync layer is
 * wired in every build, and a missing wire has to blank a row instead of throwing on the way in.
 */
class ProvidersViewModel : ViewModel() {

    private val _state = MutableStateFlow(
        ProvidersUiState(rows = Providers.ALL.map(::seed)),
    )

    val state: StateFlow<ProvidersUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { refreshAll() }
    }

    /**
     * Optimistic: the segmented control moves under the finger, then the write either stands or the
     * row falls back to the mode it had and shows why.
     */
    fun onSyncModeChange(providerId: String, mode: SyncMode) {
        val current = row(providerId) ?: return
        if (current.syncMode == mode) return
        val wire = wireOrNull()
        if (wire == null) {
            update(providerId) { it.copy(message = UNAVAILABLE_MESSAGE) }
            return
        }
        update(providerId) { it.copy(syncMode = mode, message = null) }
        viewModelScope.launch {
            val failure = runCatching { wire.setMode(providerId, mode) }.exceptionOrNull()
            if (failure != null) {
                update(providerId) {
                    it.copy(syncMode = current.syncMode, message = failure.readable())
                }
            }
        }
    }

    /**
     * One force-sync for one provider. The flag is set before the coroutine starts, so a second tap
     * in the same frame cannot open a second run; other providers keep their own flags and stay
     * tappable throughout.
     */
    fun onSync(providerId: String) {
        val current = row(providerId) ?: return
        if (current.syncing) return
        val wire = wireOrNull()
        if (wire == null) {
            update(providerId) { it.copy(message = UNAVAILABLE_MESSAGE) }
            return
        }
        update(providerId) {
            it.copy(syncing = true, done = 0, total = 0, message = null)
        }
        viewModelScope.launch {
            val result = runCatching {
                wire.sync(providerId, force = true) { done, total ->
                    update(providerId) { it.copy(done = done, total = total) }
                }
            }.fold(onSuccess = { it }, onFailure = { Result.failure<Int>(it) })
            // A finished sync is the only moment the row count is known to be current.
            val count = runCatching { wire.countFor(providerId) }.getOrNull()
            update(providerId) {
                it.copy(
                    syncing = false,
                    count = count ?: it.count,
                    message = result.exceptionOrNull()?.readable(),
                )
            }
        }
    }

    private suspend fun refreshAll() {
        val wire = wireOrNull()
        if (wire == null) {
            _state.update { it.copy(unavailable = true) }
            return
        }
        _state.update { it.copy(unavailable = false) }
        Providers.ALL.forEach { spec -> refreshRow(spec.id, wire) }
    }

    private suspend fun refreshRow(providerId: String, wire: ProviderSync) {
        val count = runCatching { wire.countFor(providerId) }.getOrNull()
        val mode = runCatching { wire.syncMode(providerId) }.getOrNull()
        update(providerId) { row ->
            // A sync in flight owns its own progress; its count is rewritten when it finishes.
            if (row.syncing) row else row.copy(count = count ?: row.count, syncMode = mode ?: row.syncMode)
        }
    }

    private fun row(providerId: String): ProviderRow? =
        _state.value.rows.firstOrNull { it.id == providerId }

    private fun update(providerId: String, transform: (ProviderRow) -> ProviderRow) {
        _state.update { current ->
            current.copy(
                rows = current.rows.map { row ->
                    if (row.id == providerId) transform(row) else row
                },
            )
        }
    }

    companion object {

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProvidersViewModel() }
        }
    }
}

private const val UNAVAILABLE_MESSAGE = "Sync is unavailable in this build."

private fun wireOrNull(): ProviderSync? = runCatching { ServiceLocator.providerSync }.getOrNull()

private fun Throwable.readable(): String = when (this) {
    // The refusal is the feature, not a fault: say what to do instead of repeating an id.
    is SyncDisabledException -> "Sync is off for this provider. Pick Manual or Auto to sync."
    else -> message ?: this::class.simpleName ?: "Unknown error"
}

/**
 * The page opens on the compiled-in metadata, so it never shows an empty screen while Room answers:
 * the counts arrive a moment later and fill in under the same rows.
 */
private fun seed(spec: ProviderSpec): ProviderRow = ProviderRow(
    id = spec.id,
    displayName = spec.displayName,
    summary = spec.summary,
    languages = spec.languages.sorted(),
    kinds = ContentKind.ALL.filter { spec.supports(it) },
    syncMode = spec.defaultSyncMode,
    serversByKind = serversFor(spec),
)

private fun serversFor(spec: ProviderSpec): Map<ContentKind, List<ProviderServer>> =
    buildMap<ContentKind, List<ProviderServer>> {
        ContentKind.ALL.forEach { kind ->
            val servers = spec.serversFor(kind)
            if (spec.supports(kind) && servers.isNotEmpty()) put(kind, servers)
        }
    }

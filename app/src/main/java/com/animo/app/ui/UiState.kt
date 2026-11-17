package com.animo.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.animo.app.domain.repo.AnimeRepository
import com.animo.app.domain.repo.HindiCatalog
import com.animo.app.domain.repo.StreamRepository

/**
 * Four-state screen model used by every ViewModel in the app.
 *
 * [T] is declared `out` so `UiState.Empty` / `UiState.Error` (which carry no payload) can be
 * returned wherever `UiState<Something>` is expected.
 */
sealed interface UiState<out T> {

    /** First load, nothing to show yet. */
    data object Loading : UiState<Nothing>

    /** Loaded successfully but there is nothing to display (empty catalog, no search match). */
    data object Empty : UiState<Nothing>

    /**
     * Terminal failure with copy the UI can show. [message] is a technical cause, not user copy —
     * screens render their own headline and use this as the secondary line.
     */
    data class Error(val message: String, val cause: Throwable? = null) : UiState<Nothing>

    data class Content<T>(val data: T) : UiState<T>
}

/** Convenience for screens that only care about the payload. */
val <T> UiState<T>.contentOrNull: T?
    get() = (this as? UiState.Content)?.data

/**
 * Service graph handed to the UI. Implemented by `di/ServiceLocator` (Agent A) — the UI only ever
 * depends on the domain interfaces, never on the data layer.
 */
interface AppContainer {
    val animeRepository: AnimeRepository
    val streamRepository: StreamRepository

    /**
     * Hindi-dub catalogue, or `null` in builds/previews where it is not registered. Screens must
     * treat `null` as "hide the Hindi affordances", never as an error.
     */
    val hindiCatalog: HindiCatalog? get() = null
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer was not provided. Wrap the UI in CompositionLocalProvider(LocalAppContainer provides …).")
}

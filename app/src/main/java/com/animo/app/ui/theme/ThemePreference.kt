package com.animo.app.ui.theme

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.animo.app.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One DataStore for every UI preference (theme, playback defaults, last sync). It lives next to the
 * theme because the theme is the only consumer that has to be available before the first screen.
 */
internal val Context.animoUiPreferences: DataStore<Preferences> by preferencesDataStore(name = "animo_ui")

internal val ThemeModeKey = stringPreferencesKey("ui_theme_mode")
internal val AccentKey = stringPreferencesKey("ui_accent")
internal val AmoledKey = androidx.datastore.preferences.core.booleanPreferencesKey("ui_amoled")

/** Dark is the default product identity, so an absent key means dark, not "follow system". */
enum class ThemeMode { DARK, LIGHT, SYSTEM }

@StringRes
fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.DARK -> R.string.ui_theme_dark
    ThemeMode.LIGHT -> R.string.ui_theme_light
    ThemeMode.SYSTEM -> R.string.ui_theme_system
}

internal fun String?.toThemeMode(): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == this } ?: ThemeMode.DARK

/**
 * Read-side holder for the persisted theme. [mode] is the current value, [onModeChange] writes it
 * back to DataStore; both the drawer footer and the Settings radio use this single instance.
 */
@Stable
class ThemePreference(
    val mode: ThemeMode,
    val onModeChange: (ThemeMode) -> Unit,
    val accent: AccentPalette = AccentPalette.TEAL,
    val onAccentChange: (AccentPalette) -> Unit = {},
    /** Pure black surfaces in dark mode, for OLED screens. */
    val amoled: Boolean = false,
    val onAmoledChange: (Boolean) -> Unit = {},
)

/**
 * Defaults to a no-op dark preference so previews and any screen rendered outside the provider
 * (for example a single composable preview) still compose.
 */
val LocalThemePreference = staticCompositionLocalOf {
    ThemePreference(ThemeMode.DARK, onModeChange = {})
}

/**
 * Reads the persisted theme and keeps it in sync. A DataStore round trip is asynchronous, so the
 * first frame renders [ThemeMode.DARK] — the app's own default — instead of flashing white.
 */
@Composable
fun rememberThemePreference(): ThemePreference {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val modeFlow = remember(context) {
        context.animoUiPreferences.data
            .map { prefs -> Triple(prefs[ThemeModeKey].toThemeMode(), AccentPalette.of(prefs[AccentKey]), prefs[AmoledKey] ?: false) }
            .distinctUntilChanged()
    }
    val state by modeFlow.collectAsState(initial = Triple(ThemeMode.DARK, ActiveAccent.palette, false))
    val (mode, accent, amoled) = state
    return remember(state, context) {
        ThemePreference(
            mode = mode,
            onModeChange = { next ->
                scope.launch { context.animoUiPreferences.edit { prefs -> prefs[ThemeModeKey] = next.name } }
            },
            accent = accent,
            onAccentChange = { next ->
                scope.launch { context.animoUiPreferences.edit { prefs -> prefs[AccentKey] = next.name } }
            },
            amoled = amoled,
            onAmoledChange = { next ->
                scope.launch { context.animoUiPreferences.edit { prefs -> prefs[AmoledKey] = next } }
            },
        )
    }
}

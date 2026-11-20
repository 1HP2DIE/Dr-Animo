package com.animo.app.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.animo.app.data.update.LatestRelease
import com.animo.app.data.update.UpdateCenter
import com.animo.app.ui.changelog.Release
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the in-app update notice.
 *
 * [visible] is only true when the feed advertises a build newer than this process and the user has
 * not dismissed that exact version. Everything else — no network, feed 404, same version — is the
 * quiet path.
 */
data class UpdateUiState(
    val visible: Boolean = false,
    val latest: LatestRelease? = null,
    val releases: List<Release> = emptyList(),
    val currentVersion: String = "",
    val currentVersionCode: Int = 0,
    val checking: Boolean = true,
)

class UpdateViewModel(private val center: UpdateCenter) : ViewModel() {

    private val _state = MutableStateFlow(
        UpdateUiState(
            currentVersion = center.currentVersionName,
            currentVersionCode = center.currentVersionCode,
        ),
    )
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh(force: Boolean = false) {
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val feed = center.feed(force = force)
            // Read once; a dismissal only matters at decision time.
            val dismissed = center.dismissedVersion.first()
            val latest = feed?.latest
            _state.update {
                it.copy(
                    checking = false,
                    latest = latest,
                    releases = center.newerReleases(feed),
                    visible = center.isUpdateAvailable(feed, dismissed),
                )
            }
        }
    }

    /** "Later": this version stops prompting. A newer release prompts again. */
    fun dismiss() {
        val version = _state.value.latest?.version ?: return
        _state.update { it.copy(visible = false) }
        viewModelScope.launch { center.dismiss(version) }
    }
}

fun updateViewModelFactory(center: UpdateCenter): ViewModelProvider.Factory = viewModelFactory {
    initializer { UpdateViewModel(center) }
}

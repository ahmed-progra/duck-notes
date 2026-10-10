package app.duck.notes.presentation.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.duck.notes.data.settings.SettingsRepository
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.domain.model.SettingsSnapshot
import app.duck.notes.domain.model.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val snapshot: SettingsSnapshot = SettingsSnapshot(),
    val pendingWrite: Boolean = false,
    val writeFailed: Boolean = false,
    val startupTimedOut: Boolean = false,
)

internal fun isAppearanceReady(
    snapshot: SettingsSnapshot,
    appliedTheme: ThemePreference?,
    appliedDark: Boolean?,
    resourceDark: Boolean,
    systemDark: Boolean,
): Boolean {
    val dark = app.duck.notes.domain.model.effectiveDark(snapshot.settings.theme, systemDark)
    return snapshot.readStatus != SettingsReadStatus.Loading &&
        appliedTheme == snapshot.settings.theme && appliedDark == dark && resourceDark == dark
}

internal fun shouldKeepStartupSplash(
    startElapsedMs: Long,
    nowElapsedMs: Long,
    appearanceReady: Boolean,
): Boolean = !appearanceReady && nowElapsedMs - startElapsedMs < 1_000L

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val snapshot = repository.state
    val settingsOpen = savedStateHandle.getStateFlow("settings_open", false)

    private val pendingWrite = MutableStateFlow(false)
    private val writeFailed = MutableStateFlow(false)
    private val startupTimedOut = MutableStateFlow(false)
    internal var firstLaunchElapsedMs: Long? = null
        private set
    private var lastRequestedTheme: ThemePreference? = null

    val uiState = combine(snapshot, pendingWrite, writeFailed, startupTimedOut) {
            snapshot, pendingWrite, writeFailed, startupTimedOut ->
        SettingsUiState(snapshot, pendingWrite, writeFailed, startupTimedOut)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState(snapshot.value))

    fun openSettings() {
        savedStateHandle["settings_open"] = true
    }

    fun closeSettings() {
        savedStateHandle["settings_open"] = false
    }

    fun setTheme(value: ThemePreference) {
        if (pendingWrite.value) return
        pendingWrite.value = true
        writeFailed.value = false
        lastRequestedTheme = value
        viewModelScope.launch {
            try {
                repository.setTheme(value)
                lastRequestedTheme = null
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                writeFailed.value = true
            } finally {
                pendingWrite.value = false
            }
        }
    }

    fun retryRead() {
        repository.retryRead()
    }

    fun retry() {
        val theme = lastRequestedTheme
        if (writeFailed.value && theme != null) setTheme(theme) else retryRead()
    }

    fun retainFirstLaunch(startElapsedMs: Long) {
        if (firstLaunchElapsedMs == null) firstLaunchElapsedMs = startElapsedMs
    }

    fun markAppearanceReady() {
        if (snapshot.value.readStatus != SettingsReadStatus.Loading) startupTimedOut.value = false
    }

    fun shouldHoldStartup(nowElapsedMs: Long, appearanceReady: Boolean): Boolean {
        val startElapsedMs = firstLaunchElapsedMs ?: return false
        val ready = snapshot.value.readStatus != SettingsReadStatus.Loading && appearanceReady
        val hold = (snapshot.value.readStatus != SettingsReadStatus.Loading && !ready) ||
            shouldKeepStartupSplash(startElapsedMs, nowElapsedMs, ready)
        if (!hold && !ready) startupTimedOut.value = true
        return hold
    }
}

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
    private var pendingPreference: (suspend () -> Unit)? = null

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

    fun setTheme(value: ThemePreference) = writePreference { repository.setTheme(value) }

    fun setLockEnabled(value: Boolean) = writePreference { repository.setLockEnabled(value) }

    fun setLanguage(value: app.duck.notes.domain.model.LanguagePreference) = writePreference {
        repository.setLanguage(value)
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(value.languageTag))
    }

    fun syncSystemLanguage(value: app.duck.notes.domain.model.LanguagePreference) = writePreference { repository.setLanguage(value) }

    fun setEditorFont(value: app.duck.notes.domain.model.EditorFont) = writePreference { repository.setEditorFont(value) }

    fun setEditorSize(value: Int) = writePreference { repository.setEditorSize(value) }

    fun setNoteSort(value: app.duck.notes.domain.model.NoteSort) = writePreference { repository.setNoteSort(value) }

    private fun writePreference(write: suspend () -> Unit) {
        if (pendingWrite.value) return
        pendingWrite.value = true
        writeFailed.value = false
        pendingPreference = write
        viewModelScope.launch {
            try {
                write()
                pendingPreference = null
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                writeFailed.value = true
            } finally { pendingWrite.value = false }
        }
    }

    fun retryRead() { repository.retryRead() }

    fun retry() {
        val write = pendingPreference
        if (writeFailed.value && write != null) writePreference(write) else retryRead()
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

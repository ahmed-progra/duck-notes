package app.duck.notes.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.duck.notes.di.SettingsScope
import app.duck.notes.domain.model.AppSettings
import app.duck.notes.domain.model.SettingsNotice
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.domain.model.SettingsSnapshot
import app.duck.notes.domain.model.ThemePreference
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

internal fun createSettingsDataStore(
    file: File,
    scope: CoroutineScope,
    notices: MutableStateFlow<SettingsNotice?>,
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler {
        notices.value = SettingsNotice.ResetAfterCorruption
        emptyPreferences()
    },
    scope = scope,
    produceFile = { file },
)

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    notices: MutableStateFlow<SettingsNotice?>,
    @SettingsScope scope: CoroutineScope,
) {
    private val readAttempts = MutableStateFlow(0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val reads = readAttempts.flatMapLatest {
        dataStore.data.map { preferences ->
            SettingsSnapshot(parse(preferences), SettingsReadStatus.Ready)
        }.catch { failure ->
            if (failure is CancellationException || failure !is Exception) throw failure
            emit(
                SettingsSnapshot(
                    readStatus = SettingsReadStatus.TemporaryDefaults,
                    notice = SettingsNotice.TemporaryReadFailure,
                ),
            )
        }
    }

    val state: StateFlow<SettingsSnapshot> = combine(reads, notices) { snapshot, notice ->
        if (snapshot.readStatus == SettingsReadStatus.Ready) {
            snapshot.copy(notice = notice)
        } else {
            snapshot
        }
    }.stateIn(scope, SharingStarted.Eagerly, SettingsSnapshot())

    fun retryRead() {
        readAttempts.update { it + 1L }
    }

    suspend fun setTheme(value: ThemePreference) {
        dataStore.edit { it[themeKey] = value.storedValue }
        resumeReadAfterWrite()
    }

    suspend fun setLockEnabled(value: Boolean) {
        dataStore.edit { it[lockEnabledKey] = value }
        resumeReadAfterWrite()
    }

    suspend fun setLockTimeoutMs(value: Long) {
        require(value == 0L || value == 60_000L || value == 300_000L || value == 900_000L)
        dataStore.edit { it[lockTimeoutKey] = value }
        resumeReadAfterWrite()
    }

    suspend fun setHideRecents(value: Boolean) {
        dataStore.edit { it[hideRecentsKey] = value }
        resumeReadAfterWrite()
    }

    private fun resumeReadAfterWrite() {
        if (state.value.readStatus == SettingsReadStatus.TemporaryDefaults) retryRead()
    }

    private fun parse(preferences: Preferences): AppSettings {
        // Read by name so a valid protobuf containing the wrong value type also falls back.
        val values = preferences.asMap().entries.associate { it.key.name to it.value }
        val storedTheme = values["theme"] as? String
        val timeout = (values["lock_timeout_ms"] as? Long)?.takeIf {
            it == 0L || it == 60_000L || it == 300_000L || it == 900_000L
        } ?: 60_000L
        return AppSettings(
            theme = ThemePreference.entries.firstOrNull { it.storedValue == storedTheme }
                ?: ThemePreference.System,
            lockEnabled = values["lock_enabled"] as? Boolean ?: false,
            lockTimeoutMs = timeout,
            hideRecents = values["hide_recents"] as? Boolean ?: true,
        )
    }

    private companion object {
        val themeKey = stringPreferencesKey("theme")
        val lockEnabledKey = booleanPreferencesKey("lock_enabled")
        val lockTimeoutKey = longPreferencesKey("lock_timeout_ms")
        val hideRecentsKey = booleanPreferencesKey("hide_recents")
    }
}

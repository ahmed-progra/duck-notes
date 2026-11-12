package app.duck.notes.startup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.duck.notes.di.WelcomeStore
import javax.inject.Inject
import javax.inject.Singleton

/** Claims before writing notes: interruption may leave a partial tour, but never retries it. */
@Singleton
class WelcomeSeedState @Inject constructor(
    @WelcomeStore private val store: DataStore<Preferences>,
) {
    suspend fun claim(databaseExistedAtStartup: Boolean): Boolean {
        var claimed = false
        store.edit { preferences ->
            val origin = preferences[originKey] ?: if (databaseExistedAtStartup) "existing" else "fresh"
            preferences[originKey] = origin
            if (origin == "fresh" && preferences[attemptedKey] != true) {
                preferences[attemptedKey] = true
                claimed = true
            }
        }
        return claimed
    }

    private companion object {
        val originKey = stringPreferencesKey("installation_origin_v1")
        val attemptedKey = booleanPreferencesKey("welcome_attempted_v1")
    }
}

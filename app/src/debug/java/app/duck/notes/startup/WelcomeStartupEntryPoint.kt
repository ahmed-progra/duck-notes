package app.duck.notes.startup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.di.WelcomeStore
import app.duck.notes.domain.usecase.DeleteNote
import app.duck.notes.domain.usecase.SaveNote
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug inspection of the actual production component; never packaged in release. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WelcomeStartupEntryPoint {
    fun database(): DuckDatabase
    fun seeder(): SeedWelcomeNotes
    fun startup(): WelcomeStartup
    fun saveNote(): SaveNote
    fun settings(): app.duck.notes.data.settings.SettingsRepository
    fun deleteNote(): DeleteNote
    @WelcomeStore fun store(): DataStore<Preferences>
}

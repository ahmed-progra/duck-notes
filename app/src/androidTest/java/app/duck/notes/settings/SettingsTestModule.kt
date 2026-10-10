package app.duck.notes.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.DuckDatabaseCallback
import app.duck.notes.data.local.dao.AttachmentDao
import app.duck.notes.data.local.dao.NoteDao
import app.duck.notes.data.local.dao.NoteLinkDao
import app.duck.notes.data.local.dao.TagDao
import app.duck.notes.data.settings.createSettingsDataStore
import app.duck.notes.di.DatabaseModule
import app.duck.notes.di.SettingsModule
import app.duck.notes.di.SettingsScope
import app.duck.notes.domain.model.SettingsNotice
import app.duck.notes.domain.model.ThemePreference
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.io.File
import java.util.UUID
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/** Set on the instrumentation test thread before hiltRule.inject creates the test component. */
internal object SettingsTestOptions {
    var initialTheme = ThemePreference.Light
    fun reset() {
        initialTheme = ThemePreference.Light
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsTestResources(context: Context) {
    private val testRoot = File(context.cacheDir, "settings-instrumentation").canonicalFile
    val directory = File(testRoot, UUID.randomUUID().toString()).canonicalFile
    val file = File(directory, "settings.preferences_pb")
    val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val job = SupervisorJob()
    val scope = CoroutineScope(job + dispatcher)
    val notices = MutableStateFlow<SettingsNotice?>(null)

    init {
        check(directory.path.startsWith(testRoot.path + File.separator))
        check(directory.mkdirs())
        // Seed and close the first real instance before creating the test's active instance.
        runBlocking {
            val seedJob = SupervisorJob()
            val seedScope = CoroutineScope(seedJob + Dispatchers.IO)
            val seedStore = createSettingsDataStore(file, seedScope, MutableStateFlow(null))
            try {
                seedStore.edit {
                    it[stringPreferencesKey("theme")] = SettingsTestOptions.initialTheme.storedValue
                    it[booleanPreferencesKey("lock_enabled")] = false
                    it[longPreferencesKey("lock_timeout_ms")] = 60_000L
                    it[booleanPreferencesKey("hide_recents")] = true
                }
            } finally {
                seedJob.cancelAndJoin()
            }
        }
    }

    val store = createSettingsDataStore(file, scope, notices)

    fun advanceSettingsWork() = scheduler.advanceUntilIdle()

    fun blockWrites() {
        check(file.canonicalFile.parentFile == directory)
        check(file.delete())
        check(directory.delete())
        directory.writeText("A regular file deliberately blocks this isolated store's child")
    }

    fun unblockWrites() {
        check(directory.isFile)
        check(directory.delete())
        check(directory.mkdirs())
    }

    suspend fun close() {
        job.cancel()
        withTimeout(10_000L) {
            while (!job.isCompleted) {
                scheduler.advanceUntilIdle()
                delay(5L)
            }
        }
        job.join()
        check(directory.canonicalFile.path.startsWith(testRoot.path + File.separator))
        check(directory.deleteRecursively())
    }
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [SettingsModule::class, DatabaseModule::class],
)
object SettingsTestModule {
    @Provides @Singleton
    fun provideResources(@ApplicationContext context: Context) = SettingsTestResources(context)

    @Provides @Singleton @SettingsScope
    fun provideScope(resources: SettingsTestResources): CoroutineScope = resources.scope

    @Provides @Singleton
    fun provideNotices(resources: SettingsTestResources): MutableStateFlow<SettingsNotice?> = resources.notices

    @Provides @Singleton
    fun provideStore(resources: SettingsTestResources): DataStore<Preferences> = resources.store

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DuckDatabase =
        Room.inMemoryDatabaseBuilder(context, DuckDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .addCallback(DuckDatabaseCallback(seedSampleNotes = false))
            .build()

    @Provides fun provideNoteDao(database: DuckDatabase): NoteDao = database.noteDao()
    @Provides fun provideTagDao(database: DuckDatabase): TagDao = database.tagDao()
    @Provides fun provideNoteLinkDao(database: DuckDatabase): NoteLinkDao = database.noteLinkDao()
    @Provides fun provideAttachmentDao(database: DuckDatabase): AttachmentDao = database.attachmentDao()
}

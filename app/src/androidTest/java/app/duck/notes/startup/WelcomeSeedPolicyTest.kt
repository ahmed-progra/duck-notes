package app.duck.notes.startup

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.RealClock
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.DuckDatabaseCallback
import app.duck.notes.data.repository.NoteRepositoryImpl
import app.duck.notes.domain.usecase.DeriveTitle
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.ResolveWikiLinks
import app.duck.notes.domain.usecase.SaveNote
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Disk-backed policy checks using real DataStore, SaveNote, repository and bundled SQLite.
 * Closing Room makes the first real save fail after the persisted claim; no save fake replaces it.
 * WELCOME_NO_ATTEMPT_WRITE must break the reopened-store four-note and empty-database assertions.
 * Actual application startup and a fresh process are covered separately by WelcomeStartupTest.
 */
@RunWith(AndroidJUnit4::class)
class WelcomeSeedPolicyTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var database: DuckDatabase
    private lateinit var store: DataStore<Preferences>
    private lateinit var storeJob: Job
    private val clock = RealClock()

    private val stateFile get() = File(directory, "welcome.preferences_pb")
    private val databaseFile get() = File(directory, "notes.db")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        directory = File(context.cacheDir, "welcome-policy-${UUID.randomUUID()}").canonicalFile
        check(directory.parentFile == context.cacheDir.canonicalFile)
        check(directory.mkdirs())
        openDatabase()
        openStore()
    }

    @After
    fun tearDown() {
        runBlocking { storeJob.cancelAndJoin() }
        database.close()
        check(directory.parentFile == context.cacheDir.canonicalFile)
        check(directory.deleteRecursively())
    }

    @Test
    fun freshSeedsOnceAndKeepsUserEditsAfterReopeningStore() = runBlocking {
        assertFalse(databaseFile.exists())
        seeder()(false)
        assertEquals(4, database.noteDao().count())
        assertEquals("fresh", store.data.first()[stringPreferencesKey("installation_origin_v1")])
        val original = database.noteDao().observeNotes().first().single { it.title == "Welcome to Duck" }
        val edited = original.body + "\nMy welcome edit survives.\n"
        saveNote()(original.id, edited)

        restartStore()
        seeder()(false)
        assertEquals(4, database.noteDao().count())
        assertEquals(true, store.data.first()[booleanPreferencesKey("welcome_attempted_v1")])
        assertEquals(edited, database.noteDao().getById(original.id)?.body)

        database.noteDao().observeNotes().first().forEach { database.noteDao().delete(it) }
        assertEquals(0, database.noteDao().count())
        restartStore()
        seeder()(false)
        assertEquals("persisted claim, not an empty database, controls seeding", 0, database.noteDao().count())
        assertEquals(true, store.data.first()[booleanPreferencesKey("welcome_attempted_v1")])
    }

    @Test
    fun existingEmptyDatabasePersistsItsOriginAcrossStoreRestart() = runBlocking {
        assertEquals(0, database.noteDao().count())
        assertTrue(databaseFile.exists())
        seeder()(true)
        assertEquals(0, database.noteDao().count())
        assertEquals("existing", store.data.first()[stringPreferencesKey("installation_origin_v1")])
        assertNull(store.data.first()[booleanPreferencesKey("welcome_attempted_v1")])

        restartStore()
        // The persisted origin wins over a later caller's fresh-install classification.
        seeder()(false)
        assertEquals(0, database.noteDao().count())
        assertEquals("existing", store.data.first()[stringPreferencesKey("installation_origin_v1")])
    }

    @Test
    fun existingPopulatedDatabaseGetsNoWelcomeNotes() = runBlocking {
        val own = saveNote()(null, "# Existing note\n\nWritten before the welcome feature.\n")
        assertEquals(1, database.noteDao().count())
        seeder()(true)
        restartStore()
        seeder()(false)
        assertEquals(1, database.noteDao().count())
        assertEquals(own.body, database.noteDao().getById(own.id)?.body)
        assertEquals("existing", store.data.first()[stringPreferencesKey("installation_origin_v1")])
    }

    @Test
    fun firstRealSaveFailureLeavesClaimAndReopenedStoreDoesNotRetry() = runBlocking {
        assertEquals(0, database.noteDao().count())
        assertTrue(store.data.first().asMap().isEmpty())
        database.close()
        val failure = runCatching { seeder()(false) }.exceptionOrNull()
        // Closing this Room version cancels its query scope rather than returning a "closed" message.
        assertTrue("closed Room must cancel the first production save: $failure", failure is CancellationException)
        assertTrue("the caller must still be active", currentCoroutineContext().isActive)
        val queryFailure = runCatching { database.noteDao().count() }.exceptionOrNull()
        assertTrue("positive control: the closed real DAO must also reject queries", queryFailure is CancellationException)
        assertEquals("fresh", store.data.first()[stringPreferencesKey("installation_origin_v1")])
        assertEquals("claim must precede the failing save", true, store.data.first()[booleanPreferencesKey("welcome_attempted_v1")])

        restartStore()
        openDatabase()
        seeder()(false)
        assertEquals("a claimed but interrupted tour is not retried", 0, database.noteDao().count())
        assertEquals(true, store.data.first()[booleanPreferencesKey("welcome_attempted_v1")])
    }

    @Test
    fun corruptionCannotResetStateToFreshDefaultsAndSeed() = runBlocking {
        storeJob.cancelAndJoin()
        val corrupt = byteArrayOf(0xff.toByte(), 0x00, 0x7f)
        stateFile.writeBytes(corrupt)
        openStore()
        try {
            seeder()(false)
            fail("corrupt welcome preferences must stop seeding")
        } catch (_: CorruptionException) {
            // The actual Preferences serializer rejects these bytes; no corruption handler resets them.
        }
        assertEquals(0, database.noteDao().count())
        assertArrayEquals("corruption must not be silently replaced", corrupt, stateFile.readBytes())

        // Positive control: the same production save chain seeds with a valid fresh state file.
        storeJob.cancelAndJoin()
        check(stateFile.delete())
        openStore()
        seeder()(false)
        assertEquals(4, database.noteDao().count())
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(context, DuckDatabase::class.java, databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addCallback(DuckDatabaseCallback())
            .build()
    }

    private fun openStore() {
        check(!::storeJob.isInitialized || !storeJob.isActive)
        storeJob = SupervisorJob()
        store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(storeJob + Dispatchers.IO),
            produceFile = { stateFile },
        )
    }

    private suspend fun restartStore() {
        storeJob.cancelAndJoin()
        openStore()
    }

    private fun saveNote() = SaveNote(
        NoteRepositoryImpl(database.noteDao()), DeriveTitle(), ParseTags(), ResolveWikiLinks(), clock,
    )

    private fun seeder() = SeedWelcomeNotes(WelcomeSeedState(store), saveNote(), clock)
}

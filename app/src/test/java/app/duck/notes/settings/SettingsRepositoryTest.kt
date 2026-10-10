package app.duck.notes.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.duck.notes.data.settings.SettingsRepository
import app.duck.notes.data.settings.createSettingsDataStore
import app.duck.notes.domain.model.SettingsNotice
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.domain.model.SettingsSnapshot
import app.duck.notes.domain.model.ThemePreference
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real Preferences DataStore files, with the production factory and repository.
 * Plants: STORE_ROUNDTRIP, STORE_DEFAULTS, STORE_FALLBACK, STORE_CORRUPTION,
 * STORE_READ_RETRY, STORE_TIMEOUT_SETTER and STORE_WRITE_DROPPED.
 * A failing filesystem write below exercises the real write boundary; visible UI recovery
 * remains the responsibility of the Activity test rather than a substitute UI here.
 */
class SettingsRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun missingKeysUseLiteralDefaults() = runBlocking {
        val opened = open(newSettingsFile())
        try {
            val snapshot = ready(opened)
            assertEquals(ThemePreference.System, snapshot.settings.theme)
            assertFalse(snapshot.settings.lockEnabled)
            assertEquals(60_000L, snapshot.settings.lockTimeoutMs)
            assertTrue(snapshot.settings.hideRecents)
            assertNull(snapshot.notice)
        } finally {
            opened.close()
        }
    }

    @Test fun editsRoundTripThroughANewStoreWithOnlyTheFourSettingsKeys() = runBlocking {
        val file = newSettingsFile()
        val first = open(file)
        try {
            first.repository.setTheme(ThemePreference.Dark)
            first.repository.setLockEnabled(true)
            first.repository.setLockTimeoutMs(900_000L)
            first.repository.setHideRecents(false)
            assertEquals(
                setOf("theme", "lock_enabled", "lock_timeout_ms", "hide_recents"),
                first.store.data.first().asMap().keys.map { it.name }.toSet(),
            )
        } finally {
            first.close()
        }
        val reopened = open(file)
        try {
            val snapshot = ready(reopened)
            assertEquals(ThemePreference.Dark, snapshot.settings.theme)
            assertTrue(snapshot.settings.lockEnabled)
            assertEquals(900_000L, snapshot.settings.lockTimeoutMs)
            assertFalse(snapshot.settings.hideRecents)
        } finally {
            reopened.close()
        }
    }

    @Test fun unknownValuesFallBackPerFieldAndPreserveValidFields() = runBlocking {
        val opened = open(newSettingsFile()) { store ->
            store.edit {
                it[stringPreferencesKey("theme")] = "ultraviolet"
                it[booleanPreferencesKey("lock_enabled")] = true
                it[longPreferencesKey("lock_timeout_ms")] = 17L
                it[booleanPreferencesKey("hide_recents")] = false
            }
        }
        try {
            val snapshot = ready(opened)
            assertEquals(ThemePreference.System, snapshot.settings.theme)
            assertTrue(snapshot.settings.lockEnabled)
            assertEquals(60_000L, snapshot.settings.lockTimeoutMs)
            assertFalse(snapshot.settings.hideRecents)
        } finally {
            opened.close()
        }
    }

    @Test fun wrongStoredTypesFallBackWithoutTreatingTheProtobufAsCorrupt() = runBlocking {
        val opened = open(newSettingsFile()) { store ->
            store.updateData {
                mutablePreferencesOf(
                    booleanPreferencesKey("theme") to true,
                    stringPreferencesKey("lock_enabled") to "true",
                    intPreferencesKey("lock_timeout_ms") to 0,
                    longPreferencesKey("hide_recents") to 0L,
                )
            }
        }
        try {
            val snapshot = ready(opened)
            assertEquals(ThemePreference.System, snapshot.settings.theme)
            assertFalse(snapshot.settings.lockEnabled)
            assertEquals(60_000L, snapshot.settings.lockTimeoutMs)
            assertTrue(snapshot.settings.hideRecents)
            assertNull(snapshot.notice)
        } finally {
            opened.close()
        }
    }

    @Test fun allFourLiteralTimeoutChoicesCanBeSaved() = runBlocking {
        val opened = open(newSettingsFile())
        try {
            ready(opened)
            for (timeout in listOf(0L, 60_000L, 300_000L, 900_000L)) {
                opened.repository.setLockTimeoutMs(timeout)
                assertEquals(timeout, opened.store.data.first()[longPreferencesKey("lock_timeout_ms")])
                assertEquals(timeout, ready(opened) { it.settings.lockTimeoutMs == timeout }
                    .settings.lockTimeoutMs)
            }
        } finally {
            opened.close()
        }
    }

    @Test fun corruptProtobufResetsToDefaultsAndReportsAnActualReset() = runBlocking {
        val file = newSettingsFile()
        file.writeBytes(byteArrayOf(0xFF.toByte()))
        val opened = open(file)
        try {
            val snapshot = ready(opened)
            assertEquals(ThemePreference.System, snapshot.settings.theme)
            assertFalse(snapshot.settings.lockEnabled)
            assertEquals(60_000L, snapshot.settings.lockTimeoutMs)
            assertTrue(snapshot.settings.hideRecents)
            assertEquals(SettingsNotice.ResetAfterCorruption, opened.notices.value)
            assertEquals(
                SettingsNotice.ResetAfterCorruption,
                ready(opened) { it.notice == SettingsNotice.ResetAfterCorruption }.notice,
            )
            assertTrue(opened.store.data.first().asMap().isEmpty())
        } finally {
            opened.close()
        }
    }

    @Test fun failedFileWriteThrowsAndLeavesThePublishedSavedValueAlone() = runBlocking {
        val parent = temporary.newFolder("blocked-on-write")
        val opened = open(File(parent, "settings.preferences_pb"))
        try {
            assertEquals(ThemePreference.System, ready(opened).settings.theme)
            assertTrue("The initial read should not create the preferences file", parent.delete())
            parent.writeText("A regular file now blocks creation of its child")
            try {
                opened.repository.setTheme(ThemePreference.Dark)
                fail("A real filesystem write failure must reach the caller")
            } catch (_: IOException) {
                assertEquals(ThemePreference.System, opened.repository.state.value.settings.theme)
            }
        } finally {
            opened.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun temporaryReadFailureDoesNotResetTheFileAndCanBeRetried() = runBlocking {
        val file = newSettingsFile()
        assertTrue(file.mkdir())
        val sentinel = File(file, "keep.txt").apply { writeText("preserve") }
        val dispatcher = StandardTestDispatcher()
        val opened = open(file, dispatcher)
        try {
            dispatcher.scheduler.advanceUntilIdle()
            val failed = opened.repository.state.value
            assertEquals(SettingsReadStatus.TemporaryDefaults, failed.readStatus)
            assertEquals(SettingsNotice.TemporaryReadFailure, failed.notice)
            assertEquals(ThemePreference.System, failed.settings.theme)
            assertFalse(failed.settings.lockEnabled)
            assertEquals("preserve", sentinel.readText())
            assertTrue(sentinel.delete())
            assertTrue(file.delete())
            opened.repository.retryRead()
            dispatcher.scheduler.advanceUntilIdle()
            val recovered = opened.repository.state.value
            assertEquals("STORE_READ_RETRY: repaired real file did not become Ready", SettingsReadStatus.Ready, recovered.readStatus)
            assertEquals(ThemePreference.System, recovered.settings.theme)
            assertNull(recovered.notice)
            assertFalse(file.exists())
        } finally {
            opened.job.cancel()
            dispatcher.scheduler.advanceUntilIdle()
            opened.close()
        }
    }

    private fun newSettingsFile(): File = File(temporary.newFolder(), "settings.preferences_pb")

    private suspend fun ready(
        opened: OpenSettings,
        predicate: (SettingsSnapshot) -> Boolean = { true },
    ): SettingsSnapshot = withTimeout(10_000L) {
        opened.repository.state.first {
            it.readStatus == SettingsReadStatus.Ready && predicate(it)
        }
    }

    private suspend fun open(
        file: File,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        seed: suspend (DataStore<Preferences>) -> Unit = {},
    ): OpenSettings {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + dispatcher)
        val notices = MutableStateFlow<SettingsNotice?>(null)
        val store = createSettingsDataStore(file, scope, notices)
        try {
            seed(store)
            return OpenSettings(store, SettingsRepository(store, notices, scope), notices, job)
        } catch (failure: Throwable) {
            job.cancelAndJoin()
            throw failure
        }
    }

    private data class OpenSettings(
        val store: DataStore<Preferences>,
        val repository: SettingsRepository,
        val notices: MutableStateFlow<SettingsNotice?>,
        val job: Job,
    ) {
        suspend fun close() = job.cancelAndJoin()
    }
}

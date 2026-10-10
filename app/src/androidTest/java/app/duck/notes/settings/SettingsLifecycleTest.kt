package app.duck.notes.settings

import android.app.UiModeManager
import android.os.Build
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.settings.SettingsRepository
import app.duck.notes.di.AutosaveDispatcher
import app.duck.notes.di.AutosaveModule
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.domain.model.ThemePreference
import app.duck.notes.presentation.MainActivity
import app.duck.notes.presentation.settings.SettingsViewModel
import app.duck.notes.presentation.shell.DuckShellViewModel
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual MainActivity stop + actual Room write, with the actual ViewModel's debounce paused.
 * Only the autosave observer uses the owned scheduler, pumped on Android's real main Looper.
 * Lifecycle, buffer sync, state sharing and flush stay on real Main; stop tests hold autosave at 0.
 * HiltTestApplication makes this isolated Activity coverage, not production Application startup.
 *
 * Named plants in real DuckScreen.kt, each a single anchored deletion of
 * LifecycleEventEffect(Lifecycle.Event.ON_STOP) { shellViewModel.onStop() }:
 * ON_STOP_EDITOR_NO_FLUSH -> editorStopWritesActualRoomWhileRealDebounceRemainsAtZero.
 * ON_STOP_SETTINGS_NO_FLUSH -> settingsOpenStopWritesActualRoomWhileRealDebounceRemainsAtZero.
 * A build/runtime failure is not the intended assertion RED. Both must fail their Room-save
 * assertion under the deletion, then pass after reverse-only restoration.
 * AUTOSAVE_IDLE_LATE: production IDLE_MS = 400L -> 401L must fail
 * activeAutosaveObserverSavesAtLiteralIdleWithoutStopping's literal-400ms Room assertion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@UninstallModules(AutosaveModule::class)
@RunWith(AndroidJUnit4::class)
class SettingsLifecycleTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var resources: SettingsTestResources
    @Inject lateinit var repository: SettingsRepository
    @Inject lateinit var database: DuckDatabase
    private val mainScheduler = TestCoroutineScheduler()
    @BindValue @AutosaveDispatcher @JvmField
    val autosaveDispatcher: CoroutineDispatcher = StandardTestDispatcher(mainScheduler)
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var owner: DuckShellViewModel

    @Before fun setUp() {
        SettingsTestOptions.initialTheme = ThemePreference.Light
        hilt.inject()
        // The Preferences worker has a separate scheduler from the autosave observer.
        awaitAtZero("isolated Preferences never became ready") {
            repository.state.value.readStatus == SettingsReadStatus.Ready
        }
        runBlocking {
            database.noteDao().insert(NoteEntity(
                id = NOTE_ID,
                title = NOTE_TITLE,
                body = ORIGINAL_BODY,
                createdAt = 1_700_000_000_000L,
                modifiedAt = 1_700_000_000_000L,
            ))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitAtZero("real note row never reached the real shell") {
            compose.onAllNodesWithText(NOTE_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(NOTE_TITLE).performClick()
        requireNotNull(scenario).onActivity {
            owner = ViewModelProvider(it)[DuckShellViewModel::class.java]
        }
        awaitAtZero("real owner never loaded the seeded body") {
            ::owner.isInitialized && owner.state.value.selectedNoteId == NOTE_ID &&
                owner.buffer.text.toString() == ORIGINAL_BODY
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        assertZero()
        assertEquals("Positive control: real Room seed differs", ORIGINAL_BODY, storedBody())
    }

    @After fun tearDown() {
        try {
            scenario?.close() // Clears retained ViewModels and cancels their timers/collectors.
        } finally {
            scenario = null
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    Snapshot.sendApplyNotifications()
                    mainScheduler.runCurrent()
                }
            } finally {
                try {
                    if (::resources.isInitialized) runBlocking { resources.close() }
                } finally {
                    try {
                        if (::database.isInitialized) database.close()
                    } finally {
                        try {
                            val instrumentation = InstrumentationRegistry.getInstrumentation()
                            instrumentation.runOnMainSync {
                                try {
                                    if (Build.VERSION.SDK_INT >= 31) {
                                        instrumentation.targetContext.getSystemService(UiModeManager::class.java)
                                            .setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                                    }
                                } finally {
                                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                                }
                            }
                        } finally {
                            SettingsTestOptions.reset()
                        }
                    }
                }
            }
        }
    }

    @Test fun activeAutosaveObserverSavesAtLiteralIdleWithoutStopping() {
        val activity = requireNotNull(scenario)
        activity.onActivity { owner.buffer.edit { selection = TextRange(length) } }
        val sentinel = "\nTyped active-autosave sentinel"
        compose.onNode(hasSetTextAction()).performTextInput(sentinel)
        pumpCurrentMain()
        val expected = ORIGINAL_BODY + sentinel
        assertEquals("Real editor input did not reach the retained owner", expected, owner.buffer.text.toString())
        assertFalse("Positive control is vacuous: edited text equals the seed", expected == ORIGINAL_BODY)
        assertEquals("Active autosave wrote before its idle interval", ORIGINAL_BODY, storedBody())
        assertEquals("Positive control: Activity stopped before the idle save", Lifecycle.State.RESUMED, activity.state)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mainScheduler.advanceTimeBy(399L)
            mainScheduler.runCurrent()
        }
        assertEquals("Literal pre-idle boundary moved", 399L, mainScheduler.currentTime)
        assertEquals("Active autosave wrote before literal 400ms", ORIGINAL_BODY, storedBody())

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mainScheduler.advanceTimeBy(1L)
            mainScheduler.runCurrent()
        }
        assertEquals("Literal idle boundary moved", 400L, mainScheduler.currentTime)
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline && storedBody() != expected) {
            pumpCurrentMain(400L)
            resources.advanceSettingsWork()
            Thread.sleep(10L)
        }
        assertEquals("Positive control: Activity stopped during the idle save", Lifecycle.State.RESUMED, activity.state)
        assertEquals("ACTIVE_AUTOSAVE: literal 400ms did not save without ON_STOP", expected, storedBody())
        assertEquals("Real Room IO advanced the autosave scheduler", 400L, mainScheduler.currentTime)
    }

    @Test fun editorStopWritesActualRoomWhileRealDebounceRemainsAtZero() {
        requireNotNull(scenario).onActivity {
            owner.buffer.edit { selection = TextRange(length) }
        }
        val sentinel = "\nTyped editor-stop sentinel"
        compose.onNode(hasSetTextAction()).performTextInput(sentinel)
        pumpCurrentMain()
        val expected = ORIGINAL_BODY + sentinel
        assertEquals("Real editor input did not reach the retained owner", expected, owner.buffer.text.toString())
        proveRealDebounceIsPaused(expected)
        stopAndAssertActualRoomSaved(expected)
    }

    @Test fun settingsOpenStopWritesActualRoomWhileRealDebounceRemainsAtZero() {
        requireNotNull(scenario).onActivity {
            owner.buffer.edit { selection = TextRange(length) }
        }
        val sentinel = "\nTyped Settings-open final sentinel"
        compose.onNode(hasSetTextAction()).performTextInput(sentinel)
        pumpCurrentMain()
        val expected = ORIGINAL_BODY + sentinel
        assertEquals("Real editor input did not reach the retained owner", expected, owner.buffer.text.toString())
        requireNotNull(scenario).onActivity {
            ViewModelProvider(it)[SettingsViewModel::class.java].openSettings()
        }
        awaitAtZero("actual Settings destination never opened") {
            var opened = false
            requireNotNull(scenario).onActivity {
                opened = ViewModelProvider(it)[SettingsViewModel::class.java].settingsOpen.value
                assertSame(owner, ViewModelProvider(it)[DuckShellViewModel::class.java])
            }
            opened && compose.onAllNodesWithText("Theme").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
        // The real edit preceded the route switch, while its production debounce stayed at 0.
        assertEquals(expected, owner.buffer.text.toString())
        proveRealDebounceIsPaused(expected)
        stopAndAssertActualRoomSaved(expected)
    }

    private fun proveRealDebounceIsPaused(expected: String) {
        val realStart = SystemClock.elapsedRealtime()
        // Literal >400ms positive control, while the actual production coroutine timers stay at 0.
        while (SystemClock.elapsedRealtime() - realStart < 500L) {
            pumpCurrentMain()
            resources.advanceSettingsWork()
            Thread.sleep(10L)
        }
        assertTrue(SystemClock.elapsedRealtime() - realStart >= 500L)
        assertZero()
        assertEquals("Buffer sentinel was lost before stop", expected, owner.buffer.text.toString())
        assertEquals("Paused production debounce saved before the actual stop", ORIGINAL_BODY, storedBody())
        assertFalse("Positive control is vacuous: edited text equals the seed", expected == ORIGINAL_BODY)
    }

    private fun stopAndAssertActualRoomSaved(expected: String) {
        val activity = requireNotNull(scenario)
        activity.moveToState(Lifecycle.State.CREATED)
        assertEquals("Positive control: real Activity did not stop", Lifecycle.State.CREATED, activity.state)
        // The lifecycle hook runs on real Main. Pumping autosave's current tasks cannot expire
        // either its 400ms debounce or 2000ms max-wait while real Main and Room IO complete.
        awaitAtZero("Actual ON_STOP did not save the sentinel in Room at virtual time 0") {
            storedBody() == expected
        }
        assertEquals(expected, storedBody())
        assertZero()
    }

    private fun storedBody(): String {
        val note = runBlocking { database.noteDao().getById(NOTE_ID) }
        assertNotNull("Positive control: actual isolated Room note is missing", note)
        return requireNotNull(note).body
    }

    private fun awaitAtZero(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var satisfied = false
        while (SystemClock.elapsedRealtime() < deadline) {
            pumpCurrentMain()
            resources.advanceSettingsWork()
            if (predicate()) { satisfied = true; break }
            Thread.sleep(10L)
        }
        assertTrue(message, satisfied)
        assertZero()
    }

    private fun pumpCurrentMain(expectedTime: Long = 0L) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Snapshot.sendApplyNotifications()
            mainScheduler.runCurrent()
            assertEquals("Compose or a helper advanced the owned autosave scheduler", expectedTime, mainScheduler.currentTime)
        }
    }

    private fun assertZero() {
        assertEquals("Compose or a helper advanced the owned autosave scheduler", 0L, mainScheduler.currentTime)
    }

    private companion object {
        const val NOTE_ID = "settings-lifecycle-fixture"
        const val NOTE_TITLE = "Settings lifecycle fixture"
        const val ORIGINAL_BODY = "Seeded original body; no timer should replace this before stop."
    }
}

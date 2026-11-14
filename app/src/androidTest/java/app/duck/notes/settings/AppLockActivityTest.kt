package app.duck.notes.settings

import android.content.Intent
import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import android.view.WindowManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.settings.SettingsRepository
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.presentation.MainActivity
import app.duck.notes.presentation.settings.AppLockSession
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import app.duck.notes.presentation.shell.DuckShellViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Activity/Room/Preferences visibility and lifecycle. Plants ACTIVITY_SECURE_FLAG and
 * ACTIVITY_LOCK_GATE: remove the real flag, or replace mayShowNotes with true.
 * The session-success fixture below does not qualify Android credential authentication;
 * that boundary requires a separate real device-credential interaction.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppLockActivityTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var resources: SettingsTestResources
    @Inject lateinit var repository: SettingsRepository
    @Inject lateinit var database: DuckDatabase
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setup() {
        hilt.inject()
        await { repository.state.value.readStatus == SettingsReadStatus.Ready }
        runBlocking { database.noteDao().insert(NoteEntity(id = "private-fixture", title = "Private security fixture",
            body = "Synthetic private text", createdAt = 1, modifiedAt = 1)) }
    }

    @After fun cleanup() {
        try { scenario?.close() } finally {
            if (::resources.isInitialized) runBlocking { resources.close() }
            if (::database.isInitialized) database.close()
            SettingsTestOptions.reset()
        }
    }

    @Test fun secureWindowIsEnabledAndDisabledLockStillShowsTheRealNote() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await { compose.onAllNodesWithText("Private security fixture").fetchSemanticsNodes().isNotEmpty() }
        scenario?.onActivity {
            assertTrue("ACTIVITY_SECURE_FLAG", it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        compose.onAllNodesWithText("Private security fixture").assertCountEquals(1)
    }

    @Test fun enabledLockHidesNotesAcrossTheRealActivityBackgroundAndRecreation() {
        resources.scope.launch { repository.setLockEnabled(true) }
        await { repository.state.value.settings.lockEnabled }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var owner: DuckShellViewModel
        scenario?.onActivity { owner = ViewModelProvider(it)[DuckShellViewModel::class.java] }
        val dataControl = CoroutineScope(Dispatchers.Main.immediate).launch { owner.state.collect {} }
        try {
            // Read the real note flow even while gated, so an empty UI cannot vouch for unloaded data.
            await { owner.state.value.notes.any { it.id == "private-fixture" } }
            compose.waitForIdle()
            compose.onAllNodesWithText("Private security fixture").assertCountEquals(0)
            scenario?.onActivity {
                ViewModelProvider(it)[AppLockSession::class.java].apply { begin(); finish(true) }
            }
            await { compose.onAllNodesWithText("Private security fixture").fetchSemanticsNodes().isNotEmpty() }
            scenario?.recreate()
            await { compose.onAllNodesWithText("Private security fixture").fetchSemanticsNodes().isNotEmpty() }
            scenario?.moveToState(Lifecycle.State.CREATED)
            scenario?.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            compose.onAllNodesWithText("Private security fixture").assertCountEquals(0)
            assertEquals("Synthetic private text", runBlocking { database.noteDao().getById("private-fixture") }?.body)
        } finally {
            runBlocking { dataControl.cancelAndJoin() }
        }
    }

    /** Plant USER_LEAVE_NO_LOCK: remove background(false) from MainActivity.onUserLeaveHint. */
    @Test fun userLeaveCallbackLocksBeforeTheActivityHasStopped() {
        resources.scope.launch { repository.setLockEnabled(true) }
        await { repository.state.value.settings.lockEnabled }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.onActivity { ViewModelProvider(it)[AppLockSession::class.java].apply { begin(); finish(true) } }
        await { compose.onAllNodesWithText("Private security fixture").fetchSemanticsNodes().isNotEmpty() }
        scenario?.onActivity {
            assertTrue("positive control: this must not rely on onStop", it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            it.onUserLeaveHint()
            assertFalse(ViewModelProvider(it)[AppLockSession::class.java].state.value.authenticated)
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Private security fixture").assertCountEquals(0)
    }

    /** Plant PAUSE_NO_LOCK: skip invalidation in both onPause and loss-of-top-resumed callbacks. */
    @Test fun translucentExternalActivityCannotLeaveAnAuthenticatedSessionOpen() {
        resources.scope.launch { repository.setLockEnabled(true) }
        await { repository.state.value.settings.lockEnabled }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.onActivity { ViewModelProvider(it)[AppLockSession::class.java].apply { begin(); finish(true) } }
        await { compose.onAllNodesWithText("Private security fixture").fetchSemanticsNodes().isNotEmpty() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val overlay = Intent().setComponent(ComponentName(instrumentation.context.packageName,
            PauseOnlyOverlayActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        try {
            val command = "am start -W -f 0x10040000 -n " + requireNotNull(overlay.component).flattenToString()
            val launch = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
            assertTrue("external overlay did not launch: $launch", launch.contains("Status: ok"))
            instrumentation.waitForIdleSync()
            scenario?.onActivity {
                assertEquals("positive control: translucent overlay only pauses Duck", Lifecycle.State.STARTED, it.lifecycle.currentState)
                assertFalse("PAUSE_NO_LOCK", ViewModelProvider(it)[AppLockSession::class.java].state.value.authenticated)
            }
        } finally {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
        }
    }

    /** Plant TOP_RESUMED_NO_LOCK: remove loss-of-top-resumed invalidation. */
    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    fun losingTopResumedPositionLocksWithoutPausingTheActivity() {
        resources.scope.launch { repository.setLockEnabled(true) }
        await { repository.state.value.settings.lockEnabled }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.onActivity { ViewModelProvider(it)[AppLockSession::class.java].apply { begin(); finish(true) } }
        await { compose.onAllNodesWithText("Private security fixture").fetchSemanticsNodes().isNotEmpty() }
        scenario?.onActivity {
            assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState)
            it.onTopResumedActivityChanged(false)
            assertFalse(ViewModelProvider(it)[AppLockSession::class.java].state.value.authenticated)
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Private security fixture").assertCountEquals(0)
    }

    private fun await(predicate: () -> Boolean) {
        compose.waitUntil(10_000) { resources.advanceSettingsWork(); predicate() }
    }
}

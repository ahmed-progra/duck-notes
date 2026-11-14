package app.duck.notes.settings

import android.accessibilityservice.AccessibilityService
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.execSQL
import androidx.room.useWriterConnection
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.settings.SettingsRepository
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.domain.model.ThemePreference
import app.duck.notes.presentation.MainActivity
import app.duck.notes.presentation.shell.DuckShellViewModel
import app.duck.notes.presentation.settings.AppLockSession
import app.duck.notes.presentation.settings.SettingsViewModel
import app.duck.notes.presentation.transfer.TransferViewModel
import app.duck.notes.presentation.transfer.TransferKind
import app.duck.notes.data.repository.TransferDocumentProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TransferActivityTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var resources: SettingsTestResources
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var database: DuckDatabase
    private var scenario: ActivityScenario<MainActivity>? = null
    private val id = UUID.randomUUID().toString()
    private val body = "# Transfer fixture\n#research\nA synthetic note."
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun setUp() {
        instrumentation.runOnMainSync {
            if (Build.VERSION.SDK_INT >= 33) instrumentation.targetContext.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
            else AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.getEmptyLocaleList())
        }
        SettingsTestOptions.initialTheme = ThemePreference.Light
        hilt.inject()
        compose.waitUntil(10_000) { resources.advanceSettingsWork(); settings.state.value.readStatus == SettingsReadStatus.Ready }
        runBlocking { database.noteDao().saveNoteWithTagsAndLinks(NoteEntity(id = id, title = "Transfer fixture", body = body, createdAt = 1, modifiedAt = 2), listOf("research")) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }
    @After fun close() {
        // Return from a real external picker before closing the Activity fixture.
        if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.endsWith(".documentsui") == true) {
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            instrumentation.waitForIdleSync()
        }
        scenario?.close()
        if (::resources.isInitialized) runBlocking { resources.close() }
        if (::database.isInitialized) database.close()
        SettingsTestOptions.reset()
    }
    private fun picker() {
        compose.waitUntil(10_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.endsWith(".documentsui") == true }
    }
    private fun cancelPicker() {
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        instrumentation.waitForIdleSync()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Transfer cancelled.").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun selectNote() {
        compose.onNodeWithText("Transfer fixture").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("noteEditor").fetchSemanticsNodes().isNotEmpty() }
    }

    /** Real ACTION_OPEN_DOCUMENT, actual system cancellation, and retained user-visible result. */
    @Test fun importCancellationReturnsFromTheRealPickerWithoutChangingNotes() {
        compose.onNodeWithText("Files").performClick()
        compose.onNodeWithText("Import notes").performClick()
        picker()
        cancelPicker()
        scenario?.recreate()
        compose.onNodeWithText("Transfer cancelled.").assertIsDisplayed()
        runBlocking { assertEquals(1, database.noteDao().count()); assertEquals(body, database.noteDao().getById(id)?.body) }
    }

    /** Plant: prepare exports without flushing the real editor's pending text first. */
    @Test fun noteExportFlushesActualTypingBeforeOpeningTheRealDestinationPicker() {
        selectNote()
        val editor = compose.onNodeWithTag("noteEditor")
        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(body.length, body.length, false) }
        editor.performTextInput(" Latest edit.")
        compose.onNodeWithText("Note actions").performClick()
        compose.onNodeWithText("Export note").performClick()
        picker()
        val payloads = File(instrumentation.targetContext.noBackupFilesDir, "transfers").listFiles().orEmpty()
            .map { File(it, "payload") }.filter { it.isFile }
        assertEquals(1, payloads.size)
        assertEquals(body + " Latest edit.", payloads.single().readText(Charsets.UTF_8))
        runBlocking { assertEquals(body + " Latest edit.", database.noteDao().getById(id)?.body) }
        cancelPicker()
    }

    /** Real failed Room update must block the picker and retain both the edit and its retry. */
    @Test fun failedSaveBlocksExportAcrossRecreationAndRetryUsesThePendingText() {
        runBlocking { database.useWriterConnection { it.execSQL("CREATE TRIGGER export_save_failure BEFORE UPDATE ON notes BEGIN SELECT RAISE(ABORT, 'export save test'); END") } }
        selectNote()
        val editor = compose.onNodeWithTag("noteEditor")
        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(body.length, body.length, false) }
        editor.performTextInput(" Kept after failure.")
        compose.onNodeWithText("Note actions").performClick()
        compose.onNodeWithText("Export note").performClick()
        val error = "Could not prepare the export. Resolve any save error and check available storage, then retry."
        compose.waitUntil(10_000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.endsWith(".documentsui") == true)
        scenario?.recreate()
        compose.onNodeWithText(error).assertIsDisplayed()
        runBlocking { database.useWriterConnection { it.execSQL("DROP TRIGGER export_save_failure") } }
        compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
        picker()
        runBlocking { assertEquals(body + " Kept after failure.", database.noteDao().getById(id)?.body) }
        cancelPicker()
    }

    /** Plant: drain while locked. Real provider/Room; authentication success is supplied directly. */
    @Test fun queuedPickerSelectionDoesNotReadTheProviderUntilTheAppUnlocks() {
        val source = Uri.parse("content://${TransferDocumentProvider.AUTHORITY}/source")
        val resolver = instrumentation.targetContext.contentResolver
        resolver.call(source, "reset", null, null)
        resolver.call(source, "grant", null, null)
        resolver.call(source, "source", null, Bundle().apply { putByteArray("bytes", "# Imported after unlock\nSynthetic note".toByteArray(Charsets.UTF_8)) })
        lateinit var transfer: TransferViewModel
        lateinit var lock: AppLockSession
        scenario?.onActivity { activity ->
            transfer = ViewModelProvider(activity)[TransferViewModel::class.java]
            lock = ViewModelProvider(activity)[AppLockSession::class.java]
            lock.begin(); lock.finish(true)
            ViewModelProvider(activity)[SettingsViewModel::class.java].setLockEnabled(true)
        }
        compose.waitUntil(10_000) { resources.advanceSettingsWork(); settings.state.value.settings.lockEnabled }
        try {
            compose.onNodeWithText("Files").performClick()
            compose.onNodeWithText("Import notes").performClick()
            picker()
            // Supply the production callback's selection while the system picker has locked the app.
            instrumentation.runOnMainSync { transfer.pickerReturned(TransferKind.Import, source.toString()) }
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            instrumentation.waitForIdleSync()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Set device screen lock").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Unlock").fetchSemanticsNodes().isNotEmpty() }
            scenario?.recreate()
            assertEquals(0, resolver.call(source, "status", null, null)?.getInt("opened"))
            runBlocking { assertEquals(1, database.noteDao().count()) }
            // Exercise the app's successful-authentication callback, not Android's credential UI.
            scenario?.onActivity { lock.begin(); lock.finish(true) }
            compose.waitUntil(10_000) { runBlocking { database.noteDao().count() == 2 } }
            assertTrue((resolver.call(source, "status", null, null)?.getInt("opened") ?: 0) > 0)
            runBlocking { assertEquals(body, database.noteDao().getById(id)?.body) }
        } finally { resolver.call(source, "revoke", null, null) }
    }

    /** Real chooser and separate-UID receiver: read grant works, write grant is denied, bytes agree. */
    @Test fun shareSheetDeliversOnlyReadAccessToThePreparedNote() {
        val control = Uri.parse("content://${TransferDocumentProvider.AUTHORITY}/")
        val resolver = instrumentation.targetContext.contentResolver
        resolver.call(control, "reset_shared", null, null)
        selectNote()
        compose.onNodeWithText("Note actions").performClick()
        compose.onNodeWithText("Share note").performClick()
        var chosen = false
        compose.waitUntil(10_000) {
            val receiver = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Duck Notes transfer test")?.firstOrNull()
            if (receiver != null) {
                var node: android.view.accessibility.AccessibilityNodeInfo = receiver
                while (!node.isClickable) {
                    val parent = node.parent ?: break
                    node = parent
                }
                chosen = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
            chosen
        }
        var result: Bundle? = null
        compose.waitUntil(10_000) {
            result = resolver.call(control, "shared", null, null)
            result?.containsKey("bytes") == true || result?.containsKey("error") == true
        }
        assertNull(result?.getString("error"))
        assertArrayEquals(body.toByteArray(Charsets.UTF_8), result?.getByteArray("bytes"))
        assertEquals("text/markdown", result?.getString("type"))
        runBlocking { assertEquals(body, database.noteDao().getById(id)?.body) }
    }

    /** Plant: let temporary-directory unlink failure turn an already committed import into failure. */
    @Test fun committedImportStaysSuccessfulWhenTemporaryCleanupNeedsRetry() {
        val source = Uri.parse("content://${TransferDocumentProvider.AUTHORITY}/blocked")
        val resolver = instrumentation.targetContext.contentResolver
        resolver.call(source, "reset", null, null); resolver.call(source, "grant", null, null)
        resolver.call(source, "source", null, Bundle().apply { putByteArray("bytes", "# Cleanup boundary\nSynthetic note".toByteArray(Charsets.UTF_8)) })
        lateinit var transfer: TransferViewModel
        scenario?.onActivity { transfer = ViewModelProvider(it)[TransferViewModel::class.java] }
        var previousMode: Int? = null
        val directory = File(instrumentation.targetContext.noBackupFilesDir, "transfers")
        try {
            compose.onNodeWithText("Files").performClick()
            compose.onNodeWithText("Import notes").performClick()
            picker()
            instrumentation.runOnMainSync { transfer.pickerReturned(TransferKind.Import, source.toString()) }
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            instrumentation.waitForIdleSync()
            compose.waitUntil(10_000) { (resolver.call(source, "status", null, null)?.getInt("opened") ?: 0) > 0 }
            previousMode = android.system.Os.stat(directory.path).st_mode and 511
            android.system.Os.chmod(directory.path, 365)
            resolver.call(source, "release", null, null)
            compose.waitUntil(10_000) { transfer.ui.value.step == null && runBlocking { database.noteDao().count() == 2 } }
            assertEquals(app.duck.notes.presentation.transfer.TransferNotice.Imported, transfer.ui.value.notice)
            assertTrue(transfer.ui.value.cleanupFailed)
            compose.onNodeWithText("Imported notes: 1. Existing notes were kept.").assertIsDisplayed()
            runBlocking { assertEquals(body, database.noteDao().getById(id)?.body) }
        } finally {
            resolver.call(source, "release", null, null)
            previousMode?.let { android.system.Os.chmod(directory.path, it) }
            app.duck.notes.data.transfer.TransferFiles(instrumentation.targetContext).cleanAbandoned()
            resolver.call(source, "revoke", null, null)
        }
    }
}

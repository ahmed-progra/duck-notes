package app.duck.notes.settings

import android.accessibilityservice.AccessibilityService
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.content.ContentValues
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
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

    private fun systemNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && matches(node)) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }

    /** Real MediaStore album and OS photo selection; app auth success is supplied directly afterward. */
    @androidx.test.filters.SdkSuppress(minSdkVersion = 35)
    @Test fun realPhotoPickerSelectionWaitsForUnlockAndStillAttachesToTheOriginalNote() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val album = "DuckV2-${UUID.randomUUID().toString().take(8)}"
        val taken = java.time.Instant.parse("2030-01-02T12:34:56Z").toEpochMilli()
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "duck-v2-fixture.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$album/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_TAKEN, taken)
        }))
        val bitmap = android.graphics.Bitmap.createBitmap(20, 30, android.graphics.Bitmap.Config.ARGB_8888)
        val encoded = java.io.ByteArrayOutputStream().use { bytes ->
            bitmap.eraseColor(android.graphics.Color.rgb(215, 73, 76))
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, bytes)); bytes.toByteArray()
        }
        bitmap.recycle()
        try {
            resolver.openOutputStream(uri).use { requireNotNull(it).write(encoded) }
            resolver.openFileDescriptor(uri, "rw").use { descriptor ->
                @Suppress("DEPRECATION")
                val exif = android.media.ExifInterface(requireNotNull(descriptor).fileDescriptor)
                exif.setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2030:01:02 12:34:56")
                exif.setAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
                exif.saveAttributes()
            }
            val expected = resolver.openInputStream(uri).use { requireNotNull(it).readBytes() }
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0); put(MediaStore.Images.Media.DATE_TAKEN, taken) }, null, null))
            resolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null).use { cursor ->
                check(requireNotNull(cursor).moveToFirst()); assertEquals("Synthetic photo date positive control", taken, cursor.getLong(0))
            }
            lateinit var lock: AppLockSession
            scenario?.onActivity { activity ->
                lock = ViewModelProvider(activity)[AppLockSession::class.java]
                lock.begin(); lock.finish(true)
                ViewModelProvider(activity)[SettingsViewModel::class.java].setLockEnabled(true)
            }
            compose.waitUntil(10_000) { resources.advanceSettingsWork(); settings.state.value.settings.lockEnabled }
            selectNote()
            compose.onNodeWithContentDescription("Add image").performScrollTo().performClick()
            val day = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)
                .format(java.time.Instant.ofEpochMilli(taken).atZone(java.time.ZoneId.systemDefault()))
            var selected: AccessibilityNodeInfo? = null
            compose.waitUntil(20_000) {
                systemNode { node ->
                    val description = node.contentDescription?.toString().orEmpty()
                    description.startsWith("Photo", ignoreCase = true) && description.contains(day)
                }.also { selected = it } != null
            }
            var clickable = selected
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            assertTrue("Synthetic system photo target was not clickable", clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            val add = systemNode { it.isClickable && it.text?.toString()?.startsWith("Add") == true }
            add?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Set device screen lock").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Unlock").fetchSemanticsNodes().isNotEmpty() }
            runBlocking { kotlinx.coroutines.delay(500); assertTrue("Photo attachment was imported before app authentication", database.attachmentDao().forNote(id).isEmpty()) }
            scenario?.onActivity { lock.begin(); lock.finish(true) }
            compose.waitUntil(20_000) { runBlocking { database.attachmentDao().forNote(id).size == 1 } }
            val image = runBlocking { database.attachmentDao().forNote(id).single() }
            assertArrayEquals(expected, File(context.filesDir, image.uri).readBytes())
            runBlocking { assertTrue(database.noteDao().getById(id)?.body.orEmpty().contains(app.duck.notes.domain.markdown.MarkdownScanner.imageRef(image.id))) }
        } finally {
            if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains(".providers.media") == true) {
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                instrumentation.waitForIdleSync()
            }
            resolver.delete(uri, null, null)
            runBlocking { database.attachmentDao().forNote(id).forEach { app.duck.notes.data.repository.AttachmentStorageImpl(context).delete(it.uri) } }
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 35)
    @Test fun filesAndBackupControlsRemainUsableOnAnArabicPhoneAtTwoHundredPercentText() {
        org.junit.Assume.assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val size = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1) ?: "reset"
        val density = Regex("Override density: (\\d+)").find(shell("wm density"))?.groupValues?.get(1) ?: "reset"
        val oldFont = shell("settings get system font_scale")
        val font = if (oldFont == "null") null else requireNotNull(oldFont.toFloatOrNull()).toString()
        try {
            shell("wm size 720x1600"); shell("wm density 320"); shell("settings put system font_scale 2.0")
            instrumentation.waitForIdleSync()
            scenario?.onActivity { activity ->
                ViewModelProvider(activity)[SettingsViewModel::class.java].setLanguage(app.duck.notes.domain.model.LanguagePreference.Arabic)
            }
            compose.waitUntil(15_000) {
                resources.advanceSettingsWork()
                compose.onAllNodesWithText("الملفات").fetchSemanticsNodes().isNotEmpty()
            }
            scenario?.onActivity { activity ->
                assertEquals(720, activity.resources.displayMetrics.widthPixels)
                assertEquals(1600, activity.resources.displayMetrics.heightPixels)
                assertEquals(2f, activity.resources.configuration.fontScale, 0.01f)
                assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
            }
            val files = compose.onNodeWithText("الملفات").assertIsDisplayed()
            val bounds = files.fetchSemanticsNode().boundsInRoot
            assertTrue("Files touch target is shorter than 48 dp", bounds.height >= 96f)
            files.performClick()
            compose.onNodeWithText("استيراد ملاحظات").assertIsDisplayed()
            compose.onNodeWithText("تصدير هذه المجموعة").assertIsDisplayed()
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("استيراد ملاحظات").fetchSemanticsNodes().isEmpty() }
            instrumentation.waitForIdleSync()
            compose.onNodeWithContentDescription("فتح القائمة الجانبية").assertIsDisplayed().performClick()
            compose.onNodeWithText("الإعدادات").assertIsDisplayed().performClick()
            compose.onNodeWithText("إنشاء نسخة احتياطية مشفرة").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("backupPassphrase").performScrollTo().assertIsDisplayed()
            assertTrue("Passphrase field lacks password accessibility semantics", compose.onNodeWithTag("backupPassphrase").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Password))
            compose.onNodeWithText("إنشاء النسخة").assertIsDisplayed()
            compose.onNodeWithText("إلغاء").assertIsDisplayed().performClick()
            runBlocking { assertEquals(body, database.noteDao().getById(id)?.body) }
        } finally {
            scenario?.close(); scenario = null
            instrumentation.runOnMainSync { instrumentation.targetContext.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList() }
            shell(if (font == null) "settings delete system font_scale" else "settings put system font_scale $font")
            shell("wm density $density"); shell("wm size $size")
        }
    }

    /** Real settings/password controls: mismatches cannot start work; backgrounding clears secrets. */
    @Test fun backupRequiresMatchingPassphrasesAndClearsThemWhenBackgrounded() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Create encrypted backup").performScrollTo().performClick()
        compose.onNodeWithTag("backupPassphrase").performTextInput("a synthetic backup passphrase")
        compose.onNodeWithTag("backupConfirmation").performTextInput("different synthetic passphrase")
        compose.onNodeWithText("Create backup").performClick()
        compose.onNodeWithText("Passphrases do not match.").assertIsDisplayed()
        scenario?.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario?.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithTag("backupPassphrase").assertTextEquals("")
        compose.onNodeWithTag("backupConfirmation").assertTextEquals("")
        compose.onNodeWithText("Cancel").performClick()
        runBlocking { assertEquals(body, database.noteDao().getById(id)?.body) }
    }

    /** Real editor -> save -> encrypted payload -> ACTION_CREATE_DOCUMENT, then system cancellation. */
    @Test fun backupCreationFlushesActualTypingAndKeepsNoPasswordHistory() {
        selectNote()
        val editor = compose.onNodeWithTag("noteEditor")
        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(body.length, body.length, false) }
        editor.performTextInput(" Latest backup edit.")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Create encrypted backup").performScrollTo().performClick()
        val password = "a synthetic portable passphrase"
        compose.onNodeWithTag("backupPassphrase").performTextInput(password)
        compose.onNodeWithTag("backupConfirmation").performTextInput(password)
        compose.onNodeWithText("Create backup").performClick()
        compose.waitUntil(30_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.endsWith(".documentsui") == true }
        lateinit var document: app.duck.notes.domain.model.PreparedDocument
        scenario?.onActivity { activity ->
            val transfer = ViewModelProvider(activity)[TransferViewModel::class.java]
            document = requireNotNull(transfer.request.value?.document)
            assertTrue(document.displayName.endsWith(".duckbackup"))
            assertEquals("", transfer.passphrase.text.toString())
            assertEquals("", transfer.confirmation.text.toString())
            assertFalse(transfer.passphrase.undoState.canUndo)
            assertFalse(transfer.confirmation.undoState.canUndo)
        }
        val context = instrumentation.targetContext
        val payload = File(context.noBackupFilesDir, "transfers/${document.id}/payload")
        val plain = File(context.noBackupFilesDir, "backup-ui-${UUID.randomUUID()}.zip")
        try {
            runBlocking { app.duck.notes.data.transfer.BackupEncryption.decrypt(payload, plain, password.toCharArray()) }
            java.util.zip.ZipFile(plain).use { zip ->
                assertEquals(body + " Latest backup edit.", zip.getInputStream(zip.getEntry("notes/$id.md")).bufferedReader(Charsets.UTF_8).use { it.readText() })
            }
            runBlocking { assertEquals(body + " Latest backup edit.", database.noteDao().getById(id)?.body) }
            cancelPicker()
        } finally {
            plain.delete()
            app.duck.notes.data.transfer.TransferFiles(context).close(document.id)
        }
    }

    /** Actual backup picker/result and provider; successful app auth is supplied directly, not by OS credentials. */
    @Test fun restoreSelectionCannotReadOrPromptForAPassphraseUntilTheAppUnlocks() {
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val output = Uri.parse("content://${TransferDocumentProvider.AUTHORITY}/output")
        resolver.call(output, "reset", null, null); resolver.call(output, "grant", null, null)
        val port = app.duck.notes.data.repository.NoteTransferRepositoryImpl(context,
            app.duck.notes.data.transfer.NoteArchiveStore(context, database, app.duck.notes.data.repository.AttachmentStorageImpl(context),
                app.duck.notes.domain.usecase.DeriveTitle(), app.duck.notes.domain.usecase.ParseTags(), app.duck.notes.domain.usecase.ResolveWikiLinks()))
        val password = "a synthetic portable passphrase"
        val document = runBlocking { port.prepareBackup(password.toCharArray()).also { port.writePrepared(it.id, output.toString()) } }
        lateinit var transfer: TransferViewModel
        lateinit var lock: AppLockSession
        try {
            resolver.call(output, "reset", null, null)
            scenario?.onActivity { activity ->
                transfer = ViewModelProvider(activity)[TransferViewModel::class.java]
                lock = ViewModelProvider(activity)[AppLockSession::class.java]
                lock.begin(); lock.finish(true)
                ViewModelProvider(activity)[SettingsViewModel::class.java].setLockEnabled(true)
            }
            compose.waitUntil(10_000) { resources.advanceSettingsWork(); settings.state.value.settings.lockEnabled }
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Restore backup").performScrollTo().performClick()
            picker()
            instrumentation.runOnMainSync { transfer.pickerReturned(TransferKind.Restore, output.toString()) }
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            instrumentation.waitForIdleSync()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Set device screen lock").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Unlock").fetchSemanticsNodes().isNotEmpty() }
            scenario?.recreate()
            assertEquals(0, resolver.call(output, "status", null, null)?.getInt("opened"))
            compose.onNodeWithTag("backupPassphrase").assertDoesNotExist()
            scenario?.onActivity { lock.begin(); lock.finish(true) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("backupPassphrase").fetchSemanticsNodes().isNotEmpty() }
            assertTrue((resolver.call(output, "status", null, null)?.getInt("opened") ?: 0) > 0)
            compose.onNodeWithTag("backupPassphrase").performTextInput("wrong synthetic passphrase")
            compose.onNodeWithText("Unlock and restore").performClick()
            val unlockError = "Could not unlock this backup. Check the passphrase and file, or cancel and choose the file again. Existing notes are unchanged."
            compose.waitUntil(30_000) { compose.onAllNodesWithText(unlockError).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("backupPassphrase").assertTextEquals("")
            runBlocking { assertEquals(body, database.noteDao().getById(id)?.body) }
            compose.onNodeWithTag("backupPassphrase").performTextInput(password)
            compose.onNodeWithText("Unlock and restore").performClick()
            val result = "Restored notes: 0. Existing note IDs skipped: 1. Existing notes were kept unchanged."
            compose.waitUntil(30_000) { compose.onAllNodesWithText(result).fetchSemanticsNodes().isNotEmpty() }
            runBlocking { assertEquals(1, database.noteDao().count()); assertEquals(body, database.noteDao().getById(id)?.body) }
        } finally {
            runBlocking { port.discardPrepared(document.id) }
            resolver.call(output, "revoke", null, null)
        }
    }

    /** Plant: omit ViewModel finish cleanup; encrypted staging then survives a finished restore dialog. */
    @Test fun finishingTheActivityDiscardsAPendingEncryptedRestore() {
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val output = Uri.parse("content://${TransferDocumentProvider.AUTHORITY}/output")
        resolver.call(output, "reset", null, null); resolver.call(output, "grant", null, null)
        val port = app.duck.notes.data.repository.NoteTransferRepositoryImpl(context,
            app.duck.notes.data.transfer.NoteArchiveStore(context, database, app.duck.notes.data.repository.AttachmentStorageImpl(context),
                app.duck.notes.domain.usecase.DeriveTitle(), app.duck.notes.domain.usecase.ParseTags(), app.duck.notes.domain.usecase.ResolveWikiLinks()))
        val document = runBlocking { port.prepareBackup("a synthetic portable passphrase".toCharArray()).also { port.writePrepared(it.id, output.toString()) } }
        val directory = File(context.noBackupFilesDir, "transfers")
        val before = directory.list().orEmpty().toSet()
        var pending: String? = null
        try {
            lateinit var transfer: TransferViewModel
            scenario?.onActivity { transfer = ViewModelProvider(it)[TransferViewModel::class.java] }
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Restore backup").performScrollTo().performClick()
            picker()
            instrumentation.runOnMainSync { transfer.pickerReturned(TransferKind.Restore, output.toString()) }
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            instrumentation.waitForIdleSync()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("backupPassphrase").fetchSemanticsNodes().isNotEmpty() }
            val created = directory.list().orEmpty().toSet() - before
            assertEquals("Positive control: pending encrypted stage was not created", 1, created.size)
            pending = created.single()
            scenario?.close(); scenario = null
            val cleaned = runBlocking { kotlinx.coroutines.withTimeoutOrNull(2_000) {
                while (File(directory, requireNotNull(pending)).exists()) kotlinx.coroutines.delay(10)
                true
            } ?: false }
            assertTrue("Finished Activity leaked the encrypted restore stage", cleaned)
            runBlocking { assertEquals(body, database.noteDao().getById(id)?.body) }
        } finally {
            pending?.let { app.duck.notes.data.transfer.TransferFiles(context).close(it) }
            runBlocking { port.discardPrepared(document.id) }
            resolver.call(output, "revoke", null, null)
        }
    }

    /** Real failed SQLite save -> Settings -> recreation -> Retry; leaving the editor cannot hide it. */
    @Test fun saveFailureAndRetryRemainVisibleInSettingsAcrossRecreation() {
        runBlocking { database.useWriterConnection { it.execSQL("CREATE TRIGGER settings_save_failure BEFORE UPDATE ON notes BEGIN SELECT RAISE(ABORT, 'settings save test'); END") } }
        selectNote()
        val editor = compose.onNodeWithTag("noteEditor")
        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(body.length, body.length, false) }
        editor.performTextInput(" Kept in Settings.")
        val message = "Edits to “Transfer fixture” were not saved. Retry before leaving."
        compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText(message).assertIsDisplayed()
        scenario?.recreate()
        compose.onNodeWithText(message).assertIsDisplayed()
        runBlocking { database.useWriterConnection { it.execSQL("DROP TRIGGER settings_save_failure") } }
        compose.onNodeWithText("Try again").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { runBlocking { database.noteDao().getById(id)?.body == body + " Kept in Settings." } }
        compose.onNodeWithText(message).assertDoesNotExist()
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

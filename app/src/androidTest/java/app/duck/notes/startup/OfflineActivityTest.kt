package app.duck.notes.startup

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.R
import app.duck.notes.presentation.MainActivity
import app.duck.notes.presentation.shell.DuckShellViewModel
import app.duck.notes.presentation.shell.ShellFilter
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Application, Activity, retained ViewModel, repositories and on-disk Room. */
@RunWith(AndroidJUnit4::class)
class OfflineActivityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val graph get() = EntryPointAccessors.fromApplication(rule.activity.application, WelcomeStartupEntryPoint::class.java)
    private val vm get() = ViewModelProvider(rule.activity)[DuckShellViewModel::class.java]
    private fun label(id: Int) = rule.activity.getString(id)
    private fun awaitSelected(id: String) = rule.waitUntil(10_000) { vm.state.value.selectedNoteId == id }
    private fun flush() = runBlocking { withContext(Dispatchers.Main) { vm.flush() } }
    private fun replaceThroughSemantics(text: String): Boolean {
        val action = rule.onNodeWithTag("noteEditor").fetchSemanticsNode().config
            .getOrNull(SemanticsActions.SetText)?.action
        return rule.runOnIdle { action?.invoke(AnnotatedString(text)) ?: false }
    }
    private fun noteAction(label: String) {
        rule.onNodeWithText(this.label(R.string.note_actions)).performClick()
        rule.onNode(hasText(label) and hasAnyAncestor(isPopup())).performClick()
    }

    /**
     * Plants NEW_NOTE_ACTION_MISSING: disconnect onNewNote;
     * TRASH_SEMANTICS_WRITABLE: set MarkdownEditorScreen's BasicTextField readOnly to false.
     */
    @Test fun realActivityCreatesEditsSearchesArchivesTrashesAndRestoresPersistedNote() {
        val scenario = InstrumentationRegistry.getArguments().getString("offlineScenario", "create")
        require(scenario in setOf("create", "empty", "restart"))
        rule.waitUntil(10_000) { graph.startup().state.value != WelcomeStartupState.Loading }
        if (scenario == "restart") {
            val persisted = runBlocking { graph.database().noteDao().search("offlineuniquesentinel").single() }
            assertEquals(BODY, persisted.body)
            assertTrue(persisted.isArchived)
            assertFalse(persisted.isTrashed)
            val storage = app.duck.notes.data.repository.AttachmentStorageImpl(rule.activity)
            rule.waitUntil(10_000) { runBlocking { !storage.exists(CLEANUP_KEY) } }
            assertFalse(runBlocking { storage.pendingDeletions().contains(CLEANUP_KEY) })
            rule.runOnIdle { vm.onSelectNote(persisted.id) }
            awaitSelected(persisted.id)
            rule.onNodeWithTag("noteEditor").assertTextEquals(BODY)
            return
        }
        if (scenario == "empty") {
            runBlocking {
                val notes = app.duck.notes.data.repository.NoteRepositoryImpl(graph.database().noteDao())
                val trash = app.duck.notes.domain.usecase.DeleteNote(notes,
                    app.duck.notes.domain.usecase.ParseTags(), app.duck.notes.domain.usecase.ResolveWikiLinks(),
                    app.duck.notes.domain.Clock { 100L })
                graph.database().noteDao().observeNotes().first().forEach { trash(it.id) }
            }
            rule.waitUntil(10_000) { vm.state.value.notes.isEmpty() }
            rule.onNodeWithContentDescription(label(R.string.note_new)).assertIsDisplayed()
        }
        val before = runBlocking { graph.database().noteDao().count() }
        rule.onNodeWithContentDescription(label(R.string.note_new)).assertHasClickAction().performClick()
        try { rule.waitUntil(10_000) { vm.state.value.selectedNoteId != null } }
        catch (_: ComposeTimeoutException) { fail("New note must persist and select a note") }
        val id = requireNotNull(vm.state.value.selectedNoteId)
        assertEquals(id, java.util.UUID.fromString(id).toString())
        val created = runBlocking { graph.database().noteDao().getById(id) }
        assertEquals("", created?.body)
        assertEquals("Untitled", created?.title)
        assertEquals(before + 1, runBlocking { graph.database().noteDao().count() })
        rule.onNodeWithTag("noteEditor").performTextReplacement(BODY)
        flush()
        assertEquals(BODY, runBlocking { graph.database().noteDao().getById(id)?.body })
        noteAction(label(R.string.note_archive))
        rule.waitUntil(10_000) { vm.state.value.selectedNoteArchived }
        rule.runOnIdle { vm.onFilter(ShellFilter.All) }
        rule.waitUntil(10_000) { vm.state.value.selectedNoteId == null }
        rule.onNodeWithText(label(R.string.search_notes)).performTextInput("offlineuniquesent")
        rule.waitUntil(10_000) { vm.state.value.search.notes.any { it.id == id } }
        rule.onNodeWithText("offlineuniquesentinel").performClick()
        awaitSelected(id)
        noteAction(label(R.string.note_trash))
        rule.waitUntil(10_000) { vm.state.value.selectedNoteTrashed }
        rule.onNodeWithTag("noteEditor").assertIsDisplayed()
        rule.runOnIdle { assertFalse(requireNotNull(vm.editorSession).canEdit) }
        assertFalse("Trash must refuse the real editor SetText action", replaceThroughSemantics("Rejected trash edit"))
        rule.onNodeWithTag("noteEditor").assertTextEquals(BODY)
        flush()
        assertEquals(BODY, runBlocking { graph.database().noteDao().getById(id)?.body })
        noteAction(label(R.string.note_restore))
        rule.waitUntil(10_000) { !vm.state.value.selectedNoteTrashed }
        assertTrue(vm.state.value.selectedNoteArchived)
        assertTrue("Restored editor must accept the same SetText action", replaceThroughSemantics("Restored editing control"))
        flush()
        assertEquals("Restored editing control", runBlocking { graph.database().noteDao().getById(id)?.body })
        assertTrue(replaceThroughSemantics(BODY))
        flush()
        assertEquals(BODY, runBlocking { graph.database().noteDao().getById(id)?.body })
        pickerOwnerSurvivesActivityRecreation(id)
        val storage = app.duck.notes.data.repository.AttachmentStorageImpl(rule.activity)
        val orphan = requireNotNull(app.duck.notes.core.files.AttachmentFiles(rule.activity.filesDir).resolve(CLEANUP_KEY))
        orphan.writeText("SYNTHETIC_DELETION_RECOVERY")
        runBlocking { storage.markForDeletion(CLEANUP_KEY) }
        assertTrue(orphan.isFile)
    }

    /** Plant PICKER_DISPOSE_OWNER: clear the pending picker owner when its editor detaches. */
    private fun pickerOwnerSurvivesActivityRecreation(id: String) {
        val retained = vm
        val origin = requireNotNull(retained.editorSession)
        val source = java.io.File.createTempFile("picker-recreation-", ".png", rule.activity.cacheDir)
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        try {
            rule.runOnIdle { assertTrue(retained.onImagePickerOpened(origin)) }
            rule.activityRule.scenario.recreate()
            rule.waitForIdle()
            assertSame("the actual Activity must retain the picker owner", retained, vm)
            assertSame(origin, vm.editorSession)
            rule.runOnIdle { vm.onImagePickerResult(android.net.Uri.fromFile(source).toString(), "image/png") }
            rule.waitUntil(10_000) { vm.state.value.selectedNoteAttachments.size == 1 }
            val attachment = vm.state.value.selectedNoteAttachments.single()
            assertEquals(id, attachment.noteId)
            assertTrue(runBlocking { graph.database().noteDao().getById(id)?.body.orEmpty().contains(attachment.id) })
            rule.runOnIdle { vm.onDeleteAttachment(origin, attachment.id) }
            rule.waitUntil(10_000) { vm.state.value.selectedNoteAttachments.isEmpty() }
            flush()
            assertEquals(BODY, runBlocking { graph.database().noteDao().getById(id)?.body })
        } finally { source.delete() }
    }
    private companion object {
        const val BODY = "offlineuniquesentinel\nTyped in the real Activity. #offline/check"
        const val CLEANUP_KEY = "attachments/00000000-0000-0000-0000-000000000099"
    }
}


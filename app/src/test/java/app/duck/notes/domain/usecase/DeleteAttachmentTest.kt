package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeAttachmentRepository
import app.duck.notes.domain.fake.FakeAttachmentStorage
import app.duck.notes.domain.fake.FakeNoteRepository
import app.duck.notes.domain.fake.FixedClock
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.model.Note
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delete removes all three — file, row, and body reference — so no half is left orphaned.
 *
 * A row without its file is a broken thumbnail; a file without its row is a leak the strip cannot
 * reach to clean; a reference without its row is a dangling `attachment:<id>` in exported markdown.
 * Each axis is asserted from a real attach, so the preconditions are the use case's own output rather
 * than hand-built rows.
 */
class DeleteAttachmentTest {

    private val notes = FakeNoteRepository()
    private val attachments = FakeAttachmentRepository()
    private val storage = FakeAttachmentStorage()
    private val clock = FixedClock(7_000L)
    private val parseTags = ParseTags()
    private val save = SaveNote(notes, DeriveTitle(), parseTags, ResolveWikiLinks(), clock)
    private val attach = AttachImage(storage, attachments, notes, save, clock)
    private val delete = DeleteAttachment(storage, attachments, notes, save)

    private suspend fun seed(id: String, body: String) =
        notes.seed(Note(id = id, title = "", body = body, createdAt = 0, modifiedAt = 0))

    private fun bodyOf(id: String) = notes.current(id)!!.body

    @Test
    fun `removes the file, the row, and the reference`() = runTest {
        seed("n1", "note")
        val a = attach("n1", "content://pick/1", "image/png")!!
        // Preconditions, from the attach itself — all three present.
        assertTrue("file present before delete", storage.exists(a.uri))
        assertEquals(setOf(a.id), MarkdownScanner.attachmentIdsIn(bodyOf("n1")))

        delete(a.id)

        assertFalse("file orphan left behind", storage.exists(a.uri))
        assertTrue("row orphan left behind", attachments.all().isEmpty())
        assertEquals(
            "reference orphan left behind",
            emptySet<String>(),
            MarkdownScanner.attachmentIdsIn(bodyOf("n1")),
        )
        assertEquals("the whole appended line goes, no blank left", "note", bodyOf("n1"))
    }

    /** Idempotent like `DeleteNote`: a double-tap on delete cannot throw. */
    @Test
    fun `a missing id is a no-op`() = runTest {
        seed("n1", "note")
        delete("nope")
        assertEquals("note", bodyOf("n1"))
        assertTrue(attachments.all().isEmpty())
    }
}

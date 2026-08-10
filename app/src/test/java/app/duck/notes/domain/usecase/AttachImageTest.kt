package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeAttachmentRepository
import app.duck.notes.domain.fake.FakeAttachmentStorage
import app.duck.notes.domain.fake.FakeNoteRepository
import app.duck.notes.domain.fake.FixedClock
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.model.Note
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strip-vs-body agreement, and durability at the JVM boundary.
 *
 * **Agreement** is the load-bearing property: after an attach, the note holds exactly one attachment
 * row *and* the body holds exactly one `attachment:<id>` reference to the same id. The strip renders
 * the rows, export reads the references, and if they name different ids one of the two lies. Both
 * halves come from `AttachImage`, so this is where they are pinned together.
 *
 * **Durability at this layer** means the recorded uri is `storage.store()`'s durable return, never the
 * picker's source — a `FakeAttachmentStorage` can prove the distinction but not the real byte-copy,
 * which is `AttachmentDurabilityTest`'s job on device.
 */
class AttachImageTest {

    private val notes = FakeNoteRepository()
    private val attachments = FakeAttachmentRepository()
    private val storage = FakeAttachmentStorage()
    private val clock = FixedClock(7_000L)
    private val parseTags = ParseTags()
    private val attach = AttachImage(
        storage = storage,
        attachments = attachments,
        notes = notes,
        saveNote = SaveNote(notes, DeriveTitle(), parseTags, ResolveWikiLinks(), clock),
        clock = clock,
    )

    private suspend fun seed(id: String, body: String) =
        notes.seed(Note(id = id, title = "", body = body, createdAt = 0, modifiedAt = 0))

    private fun bodyOf(id: String) = notes.current(id)!!.body

    @Test
    fun `the row and the body reference agree on the id`() = runTest {
        seed("n1", "buy milk")
        val result = attach("n1", "content://pick/9", "image/png")!!

        assertEquals("exactly one row", listOf(result.id), attachments.all().map { it.id })
        assertEquals(
            "exactly one reference, to the same id",
            setOf(result.id),
            MarkdownScanner.attachmentIdsIn(bodyOf("n1")),
        )
    }

    @Test
    fun `the reference is appended on its own line`() = runTest {
        seed("n1", "buy milk")
        val result = attach("n1", "content://pick/9", "image/png")!!
        assertEquals("buy milk\n" + MarkdownScanner.imageRef(result.id), bodyOf("n1"))
    }

    @Test
    fun `the recorded uri is the durable copy, not the source`() = runTest {
        seed("n1", "note")
        val result = attach("n1", "content://pick/9", "image/png")!!
        assertTrue("durable, not the source uri", result.uri.startsWith(FakeAttachmentStorage.DURABLE))
        assertEquals("the source was read", "content://pick/9", storage.stored.single().first)
    }

    @Test
    fun `the row carries the note id and mime`() = runTest {
        seed("n1", "note")
        val result = attach("n1", "content://pick/9", "image/jpeg")!!
        assertEquals("n1", result.noteId)
        assertEquals("image/jpeg", result.mime)
        assertEquals("stamped by the clock", 7_000L, result.createdAt)
    }

    @Test
    fun `an unreadable source records nothing`() = runTest {
        seed("n1", "note")
        storage.storeSucceeds = false
        assertNull(attach("n1", "content://pick/9", "image/png"))
        assertTrue("no row", attachments.all().isEmpty())
        assertEquals("body untouched", "note", bodyOf("n1"))
    }

    /** Note-first: a dead id must not leak a stored file, so storage is never even reached. */
    @Test
    fun `an unknown note attaches nothing and never touches storage`() = runTest {
        assertNull(attach("nope", "content://pick/9", "image/png"))
        assertTrue(attachments.all().isEmpty())
        assertTrue("storage untouched for a dead note", storage.stored.isEmpty())
    }
}

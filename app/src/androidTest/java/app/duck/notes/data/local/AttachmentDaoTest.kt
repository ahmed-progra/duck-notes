package app.duck.notes.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.local.dao.AttachmentDao
import app.duck.notes.data.local.dao.NoteDao
import app.duck.notes.data.local.entity.AttachmentEntity
import app.duck.notes.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `AttachmentDao` against real SQLite: ordering, per-note scoping, and the FK cascade.
 *
 * The cascade is the one behaviour a JVM fake cannot model — it is the database's, declared on
 * `AttachmentEntity`'s `ForeignKey(onDelete = CASCADE)` — so it is asserted here rather than in
 * `DeleteAttachmentTest`. Attachments are inserted against a **real note row** because that FK
 * requires a parent; an orphan insert would fail the constraint, which `insertRequiresAParentNote`
 * pins as the positive statement of the same rule.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentDaoTest {

    private lateinit var db: DuckDatabase
    private lateinit var notes: NoteDao
    private lateinit var attachments: AttachmentDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DuckDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .addCallback(DuckDatabaseCallback(seedSampleNotes = false))
            .build()
        notes = db.noteDao()
        attachments = db.attachmentDao()
    }

    @After
    fun tearDown() = db.close()

    private fun note(id: String) =
        NoteEntity(id = id, title = "", body = "", createdAt = 0L, modifiedAt = 0L)

    private fun att(id: String, noteId: String, createdAt: Long) =
        AttachmentEntity(id = id, noteId = noteId, uri = "/files/attachments/$id", mime = "image/png", createdAt = createdAt)

    @Test
    fun observeForNote_returnsRowsOldestFirst() = runBlocking {
        notes.insert(note("n1"))
        // Inserted newest-first to prove the ORDER BY, not the insert order, decides.
        attachments.insert(att("a2", "n1", createdAt = 200L))
        attachments.insert(att("a1", "n1", createdAt = 100L))

        val ids = attachments.observeForNote("n1").first().map { it.id }
        assertEquals(listOf("a1", "a2"), ids)
    }

    @Test
    fun observeForNote_isScopedToTheNote() = runBlocking {
        notes.insert(note("n1"))
        notes.insert(note("n2"))
        attachments.insert(att("a1", "n1", createdAt = 0L))
        attachments.insert(att("b1", "n2", createdAt = 0L))

        assertEquals(listOf("a1"), attachments.forNote("n1").map { it.id })
        assertEquals(listOf("b1"), attachments.forNote("n2").map { it.id })
    }

    @Test
    fun getById_andDelete() = runBlocking {
        notes.insert(note("n1"))
        attachments.insert(att("a1", "n1", createdAt = 0L))

        assertEquals("n1", attachments.getById("a1")?.noteId)
        attachments.deleteById("a1")
        assertNull("row must be gone after delete", attachments.getById("a1"))
    }

    @Test
    fun deletingTheNoteCascadesItsAttachmentsAway() = runBlocking {
        notes.insert(note("n1"))
        attachments.insert(att("a1", "n1", createdAt = 0L))
        assertTrue(attachments.forNote("n1").isNotEmpty())

        // delete matches by rowid; re-read the stored row to get its assigned rowid
        val stored = notes.observeNotes().first().single { it.id == "n1" }
        notes.delete(stored)

        assertTrue("the FK cascade must remove the attachment", attachments.forNote("n1").isEmpty())
    }

    /**
     * The FK's positive control: an attachment with no parent note cannot be stored. Without this the
     * cascade test above could pass over a table that simply never enforced the constraint.
     */
    @Test
    fun insertRequiresAParentNote() = runBlocking {
        val threw = runCatching { attachments.insert(att("a1", "ghost", createdAt = 0L)) }.isFailure
        assertTrue("inserting against a missing note must violate the foreign key", threw)
    }
}

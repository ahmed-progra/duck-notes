package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeNoteRepository
import app.duck.notes.domain.fake.FixedClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteNoteTest {

    private val notes = FakeNoteRepository()
    private val clock = FixedClock(now = 1_000L)
    private val saveNote = SaveNote(notes, DeriveTitle(), ParseTags(), ResolveWikiLinks(), clock)
    private val deleteNote = DeleteNote(notes, ParseTags(), ResolveWikiLinks(), clock)

    @Test
    fun trashesTheNoteAndStampsTrashedAt() = runTest {
        val note = saveNote(id = null, body = "doomed")
        clock.now = 9_000L

        deleteNote(note.id)

        val trashed = requireNotNull(notes.current(note.id))
        assertTrue(trashed.isTrashed)
        assertEquals(9_000L, trashed.trashedAt)
        assertEquals(9_000L, trashed.modifiedAt)
    }

    @Test
    fun keepsTheRowSoRestoreCanUndoIt() = runTest {
        val note = saveNote(id = null, body = "doomed")

        deleteNote(note.id)

        assertEquals("soft delete must not drop the row", note.id, notes.current(note.id)?.id)
    }

    @Test
    fun retrashingIsANoOpSoThePurgeClockKeepsRunning() = runTest {
        val note = saveNote(id = null, body = "doomed")
        clock.now = 9_000L
        deleteNote(note.id)

        clock.now = 50_000L
        deleteNote(note.id)

        assertEquals(9_000L, notes.current(note.id)?.trashedAt)
    }

    @Test
    fun preservesTagsOfTheTrashedNote() = runTest {
        val note = saveNote(id = null, body = "doomed #work")

        deleteNote(note.id)

        assertEquals(listOf("work"), notes.savedTags[note.id])
    }

    /**
     * The mirror of [preservesTagsOfTheTrashedNote], and the same call for the same reason.
     *
     * `NoteDao.saveNoteWithTags` **clears** a note's link rows before re-inserting, so trashing has
     * to hand it the body's links or it deletes them. It must not: a trashed note still holds
     * `[[Pond]]` in its body, so its rows stay correct and `RestoreNote` has nothing to rebuild.
     * Hiding them from other notes' panels is the *query's* job — `NoteLinkDao.observeBacklinks`
     * filters on the source's `is_trashed` — which is exactly the split `NoteDao.notesByTagPathOnce`
     * already argued for tags: the filter hides, the rewrite must not.
     */
    @Test
    fun preservesLinksOfTheTrashedNote() = runTest {
        val note = saveNote(id = null, body = "doomed, but still points at [[Pond]]")

        deleteNote(note.id)

        assertEquals(
            "trashing a note must not delete its links — the body still contains them",
            listOf("Pond"),
            notes.savedLinks[note.id],
        )
    }

    @Test
    fun unknownIdIsANoOp() = runTest {
        deleteNote("nope")

        assertNull(notes.current("nope"))
    }
}

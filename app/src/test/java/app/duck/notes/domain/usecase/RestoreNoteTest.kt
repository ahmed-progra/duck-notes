package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeNoteRepository
import app.duck.notes.domain.fake.FixedClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RestoreNoteTest {

    private val notes = FakeNoteRepository()
    private val clock = FixedClock(now = 1_000L)
    private val saveNote = SaveNote(notes, DeriveTitle(), ParseTags(), clock)
    private val deleteNote = DeleteNote(notes, ParseTags(), clock)
    private val restoreNote = RestoreNote(notes, ParseTags(), clock)

    @Test
    fun clearsTheTrashFlagAndTimestamp() = runTest {
        val note = saveNote(id = null, body = "back from the dead")
        deleteNote(note.id)
        clock.now = 7_000L

        restoreNote(note.id)

        val restored = requireNotNull(notes.current(note.id))
        assertFalse(restored.isTrashed)
        assertNull(restored.trashedAt)
        assertEquals(7_000L, restored.modifiedAt)
    }

    @Test
    fun restoringAnActiveNoteIsANoOp() = runTest {
        val note = saveNote(id = null, body = "never trashed")
        clock.now = 7_000L

        restoreNote(note.id)

        assertEquals("modifiedAt must not move", 1_000L, notes.current(note.id)?.modifiedAt)
    }

    @Test
    fun unknownIdIsANoOp() = runTest {
        restoreNote("nope")

        assertNull(notes.current("nope"))
    }
}

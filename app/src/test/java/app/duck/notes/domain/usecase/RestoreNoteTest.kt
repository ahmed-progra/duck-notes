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
    private val saveNote = SaveNote(notes, DeriveTitle(), ParseTags(), ResolveWikiLinks(), clock)
    private val deleteNote = DeleteNote(notes, ParseTags(), ResolveWikiLinks(), clock)
    private val restoreNote = RestoreNote(notes, ParseTags(), ResolveWikiLinks(), clock)

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

    /**
     * A restored note comes back with its links intact — because trashing never removed them.
     *
     * Restore rewrites the same rows from the same body rather than rebuilding anything, which is the
     * whole benefit of `DeleteNote` not deleting them: a rebuild would have to re-resolve every title
     * against a note set that may have moved on, silently dropping links whose targets were renamed
     * while this note sat in the bin.
     */
    @Test
    fun aRestoredNoteKeepsItsLinks() = runTest {
        val note = saveNote(id = null, body = "points at [[Pond]]")
        deleteNote(note.id)

        restoreNote(note.id)

        assertEquals(listOf("Pond"), notes.savedLinks[note.id])
    }
}

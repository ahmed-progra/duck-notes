package app.duck.notes.presentation.shell

import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.NoteSort
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteOrderingTest {
    private val notes = listOf(
        Note("z", "Zebra", "body", 3, 1),
        Note("p", "Pinned", "body", 1, 2, isPinned = true),
        Note("a", "Apple", "body", 2, 3),
    )
    /** Plant: remove the pinned comparator; each ordinary order must stop leading with p. */
    @Test fun pinsLeadEveryOrderAndMetadataIsUntouched() {
        assertEquals(listOf("p", "a", "z"), orderedNotes(notes, NoteSort.Modified, false).map { it.id })
        assertEquals(listOf("p", "z", "a"), orderedNotes(notes, NoteSort.Created, false).map { it.id })
        assertEquals(listOf("p", "a", "z"), orderedNotes(notes, NoteSort.Title, false, Locale.US).map { it.id })
        assertEquals(2L, notes[1].modifiedAt)
    }
    @Test fun tiesAreStableAndTrashIgnoresPinning() {
        val ties = listOf(notes[0].copy(id = "b"), notes[0].copy(id = "a"))
        assertEquals(listOf("a", "b"), orderedNotes(ties, NoteSort.Modified, false).map { it.id })
        val trash = listOf(notes[1].copy(isTrashed = true, trashedAt = 1), notes[0].copy(isTrashed = true, trashedAt = 2))
        assertEquals(listOf("z", "p"), orderedNotes(trash, NoteSort.Title, true).map { it.id })
    }
}

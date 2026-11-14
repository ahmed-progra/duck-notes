package app.duck.notes.presentation.shell

import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.NoteSort
import java.util.Locale
import java.text.Collator

internal fun orderedNotes(notes: List<Note>, order: NoteSort, trash: Boolean, locale: Locale = Locale.getDefault()): List<Note> {
    if (trash) return notes.sortedWith(compareByDescending<Note> { it.trashedAt ?: it.modifiedAt }.thenBy { it.id })
    val withinPins = when (order) {
        NoteSort.Modified -> compareByDescending<Note> { it.modifiedAt }
        NoteSort.Created -> compareByDescending<Note> { it.createdAt }
        NoteSort.Title -> {
            val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
            Comparator<Note> { a, b -> collator.compare(a.title, b.title) }
        }
    }
    return notes.sortedWith(compareByDescending<Note> { it.isPinned }.then(withinPins).thenBy { it.id })
}

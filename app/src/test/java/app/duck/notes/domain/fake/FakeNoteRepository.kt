package app.duck.notes.domain.fake

import app.duck.notes.domain.model.Note
import app.duck.notes.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [NoteRepository]. Mirrors the real one's observable contract — upsert by id, trashed
 * notes hidden, newest first — so use-case tests exercise the same behaviour the DAO provides.
 *
 * Tag membership is keyed by tag *name* rather than id: the fake has no id-generating tag table, and
 * use cases only ever pass an opaque string through.
 */
class FakeNoteRepository : NoteRepository {

    private val notes = MutableStateFlow<List<Note>>(emptyList())

    /** Tag names recorded by the most recent [save] of each note — what the tests assert against. */
    val savedTags = mutableMapOf<String, List<String>>()

    suspend fun seed(note: Note, tags: List<String> = emptyList()) = save(note, tags)

    fun current(id: String) = notes.value.find { it.id == id }

    override fun observeNotes(tagId: String?): Flow<List<Note>> = notes.map { all ->
        all.filterNot { it.isTrashed }
            .filter { tagId == null || tagId in savedTags[it.id].orEmpty() }
            .sortedByDescending { it.modifiedAt }
    }

    override fun observeNote(id: String): Flow<Note?> = notes.map { all -> all.find { it.id == id } }

    override suspend fun getNote(id: String): Note? = notes.value.find { it.id == id }

    override suspend fun save(note: Note, tagNames: List<String>) {
        savedTags[note.id] = tagNames
        notes.value = notes.value.filterNot { it.id == note.id } + note
    }
}

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

    /**
     * Every [Note] [save] was called with, in order, including seeds.
     *
     * Phase 8a asserts on write *counts*, not just final state: "opening a note writes nothing" and
     * "N keystrokes inside the idle window produce one save" are both invisible to a final-state
     * check, because the end result is identical either way.
     *
     * The whole `Note` rather than `(id, body)`, because **two different writers reach this method
     * and only one of them restamps**. `SaveNote` sets `modifiedAt` to the clock; a tag rewrite
     * copies the note and changes only the body. A test that counts writes alone cannot tell them
     * apart, and reads as though it proved something about `modifiedAt` when it did not.
     */
    val writes = mutableListOf<Note>()

    /**
     * The `(id, tagNames)` of every [save], in order — the tag half of [writes].
     *
     * [savedTags] keeps only the most recent list per note, which cannot answer the question the
     * in-progress-tag suppression exists for: *was a partial ever minted on the way past?* A partial
     * is orphaned and reaped by the very next save, so final state is clean either way. Same argument
     * as [writes] itself.
     */
    val writtenTags = mutableListOf<Pair<String, List<String>>>()

    suspend fun seed(note: Note, tags: List<String> = emptyList()) = save(note, tags)

    fun current(id: String) = notes.value.find { it.id == id }

    /** Every note, for [FakeTagRepository]'s optional body rewriting. */
    fun all(): List<Note> = notes.value

    /**
     * Removes the row outright, so [observeNote] emits null.
     *
     * Not the same as trashing: `DeleteNote` is a soft delete and the note stays observable. This is
     * the purge case — Phase 14's 30-day sweep, or a sync deletion arriving — which is what makes
     * `observeNote`'s null emission reachable at all.
     */
    fun hardDelete(id: String) {
        notes.value = notes.value.filterNot { it.id == id }
        savedTags -= id
    }

    override fun observeNotes(): Flow<List<Note>> = notes.map { all ->
        all.filterNot { it.isTrashed }.sortedByDescending { it.modifiedAt }
    }

    /**
     * Mirrors `NoteDao.notesByTagPath`: the exact tag **or any descendant of it**, so `projects`
     * finds a note tagged only `projects/duck/ui`.
     *
     * The `/` matters — without it `project` would match `projects/duck`, which is the bug the real
     * query's `|| '/'` exists to prevent.
     */
    override fun observeNotesByTagPath(path: String): Flow<List<Note>> = notes.map { all ->
        all.filterNot { it.isTrashed }
            .filter { note ->
                savedTags[note.id].orEmpty().any { it == path || it.startsWith("$path/") }
            }
            .sortedByDescending { it.modifiedAt }
    }

    override fun observeTrashed(): Flow<List<Note>> = notes.map { all ->
        all.filter { it.isTrashed }.sortedByDescending { it.trashedAt ?: 0L }
    }

    override fun observeUntagged(): Flow<List<Note>> = notes.map { all ->
        all.filterNot { it.isTrashed }
            .filter { savedTags[it.id].isNullOrEmpty() }
            .sortedByDescending { it.modifiedAt }
    }

    override fun observeNote(id: String): Flow<Note?> = notes.map { all -> all.find { it.id == id } }

    override suspend fun getNote(id: String): Note? = notes.value.find { it.id == id }

    override suspend fun save(note: Note, tagNames: List<String>) {
        savedTags[note.id] = tagNames
        writes += note
        writtenTags += note.id to tagNames
        notes.value = notes.value.filterNot { it.id == note.id } + note
    }
}

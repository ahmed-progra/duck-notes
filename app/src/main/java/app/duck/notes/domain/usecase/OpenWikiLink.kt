package app.duck.notes.domain.usecase

import app.duck.notes.domain.repository.NoteRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Resolve first; target creation resolves stored references without rewriting their sources. */
@Singleton
class OpenWikiLink @Inject constructor(
    private val notes: NoteRepository,
    private val saveNote: SaveNote,
) {
    private val creationLock = Mutex()

    suspend operator fun invoke(title: String): String = creationLock.withLock {
        val literal = title.trim()
        require(literal.isNotEmpty())
        notes.noteIdByTitle(literal)?.let { return@withLock it }
        saveNote(id = null, body = literal).id
    }
}

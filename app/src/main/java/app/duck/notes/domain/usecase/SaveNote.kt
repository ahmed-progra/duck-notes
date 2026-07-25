package app.duck.notes.domain.usecase

import app.duck.notes.domain.Clock
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.repository.NoteRepository
import java.util.UUID
import javax.inject.Inject

/**
 * Creates or updates a note from raw markdown — the only way a body reaches storage.
 *
 * Title and tags are always re-derived here rather than passed in, so they cannot drift from the
 * text. Pass a null [id] to create; pass an existing one to update, which preserves `createdAt` and
 * every user flag and restamps `modifiedAt`.
 */
class SaveNote @Inject constructor(
    private val notes: NoteRepository,
    private val deriveTitle: DeriveTitle,
    private val parseTags: ParseTags,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: String?, body: String): Note {
        val now = clock.nowMillis()
        val existing = id?.let { notes.getNote(it) }
        val note = existing?.copy(
            title = deriveTitle(body),
            body = body,
            modifiedAt = now,
        ) ?: Note(
            id = id ?: UUID.randomUUID().toString(),
            title = deriveTitle(body),
            body = body,
            createdAt = now,
            modifiedAt = now,
        )
        notes.save(note, parseTags(body))
        return note
    }
}

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
    private val resolveWikiLinks: ResolveWikiLinks,
    private val clock: Clock,
) {
    /**
     * @param caret suppresses the tag being typed at that offset — see [ParseTags]. Null parses the
     *   whole body, which is what every caller outside the editor's autosave wants.
     * @param restamp false keeps the existing `modifiedAt`. **Only for a write that carries no new
     *   edit**: the editor's autosave suppresses an in-progress tag, then owes one more write to
     *   mint it once the caret moves on, and those keystrokes were already stamped by the write that
     *   suppressed them. Stamping again would age the note by however long the caret sat parked. The
     *   same rule Phase 7 applies when a rename declines to restamp. Ignored when creating.
     */
    suspend operator fun invoke(
        id: String?,
        body: String,
        caret: Int? = null,
        restamp: Boolean = true,
    ): Note {
        val now = clock.nowMillis()
        val existing = id?.let { notes.getNote(it) }
        val note = existing?.copy(
            title = deriveTitle(body),
            body = body,
            modifiedAt = if (restamp) now else existing.modifiedAt,
        ) ?: Note(
            id = id ?: UUID.randomUUID().toString(),
            title = deriveTitle(body),
            body = body,
            createdAt = now,
            modifiedAt = now,
        )
        // Links are re-derived from the body on every save, exactly as tags and the title are, so a
        // `note_links` row can never describe a body that no longer contains the link. No caret
        // suppression here: an in-progress `[[Pon` is not a link at all until its closing brackets
        // exist, so there is nothing half-written for `WikiLinkScanner` to mint prematurely.
        notes.save(note, parseTags(body, caret), resolveWikiLinks(body))
        return note
    }
}

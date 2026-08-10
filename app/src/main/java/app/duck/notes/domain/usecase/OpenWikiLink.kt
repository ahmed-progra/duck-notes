package app.duck.notes.domain.usecase

import app.duck.notes.domain.repository.NoteRepository
import javax.inject.Inject

/**
 * Resolves a tapped `[[title]]` to a note id, creating the note when nothing resolves — Phase 9d.
 *
 * ## Resolution first, and through the query the save path uses
 *
 * [NoteRepository.noteIdByTitle] is the same rule `NoteDao.saveNoteWithTagsAndLinks` links by: exact
 * match, trashed excluded, most recently modified wins. So a tap opens **exactly** the note the next
 * save would write a `note_links` row to — including the tie-break when two notes share a title,
 * which is therefore the *navigate* case rather than a second creation.
 *
 * ## Creating relinks the source, and that is the point rather than a nicety
 *
 * `note_links` rows are written when the **source** note saves, so creating the target alone would
 * leave the link painted, resolvable, and with no row — the new note's backlinks panel empty, its
 * one inbound link invisible. The second save fixes that by re-deriving the source's links now that
 * the target exists.
 *
 * **`restamp = false` on that second save.** It carries no new keystrokes — the body is the one just
 * flushed — and restamping would move the source note to the top of every list because the user
 * followed a link out of it. That parameter exists on [SaveNote] for exactly this shape.
 *
 * ## The new note's body is its title, and nothing else
 *
 * [DeriveTitle] on a bare title yields that title, so the link resolves on the source's very next
 * save. A `"# "` prefix would read better and is available legally via `MarkdownScanner.prefixFor`,
 * but a bare title needs no route at all and gets the same answer.
 *
 * ## Failure
 *
 * A failed create throws out of [SaveNote]'s single transaction, so nothing partial is left and this
 * returns nothing to navigate to — the tap appears to do nothing. **Stated rather than hidden:** that
 * is the silent-sink shape `AttachmentStorageImpl.store` and `AttachImage` are already filed under in
 * DEFERRED HARDENING, and the fix is the same `NotWritable` snackbar all three want.
 */
class OpenWikiLink @Inject constructor(
    private val notes: NoteRepository,
    private val saveNote: SaveNote,
) {
    /**
     * @param sourceId the note the link was tapped in, whose links are re-derived after a create.
     *   Null only when nothing is open, in which case there is nothing to relink.
     * @param sourceBody that note's current body — already flushed by the caller, so re-saving it
     *   writes the same text back and changes only the derived rows.
     * @return the id to navigate to.
     */
    suspend operator fun invoke(title: String, sourceId: String?, sourceBody: String): String {
        notes.noteIdByTitle(title)?.let { return it }

        val created = saveNote(id = null, body = title)
        // Now that the target exists, the source's `[[title]]` resolves — but only a save re-derives
        // its rows. Without this the backlink never appears until the user edits the source again.
        if (sourceId != null) saveNote(id = sourceId, body = sourceBody, restamp = false)
        return created.id
    }
}

package app.duck.notes.domain.usecase

import app.duck.notes.domain.markdown.TagScanner
import app.duck.notes.domain.repository.NoteRepository
import javax.inject.Inject

/** What [AddTagToNote] did. Three outcomes, because all three need different things said. */
enum class AddTagResult {
    Added,

    /** The note already carries it. A no-op, and not a failure worth reporting. */
    AlreadyPresent,

    /** The tag was appended and did not survive re-parsing — see [AddTagToNote]. */
    NotWritable,
}

/**
 * Appends a tag to a note's body, from the note list's long-press menu.
 *
 * **This writes into the user's prose from a control that looks like metadata**, so where it writes
 * and what it does about a tag already there are decided here rather than left to a caller.
 *
 * ## Where
 *
 * Its own line at the end of the body. The end, because it never disturbs prose the user is reading;
 * its own line, because a tag appended to a heading or a fenced line changes what that line means.
 * `trimEnd` touches only end-of-file whitespace — which markdown carries no meaning in — and is what
 * keeps repeated adds from accumulating ragged blank lines.
 *
 * ## Already present
 *
 * A no-op, matched on the **exact** name. Not a prefix: a note carrying `#projects/duck` can
 * legitimately gain `#projects`, and the subtree filter surfaces it under both either way.
 *
 * Without this the failure is quiet and ugly. `NoteDao.saveNoteWithTagsAndLinks` inserts cross-refs with
 * `OnConflictStrategy.IGNORE` and [ParseTags] calls `distinct()`, so **the row stays single while
 * the body reads `#work` twice** — a duplicate the database cannot see and the user cannot miss.
 * The menu also omits tags the note already has; this is the guard for the body moving underneath
 * that list.
 *
 * ## The silent one: an unclosed code fence
 *
 * A body ending inside an unclosed fence swallows the appended tag — [TagScanner] yields no span,
 * [ParseTags] returns nothing, and the menu item does *nothing at all* with no error. So the write
 * is **verified by re-parsing** before it is saved: the same round-trip trick as
 * [TagScanner.isValidName], one implementation checking its own output. Closing the fence on the
 * user's behalf would be inventing an edit, which is worse than saying so.
 */
class AddTagToNote @Inject constructor(
    private val notes: NoteRepository,
    private val parseTags: ParseTags,
    private val saveNote: SaveNote,
) {
    suspend operator fun invoke(noteId: String, name: String): AddTagResult {
        val note = notes.getNote(noteId) ?: return AddTagResult.NotWritable
        if (name in parseTags(note.body)) return AddTagResult.AlreadyPresent

        val trimmed = note.body.trimEnd()
        val token = TagScanner.token(name)
        val next = if (trimmed.isEmpty()) token else "$trimmed\n$token"

        if (name !in parseTags(next)) return AddTagResult.NotWritable

        saveNote(noteId, next)
        return AddTagResult.Added
    }
}

package app.duck.notes.domain.repository

import app.duck.notes.domain.model.BodySnapshot
import app.duck.notes.domain.model.Tag
import app.duck.notes.domain.model.TagRef
import kotlinx.coroutines.flow.Flow

/**
 * Tag reads, the user-authored bits, and the two operations that rewrite note bodies.
 *
 * There is still no `create` here, and that is unchanged from Phase 3: tags come into existence only
 * by being written in a note body. [rename] and [removeTag] are not exceptions to that — they are
 * body edits, and the `tags` rows follow from re-parsing, exactly as they do on an ordinary save.
 *
 * **[rename] and [removeTag] each run in one transaction and are all-or-nothing.** A rename that
 * renamed 140 of 200 notes would leave the user with two tags where they asked for one and no way to
 * tell which notes are in which half. Both return a [BodySnapshot] so the caller can offer undo.
 */
interface TagRepository {

    fun observeAll(): Flow<List<Tag>>

    /** Every (tag name, note id) pair over non-trashed notes. Feeds `ObserveTagCounts` only. */
    fun observeNoteRefs(): Flow<List<TagRef>>

    suspend fun setPinned(tagId: String, pinned: Boolean)

    /** Null clears the override, returning the row to `DuckIcon.forTag`'s keyword resolution. */
    suspend fun setIcon(tagId: String, iconKey: String?)

    /**
     * Renames [from] to [to] **and cascades to descendants**, rewriting every note body that carries
     * either — trashed notes included.
     *
     * Trashed notes are in scope deliberately. `observeNotes` hides them by contract, but a trashed
     * note still holds `#work` in its body, and skipping it means restoring that note months later
     * re-creates the old tag through `ensureTag` — the rename silently undoing itself, one note at a
     * time.
     *
     * If [to] already exists the two **merge**, which is what the machinery does naturally rather
     * than a case handled here: the rewritten bodies resolve to the existing row's id. Callers are
     * expected to have said so before calling.
     */
    suspend fun rename(from: String, to: String): BodySnapshot

    /**
     * Strips [name] from every note body carrying it — trashed included — and deletes the row.
     * **Descendants are untouched.**
     *
     * Rename cascades and this does not, and the asymmetry is forced rather than chosen: a child's
     * name contains its parent's, so `projects/duck` must follow `projects` through a rename or the
     * names break — while `projects/duck` remains a perfectly valid name after `projects` is deleted,
     * simply becoming an intermediate segment the tree already models.
     *
     * The row is deleted explicitly, not left to `deleteOrphanTags`, which spares pinned and
     * icon'd rows by design — so the case where a user is *most* likely to be deleting is exactly
     * the case where the row would otherwise survive its own deletion.
     */
    suspend fun removeTag(name: String): BodySnapshot

    /** Writes [snapshot]'s bodies back, restores pin/icon, and sweeps any orphaned created row. */
    suspend fun restore(snapshot: BodySnapshot)
}

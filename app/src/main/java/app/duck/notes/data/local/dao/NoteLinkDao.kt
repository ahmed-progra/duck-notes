package app.duck.notes.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One backlink, joined with the title of the note it comes from.
 *
 * A projection rather than `NoteLinkEntity`, because the panel renders a **title** and the row holds
 * only ids. Resolving those ids against `ShellUiState.notes` in the ViewModel instead would look
 * equivalent and is not: that list is already narrowed by the sidebar filter, so backlinks would
 * vanish whenever the user filtered by a tag.
 */
data class BacklinkRow(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "link_text") val linkText: String,
    @ColumnInfo(name = "source_title") val sourceTitle: String,
)

/**
 * `[[WikiLink]]` reads. Writes live on [NoteDao] instead, and deliberately: they have to ride the
 * same `@Transaction` as the note and its tags, and Room only lets a transaction call methods on the
 * DAO that declares it.
 */
@Dao
interface NoteLinkDao {

    /**
     * Notes linking *to* [noteId], newest first, **excluding sources that are in the trash**.
     *
     * ## The filter is on the source, and only on the source
     *
     * "Who links to me" must not list a note the user threw away. But the **target** side gets no
     * filter at all: `ShellFilter.Trash` can open a trashed note, and the right answer there is the
     * active notes still pointing at it.
     *
     * ## Trash changes no rows — it is hidden here, not deleted
     *
     * The rows themselves survive a trash and a restore untouched, which is the same call
     * `NoteDao.notesByTagPathOnce` already made for tags: *"The filter must hide trashed notes; the
     * rewrite must not."* A trashed note still holds `[[Pond]]` in its body, so its rows are still
     * derived correctly from it. Deleting them on trash would also force restore to re-resolve every
     * title against a note set that may have moved underneath, silently dropping links whose targets
     * were themselves renamed in the meantime.
     *
     * Reads hit the `target_id` index declared on the entity; the join is by `notes.id`, which is
     * uniquely indexed.
     */
    @Query(
        """
        SELECT l.source_id, l.target_id, l.link_text, n.title AS source_title
        FROM note_links AS l
        JOIN notes AS n ON n.id = l.source_id
        WHERE l.target_id = :noteId AND n.is_trashed = 0
        ORDER BY n.modified_at DESC
        """,
    )
    fun observeBacklinks(noteId: String): Flow<List<BacklinkRow>>
}

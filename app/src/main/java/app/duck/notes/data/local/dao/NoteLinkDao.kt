package app.duck.notes.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class BacklinkRow(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "link_text") val linkText: String,
    @ColumnInfo(name = "source_title") val sourceTitle: String,
)

@Dao
interface NoteLinkDao {
    /** Text-addressed links follow current titles; neither endpoint may be in Trash. */
    @Query(
        """
        SELECT l.source_id, t.id AS target_id, MIN(l.link_text) AS link_text, n.title AS source_title
        FROM note_links AS l
        JOIN notes AS n ON n.id = l.source_id
        JOIN notes AS t ON t.id = :noteId AND t.is_trashed = 0
        WHERE n.is_trashed = 0
          AND (l.link_text = t.title OR l.fallback_title = t.title)
          AND t.id = COALESCE(
              (SELECT id FROM notes WHERE title = l.link_text AND is_trashed = 0
               ORDER BY modified_at DESC, id ASC LIMIT 1),
              (SELECT id FROM notes WHERE title = l.fallback_title AND is_trashed = 0
               ORDER BY modified_at DESC, id ASC LIMIT 1)
          )
        GROUP BY l.source_id, t.id, n.title, n.modified_at
        ORDER BY n.modified_at DESC, l.source_id ASC
        """,
    )
    fun observeBacklinks(noteId: String): Flow<List<BacklinkRow>>
}

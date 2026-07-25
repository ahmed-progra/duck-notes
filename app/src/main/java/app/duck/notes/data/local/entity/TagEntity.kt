package app.duck.notes.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A tag. Derived from note bodies (parsing lands in Phase 3), but its user customization —
 * [isPinned], [iconKey] — is authored here and must survive losing its last note
 * (see orphan-cleanup rule in `TagDao`). [parentId] encodes the nested `/` tree (Phase 7).
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    @ColumnInfo(name = "icon_key") val iconKey: String? = null,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)

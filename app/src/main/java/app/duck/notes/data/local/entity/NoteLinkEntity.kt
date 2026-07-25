package app.duck.notes.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * A resolved `[[WikiLink]]` from one note to another. Table only this phase — population and
 * backlink queries arrive in Phase 9. Composite PK: a source links a given target once.
 */
@Entity(
    tableName = "note_links",
    primaryKeys = ["source_id", "target_id"],
    indices = [Index(value = ["target_id"])],
)
data class NoteLinkEntity(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "link_text") val linkText: String,
)

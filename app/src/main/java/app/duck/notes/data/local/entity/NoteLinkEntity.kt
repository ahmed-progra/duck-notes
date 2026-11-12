package app.duck.notes.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * A textual reference, including unresolved targets. Resolution follows current note titles.
 */
@Entity(
    tableName = "note_links",
    primaryKeys = ["source_id", "link_text"],
    foreignKeys = [ForeignKey(
        entity = NoteEntity::class,
        parentColumns = ["id"],
        childColumns = ["source_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class NoteLinkEntity(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "link_text") val linkText: String,
    @ColumnInfo(name = "fallback_title") val fallbackTitle: String,
)

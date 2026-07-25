package app.duck.notes.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A file attached to a note (image, etc.). Table only this phase — insert/render/upload land in
 * Phases 8/13. Cascades on note delete; [noteId] indexed for per-note lookups.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["note_id"])],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    val uri: String,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

package app.duck.notes.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.duck.notes.data.local.SyncState

/**
 * A note.
 *
 * [rowid] is an explicit INTEGER PRIMARY KEY alias so the FTS5 external-content mapping stays
 * stable across `VACUUM` (an implicit rowid can be renumbered, which would silently desync
 * `notes_fts`). [id] is the app-facing UUID, unique-indexed. FTS triggers key off `new.rowid`.
 */
@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["modified_at"]),
        Index(value = ["is_trashed"]),
        Index(value = ["title", "is_trashed", "modified_at", "id"]),
    ],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    val rowid: Long = 0,
    val id: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "is_trashed") val isTrashed: Boolean = false,
    @ColumnInfo(name = "is_locked") val isLocked: Boolean = false,
    @ColumnInfo(name = "is_encrypted") val isEncrypted: Boolean = false,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long? = null,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.PENDING,
    @ColumnInfo(name = "remote_rev") val remoteRev: Long? = null,
)

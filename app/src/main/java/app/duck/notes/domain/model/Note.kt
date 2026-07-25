package app.duck.notes.domain.model

/**
 * A note, as the rest of the app thinks about it.
 *
 * Deliberately narrower than `NoteEntity`: no `rowid` (a storage detail that keeps the FTS5
 * `content_rowid` mapping stable), no `syncState`/`remoteRev` (Phase 13). The mapper adds them back.
 */
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val isLocked: Boolean = false,
    val isEncrypted: Boolean = false,
    val trashedAt: Long? = null,
)

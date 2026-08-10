package app.duck.notes.data.mapper

import app.duck.notes.data.local.SyncState
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.domain.model.Note

/**
 * `NoteEntity` <-> `Note`. The domain model drops three storage-only columns, so the write
 * direction supplies them: `rowid` stays 0 because `saveNoteWithTagsAndLinks` resolves the real one, and
 * every local write is [SyncState.PENDING] — the outbox in Phase 13 is what clears it.
 */
fun NoteEntity.toDomain() = Note(
    id = id,
    title = title,
    body = body,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    isPinned = isPinned,
    isArchived = isArchived,
    isTrashed = isTrashed,
    isLocked = isLocked,
    isEncrypted = isEncrypted,
    trashedAt = trashedAt,
)

fun Note.toEntity() = NoteEntity(
    id = id,
    title = title,
    body = body,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    isPinned = isPinned,
    isArchived = isArchived,
    isTrashed = isTrashed,
    isLocked = isLocked,
    isEncrypted = isEncrypted,
    trashedAt = trashedAt,
    syncState = SyncState.PENDING,
)

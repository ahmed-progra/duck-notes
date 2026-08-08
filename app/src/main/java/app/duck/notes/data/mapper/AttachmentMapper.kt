package app.duck.notes.data.mapper

import app.duck.notes.data.local.entity.AttachmentEntity
import app.duck.notes.domain.model.Attachment

/** `AttachmentEntity` <-> `Attachment`. A straight field copy — unlike a note, an attachment has no
 * storage-only columns the domain drops. */
fun AttachmentEntity.toDomain() = Attachment(
    id = id,
    noteId = noteId,
    uri = uri,
    mime = mime,
    width = width,
    height = height,
    size = size,
    createdAt = createdAt,
)

fun Attachment.toEntity() = AttachmentEntity(
    id = id,
    noteId = noteId,
    uri = uri,
    mime = mime,
    width = width,
    height = height,
    size = size,
    createdAt = createdAt,
)

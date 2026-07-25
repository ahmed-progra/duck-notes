package app.duck.notes.data.mapper

import app.duck.notes.data.local.entity.NoteLinkEntity
import app.duck.notes.domain.model.NoteLink

fun NoteLinkEntity.toDomain() = NoteLink(
    sourceId = sourceId,
    targetId = targetId,
    linkText = linkText,
)

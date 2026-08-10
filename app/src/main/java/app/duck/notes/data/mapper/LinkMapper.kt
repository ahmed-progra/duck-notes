package app.duck.notes.data.mapper

import app.duck.notes.data.local.dao.BacklinkRow
import app.duck.notes.domain.model.NoteLink

fun BacklinkRow.toDomain() = NoteLink(
    sourceId = sourceId,
    targetId = targetId,
    linkText = linkText,
    sourceTitle = sourceTitle,
)

package app.duck.notes.data.mapper

import app.duck.notes.data.local.dao.TagNoteRefRow
import app.duck.notes.data.local.entity.TagEntity
import app.duck.notes.domain.model.Tag
import app.duck.notes.domain.model.TagRef

fun TagEntity.toDomain() = Tag(
    id = id,
    name = name,
    parentId = parentId,
    iconKey = iconKey,
    isPinned = isPinned,
    sortOrder = sortOrder,
)

fun TagNoteRefRow.toDomain() = TagRef(tagName = name, noteId = noteId)

fun Tag.toEntity() = TagEntity(
    id = id,
    name = name,
    parentId = parentId,
    iconKey = iconKey,
    isPinned = isPinned,
    sortOrder = sortOrder,
)

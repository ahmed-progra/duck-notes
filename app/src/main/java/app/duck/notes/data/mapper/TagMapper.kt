package app.duck.notes.data.mapper

import app.duck.notes.data.local.entity.TagEntity
import app.duck.notes.domain.model.Tag

fun TagEntity.toDomain() = Tag(
    id = id,
    name = name,
    parentId = parentId,
    iconKey = iconKey,
    isPinned = isPinned,
    sortOrder = sortOrder,
)

fun Tag.toEntity() = TagEntity(
    id = id,
    name = name,
    parentId = parentId,
    iconKey = iconKey,
    isPinned = isPinned,
    sortOrder = sortOrder,
)

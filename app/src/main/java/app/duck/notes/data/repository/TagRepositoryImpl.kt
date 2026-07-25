package app.duck.notes.data.repository

import app.duck.notes.data.local.dao.TagDao
import app.duck.notes.data.mapper.toDomain
import app.duck.notes.domain.model.Tag
import app.duck.notes.domain.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class TagRepositoryImpl @Inject constructor(
    private val tags: TagDao,
) : TagRepository {

    override fun observeAll(): Flow<List<Tag>> =
        tags.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** No-op for an unknown id rather than creating a tag — tags only come from note bodies. */
    override suspend fun setPinned(tagId: String, pinned: Boolean) {
        val tag = tags.getById(tagId) ?: return
        tags.upsert(tag.copy(isPinned = pinned))
    }
}

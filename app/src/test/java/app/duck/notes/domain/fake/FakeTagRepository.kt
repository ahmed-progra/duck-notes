package app.duck.notes.domain.fake

import app.duck.notes.domain.model.Tag
import app.duck.notes.domain.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeTagRepository(initial: List<Tag> = emptyList()) : TagRepository {

    private val tags = MutableStateFlow(initial)

    fun current() = tags.value

    override fun observeAll(): Flow<List<Tag>> = tags

    override suspend fun setPinned(tagId: String, pinned: Boolean) {
        tags.value = tags.value.map { if (it.id == tagId) it.copy(isPinned = pinned) else it }
    }
}

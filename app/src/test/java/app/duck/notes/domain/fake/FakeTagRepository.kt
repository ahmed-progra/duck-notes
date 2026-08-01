package app.duck.notes.domain.fake

import app.duck.notes.domain.model.BodySnapshot
import app.duck.notes.domain.model.Tag
import app.duck.notes.domain.model.TagRef
import app.duck.notes.domain.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [TagRepository].
 *
 * [observeAll], [observeNoteRefs], [setPinned] and [setIcon] behave for real, because the use cases
 * over them (`ObserveTagTree`, `ObserveTagCounts`, `PinTag`, `SetTagIcon`) are pure derivations and
 * a fake can model them exactly.
 *
 * [rename], [removeTag] and [restore] **record their calls and do nothing else**, deliberately. What
 * makes those correct is a Room transaction rewriting N bodies atomically, and a fake that pretended
 * to do that would be asserting against a reimplementation rather than against the thing that ships.
 * `RenameTagTest` and `DeleteTagTest` use these to pin what the use case decides — validation,
 * lowercasing, the no-op cases — and `DuckDatabaseTest` covers the rest on a real database.
 */
class FakeTagRepository(
    initial: List<Tag> = emptyList(),
    refs: List<TagRef> = emptyList(),
) : TagRepository {

    private val tags = MutableStateFlow(initial)
    private val noteRefs = MutableStateFlow(refs)

    /** Every (from, to) pair [rename] was called with, in order. Empty means it was never reached. */
    val renames = mutableListOf<Pair<String, String>>()

    val deletes = mutableListOf<String>()

    val restores = mutableListOf<BodySnapshot>()

    fun current() = tags.value

    override fun observeAll(): Flow<List<Tag>> = tags

    override fun observeNoteRefs(): Flow<List<TagRef>> = noteRefs

    override suspend fun setPinned(tagId: String, pinned: Boolean) {
        tags.value = tags.value.map { if (it.id == tagId) it.copy(isPinned = pinned) else it }
    }

    override suspend fun setIcon(tagId: String, iconKey: String?) {
        tags.value = tags.value.map { if (it.id == tagId) it.copy(iconKey = iconKey) else it }
    }

    override suspend fun rename(from: String, to: String): BodySnapshot {
        renames += from to to
        return BodySnapshot(emptyMap(), emptyList())
    }

    override suspend fun removeTag(name: String): BodySnapshot {
        deletes += name
        return BodySnapshot(emptyMap(), emptyList())
    }

    override suspend fun restore(snapshot: BodySnapshot) {
        restores += snapshot
    }
}

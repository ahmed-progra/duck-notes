package app.duck.notes.domain.usecase

import app.duck.notes.domain.repository.TagRepository
import javax.inject.Inject

/** Pins or unpins a tag. Pinned tags sort into their own sidebar section and survive orphaning. */
class PinTag @Inject constructor(
    private val tags: TagRepository,
) {
    suspend operator fun invoke(tagId: String, pinned: Boolean) = tags.setPinned(tagId, pinned)
}

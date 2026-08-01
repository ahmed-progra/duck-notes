package app.duck.notes.domain.usecase

import app.duck.notes.domain.repository.TagRepository
import javax.inject.Inject

/**
 * Sets a tag's icon override, or clears it with a null [iconKey].
 *
 * Clearing is not "no icon" — it returns the row to `DuckIcon.forTag`'s keyword resolution, which is
 * total and falls back to the generic glyph. That is why the picker's ninth cell is *Automatic*
 * rather than *None*.
 *
 * The column has existed since Phase 2 and nothing wrote it until now.
 */
class SetTagIcon @Inject constructor(
    private val tags: TagRepository,
) {
    suspend operator fun invoke(tagId: String, iconKey: String?) = tags.setIcon(tagId, iconKey)
}

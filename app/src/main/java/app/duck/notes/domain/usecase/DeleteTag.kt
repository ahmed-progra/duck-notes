package app.duck.notes.domain.usecase

import app.duck.notes.domain.model.BodySnapshot
import app.duck.notes.domain.repository.TagRepository
import javax.inject.Inject

/**
 * Deletes a tag: strips it from every note body carrying it, and removes the row. **The notes are
 * kept** — that is what the phase spec means by "untag, keep notes", and what the confirm dialog
 * says out loud, because it is the thing a user fears when they press delete.
 *
 * **This destroys more than rename does.** A rename leaves every character of the tag on screen
 * under a different spelling; this removes the text. So it gets the same machinery — one
 * transaction, full rollback, trashed notes in scope, a [BodySnapshot] for undo — plus the
 * confirmation rename does not need, since rename *is* a dialog and this is a menu item that would
 * otherwise fire on one tap.
 *
 * Descendants survive; see `TagRepository.removeTag` for why that asymmetry with rename is forced
 * rather than chosen.
 */
class DeleteTag @Inject constructor(
    private val tags: TagRepository,
) {
    suspend operator fun invoke(name: String): BodySnapshot = tags.removeTag(name)
}

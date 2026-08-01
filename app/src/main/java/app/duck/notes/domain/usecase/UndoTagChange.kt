package app.duck.notes.domain.usecase

import app.duck.notes.domain.model.BodySnapshot
import app.duck.notes.domain.repository.TagRepository
import javax.inject.Inject

/**
 * Puts a [BodySnapshot] back — the undo behind rename's and delete's Snackbar.
 *
 * One verb for both, because both are the same operation in opposite directions. That is not a
 * convenience: an inverse *rename* would silently fail to undo a merge, since two tags that became
 * one carry no record of which notes came from where. Bodies do carry it, and `note_tags` is derived
 * from bodies, so writing them back re-splits the tags exactly.
 */
class UndoTagChange @Inject constructor(
    private val tags: TagRepository,
) {
    suspend operator fun invoke(snapshot: BodySnapshot) = tags.restore(snapshot)
}

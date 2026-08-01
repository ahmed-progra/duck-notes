package app.duck.notes.domain.usecase

import app.duck.notes.domain.markdown.TagScanner
import app.duck.notes.domain.model.BodySnapshot
import app.duck.notes.domain.repository.TagRepository
import java.util.Locale
import javax.inject.Inject

/**
 * Renames a tag, rewriting the literal text inside every note body that carries it.
 *
 * **This is the only code path in the project that edits prose the user is not looking at.** Every
 * other write replaces a body they just typed. The three properties that make that safe all live
 * below the use case: the syntax is [TagScanner]'s, the atomicity is the repository's transaction,
 * and the recoverability is the returned [BodySnapshot].
 *
 * Returns null when there is nothing to do — an invalid name, or the same name after lowercasing.
 * The rename dialog validates live to drive its confirm button, so null is belt-and-braces rather
 * than the primary path; a correctness-critical precondition is not left to a caller.
 */
class RenameTag @Inject constructor(
    private val tags: TagRepository,
) {
    /**
     * [to] is lowercased before anything else happens. Names are lowercased for storage because the
     * `tags.name` UNIQUE index is a byte comparison, so `#Work` and `#work` must collapse to one row
     * — and [TagScanner.isValidName] enforces it structurally, since an uppercase name cannot
     * round-trip through a scanner that lowercases.
     */
    suspend operator fun invoke(from: String, to: String): BodySnapshot? {
        val target = to.trim().lowercase(Locale.ROOT)
        if (target == from || !TagScanner.isValidName(target)) return null
        return tags.rename(from, target)
    }
}

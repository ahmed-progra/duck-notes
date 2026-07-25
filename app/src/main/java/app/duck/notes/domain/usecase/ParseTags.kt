package app.duck.notes.domain.usecase

import app.duck.notes.domain.markdown.TagScanner
import app.duck.notes.domain.markdown.TagSpan
import javax.inject.Inject

/**
 * Extracts tag names from a note body — what gets written to `note_tags` on every save.
 *
 * The syntax itself lives in [TagScanner], which the editor's highlighter and the CommonMark
 * extension also call. This is only the "names, deduped, first-appearance order" view of it, so
 * those three can never disagree about what a tag is.
 */
class ParseTags @Inject constructor() {

    operator fun invoke(body: String): List<String> =
        TagScanner.scan(body).map(TagSpan::name).distinct()
}

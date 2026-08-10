package app.duck.notes.domain.usecase

import app.duck.notes.domain.markdown.WikiLinkScanner
import javax.inject.Inject

/**
 * Pulls the `[[Note title]]` targets out of a body, deduped in first-appearance order.
 *
 * A thin view over [WikiLinkScanner] — the single home for the bracket rule — so what gets written
 * to `note_links` can never disagree with what the editor paints. In particular a `[[link]]` inside
 * a code fence or inline span is not resolved, which the bare regex this replaced got wrong. Titles
 * come back exactly as typed because they are matched against note titles, which preserve casing.
 */
class ResolveWikiLinks @Inject constructor() {

    operator fun invoke(body: String): List<String> = WikiLinkScanner.titlesIn(body)
}

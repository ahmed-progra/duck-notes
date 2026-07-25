package app.duck.notes.domain.usecase

import javax.inject.Inject

/**
 * Pulls the `[[Note title]]` targets out of a body, deduped in first-appearance order.
 *
 * Parsing only: turning these titles into `note_links` rows on save, and the create-on-tap for
 * unresolved ones, is Phase 9. Titles come back exactly as typed because they are matched against
 * note titles, which preserve their casing.
 */
class ResolveWikiLinks @Inject constructor() {

    operator fun invoke(body: String): List<String> =
        WIKI_LINK.findAll(body)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
            .toList()

    private companion object {
        val WIKI_LINK = Regex("\\[\\[([^\\[\\]]+)]]")
    }
}

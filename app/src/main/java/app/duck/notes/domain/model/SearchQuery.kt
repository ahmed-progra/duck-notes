package app.duck.notes.domain.model

/**
 * What the user is looking for. [text] is plain terms — the `@todo` / `tag:x` / `-exclude` /
 * `"phrase"` operator syntax is Phase 10 and will parse into richer fields here.
 */
data class SearchQuery(
    val text: String,
    val tagId: String? = null,
)

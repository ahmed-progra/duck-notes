package app.duck.notes.domain.model

/** Literal prefix terms plus independent filters; no search operator language is interpreted. */
data class SearchQuery(
    val text: String,
    val tagId: String? = null,
    val filters: SearchFilters = SearchFilters(),
)

enum class SearchScope { Both, Active, Archive }

data class SearchFilters(
    val tagPath: String? = null,
    val pinnedOnly: Boolean = false,
    val openTasksOnly: Boolean = false,
    val imagesOnly: Boolean = false,
    val scope: SearchScope = SearchScope.Both,
)

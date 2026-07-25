package app.duck.notes.domain.model

/**
 * A tag. [name] is the full flat name including any `/` separators — `projects/duck/ui` is one tag,
 * not three. The nested tree is derived from those separators by `ObserveTagTree`.
 */
data class Tag(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val iconKey: String? = null,
    val isPinned: Boolean = false,
    val sortOrder: Int = 0,
)

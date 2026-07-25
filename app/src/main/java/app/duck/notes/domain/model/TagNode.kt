package app.duck.notes.domain.model

/**
 * One level of the derived tag tree.
 *
 * [tag] is null for an intermediate segment nobody tagged directly: with only `a/b` stored, the `a`
 * node exists so the tree has a root to hang `b` from, but there is no `a` row to pin or rename.
 */
data class TagNode(
    val segment: String,
    val path: String,
    val tag: Tag?,
    val children: List<TagNode> = emptyList(),
)

package app.duck.notes.domain.usecase

import app.duck.notes.domain.model.Tag
import app.duck.notes.domain.model.TagNode
import app.duck.notes.domain.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Turns the flat tag list into the nested sidebar tree by splitting names on `/`.
 *
 * The tree is derived, never stored: `#projects/duck/ui` is one row, and the `projects` and
 * `projects/duck` levels are built here. Intermediate levels nobody tagged directly get a null
 * [TagNode.tag] — there is no row to pin or rename. Sibling order follows the repository's order.
 */
class ObserveTagTree @Inject constructor(
    private val tags: TagRepository,
) {
    operator fun invoke(): Flow<List<TagNode>> = tags.observeAll().map(::build)

    private fun build(tags: List<Tag>): List<TagNode> {
        val roots = LinkedHashMap<String, Branch>()
        for (tag in tags) {
            val segments = tag.name.split(SEPARATOR).filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue
            var level = roots
            var branch: Branch? = null
            var path = ""
            for (segment in segments) {
                path = if (path.isEmpty()) segment else "$path$SEPARATOR$segment"
                branch = level.getOrPut(segment) { Branch(segment, path) }
                level = branch.children
            }
            branch?.tag = tag
        }
        return roots.values.map(Branch::toNode)
    }

    /** Mutable while assembling; frozen into an immutable [TagNode] at the end. */
    private class Branch(val segment: String, val path: String) {
        var tag: Tag? = null
        val children = LinkedHashMap<String, Branch>()

        fun toNode(): TagNode = TagNode(segment, path, tag, children.values.map(Branch::toNode))
    }

    private companion object {
        const val SEPARATOR = "/"
    }
}

package app.duck.notes.presentation.shell

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.TagNode

/**
 * What the sidebar is filtering the note list by — the seven fixed rows, plus a tag.
 *
 * [key] is what survives into `SavedStateHandle`: a single string rather than a parcelable, so there
 * is no `@Parcelize` annotation to keep in sync and nothing that can fail to restore. [fromKey] is
 * **total** — an unrecognised key falls back to [All] rather than throwing, because the only way to
 * see one is an older build reading a newer bundle.
 *
 * All seven filter for real. Five are fields [Note] already carries, so they cost nothing;
 * [Untagged] and [Trash] each needed one `NoteDao` query, since `observeNotes` deliberately hides
 * trashed rows and knows nothing about tag membership.
 *
 * [Locked] will be empty until Phase 12 encrypts anything, and that is the honest result rather than
 * a stub: the row filters `isLocked` correctly and finds nothing, which is what "no locked notes"
 * means today.
 */
sealed interface ShellFilter {

    val key: String

    /** Everything not archived and not trashed. */
    data object All : ShellFilter {
        override val key = "all"
    }

    data object Untagged : ShellFilter {
        override val key = "untagged"
    }

    /** Notes with at least one open task line. Phase 8 owns toggling them. */
    data object Todo : ShellFilter {
        override val key = "todo"
    }

    /** Modified since local midnight, per the injected `Clock`. */
    data object Today : ShellFilter {
        override val key = "today"
    }

    data object Locked : ShellFilter {
        override val key = "locked"
    }

    data object Archive : ShellFilter {
        override val key = "archive"
    }

    data object Trash : ShellFilter {
        override val key = "trash"
    }

    /** [path] is the full flat tag name, `projects/duck/ui` — the same string [TagNode.path] holds. */
    data class Tag(val path: String) : ShellFilter {
        override val key = "$TAG_PREFIX$path"
    }

    companion object {
        private const val TAG_PREFIX = "tag:"

        /** The six fixed rows above the tag tree, in sidebar order. [Trash] is rendered below it. */
        val navRows: List<ShellFilter> = listOf(All, Untagged, Todo, Today, Locked, Archive)

        fun fromKey(key: String?): ShellFilter = when {
            key == null -> All
            key.startsWith(TAG_PREFIX) -> Tag(key.removePrefix(TAG_PREFIX))
            else -> (navRows + Trash).find { it.key == key } ?: All
        }
    }
}

/**
 * How many panes fit.
 *
 * Deliberately **not** Material's `WindowSizeClass`. `calculateWindowSizeClass` is experimental,
 * soft-deprecated in favour of the adaptive libraries, needs an `Activity` handle and a dependency,
 * and cannot be exercised without a device. A size class *is* a width threshold, and [paneModeFor]
 * is the whole of it — so the breakpoints are a JVM unit test instead of a manual resize.
 */
enum class PaneMode { Compact, Medium, Expanded }

/** Material's own breakpoints: 600dp and 840dp. */
fun paneModeFor(width: Dp): PaneMode = when {
    width < 600.dp -> PaneMode.Compact
    width < 840.dp -> PaneMode.Medium
    else -> PaneMode.Expanded
}

/**
 * One rendered line of the tag tree — a [TagNode] flattened with the depth it draws at.
 *
 * [selectable] is false for an intermediate segment nobody tagged directly: with only `a/b` stored,
 * `a` exists so `b` has a root, but there is no `a` row to filter by. It can still be expanded.
 */
@Immutable
data class TagRow(
    val path: String,
    val segment: String,
    val depth: Int,
    val expanded: Boolean,
    val hasChildren: Boolean,
    val selectable: Boolean,
    val pinned: Boolean,
)

/**
 * Depth-first flatten, honouring [expanded].
 *
 * A collapsed parent hides **all** its descendants, not one level — `flatten` simply does not
 * recurse, so the grandchildren never enter the list. That is the case `ShellStateTest` pins,
 * because "hides one level" is the plausible wrong answer.
 *
 * Lives here rather than in `domain/` on purpose: it depends on transient UI expansion state, not
 * on a business rule. `ObserveTagTree` owns the actual derivation and is untouched.
 */
fun List<TagNode>.flatten(expanded: Set<String>, depth: Int = 0): List<TagRow> =
    flatMap { node ->
        val isExpanded = node.path in expanded
        val row = TagRow(
            path = node.path,
            segment = node.segment,
            depth = depth,
            expanded = isExpanded,
            hasChildren = node.children.isNotEmpty(),
            selectable = node.tag != null,
            pinned = node.tag?.isPinned == true,
        )
        if (isExpanded) listOf(row) + node.children.flatten(expanded, depth + 1) else listOf(row)
    }

/**
 * Every pinned tag, flat, at depth 0 — the pinned section deliberately ignores tree structure.
 *
 * [TagRow.segment] carries the **full path** here rather than the leaf. A pinned `projects/duck/ui`
 * listed as "ui" beside a pinned `site/ui` is two identical rows, and the pinned section has no
 * indentation to disambiguate them the way the tree does.
 */
fun List<TagNode>.pinned(): List<TagRow> = flatMap { node ->
    val self = if (node.tag?.isPinned == true) {
        listOf(
            TagRow(
                path = node.path,
                segment = node.path,
                depth = 0,
                expanded = false,
                hasChildren = false,
                selectable = true,
                pinned = true,
            ),
        )
    } else {
        emptyList()
    }
    self + node.children.pinned()
}

/** Everything the three panes read. One object, so a resize can change layout but never content. */
@Immutable
data class ShellUiState(
    val filter: ShellFilter = ShellFilter.All,
    val notes: List<Note> = emptyList(),
    val selectedNoteId: String? = null,
    val selectedNoteBody: String = "",
    val pinnedTags: List<TagRow> = emptyList(),
    val tagRows: List<TagRow> = emptyList(),
)

/** The four things a row tap can do. Hoisted so the gallery can drive the shell from a fixture. */
@Immutable
data class ShellActions(
    val onFilter: (ShellFilter) -> Unit = {},
    val onSelectNote: (String) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onToggleTag: (String) -> Unit = {},
)

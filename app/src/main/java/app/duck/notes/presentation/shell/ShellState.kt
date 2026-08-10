package app.duck.notes.presentation.shell

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.duck.notes.domain.model.Attachment
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.NoteLink
import app.duck.notes.domain.model.TagCount
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
 * [tagId] is null for an intermediate segment nobody tagged directly: with only `a/b` stored, `a`
 * exists so `b` has a root, but there is no `a` row to filter by, pin, rename or delete. It can
 * still be expanded, and [selectable] is derived rather than stored so the two can never disagree
 * about the same fact.
 *
 * [count] includes descendants, because tapping the row filters the subtree — see `ObserveTagCounts`.
 *
 * [iconKey] is the row's own override **or the nearest ancestor's**. `DuckIcon.forTag` walks up
 * keyword matches on its own, but it sees one name and one key; inheriting a stored key needs the
 * whole tree, which is why its KDoc defers that to this phase. Setting an icon on `projects` and
 * having `projects/duck/ui` show it is the reason a user sets one on a parent.
 */
@Immutable
data class TagRow(
    val path: String,
    val segment: String,
    val depth: Int,
    val expanded: Boolean,
    val hasChildren: Boolean,
    val tagId: String?,
    val pinned: Boolean,
    val count: Int = 0,
    val ownCount: Int = 0,
    val iconKey: String? = null,
) {
    /** A row with no `tags` row behind it can be expanded but not filtered by, nor acted on. */
    val selectable: Boolean get() = tagId != null
}

/**
 * Which tag dialog is open, if any.
 *
 * Held in plain `remember` at the shell rather than in `SavedStateHandle`: making a sealed type
 * saveable needs a hand-written `Saver`, and the cost of *not* doing it is a rotation dismissing a
 * dialog and losing at most a half-typed tag name. Stated rather than hidden, same as the editor
 * buffer's ceiling in Phase 6.
 */
@Immutable
sealed interface TagDialog {

    data class Rename(val path: String) : TagDialog

    /** [noteCount] comes from the counts map the sidebar row already shows, so it costs no query. */
    data class Delete(val path: String, val noteCount: Int) : TagDialog

    data class Icon(val tagId: String, val path: String, val current: String?) : TagDialog

    /** Long-press on a note card. [tagsPresent] is what the picker omits — see `AddTagToNote`. */
    data class AddTag(val noteId: String, val tagsPresent: List<String>) : TagDialog
}

/**
 * What just happened to a tag, for the one Snackbar the shell shows.
 *
 * Not all of it is undoable, which is why this is not called `UndoPrompt`: [Kind.AddFailed] reports
 * a write that **did not happen**, so offering Undo for it would promise to reverse nothing.
 * [noteCount] is likewise meaningless there — nothing was touched — and the failure message does
 * not read it.
 */
@Immutable
data class TagPrompt(val kind: Kind, val tagName: String, val noteCount: Int = 0) {
    enum class Kind {
        Renamed,
        Deleted,

        /** `AddTagToNote` appended the tag and re-parsing did not find it — an unclosed code fence. */
        AddFailed,
    }

    /** Undo is offered for the two operations that actually wrote something. */
    val undoable: Boolean get() = kind != Kind.AddFailed
}

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
fun List<TagNode>.flatten(
    expanded: Set<String>,
    counts: Map<String, TagCount> = emptyMap(),
    depth: Int = 0,
    inheritedIconKey: String? = null,
): List<TagRow> =
    flatMap { node ->
        val isExpanded = node.path in expanded
        // The row's own key wins over an ancestor's, matching DuckIcon.forTag's leaf-first rule.
        val iconKey = node.tag?.iconKey ?: inheritedIconKey
        val row = TagRow(
            path = node.path,
            segment = node.segment,
            depth = depth,
            expanded = isExpanded,
            hasChildren = node.children.isNotEmpty(),
            tagId = node.tag?.id,
            pinned = node.tag?.isPinned == true,
            count = counts[node.path]?.subtree ?: 0,
            ownCount = counts[node.path]?.own ?: 0,
            iconKey = iconKey,
        )
        if (isExpanded) {
            listOf(row) + node.children.flatten(expanded, counts, depth + 1, iconKey)
        } else {
            listOf(row)
        }
    }

/**
 * Every pinned tag, flat, at depth 0 — the pinned section deliberately ignores tree structure.
 *
 * [TagRow.segment] carries the **full path** here rather than the leaf. A pinned `projects/duck/ui`
 * listed as "ui" beside a pinned `site/ui` is two identical rows, and the pinned section has no
 * indentation to disambiguate them the way the tree does.
 */
fun List<TagNode>.pinned(
    counts: Map<String, TagCount> = emptyMap(),
    inheritedIconKey: String? = null,
): List<TagRow> = flatMap { node ->
    // Inherited the same way the tree does. A pinned tag shows up in BOTH sections, so a key
    // resolved differently in the two would render one tag with two icons.
    val iconKey = node.tag?.iconKey ?: inheritedIconKey
    val self = node.tag?.takeIf { it.isPinned }?.let { tag ->
        listOf(
            TagRow(
                path = node.path,
                segment = node.path,
                depth = 0,
                expanded = false,
                hasChildren = false,
                tagId = tag.id,
                pinned = true,
                count = counts[node.path]?.subtree ?: 0,
                ownCount = counts[node.path]?.own ?: 0,
                iconKey = iconKey,
            ),
        )
    }.orEmpty()
    self + node.children.pinned(counts, iconKey)
}

/**
 * Every path with a **real `tags` row** behind it — intermediate segments excluded.
 *
 * Not `counts.keys`, which includes intermediates. The difference is load-bearing in two places:
 * the rename dialog uses this to decide whether a target already exists, and announcing *"#personal
 * already exists, 4 notes will be merged into it"* for a `personal` that is only a derived segment
 * would describe a merge that is not going to happen. The "Add tag" picker uses it for the same
 * reason — an intermediate is not a tag anyone has applied.
 *
 * Walks the whole tree, not the flattened rows: `flatten` omits collapsed subtrees, and whether a
 * name is taken has nothing to do with whether the user has expanded its parent.
 */
fun List<TagNode>.tagPaths(): List<String> =
    flatMap { node ->
        listOfNotNull(node.path.takeIf { node.tag != null }) + node.children.tagPaths()
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
    /** Every tag on the open note, for the chip row above the editor. Derived, never queried. */
    val selectedNoteTags: List<String> = emptyList(),
    /** The open note's attachments, for the strip under the editor. Empty when no note is selected. */
    val selectedNoteAttachments: List<Attachment> = emptyList(),
    /** All known tag paths, for the "Add tag" picker. */
    val allTagPaths: List<String> = emptyList(),
    /** Heading fold keys for the selected note, persisted by note id in [DuckShellViewModel]. */
    val collapsedSections: Set<String> = emptySet(),
    /**
     * Notes linking *to* the open one, newest first — Phase 9b.
     *
     * Queried, not derived. The other note's body is what contains the `[[link]]`, and the shell only
     * ever holds the open note's text, so there is nothing here to scan. Trashed sources are already
     * excluded by `NoteLinkDao.observeBacklinks`.
     */
    val selectedNoteBacklinks: List<NoteLink> = emptyList(),
    /** Whether the backlinks panel is showing — a side pane on Expanded, a bottom sheet below it. */
    val infoOpen: Boolean = false,
    val undo: TagPrompt? = null,
)

/** Everything a tap in the shell can do. Hoisted so the gallery can drive it from a fixture. */
@Immutable
data class ShellActions(
    val onFilter: (ShellFilter) -> Unit = {},
    val onSelectNote: (String) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onToggleTag: (String) -> Unit = {},
    val onPinTag: (tagId: String, pinned: Boolean) -> Unit = { _, _ -> },
    val onRenameTag: (from: String, to: String) -> Unit = { _, _ -> },
    val onDeleteTag: (path: String) -> Unit = {},
    val onSetTagIcon: (tagId: String, iconKey: String?) -> Unit = { _, _ -> },
    val onAddTagToNote: (noteId: String, name: String) -> Unit = { _, _ -> },
    /** A picked image's durable-copy source and its mime, to attach to the open note. */
    val onAttachImage: (sourceUri: String, mime: String) -> Unit = { _, _ -> },
    /** Pasted images as `sourceUri to mime` pairs, attached in order — see `onAttachImages`. */
    val onAttachImages: (images: List<Pair<String, String>>) -> Unit = {},
    /** Remove an attachment: its file, its row, and its `![](attachment:id)` reference. */
    val onDeleteAttachment: (id: String) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onUndoShown: () -> Unit = {},
    val onToggleSection: (String) -> Unit = {},
    /** Show or hide the backlinks panel. A backlink row tap reuses [onSelectNote] — it is a note id. */
    val onToggleInfo: () -> Unit = {},
    /**
     * The tags already on a note, so the "Add tag" picker can omit them.
     *
     * A function rather than a field on [ShellUiState]: it is needed only at the moment the menu
     * opens, and precomputing it would scan every visible note's body on every emission for a menu
     * nobody has opened. Supplied by the ViewModel from the injected `ParseTags`, so the UI never
     * instantiates a use case and the picker can never disagree with `note_tags` about what a note
     * carries — both come from one scanner.
     */
    val tagsOfNote: (noteId: String) -> List<String> = { emptyList() },
)

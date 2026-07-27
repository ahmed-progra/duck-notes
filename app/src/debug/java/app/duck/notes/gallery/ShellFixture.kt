package app.duck.notes.gallery

import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.Tag
import app.duck.notes.domain.model.TagNode
import app.duck.notes.presentation.shell.ShellFilter
import app.duck.notes.presentation.shell.ShellUiState
import app.duck.notes.presentation.shell.flatten
import app.duck.notes.presentation.shell.pinned

/**
 * A populated shell, without a database.
 *
 * Phase 6's palette work is checked by looking at a sidebar and a note list that have something in
 * them, across all eight themes — and a fresh install's database is empty, so the page that verifies
 * the colours would render two empty states.
 *
 * Timestamps are fixed rather than offsets from now, so the list reads identically on every launch
 * and two screenshots of different palettes differ only in colour. They are old enough that
 * `DateUtils` renders absolute dates, which is the case worth looking at — a column of "5 minutes
 * ago" hides how a long date wraps beside a long title.
 *
 * The tree covers what `flatten` has to get right: a three-level nest, two pinned tags at different
 * depths, and `personal` as a segment nobody tagged directly — the row that expands but cannot be
 * selected.
 */
internal object ShellFixture {

    private const val DAY = 86_400_000L

    /** 2024-11-30. Fixed so the harness is reproducible. */
    private const val EPOCH = 1_733_000_000_000L

    private fun tag(name: String, pinned: Boolean = false) =
        Tag(id = name, name = name, isPinned = pinned)

    val tree: List<TagNode> = listOf(
        TagNode(
            segment = "projects",
            path = "projects",
            tag = tag("projects", pinned = true),
            children = listOf(
                TagNode(
                    segment = "duck",
                    path = "projects/duck",
                    tag = tag("projects/duck"),
                    children = listOf(
                        TagNode(segment = "ui", path = "projects/duck/ui", tag = tag("projects/duck/ui")),
                        TagNode(segment = "sync", path = "projects/duck/sync", tag = tag("projects/duck/sync")),
                    ),
                ),
            ),
        ),
        // tag = null: nobody wrote #personal, only #personal/recipes. Expandable, not selectable.
        TagNode(
            segment = "personal",
            path = "personal",
            tag = null,
            children = listOf(
                TagNode(
                    segment = "recipes",
                    path = "personal/recipes",
                    tag = tag("personal/recipes", pinned = true),
                ),
            ),
        ),
        TagNode(segment = "work", path = "work", tag = tag("work")),
    )

    val initiallyExpanded = setOf("projects", "projects/duck")

    private val notes = listOf(
        note("n1", "Phase 6 — navigation shell", 0, "Three panes, derived from state.\n\nNo NavHost: one selectedNoteId beats a back stack plus pane state to reconcile."),
        note("n2", "A deliberately long title that has to ellipsize somewhere sensible", 1, "Testing how the card handles a title that will not fit on one line at any pane width."),
        note("n3", "Groceries", 2, "- [ ] oat milk\n- [x] coffee\n- [ ] bread"),
        note("n4", "Untitled", 3, ""),
        note("n5", "Sync design", 9, "Supabase, last-write-wins on modifiedAt.\n\nPhase 13 owns the conflict rule."),
    )

    /**
     * Opens with a note **already selected**, deliberately.
     *
     * The four criteria this page exists for are all about the *active* state — the accent bar on the
     * note card, the accent bar on the sidebar row, and whether either reads as a fill. A fixture
     * that opens with nothing selected shows none of them until someone thinks to tap, which is the
     * same defect Phase 5 recorded when the gallery rendered three checkbox states and omitted the
     * fourth: **a harness that does not show the state cannot verify it.**
     */
    val state = ShellUiState(
        filter = ShellFilter.All,
        notes = notes,
        // The FIRST note, so the active card's accent bar is above the fold. The gallery's control
        // chrome costs ~460px of a 1080px landscape screen, and a selected row that needs scrolling
        // to reach is one nobody will check.
        selectedNoteId = "n1",
        selectedNoteBody = bodyOf("n1"),
        pinnedTags = tree.pinned(),
        tagRows = tree.flatten(initiallyExpanded),
    )

    fun bodyOf(id: String) = notes.find { it.id == id }?.body.orEmpty()

    /**
     * What the fixture's sidebar does when a row is tapped.
     *
     * Only the four rows a fixture can answer honestly return anything. Todo, Today and Locked go
     * through the same path to an empty list, which is what they would show against this data in the
     * real app too — nothing here is locked and every timestamp is months old.
     */
    fun apply(state: ShellUiState, filter: ShellFilter) = state.copy(
        filter = filter,
        selectedNoteId = null,
        selectedNoteBody = "",
        notes = when (filter) {
            ShellFilter.All -> notes
            ShellFilter.Untagged -> notes.filter { it.id == "n4" }
            ShellFilter.Archive -> notes.filter { it.id == "n5" }
            ShellFilter.Trash -> notes.filter { it.id == "n3" }
            is ShellFilter.Tag -> notes.filter { it.id == "n1" || it.id == "n5" }
            else -> emptyList()
        },
    )

    private fun note(id: String, title: String, daysAgo: Int, body: String) = Note(
        id = id,
        title = title,
        body = body,
        createdAt = EPOCH - daysAgo * DAY,
        modifiedAt = EPOCH - daysAgo * DAY,
    )
}

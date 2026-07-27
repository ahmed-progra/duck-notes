package app.duck.notes.presentation.shell

import androidx.compose.ui.unit.dp
import app.duck.notes.domain.model.Tag
import app.duck.notes.domain.model.TagNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell's pure state layer, on the JVM.
 *
 * This is the payoff for [paneModeFor] being a function of a `Dp` rather than a `WindowSizeClass`:
 * the breakpoints that decide how many panes render are asserted here in milliseconds instead of by
 * dragging a window on a device, which is the only way Material's own size class could be checked.
 */
class ShellStateTest {

    private fun tag(name: String, pinned: Boolean = false) =
        Tag(id = name, name = name, isPinned = pinned)

    /** `projects` > `duck` > `ui`, plus an intermediate `personal` nobody tagged directly. */
    private val tree = listOf(
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
                        TagNode("ui", "projects/duck/ui", tag("projects/duck/ui")),
                    ),
                ),
            ),
        ),
        TagNode(
            segment = "personal",
            path = "personal",
            tag = null,
            children = listOf(TagNode("recipes", "personal/recipes", tag("personal/recipes", pinned = true))),
        ),
    )

    // --- paneModeFor ---------------------------------------------------------

    @Test
    fun paneBreakpointsAreMaterialsOwn() {
        assertEquals(PaneMode.Compact, paneModeFor(599.dp))
        assertEquals(PaneMode.Medium, paneModeFor(600.dp))
        assertEquals(PaneMode.Medium, paneModeFor(839.dp))
        assertEquals(PaneMode.Expanded, paneModeFor(840.dp))
    }

    @Test
    fun aZeroWidthIsCompactRatherThanACrash() {
        // BoxWithConstraints reports 0 for one frame during some resizes.
        assertEquals(PaneMode.Compact, paneModeFor(0.dp))
    }

    // --- flatten -------------------------------------------------------------

    @Test
    fun collapsedByDefaultShowsOnlyRoots() {
        assertEquals(listOf("projects", "personal"), tree.flatten(emptySet()).map { it.path })
    }

    @Test
    fun expandingAParentRevealsOnlyItsChildren() {
        assertEquals(
            listOf("projects", "projects/duck", "personal"),
            tree.flatten(setOf("projects")).map { it.path },
        )
    }

    /**
     * The case worth pinning: a collapsed parent hides its **whole** subtree, not one level.
     *
     * `projects/duck` is in the expanded set, but `projects` is not — so `flatten` never recurses
     * into it and the grandchild never appears. "Hides one level" is the plausible wrong answer, and
     * it is invisible until someone collapses a deep tree.
     */
    @Test
    fun aCollapsedParentHidesItsGrandchildrenToo() {
        assertEquals(
            listOf("projects", "personal"),
            tree.flatten(setOf("projects/duck")).map { it.path },
        )
    }

    @Test
    fun depthIsTheRenderIndent() {
        val rows = tree.flatten(setOf("projects", "projects/duck")).associateBy { it.path }

        assertEquals(0, rows.getValue("projects").depth)
        assertEquals(1, rows.getValue("projects/duck").depth)
        assertEquals(2, rows.getValue("projects/duck/ui").depth)
    }

    @Test
    fun anIntermediateSegmentIsExpandableButNotSelectable() {
        val personal = tree.flatten(emptySet()).single { it.path == "personal" }

        assertFalse("nobody wrote #personal, so there is no row to filter by", personal.selectable)
        assertTrue("it still has to open to reach #personal/recipes", personal.hasChildren)
    }

    @Test
    fun aLeafReportsNoChildren() {
        val ui = tree.flatten(setOf("projects", "projects/duck")).single { it.path == "projects/duck/ui" }

        assertFalse(ui.hasChildren)
        assertTrue(ui.selectable)
    }

    // --- pinned --------------------------------------------------------------

    @Test
    fun pinnedIsFlatAndIgnoresExpansion() {
        assertEquals(listOf("projects", "personal/recipes"), tree.pinned().map { it.path })
    }

    /** A pinned nested tag shows its whole path: "recipes" alone would collide with any other. */
    @Test
    fun aPinnedNestedTagShowsItsFullPath() {
        assertEquals("personal/recipes", tree.pinned().single { it.path == "personal/recipes" }.segment)
        assertEquals(0, tree.pinned().single { it.path == "personal/recipes" }.depth)
    }

    // --- ShellFilter keys ----------------------------------------------------

    @Test
    fun everyFilterRoundTripsThroughItsSavedStateKey() {
        val all = ShellFilter.navRows + ShellFilter.Trash + ShellFilter.Tag("projects/duck")

        all.forEach { assertEquals(it, ShellFilter.fromKey(it.key)) }
    }

    @Test
    fun anUnknownOrAbsentKeyFallsBackToAll() {
        // The only source of an unknown key is an older build reading a newer bundle.
        assertEquals(ShellFilter.All, ShellFilter.fromKey(null))
        assertEquals(ShellFilter.All, ShellFilter.fromKey("something-phase-9-added"))
    }

    @Test
    fun aTagPathContainingTheRowKeysStillRoundTrips() {
        // "tag:" is a prefix, not a delimiter, so a path may contain anything including a colon.
        val odd = ShellFilter.Tag("archive")

        assertEquals(odd, ShellFilter.fromKey(odd.key))
        assertEquals("tag:archive", odd.key)
    }

    /**
     * A tag named after a fixed row must not collide with it. **This crashed the app.**
     *
     * The sidebar is one `LazyColumn` holding both the fixed rows and the tag tree, and Compose
     * throws `IllegalArgumentException: Key "todo" was already used` when two items share a key. The
     * first launch against a database containing a `#todo` note died instantly — every unit test
     * passed, because the collision lives in the sidebar's `items(key = ...)` and not in the state.
     *
     * `ShellFilter.Tag` already prefixes with `tag:`, so the fix was for `DuckSidebar` to key its
     * rows through it rather than by raw `path`. This asserts the property that makes that safe: no
     * tag path, however adversarial, can produce a fixed row's key.
     */
    @Test
    fun noTagPathCanCollideWithAFixedRowKey() {
        val fixed = (ShellFilter.navRows + ShellFilter.Trash).map { it.key }.toSet()
        val adversarial = fixed + listOf("tag:all", "", "tag", "tag:tag:todo")

        adversarial.forEach { path ->
            val key = ShellFilter.Tag(path).key
            assertFalse("tag path '$path' produced the fixed row key '$key'", key in fixed)
        }
    }
}

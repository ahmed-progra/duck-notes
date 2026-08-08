package app.duck.notes.markdown

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.presentation.markdown.MarkdownEditorScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of 8c-c that cannot be a JVM test: **what happens when a finger lands on a fold chevron.**
 *
 * The range arithmetic is already covered without an emulator — `SectionFoldingTest` for the fold
 * ranges and the merge, `ConcealInvariantTest` for the conceal contract with folds applied. What only
 * a device can settle is that a tap reaches the chevron, that a *drag* reaches the scroll instead,
 * that TalkBack sees a button, and that the chevron stays on its own heading once an earlier section
 * has been folded out from under it.
 *
 * 8c-c shipped with none of this. Its own predecessor, 8c-b, is the reason it should not have: the
 * task overlay needed four device tests and three plants before it was trustworthy, and this is the
 * same overlay mechanism reading the same rebased offsets.
 *
 * ## Two fixture rules, both learned the hard way
 *
 * **The caret must be outside the section under test.** `SectionFolding.ranges` drops any section the
 * selection touches, so a fold with the caret in its body is a no-op that looks like a broken toggle.
 * `TextFieldState(initial)` parks the caret at the end of the text, which is inside the *last*
 * section — so every test here folds the **first** section and leaves the caret where it landed.
 * SETUP.md trap 6, in its folding form.
 *
 * **Folding changes nothing in `state.text`.** It is an `OutputTransformation`, so the buffer is
 * untouched and asserting on it would pass under every plant. The observable effects are the
 * chevron's own label, the field's height, and where the *other* chevrons sit.
 */
@RunWith(AndroidJUnit4::class)
class SectionFoldUiTest {

    @get:Rule
    val rule = createComposeRule()

    /** Hoists the fold set the way `DuckShellViewModel` does, so a tap can actually round-trip. */
    private fun editor(initial: String): TextFieldState {
        val state = TextFieldState(initial)
        rule.setContent {
            var collapsed by remember { mutableStateOf(emptySet<String>()) }
            DuckTheme {
                MarkdownEditorScreen(
                    state = state,
                    collapsedSections = collapsed,
                    onToggleSection = { key ->
                        collapsed = if (key in collapsed) collapsed - key else collapsed + key
                    },
                )
            }
        }
        rule.waitForIdle()
        return state
    }

    private fun fieldHeight() =
        rule.onNode(hasSetTextAction()).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    // --- the tap -------------------------------------------------------------

    /**
     * A tap collapses the section, and both halves are asserted for the same reason the drag test
     * pairs its outcomes: the label flipping proves the toggle reached state, and the field getting
     * shorter proves the plan actually removed text. A wiring that flipped the label without folding
     * would satisfy the first alone.
     */
    @Test
    fun aFoldChevronTapCollapsesTheSection() {
        editor(NOTE)
        val before = fieldHeight()

        rule.onNodeWithContentDescription(HIDE_ALPHA).performTouchInput { click() }
        rule.waitForIdle()

        val after = fieldHeight()
        rule.onNodeWithContentDescription(SHOW_ALPHA).assertExists()
        assertTrue(
            "the section did not fold: the field is $after tall, was $before",
            after < before,
        )
    }

    /** And back, so the control is a toggle rather than a one-way trip. */
    @Test
    fun aSecondTapExpandsItAgain() {
        editor(NOTE)
        val before = fieldHeight()

        rule.onNodeWithContentDescription(HIDE_ALPHA).performTouchInput { click() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(SHOW_ALPHA).performTouchInput { click() }
        rule.waitForIdle()

        assertEquals("expanding must restore the original height", before, fieldHeight())
    }

    // --- the drag (SETUP.md trap 7) ------------------------------------------

    /**
     * **Both outcomes, one assertion.**
     *
     * `selectable` builds on `clickable`, which *cancels* on drag rather than consuming it, and the
     * cancelled drag then travels to a scrollable **ancestor**. Placed as a sibling of the scroll
     * container instead of inside it there is no such ancestor, the drag reaches nothing, and the
     * note does not scroll — which reads as "selectable swallows scrolls" and invites exactly the
     * wrong fix. SETUP.md trap 7 is written about this.
     *
     * Asserting only "did not toggle" goes green on a chevron that silently eats the drag; asserting
     * only "did scroll" goes green on one that scrolls *and* toggles. JUnit stops at the first
     * failure, so a sequential version proves only whichever half runs first. One assertion over a
     * pair reports both every time.
     */
    @Test
    fun aDragFromTheChevronScrollsTheNoteAndDoesNotToggle() {
        editor(LONG_NOTE)
        val topBefore = rule.onNodeWithContentDescription(HIDE_ALPHA).getUnclippedBoundsInRoot().top

        rule.onNodeWithContentDescription(HIDE_ALPHA).performTouchInput {
            swipe(start = center, end = center - Offset(0f, 600f), durationMillis = 300)
        }
        rule.waitForIdle()

        // Still labelled "Hide" means it never toggled; a smaller top means the note scrolled.
        val stillExpanded = rule.onAllNodesWithContentDescription(HIDE_ALPHA)
            .fetchSemanticsNodes().isNotEmpty()
        val topAfter = rule.onNodeWithContentDescription(
            if (stillExpanded) HIDE_ALPHA else SHOW_ALPHA,
        ).getUnclippedBoundsInRoot().top

        assertEquals(
            "a drag starting on a chevron must scroll the note and must not fold it " +
                "(the chevron moved from $topBefore to $topAfter)",
            Toggled(false) to Scrolled(true),
            Toggled(!stillExpanded) to Scrolled(topAfter < topBefore),
        )
    }

    /** Named wrappers so the failure message reads `Toggled(true)`, not a bare `true`. */
    private data class Toggled(val value: Boolean)

    private data class Scrolled(val value: Boolean)

    // --- TalkBack ------------------------------------------------------------

    /**
     * The role is what makes TalkBack announce a control rather than an anonymous node; the title in
     * the description is what tells the user *which* section it folds. A screen with four chevrons
     * all reading "button" is not navigable.
     */
    @Test
    fun theChevronIsAButtonNodeWithItsSectionTitle() {
        editor(NOTE)
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

        rule.onNodeWithContentDescription(HIDE_ALPHA).assert(button)
        rule.onNodeWithContentDescription(HIDE_BETA).assert(button)
    }

    /**
     * **The containment invariant, and the guard for the defect 8c-c shipped.**
     *
     * The chevron lives in a fixed-width leading gutter. Its own bounds must lie inside that gutter,
     * which sits immediately left of the text field — so the invariant is: the chevron starts at or
     * after the root's left edge, and ends at or before the text begins.
     *
     * 8c-c positioned it at `bounds.left - 24.dp` inside the **text** Box, which for a heading at
     * column 0 put it at `[-24dp, 0dp]` in its parent's space. Compose hit-tests within ancestor
     * bounds, so it was permanently untappable while its semantics node — and therefore TalkBack —
     * looked entirely correct. This measured `-3.81dp..20.19dp` against a field starting at
     * `20.19dp` on a Pixel 7 API 35.
     *
     * **Plant:** restore `IntOffset((bounds.left - with(density) { 24.dp.toPx() }).roundToInt(), …)`
     * in `SectionFoldOverlay`. This goes red naming the negative left bound, and the four
     * interaction tests below go red with it.
     *
     * Bounds are asserted, not sizes: the chevron's height follows the reading font and the user's
     * display scale, so a pinned number would fail on a Phase 14 font change for no defect. The
     * measured extents are printed so a device run records them.
     */
    @Test
    fun theChevronStaysInsideTheGutter() {
        editor(NOTE)
        val chevron = rule.onNodeWithContentDescription(HIDE_ALPHA).getUnclippedBoundsInRoot()
        val field = rule.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        println("DUCK fold chevron = $chevron   field = $field")

        assertTrue("the chevron has no area", chevron.right > chevron.left)
        assertEquals(
            "the chevron spans ${chevron.left}..${chevron.right}, the text starts at ${field.left}. " +
                "It must sit at or after the root's left edge (or it is left of its parent's origin " +
                "and no touch can reach it) and at or before the text (or it overlaps the reading " +
                "column and steals caret taps).",
            StartsInside(true) to EndsBeforeText(true),
            StartsInside(chevron.left.value >= 0f) to EndsBeforeText(chevron.right <= field.left),
        )
    }

    /** Named wrappers so a failure reads `StartsInside(false)`, not a bare `false`. */
    private data class StartsInside(val value: Boolean)

    private data class EndsBeforeText(val value: Boolean)

    // --- the rebased position ------------------------------------------------

    /**
     * **The coordinate claim, and the one 8c-b's bug class lives in.**
     *
     * `SectionFoldOverlay` positions itself at `getBoundingBox(renderedOffset(headingStart, cuts))`.
     * The rebase is load-bearing: once an earlier section is folded, a heading's *source* offset and
     * its *rendered* offset differ by everything that was removed above it. Reading the raw source
     * offset against the rendered layout points at a different character — and for the last heading
     * in a note it points past the end, where the overlay's own length guard makes the chevron
     * disappear entirely.
     *
     * So: fold the first section and require Beta's chevron to survive **and** to have moved up. The
     * second half is what distinguishes a correct rebase from an accidental one — a chevron that
     * stayed put would mean it is tracking source coordinates in a shrunken layout.
     */
    @Test
    fun theChevronFollowsItsHeadingWhenAnEarlierSectionIsFolded() {
        editor(NOTE)
        val betaBefore = rule.onNodeWithContentDescription(HIDE_BETA).getUnclippedBoundsInRoot().top

        rule.onNodeWithContentDescription(HIDE_ALPHA).performTouchInput { click() }
        rule.waitForIdle()

        rule.onNodeWithContentDescription(HIDE_BETA).assertExists()
        val betaAfter = rule.onNodeWithContentDescription(HIDE_BETA).getUnclippedBoundsInRoot().top
        assertTrue(
            "Beta's chevron sits at $betaAfter after folding Alpha, was $betaBefore — it must rise " +
                "with the heading it belongs to, or it is reading source offsets against a " +
                "rendered layout",
            betaAfter < betaBefore,
        )
    }

    private companion object {
        const val HIDE_ALPHA = "Hide Alpha section"
        const val SHOW_ALPHA = "Show Alpha section"
        const val HIDE_BETA = "Hide Beta section"

        /**
         * Two foldable sections, each with a body — a heading whose section has no content gets no
         * chevron at all, by `MarkdownEditorScreen`'s own `section.content != null` guard.
         *
         * The caret lands at the end of this text, inside Beta, which is why every test folds Alpha.
         */
        val NOTE = """
            # Alpha

            first body line
            second body line

            # Beta

            beta body line
        """.trimIndent()

        val LONG_NOTE = NOTE + "\n\n" + (1..80).joinToString("\n") { "filler line $it" }
    }
}

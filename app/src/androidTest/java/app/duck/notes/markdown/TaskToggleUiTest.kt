package app.duck.notes.markdown

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.presentation.markdown.MarkdownEditorScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of 8c-b that cannot be a JVM test: **what happens when a finger lands on a checkbox.**
 *
 * The arithmetic behind it is already covered without an emulator — `TaskBoxTest` for the rendered
 * offsets, `TaskToggleTest` for which offsets may toggle, `GoldenSpanTest` for the rendering. What
 * only a device can settle is that a tap reaches the overlay and not the text field, that a *drag*
 * reaches the scroll and not the overlay, and that TalkBack sees a checkbox at all.
 *
 * Each test here has a plant behind it, named in `DUCK_NOTES_BUILD_PROMPT.md`: P6 replaces
 * `toggleable` with a gesture detector that fires on down, P7 widens the overlay to the whole line,
 * P8 drops the role. All three were run red before this file was trusted.
 */
@RunWith(AndroidJUnit4::class)
class TaskToggleUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun editor(initial: String): TextFieldState {
        val state = TextFieldState(initial)
        rule.setContent { DuckTheme { MarkdownEditorScreen(state) } }
        rule.waitForIdle()
        return state
    }

    // --- the tap -------------------------------------------------------------

    @Test
    fun aTapOnTheBoxTogglesItAndLeavesTheCaret() {
        val state = editor(NOTE)
        rule.runOnUiThread { state.edit { selection = TextRange(CARET_HOME) } }
        rule.waitForIdle()

        rule.onNodeWithContentDescription(OPEN_TASK).performTouchInput { click() }
        rule.waitForIdle()

        assertEquals(
            "the tapped box must flip and nothing else may change",
            NOTE.replaceFirst("- [ ] count them", "- [x] count them"),
            state.text.toString(),
        )
        assertEquals(
            "tapping a checkbox is not a caret placement — the caret must not move",
            CARET_HOME,
            state.selection.start,
        )
    }

    @Test
    fun aDoneBoxTogglesBack() {
        val state = editor(NOTE)
        rule.onNodeWithContentDescription(DONE_TASK).performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(NOTE.replaceFirst("- [x] feed them", "- [ ] feed them"), state.text.toString())
    }

    // --- the drag (P6) -------------------------------------------------------

    /**
     * **Both halves are asserted, and that is the point of the plant.**
     *
     * A gesture detector that fires on *down* fails this two separate ways — it toggles when it
     * should not, and it consumes the down so the note never scrolls. Asserting only "did not
     * toggle" would go green on a detector that merely swallowed the drag, and asserting only "did
     * scroll" would go green on one that scrolled *and* toggled. Neither alone proves the behaviour.
     *
     * Scroll is observed through the overlay's own position rather than a `ScrollState` the screen
     * owns privately: if the note scrolled, the checkbox moved up with the text it is pinned to.
     *
     * **The two outcomes are collected and asserted as one pair rather than as two `assertEquals`
     * calls, and that is not stylistic.** JUnit stops at the first failing assertion, so a sequential
     * version proves only whichever half happens to be checked first — under P6 it reported "it
     * toggled" and said nothing at all about whether the note scrolled. One assertion over a pair
     * reports both every time.
     */
    @Test
    fun aDragFromTheBoxScrollsTheNoteAndDoesNotToggle() {
        val state = editor(LONG_NOTE)
        val before = state.text.toString()
        val topBefore = rule.onNodeWithContentDescription(OPEN_TASK).getUnclippedBoundsInRoot().top

        rule.onNodeWithContentDescription(OPEN_TASK).performTouchInput {
            swipe(start = center, end = center - Offset(0f, 600f), durationMillis = 300)
        }
        rule.waitForIdle()
        val topAfter = rule.onNodeWithContentDescription(OPEN_TASK).getUnclippedBoundsInRoot().top

        assertEquals(
            "a drag starting on a checkbox must scroll the note and must not toggle it " +
                "(the box moved from $topBefore to $topAfter)",
            Toggled(false) to Scrolled(true),
            Toggled(state.text.toString() != before) to Scrolled(topAfter < topBefore),
        )
    }

    /** Named wrappers so the failure message reads `Toggled(true)`, not a bare `true`. */
    private data class Toggled(val value: Boolean)

    private data class Scrolled(val value: Boolean)

    // --- the tap just outside (P7) -------------------------------------------

    /**
     * A tap on the task's own words is an ordinary caret placement. The overlay is three characters
     * wide precisely so it cannot steal one; widening it to the line is P7.
     *
     * **The probe is anchored to the text field, not to the overlay, and the first assertion is the
     * reason.** The obvious version computes the tap as `overlay.right + 12.dp` — and then widening
     * the overlay moves the probe with it, so P7 *passed*. The test was measuring a point defined by
     * the thing it was supposed to falsify. The x now comes from the field and a fixed inset, and
     * the test states the condition under which it means anything: the probe must land **outside**
     * the overlay. P7 violates that condition and reports it in those words.
     *
     * The y still comes from the overlay's top, which is safe because P7 moves the horizontal extent
     * and leaves the vertical alone — the axis a plant moves must not be the axis the probe reads.
     */
    @Test
    fun aTapOnTheTaskTextPlacesTheCaretAndDoesNotToggle() {
        val state = editor(NOTE)
        val before = state.text.toString()
        val box = rule.onNodeWithContentDescription(OPEN_TASK).getUnclippedBoundsInRoot()
        val field = rule.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()

        // Well inside "count them" on a 360dp+ screen, and independent of the overlay's own width.
        val probe = field.left + TEXT_PROBE_INSET
        assertTrue(
            "the probe at $probe is inside the overlay (${box.left}..${box.right}), so this test " +
                "would be tapping the target it is meant to miss — the overlay is too wide",
            probe > box.right,
        )

        val x = with(rule.density) { (probe - field.left).toPx() }
        val y = with(rule.density) { ((box.top + box.bottom) / 2 - field.top).toPx() }
        rule.onNode(hasSetTextAction()).performTouchInput { click(Offset(x, y)) }
        rule.waitForIdle()

        assertEquals("a tap outside the box must not toggle", before, state.text.toString())
        assertNotEquals("the tap never reached the text field", 0, state.selection.start)
        assertTrue(
            "the caret landed at ${state.selection.start}, not in the task's text",
            state.selection.start in NOTE.indexOf("count them")..NOTE.length,
        )
    }

    // --- TalkBack (P8) -------------------------------------------------------

    /**
     * **Measured, not asserted against a floor.** The overlay is the box's own glyph extent, so its
     * size is whatever the reading font makes it — it moves with the Phase 14 font-size setting and
     * with the user's display scale. Pinning a number here would fail on the first of those changes
     * for no defect. The number is printed so a device run records it; the ceiling it implies is
     * written up in `TaskBoxOverlay`'s KDoc and the build prompt.
     */
    @Test
    fun theBoxTargetSizeIsRecorded() {
        editor(NOTE)
        val box = rule.onNodeWithContentDescription(OPEN_TASK).getUnclippedBoundsInRoot()
        println("DUCK task box target = ${box.right - box.left} x ${box.bottom - box.top}")
        // The only floor asserted is the one that is a defect at any font size: a zero-area target.
        assertTrue("the overlay has no area", box.right > box.left && box.bottom > box.top)
    }

    @Test
    fun theBoxIsACheckboxWithStateForTalkBack() {
        editor(NOTE)
        val role = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

        // The role is what makes TalkBack say "checkbox" rather than reading an anonymous control;
        // the toggle state is what makes it say "not checked". Both, or the node is half-announced.
        rule.onNodeWithContentDescription(OPEN_TASK).assert(role).assertIsOff()
        rule.onNodeWithContentDescription(DONE_TASK).assert(role).assertIsOn()
    }

    // --- what has no overlay -------------------------------------------------

    @Test
    fun aFencedTaskHasNoOverlay() {
        editor(NOTE)
        // Positive control first: the mechanism that finds overlays works on this very body.
        rule.onNodeWithContentDescription(OPEN_TASK).assertExists()
        rule.onNodeWithContentDescription(FENCED_TASK).assertDoesNotExist()
    }

    private companion object {
        /** Into the middle of "count them", clear of a three-character box at the line start. */
        val TEXT_PROBE_INSET = 48.dp

        const val OPEN_TASK = "count them"
        const val DONE_TASK = "feed them"
        const val FENCED_TASK = "not a task"

        val NOTE = """
            # Ducks

            - [x] feed them
            - [ ] count them

            ```
            - [ ] not a task
            ```
        """.trimIndent()

        /** In `# Ducks`, well away from either task line, so a moved caret is unambiguous. */
        val CARET_HOME = NOTE.indexOf("Ducks")

        val LONG_NOTE = NOTE + "\n\n" + (1..80).joinToString("\n") { "filler line $it" }
    }
}

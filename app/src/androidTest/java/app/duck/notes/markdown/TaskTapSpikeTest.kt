package app.duck.notes.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.markdown.MarkdownOutputTransformation
import app.duck.notes.core.markdown.MarkdownStyles
import app.duck.notes.core.theme.DuckThemeSpec
import app.duck.notes.core.theme.duckType
import app.duck.notes.domain.markdown.MarkdownTextCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The spike Phase 8c-b is gated on. Nothing else in the phase is written until these pass.**
 *
 * Phase 8's spec says a task checkbox toggles on tap. 8c-a's device screenshots showed there was
 * nothing on screen to tap: `MarkdownScanner` emits one marker range covering `- [ ] ` entire, and
 * `SpanPlanner` concealed it whole, so `- [ ] milk` rendered as `milk`.
 *
 * **The blocker is closed and this file stays**, with its first measurement inverted to assert the
 * behaviour that replaced it. Keeping the history in one place is the point: the reasoning that
 * chose a carve-out over a replacement glyph only makes sense next to the measurement that forced
 * it, and a deleted spike takes that with it.
 *
 * Three measurements:
 *
 * 1. [aTapOnTheRenderedBoxLandsInsideIt] — **now inverted; the box is addressable.** Before the
 *    carve-out this asserted the opposite, because the marker's six source offsets collapsed onto
 *    one rendered position. Not *unreachable*: a tap at the line's left edge landed on source 0,
 *    where Compose maps a rendered position back to the start of a deleted range. The information
 *    was shared, not destroyed — which is exactly why splitting the concealed range was enough.
 * 2. [aRenderedOffsetHasABoundingBoxATapCanBeTestedAgainst] — with the box left in the rendered
 *    text, `TextLayoutResult.getBoundingBox` gives a rect a tap can be tested against. This is the
 *    geometry the overlay is positioned from, and it runs **source → rendered**, the direction
 *    `SpanPlanner.renderedStyles` already provides. Nothing here needs Compose's reverse mapping.
 * 3. [aToggleableOverlayConsumesTheTapButNotTheDrag] — `Modifier.toggleable` swallows a tap while
 *    letting a drag reach the scroll container underneath. That is the whole reason the design is an
 *    overlay rather than a `PointerEventPass.Initial` interceptor: an interceptor would have to
 *    hand-roll slop and timeout to avoid eating scrolls, and would expose no accessibility node.
 *
 * **These are tripwires, not falsifiable guards.** They assert *Compose's* behaviour, so no edit to
 * this repo can make 2 or 3 fail — the same status the codebase already assigns
 * [OutputTransformationCaretSpikeTest]. Do not delete them; they are what goes red on a Compose
 * upgrade that moves the ground under the toggle.
 */
@RunWith(AndroidJUnit4::class)
class TaskTapSpikeTest {

    @get:Rule
    val rule = createComposeRule()

    private var layout: TextLayoutResult? = null

    private fun editor(initial: String, transformed: Boolean): TextFieldState {
        val state = TextFieldState(initial)
        rule.setContent {
            BasicTextField(
                state = state,
                outputTransformation = if (!transformed) null else MarkdownOutputTransformation(
                    MarkdownTextCache(),
                    MarkdownStyles(DuckThemeSpec.DuckRed.colors(isDark = false), duckType()),
                ),
                onTextLayout = { getResult -> layout = getResult() },
                modifier = Modifier.fillMaxWidth().testTag(FIELD),
            )
        }
        rule.waitForIdle()
        return state
    }

    // --- 1. the blocker ------------------------------------------------------

    /**
     * **The blocker, now inverted: the box IS addressable.** This is the assertion the carve-out was
     * built to make true, and it is the same measurement that proved it false beforehand.
     *
     * ### What it used to say, and why the wording matters
     *
     * Before the carve-out this asserted the opposite — that no tap could land in source 1..5 — and
     * it did so because all six of `- [ ] `'s offsets collapsed onto one rendered position. **Not
     * "unreachable": collapsed.** A tap at the line's left edge landed on source **0**, the start of
     * the deleted range, because that is where Compose maps a rendered position back to. The
     * information was shared, not destroyed, and that distinction is exactly why splitting the
     * concealed range was sufficient. Had it really been destroyed, 8c-b would have needed a
     * replacement-glyph transformation with a length-changing offset map.
     *
     * ### The sweep is across the LINE, not the field, and that is a correction
     *
     * The first version swept the field's full width in 21 steps — about 54px apart on this device,
     * while the rendered `[ ] milk` is 106px wide. It stepped clean over a 40px box. After the
     * carve-out it therefore stayed **green while asserting the box was unreachable**, which was
     * true of nothing but the sweep's resolution. A probe coarser than the thing it is probing for
     * reports absence either way.
     */
    @Test
    fun aTapOnTheRenderedBoxLandsInsideIt() {
        val state = editor(TASK_NOTE, transformed = true)
        val lineRight = layout?.getLineRight(0)
        assertNotNull("onTextLayout never fired", lineRight)

        val landed = sortedSetOf<Int>()
        // Between taps the caret is parked back off the line, or the first tap reveals the markers
        // and every later one measures a different rendering — SETUP.md trap 6.
        for (step in 0..SWEEP) {
            rule.runOnUiThread { state.edit { selection = TextRange(CARET_AWAY) } }
            rule.waitForIdle()
            rule.onNodeWithTag(FIELD).performTouchInput {
                click(Offset(1f + (lineRight!! - 2f) * step / SWEEP, top + 8f))
            }
            rule.waitForIdle()
            landed += state.selection.start
        }

        assertTrue("the sweep produced one offset ($landed) — it never varied", landed.size > 1)
        assertTrue(
            "no tap reached the box at source $BOX_START..${BOX_END - 1}. The carve-out is what " +
                "makes it addressable; if this is red the box is concealed again. Landed: $landed",
            landed.any { it in BOX_START until BOX_END },
        )
        // The bullet still collapses. `- ` is deleted, so source 0 and 1 share one rendered position
        // and a tap at the line's start still resolves to 0 — the original finding, still true, now
        // confined to the two characters that are still hidden.
        assertTrue("the concealed bullet no longer collapses onto its start — landed: $landed", 0 in landed)
        assertTrue("no tap reached the visible text", landed.any { it >= 6 })
    }

    /**
     * **What a tap at the same pixel gives with and without the conceal — measured side by side.**
     *
     * Clicking one field and reading one number cannot tell "the transformation is not applied" from
     * "the transformation is applied and Compose maps this rendered offset back to the start of the
     * deleted range". Both explain `selection == 0` at the left edge. So this lays two identical
     * fields out at the same width and clicks the same x on each: if the conceal reaches the
     * rendering, the two offsets must differ, because one field is laying out four characters and the
     * other ten.
     *
     * Written after the first version of this spike asserted a single number and drew the wrong
     * conclusion from it.
     */
    @Test
    fun theSameTapLandsDifferentlyWithAndWithoutTheConceal() {
        var plainLayout: TextLayoutResult? = null
        var concealedLayout: TextLayoutResult? = null
        val plain = TextFieldState(TASK_NOTE, TextRange(CARET_AWAY))
        val concealed = TextFieldState(TASK_NOTE, TextRange(CARET_AWAY))
        rule.setContent {
            Column(Modifier.fillMaxWidth()) {
                BasicTextField(
                    state = plain,
                    onTextLayout = { plainLayout = it() },
                    modifier = Modifier.fillMaxWidth().testTag(FIELD),
                )
                BasicTextField(
                    state = concealed,
                    outputTransformation = MarkdownOutputTransformation(
                        MarkdownTextCache(),
                        MarkdownStyles(DuckThemeSpec.DuckRed.colors(isDark = false), duckType()),
                    ),
                    onTextLayout = { concealedLayout = it() },
                    modifier = Modifier.fillMaxWidth().testTag(OVERLAY),
                )
            }
        }
        rule.waitForIdle()

        val plainRight = plainLayout?.getLineRight(0)
        val concealedRight = concealedLayout?.getLineRight(0)
        assertNotNull("onTextLayout never fired for the plain field", plainRight)
        assertNotNull("onTextLayout never fired for the concealed field", concealedRight)

        // Comparing widths rather than tap offsets because a tap past the end of BOTH strings
        // returns the same offset either way — which is how the first version of this measurement
        // drew the wrong conclusion.
        //
        // The expected difference is not a tuned ratio, it is **measured from the raw layout**: the
        // conceal removes exactly `- `, so the concealed line must be narrower by the width of
        // source 0..1 in the plain line. The first version asserted `< plainRight * 0.75f`, which
        // was calibrated against the six characters the marker used to delete; the carve-out took it
        // down to two and the ratio went red for no defect. A self-calibrating claim cannot rot that
        // way, and it says something much stronger than "narrower".
        val bulletWidth = plainLayout?.getHorizontalPosition(BOX_START, usePrimaryDirection = true)
        assertNotNull("could not measure the bullet's width", bulletWidth)
        assertEquals(
            "the concealed line must be exactly `- ` narrower than the raw one " +
                "($plainRight - $bulletWidth), or the conceal is not reaching layout as planned",
            plainRight!! - bulletWidth!!,
            concealedRight!!,
            1f,
        )
    }

    // --- 2. the geometry the overlay is positioned from ----------------------

    @Test
    fun aRenderedOffsetHasABoundingBoxATapCanBeTestedAgainst() {
        // No transformation, so rendered == source and offsets 2..4 really are `[ ]`.
        val state = editor(TASK, transformed = false)
        val result = layout
        assertNotNull("onTextLayout never fired", result)

        val open = result!!.getBoundingBox(BOX_START)
        val close = result.getBoundingBox(BOX_END - 1)
        assertTrue("the box glyphs have no width", close.right > open.left)
        assertTrue("the box glyphs have no height", open.height > 0f)

        rule.onNodeWithTag(FIELD).performTouchInput {
            click(Offset((open.left + close.right) / 2f, open.center.y))
        }
        rule.waitForIdle()

        // If this reads `got 10` (end-of-text) on a real geometry change, first rule out a bad
        // emulator snapshot before suspecting the overlay: `10` is exactly what a single unmargined
        // tap produces when the field silently never focused. Diagnosed 2026-08-04 — a session
        // teardown hard-killed the emulator mid-suite, corrupting Pixel_7's `default_boot` snapshot.
        // Every subsequent snapshot boot reported boot_completed=1 after one poll, then went offline
        // within seconds; a cold boot (-no-snapshot-load -no-snapshot-save) was stable across five
        // runs and this test passed every time. Unlike [aTapOnTheRenderedBoxLandsInsideIt], which
        // sweeps 21 taps and tolerates one bad one, this test asserts on a single tap and has no
        // such margin — that is why the same degraded device broke this test and not its sibling.
        assertTrue(
            "a tap at the box's own bounding box must land inside it, got ${state.selection.start}",
            state.selection.start in BOX_START..BOX_END,
        )
    }

    // --- 3. the primitive the overlay is built on ----------------------------

    @Test
    fun aToggleableOverlayConsumesTheTapButNotTheDrag() {
        val state = TextFieldState(LONG_NOTE)
        var toggled = false
        lateinit var scroll: androidx.compose.foundation.ScrollState

        rule.setContent {
            scroll = rememberScrollState()
            var checked by mutableStateOf(false)
            // The overlay sits INSIDE the scroll container, which is not a detail: a `toggleable`
            // cancels its gesture on drag and lets the drag reach a scrollable **ancestor**. Placed
            // as a sibling of the scrolling column it has no such ancestor, the drag reaches
            // nothing, and the note does not scroll. The first run of this spike did exactly that
            // and read as "toggleable swallows drags" — a test-construction bug wearing the costume
            // of a Compose finding. Production has the same requirement anyway: the overlay must
            // scroll with the text it is pinned to.
            Column(Modifier.height(200.dp).verticalScroll(scroll)) {
                Box {
                    BasicTextField(state = state, modifier = Modifier.fillMaxWidth())
                    // Stands in for a real box overlay: same primitive, fixed geometry so this
                    // measures `toggleable` rather than `getBoundingBox`, which test 2 covers.
                    Box(
                        Modifier
                            .offset(x = 8.dp, y = 8.dp)
                            .size(24.dp)
                            .background(Color.Transparent)
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { checked = it; toggled = true },
                            )
                            .testTag(OVERLAY),
                    )
                }
            }
        }
        rule.waitForIdle()
        state.edit { selection = TextRange(0) }
        rule.waitForIdle()

        // A tap on the overlay toggles, and the caret underneath does not move.
        rule.onNodeWithTag(OVERLAY).performTouchInput { click() }
        rule.waitForIdle()
        assertTrue("the overlay did not receive the tap", toggled)
        assertEquals("the tap reached the text field and moved the caret", 0, state.selection.start)
        assertEquals("a tap must not scroll", 0, scroll.value)

        // A drag STARTING on the overlay must scroll the note and must not toggle.
        toggled = false
        rule.onNodeWithTag(OVERLAY).performTouchInput {
            swipe(start = center, end = center - Offset(0f, 400f), durationMillis = 300)
        }
        rule.waitForIdle()

        assertFalse("a drag must not toggle — it is a scroll, not a tap", toggled)
        assertTrue("the drag was swallowed instead of scrolling the note", scroll.value > 0)
    }

    private companion object {
        const val FIELD = "duck-task-field"
        const val OVERLAY = "duck-task-overlay"

        /** `- [ ] milk`: the bullet is 0..1, the box is 2..4, the space 5, the text from 6. */
        const val TASK = "- [ ] milk"
        const val BOX_START = 2
        const val BOX_END = 5

        /**
         * The task plus a tail, and **the tail is load-bearing**.
         *
         * `SpanPlanner` reveals a token's markers whenever the selection *touches* it, and a task
         * token spans the **entire line** — so on a one-line body the caret is always inside it and
         * the marker never conceals. The first version of this spike used a bare `- [ ] milk`,
         * measured two identical line widths, and nearly recorded "the transformation does not reach
         * layout" as a Compose finding. It was the fixture. `GoldenSpanTest` parks its caret at
         * `Int.MAX_VALUE / 2` for the same reason.
         */
        const val TASK_NOTE = "- [ ] milk\n\nplain tail line"

        /** Well past the task token, so its markers conceal. */
        val CARET_AWAY = TASK_NOTE.length

        /** Taps across the width of the task line. Coarse on purpose; the claim is set membership. */
        const val SWEEP = 20

        val LONG_NOTE = (1..60).joinToString("\n") { "line $it of a note that must scroll" }
    }
}

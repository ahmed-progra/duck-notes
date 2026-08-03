package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpanPlanner.taskBoxes] — the arithmetic the editor's tap targets are positioned from.
 *
 * Every assertion here is written **against the concealed string**, not against a number pulled from
 * the implementation. `boxIn(body, caret)` renders the body the way the transformation does and then
 * reads the box back out of it, so a wrong rebase shows up as `"] m"` rather than as an integer that
 * happens to differ from another integer. That is the difference between this catching a real
 * off-by-one and it pinning whatever the code did on the day it was written.
 *
 * The caret matters and is a parameter for that reason — see SETUP.md trap 6. A task token spans its
 * whole line, so a caret anywhere on the line reveals the markers and the rendered offsets move.
 * [CARET_AWAY] is the parked position; the on-line cases pass an explicit one.
 */
class TaskBoxTest {

    private fun scan(body: String) = MarkdownTextCache.rescan(ScanResult.Empty, body).tokens

    private fun boxesIn(body: String, caret: Int = CARET_AWAY): List<TaskBox> {
        val tokens = scan(body)
        return SpanPlanner.taskBoxes(body, tokens, SpanPlanner.plan(tokens, caret, caret))
    }

    /** The body as the editor renders it at [caret] — conceal applied, exactly as the buffer does. */
    private fun rendered(body: String, caret: Int = CARET_AWAY): String {
        val plan = SpanPlanner.plan(scan(body), caret, caret)
        return StringBuilder(body).apply {
            plan.conceal.forEach { delete(it.first, it.last + 1) }
        }.toString()
    }

    /** What [TaskBox.rendered] actually points at in the rendered text. Must always be the box. */
    private fun renderedText(body: String, caret: Int = CARET_AWAY) =
        boxesIn(body, caret).map { rendered(body, caret).substring(it.rendered.first, it.rendered.last + 1) }

    // --- the rendered offsets point at the box ------------------------------

    @Test
    fun theRenderedRangeLandsOnTheBoxItself() {
        assertEquals(listOf("[ ]"), renderedText("- [ ] milk"))
        assertEquals(listOf("[x]"), renderedText("- [x] milk"))
    }

    @Test
    fun extraSpacingDoesNotMoveTheRenderedRangeOffTheBox() =
        assertEquals(listOf("[ ]"), renderedText("-   [ ] milk"))

    /** Markers stack, and the quote's own conceal shifts the task's box left by two. */
    @Test
    fun aTaskInsideAQuoteStillPointsAtItsBox() =
        assertEquals(listOf("[ ]"), renderedText("> - [ ] a task inside a quote"))

    @Test
    fun everyTaskInANoteGetsItsOwnBox() =
        assertEquals(listOf("[x]", "[ ]"), renderedText("- [x] feed them\n- [ ] count them"))

    /**
     * **The caret changes the rendered offsets and must not change the source ones.**
     *
     * With the caret on the line the markers reveal, so `- ` is no longer deleted and the box sits
     * two characters further right. An overlay positioned from a stale plan would sit on `] `.
     */
    @Test
    fun theRenderedRangeFollowsTheCaretAndTheSourceRangeDoesNot() {
        val body = "- [ ] milk"
        val away = boxesIn(body).single()
        val onLine = boxesIn(body, caret = 7).single()

        assertEquals("the source box never moves", away.source, onLine.source)
        assertEquals(2..4, away.source)
        assertTrue(
            "revealing the marker must push the box right, got ${away.rendered} then ${onLine.rendered}",
            onLine.rendered.first > away.rendered.first,
        )
        // Both still land on the box in their own rendering — the property, not the numbers.
        assertEquals(listOf("[ ]"), renderedText(body))
        assertEquals(listOf("[ ]"), renderedText(body, caret = 7))
    }

    // --- what is not a box ---------------------------------------------------

    @Test
    fun aTaskInsideAFenceHasNoBox() = assertEquals(emptyList<TaskBox>(), boxesIn("```\n- [ ] milk\n```"))

    @Test
    fun aPlainBulletHasNoBox() = assertEquals(emptyList<TaskBox>(), boxesIn("- milk"))

    // --- the fields the overlay declares -------------------------------------

    @Test
    fun doneTracksTheKind() {
        assertEquals(listOf(false, true), boxesIn("- [ ] one\n- [x] two").map { it.done })
        assertEquals(listOf(true), boxesIn("- [X] upper").map { it.done })
    }

    /** The announced label is the task's words, with no marker in it. */
    @Test
    fun textIsTheTaskWithoutItsMarker() {
        assertEquals(listOf("count them"), boxesIn("- [ ] count them").map { it.text })
        assertEquals(listOf("count them"), boxesIn("-   [ ] count them").map { it.text })
        assertEquals(listOf("a task inside a quote"), boxesIn("> - [ ] a task inside a quote").map { it.text })
    }

    /** A task with nothing after the marker is legal while typing and must not throw. */
    @Test
    fun anEmptyTaskHasAnEmptyLabelAndStillHasABox() {
        val box = boxesIn("- [ ] ").single()
        assertEquals("", box.text)
        assertEquals(2..4, box.source)
    }

    /**
     * Positive control for the two `emptyList` assertions above: a walk that resolved nothing would
     * report those green. The corpus really does produce boxes.
     */
    @Test
    fun theCorpusProducesBoxesAtAll() {
        val total = MarkdownCorpus.samples.sumOf { (_, body) -> boxesIn(body).size }
        assertTrue("no sample produced a task box — the emptiness assertions prove nothing", total > 0)
    }

    private companion object {
        /** Far past any sample, so every node counts as "caret elsewhere". Matches `GoldenSpanTest`. */
        const val CARET_AWAY = Int.MAX_VALUE / 2
    }
}

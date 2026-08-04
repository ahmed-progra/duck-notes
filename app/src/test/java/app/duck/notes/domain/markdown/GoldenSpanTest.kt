package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The golden suite: one hand-written body per case, with its expected rendering spelled out.
 *
 * Its own case list, **not** a sweep over `MarkdownCorpus` — that is `ConcealInvariantTest`'s job,
 * and the two are complementary rather than redundant: this one is narrow and exact where that one
 * is broad and shallow. (This said "the 20-sample golden suite" for several phases while holding
 * neither 20 cases nor any corpus samples. A count in prose has nothing enforcing it and only rots;
 * see `MarkdownCorpus`' own KDoc, which records the same lesson from the other direction.)
 *
 * Asserts the [SpanPlan] rather than a rendered `AnnotatedString`. A rendered comparison would pin
 * these tests to theme colours and font sizes, so Phase 5 and Phase 14 would break them without any
 * markdown behaviour having changed. The plan is the actual logic; the styling is a lookup table.
 *
 * Each expectation is written as the *concealed* rendering — what the reader sees with the caret
 * elsewhere — plus the styled ranges. Reading the expected string tells you at a glance whether the
 * conceal rules are right.
 */
class GoldenSpanTest {

    private fun scan(body: String) = MarkdownTextCache.rescan(ScanResult.Empty, body).tokens

    /** The body as it renders with the caret parked off-node — i.e. all markers hidden. */
    private fun concealed(body: String): String {
        val conceal = SpanPlanner.plan(scan(body), CARET_AWAY, CARET_AWAY).conceal
        return StringBuilder(body).apply { conceal.forEach { delete(it.first, it.last + 1) } }.toString()
    }

    private fun stylesOf(body: String) =
        SpanPlanner.plan(scan(body), CARET_AWAY, CARET_AWAY).styles
            .map { it.style to body.substring(it.start, it.end) }

    private fun assertConceals(expected: String, body: String) =
        assertEquals("body=<$body>", expected, concealed(body))

    // --- Inline emphasis -----------------------------------------------------

    @Test
    fun plainProseIsUntouched() = assertConceals("just some prose", "just some prose")

    @Test
    fun boldMarkersHide() = assertConceals("some bold text", "some **bold** text")

    @Test
    fun italicStarMarkersHide() = assertConceals("some italic text", "some *italic* text")

    @Test
    fun italicUnderscoreMarkersHide() = assertConceals("some italic text", "some _italic_ text")

    @Test
    fun strikeMarkersHide() = assertConceals("some struck text", "some ~~struck~~ text")

    @Test
    fun highlightMarkersHide() = assertConceals("some marked text", "some ==marked== text")

    @Test
    fun codeSpanTicksHide() = assertConceals("call duck() now", "call `duck()` now")

    @Test
    fun adjacentNodesEachHideTheirOwnMarkers() = assertConceals("abc", "**a**_b_==c==")

    @Test
    fun markersAtOffsetZeroHide() = assertConceals("bold at the very start", "**bold at the very start**")

    @Test
    fun unclosedDelimitersStayVisible() =
        assertConceals("a lone ** marker and a lone ` tick", "a lone ** marker and a lone ` tick")

    // --- Links, images, wiki links, tags -------------------------------------

    @Test
    fun linkKeepsTextAndHidesDestination() =
        assertConceals("see the docs here", "see [the docs](https://duck.example) here")

    @Test
    fun imageKeepsAltAndHidesSource() = assertConceals("a duck inline", "![a duck](duck.png) inline")

    @Test
    fun wikiLinkKeepsItsTitle() = assertConceals("see Another Note here", "see [[Another Note]] here")

    @Test
    fun tagStaysFullyVisible() =
        // The `#` is part of the tag, not syntax wrapped around it, so nothing is concealed.
        assertConceals("filed under #projects/duck today", "filed under #projects/duck today")

    @Test
    fun closedFormTagStaysFullyVisible() =
        assertConceals("filed under #multi word tag# today", "filed under #multi word tag# today")

    // --- Block markers -------------------------------------------------------

    @Test
    fun headingMarkerHides() = assertConceals("A heading", "# A heading")

    @Test
    fun deepHeadingMarkerHides() = assertConceals("Third level", "### Third level")

    @Test
    fun quoteMarkerHides() = assertConceals("a quoted line", "> a quoted line")

    @Test
    fun bulletMarkerHides() = assertConceals("a bullet item", "- a bullet item")

    @Test
    fun orderedMarkerHides() = assertConceals("a numbered item", "1. a numbered item")

    /**
     * **Corrected in 8c-b, not loosened.** This read `assertConceals("unfinished", …)` from Phase 4,
     * when tasks were display-only and pinned the box concealing to nothing *as intended behaviour*.
     * 8c-b makes the box tappable, so the rendering genuinely changed: `SpanPlanner` now carves the
     * box out of the marker and only the bullet hides. The expectation moved because the behaviour
     * moved — the assertion is no weaker than it was, and still names the exact rendered string.
     *
     * Same note applies to [doneTaskCheckboxRenders], [stackedMarkersHideAroundTheBox] and
     * [aRealisticNoteRendersAsExpected].
     */
    @Test
    fun taskCheckboxRenders() = assertConceals("[ ] unfinished", "- [ ] unfinished")

    @Test
    fun doneTaskCheckboxRenders() = assertConceals("[x] finished", "- [x] finished")

    /** The quote's `> ` still hides entirely; only the task's marker is the one that splits. */
    @Test
    fun stackedMarkersHideAroundTheBox() =
        assertConceals("[ ] a task inside a quote", "> - [ ] a task inside a quote")

    /** The space after `]` is kept, or every task line would read `[ ]text`. */
    @Test
    fun theSpaceAfterTheBoxSurvivesTheCarveOut() =
        assertConceals("[ ] milk", "-   [ ] milk")

    /**
     * The box is where the scanner put it, not at `markerStart + 2`.
     *
     * ## It also carries the emission order, and the name does not say so
     *
     * The assertion is an **ordered** list, so it pins a second property nothing else does: the task
     * style is emitted **before** its box. That is what makes
     * `MarkdownStylesTest.theDoneBoxIsNotStruckThroughByTheTaskItSitsOn` legitimate — it hard-codes
     * `task.merge(box)`, and the only reason that expression matches what the screen does is this
     * ordering in `SpanPlanner.plan`. Flip the emission order and `merge` composes the other way:
     * `TASK_DONE`'s `LineThrough` would win and the checkbox would paint struck through, which is the
     * exact defect `TASK_BOX_DONE`'s explicit `TextDecoration.None` exists to prevent. That test
     * cannot see the flip — it never calls `SpanPlanner` — and **this one goes red.**
     *
     * Recorded because the pairing is otherwise invisible from both sides, which is the *misnamed*
     * class in `DUCK_NOTES_BUILD_PROMPT.md`: a check whose name describes one property while it
     * quietly holds two. Nothing here should be "simplified" to an unordered comparison, and this
     * test is not redundant with the `MarkdownStyles` one even though both mention the box.
     */
    @Test
    fun theBoxIsPaintedAsItsOwnRange() {
        assertEquals(
            listOf(StyleKey.TASK_OPEN to "-   [ ] milk", StyleKey.TASK_BOX_OPEN to "[ ]"),
            stylesOf("-   [ ] milk"),
        )
        assertEquals(
            listOf(StyleKey.TASK_DONE to "- [x] done", StyleKey.TASK_BOX_DONE to "[x]"),
            stylesOf("- [x] done"),
        )
    }

    /**
     * A task line must not change shape as the caret arrives on it. The markers reveal, but the box
     * is painted at every caret position — the overlay is positioned from its rendered offsets, so a
     * box that appeared only when unfocused would move the tap target under the user.
     */
    @Test
    fun theBoxIsStillPaintedWithTheCaretOnTheLine() {
        val body = "- [ ] milk"
        val boxes = SpanPlanner.plan(scan(body), 7, 7).styles.filter { it.style == StyleKey.TASK_BOX_OPEN }
        assertEquals(listOf("[ ]"), boxes.map { body.substring(it.start, it.end) })
    }

    @Test
    fun dividerKeepsItsRule() = assertConceals("---", "---")

    // --- Fences --------------------------------------------------------------

    @Test
    fun fencedBlockIsNotStyledAsMarkdown() {
        val body = "```\nnot **markdown** in here\n```"
        assertConceals(body, body)
        val kinds = stylesOf(body).map { it.first }.toSet()
        assertEquals(setOf(StyleKey.CODE_BLOCK), kinds)
    }

    // --- Styling -------------------------------------------------------------

    @Test
    fun boldStylesOnlyTheNodeAndItsMarkers() =
        assertEquals(listOf(StyleKey.BOLD to "**bold**"), stylesOf("some **bold** text"))

    @Test
    fun headingCarriesItsLevel() {
        val levels = SpanPlanner.plan(scan("### Third level"), CARET_AWAY, CARET_AWAY).styles
            .filter { it.style == StyleKey.HEADING }
            .map { it.level }
        assertEquals(listOf(3), levels)
    }

    @Test
    fun revealedMarkersArePaintedRatherThanHidden() {
        val body = "some **bold** text"
        val at = body.indexOf("bold")
        val plan = SpanPlanner.plan(scan(body), at, at)
        assertEquals("nothing hides while the caret is inside", emptyList<IntRange>(), plan.conceal)
        assertEquals(
            "both delimiters are painted as revealed syntax",
            listOf("**", "**"),
            plan.styles.filter { it.style == StyleKey.MARKER }.map { body.substring(it.start, it.end) },
        )
    }

    /** Corrected in 8c-b with the other three; see [taskCheckboxRenders] for why. */
    @Test
    fun aRealisticNoteRendersAsExpected() = assertConceals(
        "Notes on ducks\n\nSee Pond and #birds, or code.\n\n[x] feed them\n[ ] count them",
        "# Notes on **ducks**\n\nSee [[Pond]] and #birds, or `code`.\n\n- [x] feed them\n- [ ] count them",
    )

    private companion object {
        /** Far past any sample, so every node counts as "caret elsewhere". */
        const val CARET_AWAY = Int.MAX_VALUE / 2
    }
}

package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 20-sample golden suite.
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

    @Test
    fun taskCheckboxHides() = assertConceals("unfinished", "- [ ] unfinished")

    @Test
    fun doneTaskCheckboxHides() = assertConceals("finished", "- [x] finished")

    @Test
    fun stackedMarkersAllHide() = assertConceals("a task inside a quote", "> - [ ] a task inside a quote")

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

    @Test
    fun aRealisticNoteRendersAsExpected() = assertConceals(
        "Notes on ducks\n\nSee Pond and #birds, or code.\n\nfeed them\ncount them",
        "# Notes on **ducks**\n\nSee [[Pond]] and #birds, or `code`.\n\n- [x] feed them\n- [ ] count them",
    )

    private companion object {
        /** Far past any sample, so every node counts as "caret elsewhere". */
        const val CARET_AWAY = Int.MAX_VALUE / 2
    }
}

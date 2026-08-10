package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FocusParagraph.at] and the DIMMED emission it feeds in [SpanPlanner.plan].
 *
 * The two dimming tests use an **independent oracle** for the caret's paragraph bounds (found by
 * plain `indexOf` on the fixture) rather than trusting [FocusParagraph.at]'s own answer — so forcing
 * `at` to return `null` reddens `theCaretsOwnParagraphStaysUndimmed` by dimming the paragraph, not by
 * an NPE. Tokens are `emptyList`: the dim ranges are a function of the paragraph and the length only,
 * so a scan would add coupling without adding coverage.
 */
class FocusParagraphTest {

    // --- FocusParagraph.at ---------------------------------------------------

    @Test
    fun aSingleLineNoteIsAllOneParagraph() {
        val text = "hello world"
        assertEquals(0 until text.length, FocusParagraph.at(text, 3))
        assertEquals(0 until text.length, FocusParagraph.at(text, text.length))
    }

    @Test
    fun theParagraphIsBoundedByBlankLines() {
        val text = "alpha beta\n\ngamma delta epsilon\n\nzeta eta"
        val start = text.indexOf("gamma")
        val end = text.indexOf("epsilon") + "epsilon".length
        assertEquals(start until end, FocusParagraph.at(text, text.indexOf("delta")))
    }

    @Test
    fun aMultiLineParagraphWithNoBlankBetweenLinesStaysWhole() {
        val text = "line one\nline two\nline three"
        // No blank line anywhere, so the caret's paragraph is the whole thing.
        assertEquals(0 until text.length, FocusParagraph.at(text, text.indexOf("two")))
    }

    @Test
    fun aCaretOnABlankLineHasNoParagraph() {
        val text = "alpha\n\nbeta"
        // Index of the blank line between the two paragraphs (right after the first '\n').
        assertNull(FocusParagraph.at(text, text.indexOf("alpha") + "alpha".length + 1))
    }

    @Test
    fun emptyTextHasNoParagraph() {
        assertNull(FocusParagraph.at("", 0))
    }

    // --- the DIMMED emission in SpanPlanner.plan -----------------------------

    private val fixture = "alpha beta\n\ngamma delta epsilon\n\nzeta eta"
    private val caret = fixture.indexOf("delta")

    /** Independent of [FocusParagraph.at]: the caret's paragraph, straight from the fixture. */
    private val caretParagraph =
        fixture.indexOf("gamma") until (fixture.indexOf("epsilon") + "epsilon".length)

    private fun dimmedRanges() = SpanPlanner.plan(
        tokens = emptyList(),
        selectionStart = caret,
        selectionEnd = caret,
        focusMode = true,
        focusedParagraph = FocusParagraph.at(fixture, caret),
        sourceLength = fixture.length,
    ).styles.filter { it.style == StyleKey.DIMMED }

    @Test
    fun theCaretsOwnParagraphStaysUndimmed() {
        val overlapping = dimmedRanges().filter {
            it.start <= caretParagraph.last && it.end > caretParagraph.first
        }
        assertEquals(
            "no DIMMED range may touch the caret's own paragraph $caretParagraph — that is the " +
                "line the user is typing on. Overlapping: $overlapping",
            emptyList<StyledRange>(),
            overlapping,
        )
    }

    @Test
    fun everythingOutsideTheCaretsParagraphIsDimmed() {
        val dimmed = dimmedRanges()
        fun covered(offset: Int) = dimmed.any { offset in it.start until it.end }
        assertTrue("the first paragraph must dim", covered(0))
        assertTrue("the last paragraph must dim", covered(fixture.indexOf("zeta")))
    }

    @Test
    fun focusOffDimsNothing() {
        val dimmed = SpanPlanner.plan(
            tokens = emptyList(),
            selectionStart = caret,
            selectionEnd = caret,
            sourceLength = fixture.length,
        ).styles.filter { it.style == StyleKey.DIMMED }
        assertEquals(emptyList<StyledRange>(), dimmed)
    }
}

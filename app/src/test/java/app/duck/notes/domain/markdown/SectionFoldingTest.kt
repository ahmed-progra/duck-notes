package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionFoldingTest {

    private fun tokens(body: String) = MarkdownTextCache.rescan(ScanResult.Empty, body).tokens

    /** Far past any sample, so every node counts as "caret elsewhere". Matches `GoldenSpanTest`. */
    private val CARET_AWAY = Int.MAX_VALUE / 2

    private fun rendered(body: String, folded: Set<String>, caret: Int = Int.MAX_VALUE / 2): String {
        val scan = tokens(body)
        val folds = SectionFolding.ranges(body, scan, folded, caret, caret)
        val plan = SpanPlanner.plan(scan, caret, caret, folds)
        return StringBuilder(body).apply {
            plan.conceal.forEach { delete(it.first, it.last + 1) }
        }.toString()
    }

    @Test
    fun aFoldKeepsTheHeadingAndTheNextPeer() {
        val body = "# One\nfirst\n## Two\nsecond\n# Three\nthird"
        val section = SectionFolding.sections(body, tokens(body)).first()

        assertEquals("One\nThree\nthird", rendered(body, setOf(section.key)))
    }

    @Test
    fun aNestedFoldDoesNotHideItsParentBody() {
        val body = "# One\nfirst\n## Two\nsecond\n# Three\nthird"
        val section = SectionFolding.sections(body, tokens(body))[1]

        assertEquals("One\nfirst\nTwo\nThree\nthird", rendered(body, setOf(section.key)))
    }

    @Test
    fun aCaretInsideAClosedSectionRevealsItsBody() {
        val body = "# One\nfirst\n# Two\nsecond"
        val section = SectionFolding.sections(body, tokens(body)).first()
        val caret = body.indexOf("first")

        assertEquals("One\nfirst\nTwo\nsecond", rendered(body, setOf(section.key), caret))
    }

    @Test
    fun equalHeadingsGetStableDistinctKeys() {
        val body = "# Repeat\na\n# Repeat\nb"
        val sections = SectionFolding.sections(body, tokens(body))

        assertEquals(listOf("1:0:Repeat", "1:1:Repeat"), sections.map { it.key })
        assertTrue(sections.all { it.content != null })
    }

    /**
     * **The merge's own guard.** A folded body still contains markdown markers, so `SpanPlanner.plan`
     * has to reconcile two sources of conceal range that genuinely overlap.
     *
     * Until this existed the merge was held up by one incidental fixture —
     * [aFoldKeepsTheHeadingAndTheNextPeer] happens to overlap a `## ` marker, so deleting the merge
     * turned it red, but nothing said that was the property being protected. The merge's own comment
     * cited "the invariant suite", which could not see a fold at all until this commit.
     *
     * Asserts the two properties back-to-front application depends on, directly on the merged output.
     */
    @Test
    fun aFoldOverlappingMarkerConcealsMergesToOneDescendingRange() {
        val body = "# One\nsome **bold** and `code` here\n# Two\ntail"
        val key = SectionFolding.sections(body, tokens(body)).first().key
        val folds = SectionFolding.ranges(body, tokens(body), setOf(key), CARET_AWAY, CARET_AWAY)
        val conceal = SpanPlanner.plan(tokens(body), CARET_AWAY, CARET_AWAY, folds).conceal

        // Precondition, or this proves nothing: the fold must actually swallow marker ranges.
        val markers = SpanPlanner.plan(tokens(body), CARET_AWAY, CARET_AWAY).conceal
        assertTrue(
            "the fixture stopped overlapping - markers=$markers folds=$folds",
            markers.any { m -> folds.any { f -> m.first >= f.first && m.last <= f.last } },
        )

        conceal.zipWithNext { a, b ->
            assertTrue("$a then $b is not descending", a.first > b.first)
            assertTrue("$a overlaps $b", b.last < a.first)
        }
        assertEquals("One\nTwo\ntail", rendered(body, setOf(key)))
    }

    /**
     * A task inside a folded section yields no box, rather than a zero-width one at the fold's edge.
     *
     * `taskBoxes` rebases both ends of the box through the conceal cuts; inside a fold both land on
     * the same rendered offset. Without the `renderedEnd <= renderedStart` guard the box survives as
     * an empty range, and `SectionFoldOverlay`'s sibling — the task overlay — would position a
     * toggleable at zero width on a line the user cannot see.
     */
    @Test
    fun aTaskInsideAFoldedSectionYieldsNoBox() {
        val body = "# H\n- [ ] milk\nmore"
        val key = SectionFolding.sections(body, tokens(body)).first().key
        val folds = SectionFolding.ranges(body, tokens(body), setOf(key), CARET_AWAY, CARET_AWAY)

        // Precondition: unfolded, this body really does produce a box.
        val unfolded = SpanPlanner.plan(tokens(body), CARET_AWAY, CARET_AWAY)
        assertEquals(1, SpanPlanner.taskBoxes(body, tokens(body), unfolded).size)

        val folded = SpanPlanner.plan(tokens(body), CARET_AWAY, CARET_AWAY, folds)
        assertEquals(emptyList<TaskBox>(), SpanPlanner.taskBoxes(body, tokens(body), folded))
    }

    @Test
    fun foldingParentAndChildProducesOneOuterDeletion() {
        val body = "# One\nfirst\n## Two\nsecond\n# Three\nthird"
        val keys = SectionFolding.sections(body, tokens(body)).take(2).map { it.key }.toSet()
        val folds = SectionFolding.ranges(body, tokens(body), keys, Int.MAX_VALUE / 2, Int.MAX_VALUE / 2)
        val plan = SpanPlanner.plan(tokens(body), Int.MAX_VALUE / 2, Int.MAX_VALUE / 2, folds)

        assertEquals(listOf("One\nThree\nthird"), listOf(StringBuilder(body).apply {
            plan.conceal.forEach { delete(it.first, it.last + 1) }
        }.toString()))
        assertEquals(1, folds.size)
    }
}

package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionFoldingTest {

    private fun tokens(body: String) = MarkdownTextCache.rescan(ScanResult.Empty, body).tokens

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

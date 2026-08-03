package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract the buffer edits depend on, checked at every caret position of every sample.
 *
 * We chose `OutputTransformation` precisely so Compose would derive the offset mapping from our
 * deletions instead of us hand-writing one. That trade only holds if the deletions we hand it are
 * well formed: in bounds, non-overlapping, and applied back-to-front. Those three properties are
 * this file. If any of them breaks, the mapping Compose derives is wrong and the caret lands in the
 * wrong place — so these run exhaustively rather than on a handful of spot checks.
 */
class ConcealInvariantTest {

    private fun plan(body: String, caret: Int): SpanPlan =
        SpanPlanner.plan(MarkdownTextCache.rescan(ScanResult.Empty, body).tokens, caret, caret)

    @Test
    fun concealRangesAreWellFormedAtEveryCaretPosition() {
        var examined = 0

        MarkdownCorpus.samples.forEach { (name, body) ->
            for (caret in 0..body.length) {
                val conceal = plan(body, caret).conceal
                val where = "sample=$name caret=$caret"

                conceal.forEach {
                    examined++
                    assertTrue("$where: range $it starts before 0", it.first >= 0)
                    assertTrue("$where: range $it ends past ${body.length}", it.last < body.length)
                    assertTrue("$where: range $it is empty or reversed", it.last >= it.first)
                }

                // Descending by start, which is what makes back-to-front application safe.
                conceal.zipWithNext { a, b ->
                    assertTrue("$where: $a then $b is not descending", a.first > b.first)
                }

                // Non-overlapping. Given descending order, b must end before a begins.
                conceal.zipWithNext { a, b ->
                    assertTrue("$where: $a overlaps $b", b.last < a.first)
                }
            }
        }

        // Every assertion above sits inside `conceal.forEach`, so a scanner that returned nothing
        // would report green over an empty list. Counting what was actually examined is the positive
        // control; a per-sample non-emptiness check would be wrong, since plenty of samples
        // legitimately conceal nothing at some caret positions.
        assertTrue("no conceal range was examined at all — nothing here was tested", examined > 0)
    }

    /**
     * Applying the deletions back-to-front must reproduce exactly the text with those ranges removed.
     * This is the property the ordering exists for: a forward application would corrupt every offset
     * after the first deletion.
     */
    @Test
    fun descendingApplicationRemovesExactlyTheConcealedRanges() {
        MarkdownCorpus.samples.forEach { (name, body) ->
            for (caret in 0..body.length) {
                val conceal = plan(body, caret).conceal
                val applied = StringBuilder(body).apply {
                    conceal.forEach { delete(it.first, it.last + 1) }
                }.toString()

                val expected = body.filterIndexed { i, _ -> conceal.none { i in it } }
                assertEquals("sample=$name caret=$caret", expected, applied)
            }
        }
    }

    /** A caret inside a node reveals its markers; the same node hides them when the caret is away. */
    @Test
    fun caretInsideANodeRevealsItsMarkers() {
        val body = "some **bold** text"
        val boldStart = body.indexOf("**")
        val boldEnd = body.indexOf("**", boldStart + 2) + 2

        assertTrue("markers should hide with the caret at 0", plan(body, 0).conceal.isNotEmpty())
        assertTrue(
            "markers should reveal with the caret inside",
            plan(body, boldStart + 3).conceal.isEmpty(),
        )
        // Endpoints count as inside, so markers do not vanish as you arrive next to them.
        assertTrue("caret at node start", plan(body, boldStart).conceal.isEmpty())
        assertTrue("caret at node end", plan(body, boldEnd).conceal.isEmpty())
    }

    @Test
    fun selectionSpanningANodeRevealsIt() {
        val body = "some **bold** text"
        val tokens = MarkdownTextCache.rescan(ScanResult.Empty, body).tokens
        assertTrue(
            "a selection covering the whole line must reveal every marker",
            SpanPlanner.plan(tokens, 0, body.length).conceal.isEmpty(),
        )
    }

    @Test
    fun unclosedMarkersAreNeverConcealed() {
        val body = "a lone ** marker and a lone ` tick"
        for (caret in 0..body.length) {
            assertEquals(
                "an unpaired delimiter is inert, so nothing may be hidden (caret=$caret)",
                emptyList<IntRange>(),
                plan(body, caret).conceal,
            )
        }
    }

    // --- Rendered coordinates -----------------------------------------------

    /**
     * Every style range, once rebased, must land inside the concealed text and cover exactly the
     * characters it covered before — this is what makes it safe to style after deleting.
     */
    @Test
    fun renderedStylesLandOnTheSameCharacters() {
        var examined = 0

        MarkdownCorpus.samples.forEach { (name, body) ->
            for (caret in 0..body.length) {
                val plan = plan(body, caret)
                val rendered = StringBuilder(body).apply {
                    plan.conceal.forEach { delete(it.first, it.last + 1) }
                }.toString()

                SpanPlanner.renderedStyles(plan).forEach {
                    examined++
                    val where = "sample=$name caret=$caret style=${it.style}"
                    assertTrue("$where: start ${it.start} < 0", it.start >= 0)
                    assertTrue("$where: end ${it.end} > ${rendered.length}", it.end <= rendered.length)
                    assertTrue("$where: reversed", it.end > it.start)
                }
            }
        }

        assertTrue("no rendered style was examined at all — nothing here was tested", examined > 0)
    }

    @Test
    fun rebasingIsIdentityWhenNothingIsConcealed() {
        val body = "some **bold** text"
        val at = body.indexOf("bold")
        val plan = SpanPlanner.plan(
            MarkdownTextCache.rescan(ScanResult.Empty, body).tokens, at, at,
        )
        assertEquals(plan.styles, SpanPlanner.renderedStyles(plan))
    }

    @Test
    fun rebasedStyleCoversTheSameTextAsBefore() {
        val body = "some **bold** text"
        val plan = plan(body, 0)
        val rendered = StringBuilder(body).apply {
            plan.conceal.forEach { delete(it.first, it.last + 1) }
        }.toString()

        val bold = SpanPlanner.renderedStyles(plan).single { it.style == StyleKey.BOLD }
        assertEquals("bold", rendered.substring(bold.start, bold.end))
    }

    @Test
    fun styledRangesStayInBounds() {
        var examined = 0

        MarkdownCorpus.samples.forEach { (name, body) ->
            plan(body, 0).styles.forEach {
                examined++
                assertTrue("$name: ${it.style} starts before 0", it.start >= 0)
                assertTrue("$name: ${it.style} ends past ${body.length}", it.end <= body.length)
                assertTrue("$name: ${it.style} is reversed", it.end >= it.start)
            }
        }

        assertTrue("no styled range was examined at all — nothing here was tested", examined > 0)
    }
}

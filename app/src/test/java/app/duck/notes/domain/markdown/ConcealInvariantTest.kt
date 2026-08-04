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

    /**
     * Every caret **inside** the body, plus one parked far outside it.
     *
     * The parked position is not redundant, and leaving it out cost this suite real coverage.
     * `SpanPlanner` reveals a token whenever the selection touches it, and a block token spans its
     * **whole line** — so for a single-line sample, every caret in `0..body.length` reveals the block
     * marker and it never conceals. `> - [ ] x` concealed exactly one range at every swept caret,
     * which meant the [zipWithNext] ordering and overlap checks below — the ones that need two
     * ranges to run at all — had never once executed against a pair of *block* markers. Parked, the
     * same sample yields two.
     *
     * Found in 8c-b by a plant that overlapped two block markers and stayed green. `GoldenSpanTest`
     * had the parked caret from Phase 4 and this suite did not; see SETUP.md trap 6.
     */
    private fun carets(body: String): List<Int> = (0..body.length) + CARET_AWAY

    @Test
    fun concealRangesAreWellFormedAtEveryCaretPosition() {
        var examined = 0

        MarkdownCorpus.samples.forEach { (name, body) ->
            for (caret in carets(body)) {
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
            for (caret in carets(body)) {
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
        for (caret in carets(body)) {
            assertEquals(
                "an unpaired delimiter is inert, so nothing may be hidden (caret=$caret)",
                emptyList<IntRange>(),
                plan(body, caret).conceal,
            )
        }
    }

    // --- Rendered coordinates -----------------------------------------------

    /**
     * Every style range, once rebased, lands **inside** the concealed text: non-negative, within
     * length, not reversed. Corpus-wide, at every caret.
     *
     * ## The name overpromises, and the missing half is real and lives below
     *
     * It says "the same characters"; the body asserts **bounds**. The two are not the same claim —
     * a rebase that shifted every style one character left would satisfy every assertion here. This
     * is [styledRangesStayInBounds] in rendered coordinates, and that is a worthwhile thing to run
     * across the corpus, but it is not what the name describes.
     *
     * The character-level property is guarded by [rebasedStyleCoversTheSameTextAsBefore], two tests
     * below, which substrings the rendered text and compares it to `"bold"`. **Narrow — one sample,
     * one style, one caret — where this one is broad and shallow.** Neither subsumes the other, and
     * deleting either on the grounds that "the other one covers rebasing" loses a real half.
     *
     * The name is kept rather than corrected because other checks and commit messages cite it; the
     * *misnamed* class in `DUCK_NOTES_BUILD_PROMPT.md` says to fix these by KDoc, not by rename.
     *
     * ## The positive control is per-kind, and the scalar it replaces measured the wrong thing
     *
     * `examined > 0` counts *rendered styles*, and **losing block conceal removes no style range at
     * all** — a heading's `StyledRange` is emitted whether or not its `# ` is hidden. So the counter
     * was not merely coarse against audit finding 9's example, it could not have seen it: it would
     * stay non-zero even with every block marker left visible.
     *
     * What block conceal does produce is a concealed marker, so that is what [BLOCK_KINDS] counts.
     * `carve` only ever trims a marker's tail, never moves its `first`, so matching a conceal range
     * to the token that owns it on that offset is exact.
     *
     * **Not the only guard on this, and the sibling is named deliberately.** `GoldenSpanTest` asserts
     * the concealed rendering of block samples directly and goes red on the same plant. The audit
     * called that an unnamed coupling; naming it is cheaper than pretending this control is unique.
     * What it adds is that *this* sweep — bounds at every caret, in rendered coordinates — reached
     * block markers, which `GoldenSpanTest` says nothing about.
     */
    @Test
    fun renderedStylesLandOnTheSameCharacters() {
        var examined = 0
        val concealed = mutableSetOf<TokenKind>()

        MarkdownCorpus.samples.forEach { (name, body) ->
            val tokens = MarkdownTextCache.rescan(ScanResult.Empty, body).tokens
            for (caret in carets(body)) {
                val plan = SpanPlanner.plan(tokens, caret, caret)
                val rendered = StringBuilder(body).apply {
                    plan.conceal.forEach { delete(it.first, it.last + 1) }
                }.toString()

                val starts = plan.conceal.mapTo(mutableSetOf()) { it.first }
                tokens.forEach { t -> if (t.markers.any { it.first in starts }) concealed += t.kind }

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
        assertEquals(
            "these block markers were never concealed anywhere in the corpus, so the rebase these " +
                "assertions cover ran on inline markers alone — which is exactly what a single " +
                "corpus-wide counter cannot see",
            emptySet<TokenKind>(),
            BLOCK_KINDS - concealed,
        )
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

    /**
     * **The character-level half of rebasing, and the only check in the repo that asserts it.**
     *
     * [renderedStylesLandOnTheSameCharacters] above is named for this property and does not test it
     * — it asserts bounds. This one deletes the concealed ranges for real, substrings the result at
     * the rebased offsets, and requires the bytes to read `"bold"`. A rebase that drifted by one
     * character passes every assertion up there and fails here.
     *
     * One sample and one style is thin, and it is deliberately not widened into the corpus sweep
     * above: doing that would need an expected-text table per sample per caret, which is
     * `GoldenSpanTest`'s job in source coordinates. What this holds is the *rendered* coordinate
     * space, which nothing else on the JVM does — `ConcealMappingTest` covers it on device, through
     * Compose's own mapping rather than `SpanPlanner.renderedStyles` directly.
     *
     * Small and load-bearing. See the *misnamed* class in `DUCK_NOTES_BUILD_PROMPT.md`.
     */
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

    private companion object {
        /** Far past any sample, so every node counts as "caret elsewhere". Matches `GoldenSpanTest`. */
        const val CARET_AWAY = Int.MAX_VALUE / 2

        /**
         * The block kinds that **carry markers**, and therefore the ones block conceal can lose.
         *
         * **Curated, not total**, in the same idiom as `ContrastTest.usagePairs()`, and the omissions
         * are structural rather than an oversight: `DIVIDER`, `FENCE_MARKER` and `FENCE_BODY` are
         * block kinds that `MarkdownScanner` constructs with no `markers` at all, so they can never
         * appear in a conceal range and requiring them here would be red forever. Adding a block kind
         * that does carry markers means adding it here.
         */
        val BLOCK_KINDS = setOf(
            TokenKind.HEADING,
            TokenKind.QUOTE,
            TokenKind.BULLET,
            TokenKind.ORDERED,
            TokenKind.TASK_OPEN,
            TokenKind.TASK_DONE,
        )
    }
}

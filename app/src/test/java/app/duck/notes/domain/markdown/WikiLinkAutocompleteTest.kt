package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 9c's autocomplete: the half-typed `[[` context, the titles it offers, and the completion.
 *
 * ## Why these are JVM tests and not instrumented ones
 *
 * Every rule in this phase is a pure function of `(body, caret)` and a list of strings. That is not
 * an accident of implementation — it is the phase's central design claim, that autocomplete is
 * **caret-driven and stays in source space**, so nothing here needs a `TextLayoutResult`, a
 * `SpanPlanner` rebase, or a device. If any of these ever needs an emulator, the claim has stopped
 * being true and the phase boundary against create-on-tap has moved.
 *
 * ## The offset cases are the standing requirement, not extra hardening
 *
 * Source-versus-rendered confusion has shipped four times here (8c-c-fix's fold chevron, the
 * section-visibility guard beside it, 8d-a's absolute offsets in `TaskBoxOverlay` and
 * `SectionFoldOverlay`, and the class 9a's follow-up pinned). So the RTL and non-BMP cases below pin
 * `activeAt`'s span against **literal integers**, never against a bound re-derived from the scanner
 * — the "pin the literal" rule. Both carry real plants:
 *
 * - **Non-BMP:** wrap `activeAt`'s returned start in `body.codePointCount(0, …)`. One glyph, one
 *   index — a no-op on all-BMP input, so the whole rest of the suite (and the RTL case) stays green,
 *   and this one case goes red.
 * - **RTL:** byte offsets in `MarkdownTextCache.splitLinesWithOffsets`. **Patch both the in-loop and
 *   the trailing `starts += start`** — 9a's first attempt at this plant patched only the loop, came
 *   back green, and was worthless, because the fixture's caret sits on the *last* line, whose start
 *   is appended after the loop.
 */
class WikiLinkAutocompleteTest {

    // --- the active context ---------------------------------------------------

    @Test
    fun aHalfTypedLinkIsActiveFromItsOpeningBrackets() {
        val body = "see [[Pon"

        assertEquals(4 until 9, WikiLinkScanner.activeAt(body, 9))
    }

    @Test
    fun theTypedPrefixIsWhatSitsBetweenTheBracketsAndTheCaret() {
        val body = "see [[Pon"
        val active = requireNotNull(WikiLinkScanner.activeAt(body, 9))

        assertEquals("Pon", body.substring(active.first + 2, active.last + 1))
    }

    @Test
    fun bareBracketsWithNothingTypedAreStillActive() {
        // The moment `[[` is closed the user wants the whole list, so an empty prefix is a context.
        assertEquals(0 until 2, WikiLinkScanner.activeAt("[[", 2))
    }

    @Test
    fun ordinaryProseIsNotAContext() = assertNull(WikiLinkScanner.activeAt("no brackets here", 8))

    @Test
    fun aSingleBracketIsNotAContext() = assertNull(WikiLinkScanner.activeAt("see [Pon", 8))

    /**
     * A caret inside a **finished** `[[Pond]]` is ordinary text editing, not a half-typed link.
     *
     * The answer comes from [WikiLinkScanner.matchAt] — the bracket rule's one home — rather than a
     * second opinion about what "closed" means. Dropping that consult is the plant.
     */
    @Test
    fun aClosedLinkIsNotAnActiveContext() {
        assertNull("caret inside [[Pond]]", WikiLinkScanner.activeAt("see [[Pond]] here", 8))
        assertNull("caret after [[Pond]]", WikiLinkScanner.activeAt("see [[Pond]] here", 16))
    }

    /**
     * `[[a[b]] ` is rejected for the `]]` between the opening and the caret.
     *
     * [WikiLinkScanner.matchAt] alone will not catch it — it refuses the span for the *inner* `[`, so
     * without the `]]` check this reads as a still-open context whose prefix is `a[b]] `. Dropping
     * that check is the plant, and this is the only case that distinguishes the two guards.
     */
    @Test
    fun anInnerBracketFollowedByAClosingPairIsNotActive() {
        assertNull(WikiLinkScanner.activeAt("[[a[b]] ", 8))
    }

    /**
     * Masked openings offer nothing, and the mask is [TagScanner.codeSpanMask] — the same one
     * `titlesIn` applies, so what the editor refuses to paint is what autocomplete refuses to offer.
     *
     * The fence half is inherited rather than written: `forEachContentLine` never hands a fenced line
     * to the walk, so the caret's line is simply never examined.
     */
    @Test
    fun aLinkInsideCodeOffersNothing() {
        assertNull("inline code span", WikiLinkScanner.activeAt("a `[[Pon` span", 8))
        assertNull("fenced block", WikiLinkScanner.activeAt("```\n[[Pon\n```", 9))
    }

    /** A link is line-local, so a newline ends the context — which is also what Enter does. */
    @Test
    fun aNewlineEndsTheContext() {
        assertNull(WikiLinkScanner.activeAt("see [[Pon\nnext line", 15))
    }

    /** Nothing is remembered, so a caret moved back before the brackets is simply not in a context. */
    @Test
    fun movingTheCaretBeforeTheBracketsLeavesNoContext() {
        assertNull(WikiLinkScanner.activeAt("see [[Pon", 2))
    }

    // --- offset space ---------------------------------------------------------

    /**
     * Right-to-left text before and around the opening, pinned to a literal source offset.
     *
     * The plant is the cross-line one: byte offsets in `splitLinesWithOffsets`, **both appends**.
     * Hebrew is two UTF-8 bytes per character and one UTF-16 unit, so line 2's base moves from 9 to
     * 14 and the span reads 18 instead of 13. There is no *same-line* plant, and the reason is the one
     * 9a recorded: RTL characters are BMP, a Kotlin `String` index is a logical-order code-unit index,
     * and bidi is a rendering transform this layer never touches.
     */
    @Test
    fun aHalfTypedLinkAfterRightToLeftTextKeepsItsSourceSpan() {
        // Fixture self-check, computed without the scanner: line 0 is seven characters, line 1 is
        // empty, and three Hebrew letters plus a space precede the brackets on line 2 — 7 + 1 + 1 + 4.
        assertEquals("the RTL fixture is not shaped as documented", 13, RTL_BODY.indexOf("[["))
        assertEquals(18, RTL_BODY.length)

        val active = requireNotNull(WikiLinkScanner.activeAt(RTL_BODY, 18))

        assertEquals("the context opens at source offset 13", 13, active.first)
        assertEquals(17, active.last)
        assertEquals("[[Pon", RTL_BODY.substring(active.first, active.last + 1))
        assertEquals("Pon", RTL_BODY.substring(active.first + 2, active.last + 1))
    }

    /**
     * A surrogate-pair emoji before the opening, pinned to a literal source offset.
     *
     * **9, not 8:** the duck is one glyph and two UTF-16 code units. That is the number an index
     * calculation counting glyphs gets wrong, and the plant — `codePointCount` on the returned start
     * — is a no-op everywhere else in the suite.
     */
    @Test
    fun aHalfTypedLinkAfterANonBmpCharacterKeepsItsSourceSpan() {
        assertEquals("the non-BMP fixture is not shaped as documented", 9, EMOJI_BODY.indexOf("[["))

        val active = requireNotNull(WikiLinkScanner.activeAt(EMOJI_BODY, 13))

        assertEquals("the context opens at source offset 9", 9, active.first)
        assertEquals("[[Po", EMOJI_BODY.substring(active.first, active.last + 1))
        assertEquals("Po", EMOJI_BODY.substring(active.first + 2, active.last + 1))
    }

    // --- matching -------------------------------------------------------------

    /**
     * Matching folds case; the title handed back is the note's own, verbatim.
     *
     * Those are one answer, not two: the verbatim title is what `NoteDao.noteIdByTitle` will match
     * exactly when the body is saved. Dropping `ignoreCase` is the plant.
     */
    @Test
    fun titlesMatchCaseInsensitivelyButInsertVerbatim() {
        val titles = listOf("Pond", "Ducklings", "ponder this")

        assertEquals(listOf("Pond", "ponder this"), WikiLinkScanner.suggest(titles, "pon"))
        assertEquals(listOf("Pond", "ponder this"), WikiLinkScanner.suggest(titles, "PON"))
    }

    @Test
    fun anEmptyPrefixOffersEverything() {
        val titles = listOf("Pond", "Ducklings")

        assertEquals(titles, WikiLinkScanner.suggest(titles, ""))
    }

    /** Query order is the query's — most recently modified first — and is preserved, not re-sorted. */
    @Test
    fun theQuerysOrderSurvivesMatching() {
        assertEquals(
            listOf("Zebra notes", "Apple notes"),
            WikiLinkScanner.suggest(listOf("Zebra notes", "Apple notes"), ""),
        )
    }

    /** Two notes may share a title; the picker offers it once. */
    @Test
    fun duplicateTitlesAreOfferedOnce() {
        assertEquals(listOf("Pond"), WikiLinkScanner.suggest(listOf("Pond", "Pond"), "Po"))
    }

    @Test
    fun nothingMatchesAnUnknownPrefix() {
        assertEquals(emptyList<String>(), WikiLinkScanner.suggest(listOf("Pond"), "zz"))
    }

    // --- the completion -------------------------------------------------------

    /**
     * The written form round-trips back through the scanner that reads it.
     *
     * `linkTo` is the one place `[[` and `]]` are written for output — `MarkdownScanner.imageRef`'s
     * mirror — so the literal the completion inserts and the rule `titlesIn` reads cannot drift.
     * Emitting a single `]` is the plant.
     */
    @Test
    fun theWrittenLinkScansBackAsThatLink() {
        val written = WikiLinkScanner.linkTo("Pond")

        assertEquals(written.length, WikiLinkScanner.matchAt(written, 0))
        assertEquals(listOf("Pond"), WikiLinkScanner.titlesIn(written))
    }

    private companion object {
        /** Hebrew on the line before the opening *and* around it, so both offset halves are covered. */
        val RTL_BODY = """
            # הערות

            ראו [[Pon
        """.trimIndent()

        /** The surrogate pair precedes the opening on the same line. */
        const val EMOJI_BODY = "🦆 notes [[Po"
    }
}

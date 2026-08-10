package app.duck.notes.domain.markdown

import app.duck.notes.domain.usecase.DeriveTitle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `WikiLinkScanner`'s **source offsets**, pinned against literals on non-ASCII text.
 *
 * ## Why this is not part of `TagAgreementTest`
 *
 * That suite already reads a wiki link's span — `painted()` does `body.substring(start + 2, end - 2)`
 * — and it is **structurally incapable of catching an offset defect in the scanner.** It asserts that
 * the editor and `ResolveWikiLinks` agree, and since 9a both reach the same [WikiLinkScanner.matchAt].
 * A wrong end truncates both titles identically and the equality still holds. Its corpus is also
 * entirely ASCII, so no surrogate pair or RTL run ever reaches it.
 *
 * An agreement test cannot see this class. Only an assertion against a value the scanner did not
 * produce can — which is why every expectation here is a literal substring or a literal integer, and
 * never a bound re-derived from the thing under test.
 *
 * ## Why these two inputs
 *
 * Offset confusion has shipped three times in this project: the 8c-c-fix fold chevron, the
 * section-visibility guard fixed beside it, and 8d-a's absolute-offset bug in `TaskBoxOverlay` and
 * `SectionFoldOverlay`. 9a gave the `[[...]]` rule one home; these hold that the move did not carry a
 * fourth instance in with it.
 *
 * ## What a plant proves here, and what it does not
 *
 * [aWikiLinkAfterANonBmpCharacterKeepsItsSourceSpan] has a real one: rewriting `matchAt`'s returned
 * end as `line.codePointCount(0, close) + 2` — one glyph, one index — is a no-op on every all-BMP
 * body in the suite and turns this case red.
 *
 * [aWikiLinkSurroundedByRightToLeftTextKeepsItsSourceSpan] has a plant for the **cross-line** offset
 * only: `starts += text.substring(0, start).toByteArray().size` in
 * `MarkdownTextCache.splitLinesWithOffsets` is exact on ASCII and shifts by one per Hebrew letter,
 * giving `expected:<13> but was:<18>` for this fixture's five of them.
 *
 * **That plant has to be applied to the trailing `starts += start`, not only the one in the loop**,
 * and the first attempt at it was defective in exactly the way `CLAUDE.md` warns a plant can be: it
 * patched the in-loop branch alone, the whole suite stayed green, and the green was worthless because
 * the link here sits on the **last** line, whose start is appended after the loop. It failed in the
 * safe direction — a false green while planting, not a false red — but it is why the site is named
 * precisely rather than as "the offset in `splitLinesWithOffsets`".
 *
 * Its **same-line** half has no constructible plant, and the reason is worth stating rather than
 * leaving as a shrug: RTL characters are BMP, one UTF-16 unit each, and a Kotlin `String` index is a
 * logical-order code-unit index. Bidi is a *rendering* transform; [WikiLinkScanner.matchAt] is
 * `startsWith` / `indexOf` / `line[k]` with no width, direction or codepoint arithmetic, and
 * `MarkdownScanner.scanInline` only adds `base + i` to it. That is precisely why the 8d-a RTL bug
 * lived one layer up, in a `Modifier.offset` over a `TextLayoutResult.getBoundingBox` **visual**
 * coordinate, and not down here. The case is still pinned — it costs six lines and it is the input
 * nobody had ever run through the scanner.
 */
class WikiLinkOffsetTest {

    @Test
    fun aWikiLinkSurroundedByRightToLeftTextKeepsItsSourceSpan() {
        // Fixture self-check, computed without the scanner: line 0 is seven characters, line 1 is
        // empty, and three Hebrew letters plus a space precede the link on line 2 — 7 + 1 + 1 + 4.
        assertEquals("the RTL fixture is not shaped as documented", 13, RTL_BODY.indexOf("[["))

        val token = wikiToken(RTL_BODY)
        assertEquals("the wiki link opens at source offset 13", 13, token.start)
        assertEquals("[[Pond]]", RTL_BODY.substring(token.start, token.end))

        val (open, close) = token.markers
        assertEquals("[[", RTL_BODY.substring(open.first, open.last + 1))
        assertEquals("]]", RTL_BODY.substring(close.first, close.last + 1))

        // The line-local rule, straight off the direct surface: 4 past `[[`, ends 12 past `]]`.
        assertEquals(12, WikiLinkScanner.matchAt(RTL_BODY.lines()[2], 4))

        // The consumer that turns those offsets into a frame. Descending and disjoint, as
        // `ConcealInvariantTest` requires, and sitting on the brackets rather than beside them.
        val plan = SpanPlanner.plan(listOf(token), 0, 0)
        assertEquals(listOf(19..20, 13..14), plan.conceal)
    }

    @Test
    fun aWikiLinkAfterANonBmpCharacterKeepsItsSourceSpan() {
        // 15, not 14: the duck is a surrogate pair — one glyph, two UTF-16 code units. This is the
        // number an index calculation that counts glyphs gets wrong, and the fixture check proves
        // 15 is right without asking the scanner.
        assertEquals("the non-BMP fixture is not shaped as documented", 15, EMOJI_BODY.indexOf("[["))

        val token = wikiToken(EMOJI_BODY)
        assertEquals("the wiki link opens at source offset 15", 15, token.start)
        assertEquals("[[Pond]]", EMOJI_BODY.substring(token.start, token.end))
        assertEquals(23, WikiLinkScanner.matchAt(EMOJI_BODY.lines()[0], 15))

        // The title path, through `WikiLinkScanner.stripInline` — where `DeriveTitle`'s own copy of
        // the bracket rule used to live. A short end here truncates the title to `Pon`.
        assertEquals("🦆 notes about Pond and more", DeriveTitle()(EMOJI_BODY))
    }

    private fun wikiToken(body: String): Token =
        MarkdownTextCache.rescan(ScanResult.Empty, body).tokens
            .single { it.kind == TokenKind.WIKI_LINK }

    private companion object {
        /** Hebrew on the line before the link *and* around it, so both offset halves are covered. */
        val RTL_BODY = """
            # הערות

            ראו [[Pond]] כאן
        """.trimIndent()

        /** The title line carries the surrogate pair; the link follows it on that same line. */
        const val EMOJI_BODY = "🦆 notes about [[Pond]] and more\ntail"
    }
}

package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tap mapping — Phase 9d's primary deliverable, and the reason this file is a JVM test.
 *
 * ## The split that proves the mapping is one pure leg plus one layout leg
 *
 * A tap becomes a navigation in two hops: source span → rendered offsets, then rendered offsets →
 * screen rect. **The first is arithmetic over tokens and conceal cuts and is tested here, on the
 * JVM.** Only the second needs a device, and it is tested in `WikiLinkTapUiTest` and `RtlLayoutTest`.
 * If the cases below had needed an emulator, the mapping would be wider than claimed.
 *
 * There is no third hop. `SpanPlanner.renderedOffset` maps **forward** only, no inverse of it exists
 * in this codebase, and 9d does not add one — `WikiLinkTarget` carries the title it was built from,
 * so *"which link was tapped"* is answered by which overlay was hit.
 *
 * ## Concealment is the whole off-by-two
 *
 * `MarkdownScanner` emits `WIKI_LINK` with both bracket pairs as markers and no `box`, so
 * `SpanPlanner.carve` concealsboth whenever the caret is outside. `[[Pond]]` is eight source
 * characters rendered as four. A target built from the source span would sit two characters off at
 * each end — on the wrong side of a bracket — and nothing on screen would look wrong enough to catch
 * it. Every case below pins **both** spans against literal integers.
 */
class WikiLinkTargetTest {

    private fun targetsOf(body: String, caret: Int): List<WikiLinkTarget> {
        val tokens = MarkdownTextCache.rescan(ScanResult.Empty, body).tokens
        val plan = SpanPlanner.plan(tokens, caret, caret)
        return SpanPlanner.wikiLinkTargets(body, tokens, plan, caret, caret)
    }

    private fun singleTarget(body: String, caret: Int) = targetsOf(body, caret).single()

    // --- the spans ------------------------------------------------------------

    /**
     * The rendered span is the title **without** its brackets; the source span keeps them.
     *
     * `see [[Pond]] here`: the link occupies source 4..11, and renders as `see Pond here`, where
     * `Pond` sits at 4..7.
     *
     * **The plant that would have been obvious here does not exist, and the reason is worth keeping.**
     * Building the target from `token.start`/`token.end` instead of the inner span was run as a plant
     * and came back **green**: under concealment the two map to the same rendered range, because
     * `rebase` breaks on a cut that starts exactly at the offset it is mapping, so the `[[` it would
     * have subtracted is skipped — and the `]]` end cancels the same way. That is a real invariant,
     * recorded at the call site so nobody "fixes" it in either direction.
     *
     * This assertion is not decoration for it: the RTL, non-BMP and `innerOf` plants all redden it.
     * What has no plant is specifically the inner-versus-outer choice, and that is stated rather than
     * left to read as covered.
     */
    @Test
    fun theRenderedSpanIsTheTitleWithoutItsBrackets() {
        val target = singleTarget("see [[Pond]] here", 0)

        assertEquals("source keeps the brackets", 4 until 12, target.source)
        assertEquals("rendered is the title alone", 4 until 8, target.rendered)
        assertEquals("Pond", target.title)
        assertEquals(
            "the rendered span is exactly four characters shorter — the two bracket pairs",
            4,
            (target.source.last - target.source.first) - (target.rendered.last - target.rendered.first),
        )
    }

    /**
     * A caret inside the link emits **no** target, and that absence is the routing rule.
     *
     * No target means no overlay means the tap reaches the text field and places the caret, which is
     * how a link is edited at all. Dropping the `revealed` check is the plant.
     */
    @Test
    fun aRevealedLinkGetsNoTarget() {
        assertEquals("caret inside the title", emptyList<WikiLinkTarget>(), targetsOf(BODY, 7))
        assertEquals("caret on the opening bracket", emptyList<WikiLinkTarget>(), targetsOf(BODY, 4))
    }

    /**
     * Endpoints count as touching, so a caret immediately beside a link reveals it.
     *
     * This is what makes the link editable: tap beside it, the overlay disappears, tap again to land
     * in the text. It comes from `SpanPlanner.revealed`, the same function `plan` uses to decide
     * whether to conceal, so the overlay and the brackets can never disagree about the state.
     */
    @Test
    fun aCaretImmediatelyBesideALinkRevealsIt() {
        assertEquals("just before", emptyList<WikiLinkTarget>(), targetsOf(BODY, 4))
        assertEquals("just after", emptyList<WikiLinkTarget>(), targetsOf(BODY, 12))
        assertTrue("two characters away is outside", targetsOf(BODY, 14).isNotEmpty())
    }

    @Test
    fun ordinaryProseHasNoTargets() =
        assertEquals(emptyList<WikiLinkTarget>(), targetsOf("no links here at all", 0))

    /** A link inside code is not a link, so it is not a tap target either. */
    @Test
    fun aLinkInsideCodeIsNotATarget() {
        assertEquals(emptyList<WikiLinkTarget>(), targetsOf("a `[[Pond]]` span", 0))
        assertEquals(emptyList<WikiLinkTarget>(), targetsOf("```\n[[Pond]]\n```", 0))
    }

    /**
     * Caret at 7, in the ` and ` between them — **both** links must be outside it or one drops out.
     *
     * Worth stating because the obvious caret positions do not work: 0 touches the first link's start
     * and end-of-text touches the second's end, and either way that link reveals and emits no target.
     * The first draft of this test used end-of-text and got `[A]`, which is the reveal rule behaving
     * correctly against a fixture that had not thought about it.
     */
    @Test
    fun everyLinkOnALineGetsItsOwnTarget() {
        val targets = targetsOf("[[A]] and [[B]]", 7)

        assertEquals(listOf("A", "B"), targets.map { it.title })
    }

    // --- offset space ---------------------------------------------------------

    /**
     * Right-to-left text before and around the link, both spans pinned to literals.
     *
     * The plant is the cross-line one: byte offsets in `MarkdownTextCache.splitLinesWithOffsets`,
     * **both appends** — 9a's first attempt patched only the in-loop one, came back green and was
     * worthless, and this fixture's link is likewise on the last line. Hebrew is two UTF-8 bytes per
     * character and one UTF-16 unit, so line 2's base moves and every number below moves with it.
     *
     * There is no *same-line* RTL plant here, for the reason 9a recorded: RTL characters are BMP, a
     * Kotlin `String` index is a logical-order code-unit index, and bidi is a rendering transform this
     * layer never touches. The visual reordering is real, but it happens in the leg
     * `RtlLayoutTest.theWikiLinkOverlayTracksItsGlyphsInBothDirections` measures, not in this one.
     */
    @Test
    fun aTargetAfterRightToLeftTextKeepsItsSourceSpan() {
        // Fixture self-check, computed without the planner: line 0 is three characters, line 1 is
        // empty, and three Hebrew letters plus a space precede the brackets on line 2 — 3 + 1 + 1 + 4.
        assertEquals("the RTL fixture is not shaped as documented", 9, RTL_BODY.indexOf("[["))

        val target = singleTarget(RTL_BODY, 0)

        assertEquals("source keeps the brackets", 9 until 17, target.source)
        assertEquals("rendered is the concealed-away title", 9 until 13, target.rendered)
        assertEquals("Pond", target.title)
        assertEquals("[[Pond]]", RTL_BODY.substring(target.source))
    }

    /**
     * A surrogate-pair emoji before the link.
     *
     * **3, not 2:** the duck is one glyph and two UTF-16 code units. That is the number an index
     * calculation counting glyphs gets wrong, and wrapping the target's source start in
     * `codePointCount` is the plant — a no-op on every all-BMP body in the suite.
     */
    @Test
    fun aTargetAfterANonBmpCharacterKeepsItsSourceSpan() {
        assertEquals("the non-BMP fixture is not shaped as documented", 3, EMOJI_BODY.indexOf("[["))

        val target = singleTarget(EMOJI_BODY, EMOJI_BODY.length)

        assertEquals("source opens at 3, not 2", 3 until 11, target.source)
        assertEquals(3 until 7, target.rendered)
        assertEquals("[[Pond]]", EMOJI_BODY.substring(target.source))
    }

    // --- the scanner's half ---------------------------------------------------

    /**
     * `titleAt` and `innerOf` strip exactly the brackets — the arithmetic no source guard can see.
     *
     * `MarkdownSyntaxSourceTest` bans the literals `"[["` and `"]]"` above `domain/markdown`, but `+ 2`
     * is the same knowledge in a form no scan detects. This is what stands in for that guard, which is
     * why it asserts the *characters* rather than the indices. Changing `+ 2` to `+ 1` is the plant.
     */
    @Test
    fun titleAtStripsExactlyTheBrackets() {
        val body = "see [[Pond]] here"
        val span = 4 until 12

        assertEquals("Pond", WikiLinkScanner.titleAt(body, span))
        assertEquals("Pond", body.substring(WikiLinkScanner.innerOf(span)))
        assertEquals("[[Pond]]", body.substring(span))
    }

    /**
     * Padding is trimmed, so a tap resolves the title a save wrote to `note_links`.
     *
     * The body has text before the link so a caret at 0 sits outside it — a bare `[[  Padded  ]]`
     * has no caret position that does not reveal it.
     */
    @Test
    fun theTitleIsTrimmedTheWayTitlesInTrimsIt() {
        val body = "x [[  Padded  ]] y"

        assertEquals(WikiLinkScanner.titlesIn(body).single(), singleTarget(body, 0).title)
    }

    private companion object {
        const val BODY = "see [[Pond]] here"

        /** Hebrew on the line before the link *and* around it, so both offset halves are covered. */
        val RTL_BODY = """
            ראו

            ראו [[Pond]] כאן
        """.trimIndent()

        /** The surrogate pair precedes the link on the same line. */
        const val EMOJI_BODY = "🦆 [[Pond]] tail"
    }
}

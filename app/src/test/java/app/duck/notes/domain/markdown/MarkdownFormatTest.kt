package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The format toolbar's whole contract, without Compose.
 *
 * The composable above [MarkdownFormat] does nothing but call it and hand the result to
 * `TextFieldState.edit`, so everything worth asserting is assertable here — which is why there is no
 * instrumented test for the nine actions, only for the disabled-button state that Compose owns.
 *
 * [everyMarkerScansBackAsItsOwnKind] is the load-bearing one. The rest check behaviour; that one
 * checks that the syntax the toolbar *writes* is the syntax the scanner *reads*, which is the only
 * thing standing between this file and a second implementation of Duck's markdown.
 */
class MarkdownFormatTest {

    private fun apply(body: String, start: Int, end: Int, action: FormatAction) =
        MarkdownFormat.apply(body, start, end, action)

    /** `«` and `»` mark the returned selection, so a case reads as one string instead of three. */
    private fun marked(result: FormatResult): String = buildString {
        append(result.body, 0, result.start)
        append('«')
        append(result.body, result.start, result.end)
        append('»')
        append(result.body, result.end, result.body.length)
    }

    // --- the syntax is the scanner's, not ours -------------------------------

    /**
     * **Every literal the toolbar writes must scan back as the node it claims to make.**
     *
     * This is the round trip `TagScanner.isValidName` uses, applied to the other syntax: rather than
     * asserting `markerFor(BOLD) == "**"` — which would just restate the table — it feeds the marker
     * through the real scanner and demands the kind come back. A `markerFor` entry that the parser
     * does not recognise fails here, and that is the drift this whole indirection exists to stop.
     */
    @Test
    fun everyMarkerScansBackAsItsOwnKind() {
        val inline = TokenKind.entries.mapNotNull { kind ->
            MarkdownScanner.markerFor(kind)?.let { kind to it }
        }
        assertTrue("markerFor returned nothing at all — the table is dead", inline.isNotEmpty())

        inline.forEach { (kind, marker) ->
            val body = "$marker" + "text" + "$marker"
            val kinds = MarkdownTextCache.rescan(ScanResult.Empty, body).tokens.map { it.kind }
            assertEquals("<$body> must scan as exactly one $kind", listOf(kind), kinds)
        }
    }

    @Test
    fun everyBlockPrefixScansBackAsItsOwnKind() {
        val blocks = TokenKind.entries.mapNotNull { kind ->
            MarkdownScanner.prefixFor(kind)?.let { kind to it }
        }
        assertTrue("prefixFor returned nothing at all — the table is dead", blocks.isNotEmpty())

        blocks.forEach { (kind, prefix) ->
            val body = prefix + "text"
            val token = MarkdownTextCache.rescan(ScanResult.Empty, body).tokens.first()
            assertEquals("<$body> must scan as $kind", kind, token.kind)
            // The marker the scanner reports has to be exactly what was written, or `block()`'s
            // removal would leave a fragment behind.
            assertEquals(
                "the scanner's marker range must cover the whole prefix",
                prefix,
                body.substring(token.markers.first().first, token.markers.first().last + 1),
            )
        }
    }

    // --- images (8d-b) -------------------------------------------------------

    /**
     * The image round trip, the analogue of [everyMarkerScansBackAsItsOwnKind] for attachments:
     * `appendImage` writes exactly what `attachmentIdsIn` reads back. This is what lets
     * `MarkdownSyntaxSourceTest` forbid the `![` literal everywhere above `domain/markdown` — the one
     * home is `MarkdownScanner.imageRef`, and this proves the scanner recognises its own output.
     */
    @Test
    fun anAppendedImageScansBackAsOneAttachment() {
        val body = MarkdownFormat.appendImage("buy milk", "abc-123", alt = "photo")
        assertEquals("buy milk\n![photo](attachment:abc-123)", body)
        assertEquals(setOf("abc-123"), MarkdownScanner.attachmentIdsIn(body))
    }

    @Test
    fun appendImageOntoAnEmptyBodyHasNoLeadingNewline() {
        assertEquals("![](attachment:x)", MarkdownFormat.appendImage("", "x"))
    }

    /** Removal is the mirror of the append: the whole line and its adjoining newline go together. */
    @Test
    fun removingTheAppendedImageLeavesNoBlankLine() {
        val body = MarkdownFormat.appendImage("buy milk", "abc-123")
        assertEquals("buy milk", MarkdownScanner.removeImageRef(body, "abc-123"))
    }

    // --- inline: wrap, unwrap, caret -----------------------------------------

    @Test
    fun aSelectionIsWrappedAndTheSelectionSurvives() {
        assertEquals("a **«bold»** c", marked(apply("a bold c", 2, 6, FormatAction.BOLD)))
        assertEquals("a _«it»_ c", marked(apply("a it c", 2, 4, FormatAction.ITALIC)))
        assertEquals("a `«x»` c", marked(apply("a x c", 2, 3, FormatAction.CODE)))
        assertEquals("a ==«h»== c", marked(apply("a h c", 2, 3, FormatAction.HIGHLIGHT)))
    }

    /** Wrapping the selection must keep the *text* selected, not the markers. */
    @Test
    fun theWrappedTextStaysSelectedNotTheMarkers() {
        val result = apply("a bold c", 2, 6, FormatAction.BOLD)
        assertEquals("bold", result.body.substring(result.start, result.end))
    }

    @Test
    fun pressingTheSameButtonAgainUnwraps() {
        val wrapped = apply("a bold c", 2, 6, FormatAction.BOLD)
        val plain = apply(wrapped.body, wrapped.start, wrapped.end, FormatAction.BOLD)
        assertEquals("a «bold» c", marked(plain))
    }

    @Test
    fun aCaretInsertsAnEmptyPairAndLandsInsideIt() {
        assertEquals("a**«»**b", marked(apply("ab", 1, 1, FormatAction.BOLD)))
        assertEquals("a`«»`b", marked(apply("ab", 1, 1, FormatAction.CODE)))
    }

    /** The caret case has to toggle too, or an accidental press leaves `****` behind. */
    @Test
    fun aCaretInsideAnEmptyPairRemovesIt() {
        val opened = apply("ab", 1, 1, FormatAction.BOLD)
        val closed = apply(opened.body, opened.start, opened.end, FormatAction.BOLD)
        assertEquals("a«»b", marked(closed))
    }

    // --- link ----------------------------------------------------------------

    @Test
    fun linkWrapsASelectionAndPutsTheCaretInTheParens() {
        val result = apply("see docs here", 4, 8, FormatAction.LINK)
        assertEquals("see [docs]() here", result.body)
        assertEquals("caret must land between the parens", "see [docs](", result.body.substring(0, result.start))
        assertEquals(result.start, result.end)
    }

    @Test
    fun linkOnABareCaretPutsTheCaretInTheBrackets() {
        val result = apply("ab", 1, 1, FormatAction.LINK)
        assertEquals("a[]()b", result.body)
        assertEquals("a[", result.body.substring(0, result.start))
    }

    // --- block: add, remove, replace -----------------------------------------

    @Test
    fun aBlockMarkerIsAddedToTheCaretsLine() {
        assertEquals("# Head«»ing", marked(apply("Heading", 4, 4, FormatAction.HEADING)))
        assertEquals("- it«»em", marked(apply("item", 2, 2, FormatAction.BULLET)))
        assertEquals("> qu«»ote", marked(apply("quote", 2, 2, FormatAction.QUOTE)))
        assertEquals("- [ ] mi«»lk", marked(apply("milk", 2, 2, FormatAction.TASK)))
    }

    @Test
    fun aBlockMarkerIsRemovedWhenTheLineAlreadyHasIt() {
        assertEquals("Head«»ing", marked(apply("# Heading", 6, 6, FormatAction.HEADING)))
        assertEquals("it«»em", marked(apply("- item", 4, 4, FormatAction.BULLET)))
    }

    /**
     * `- [x] done` is already a task. A `startsWith("- [ ] ")` check would say otherwise and prepend
     * a second marker, which is exactly why the check scans instead.
     */
    @Test
    fun aCompletedTaskCountsAsAlreadyBeingATask() {
        assertEquals("do«»ne", marked(apply("- [x] done", 8, 8, FormatAction.TASK)))
    }

    /** Otherwise `# ` on a bullet yields `# - item`. */
    @Test
    fun aDifferentBlockMarkerIsReplacedRatherThanStacked() {
        assertEquals("# it«»em", marked(apply("- item", 4, 4, FormatAction.HEADING)))
        assertEquals("- [ ] qu«»ote", marked(apply("> quote", 4, 4, FormatAction.TASK)))
    }

    /** Removal uses the scanner's marker range, so extra spaces go with it. */
    @Test
    fun removalTakesTheWholeMarkerIncludingExtraSpacing() {
        assertEquals("Heading", apply("##   Heading", 8, 8, FormatAction.HEADING).body)
    }

    @Test
    fun everyLineOfAMultiLineSelectionGetsTheMarker() {
        val result = apply("one\ntwo\nthree", 0, 13, FormatAction.BULLET)
        assertEquals("- one\n- two\n- three", result.body)
        // The selection keeps covering *text*: an offset sitting exactly where a marker is inserted
        // moves past it rather than growing to include syntax the user never selected.
        assertEquals(2, result.start)
        assertEquals(result.body.length, result.end)
    }

    /** Partial coverage completes the list rather than clearing it — every list editor does this. */
    @Test
    fun aPartlyMarkedSelectionIsCompletedNotCleared() {
        assertEquals("- one\n- two", apply("- one\ntwo", 0, 9, FormatAction.BULLET).body)
    }

    @Test
    fun aFullyMarkedSelectionIsCleared() {
        assertEquals("one\ntwo", apply("- one\n- two", 0, 11, FormatAction.BULLET).body)
    }

    @Test
    fun linesOutsideTheSelectionAreUntouched() {
        assertEquals("keep\n- one\nkeep", apply("keep\none\nkeep", 5, 8, FormatAction.BULLET).body)
    }

    // --- fences are refused --------------------------------------------------

    @Test
    fun aSelectionInsideAFenceIsNotWritable() {
        val body = "before\n```\ncode here\n```\nafter"
        val inside = body.indexOf("code")
        assertFalse(MarkdownFormat.isWritable(body, inside, inside + 4))
    }

    @Test
    fun aSelectionSpanningAFenceBoundaryIsNotWritable() {
        val body = "before\n```\ncode\n```\nafter"
        assertFalse(MarkdownFormat.isWritable(body, 0, body.length))
    }

    @Test
    fun aCaretOnTheFenceMarkerItselfIsNotWritable() {
        val body = "before\n```\ncode\n```\nafter"
        val fence = body.indexOf("```")
        assertFalse(MarkdownFormat.isWritable(body, fence, fence))
    }

    /**
     * The positive control. Every assertion above is `assertFalse`, so an `isWritable` that returned
     * false unconditionally would satisfy all of them and disable the toolbar permanently.
     */
    @Test
    fun ordinaryProseIsWritable() {
        assertTrue(MarkdownFormat.isWritable("just some prose", 0, 4))
        assertTrue(MarkdownFormat.isWritable("", 0, 0))
        val body = "before\n```\ncode\n```\nafter"
        assertTrue("text before a fence is unaffected by it", MarkdownFormat.isWritable(body, 0, 6))
        assertTrue("and text after it", MarkdownFormat.isWritable(body, 20, 25))
    }

    /** A caller that ignores [MarkdownFormat.isWritable] still must not be able to corrupt a fence. */
    @Test
    fun applyingInsideAFenceChangesNothing() {
        val body = "```\ncode\n```"
        val inside = body.indexOf("code")
        FormatAction.entries.forEach { action ->
            assertEquals(
                "$action must be a no-op inside a fence",
                body,
                apply(body, inside, inside + 4, action).body,
            )
        }
    }

    // --- totality ------------------------------------------------------------

    /**
     * Positive control for the suite: every action must actually do something to ordinary prose.
     * Without this an `apply` that returned its input unchanged would pass
     * [applyingInsideAFenceChangesNothing] and nothing here would notice the toolbar was inert.
     */
    @Test
    fun everyActionChangesSomething() {
        assertEquals(
            "FormatAction gained an entry without a case in this suite",
            9,
            FormatAction.entries.size,
        )
        FormatAction.entries.forEach { action ->
            val result = apply("plain text", 0, 5, action)
            assertTrue("$action left the body untouched", result.body != "plain text")
        }
    }
}

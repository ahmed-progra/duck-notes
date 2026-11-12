package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MarkdownScanner.toggleTaskAt] and the [Token.box] it reads.
 *
 * Two claims are load-bearing and each has a plant behind it:
 *
 * - **The box is carried out of the scan, not re-derived.** `markerStart + 2` is right on `- [ ] x`
 *   and wrong on `-   [ ] x`, so the spacing cases are not padding — they are the difference between
 *   a tap target on the box and a tap target on a space.
 * - **The guard is the box, not the line.** A tap on the task's text is a caret placement. Widening
 *   the check to "anywhere on a task line" is the P4 plant.
 *
 * Fence-correctness is inherited from the [LineCarry] walk rather than written, so it is tested
 * against the real thing rather than a mocked carry — the same argument `hasOpenTask` makes.
 */
class TaskToggleTest {

    // --- the box comes from the scan --------------------------------------------

    @Test
    fun anOpenBoxTogglesToDone() =
        assertEquals("- [x] milk", MarkdownScanner.toggleTaskAt("- [ ] milk", 3))

    @Test
    fun aDoneBoxTogglesBackToOpen() =
        assertEquals("- [ ] milk", MarkdownScanner.toggleTaskAt("- [x] milk", 3))

    @Test
    fun anUppercaseDoneBoxTogglesToOpen() =
        assertEquals("- [ ] milk", MarkdownScanner.toggleTaskAt("- [X] milk", 3))

    @Test
    fun everyOffsetInsideTheBoxToggles() = (BOX_START..BOX_END).forEach {
        assertEquals("offset $it is inside `[ ]`", "- [x] milk", MarkdownScanner.toggleTaskAt(TASK, it))
    }

    /**
     * **The case `markerStart + 2` gets wrong.** Extra spacing after the bullet is legal and moves
     * the box three characters right; a re-derived offset would rewrite a space into an `x` and
     * leave the box untouched. This is why [Token.box] exists rather than an arithmetic rule in
     * `SpanPlanner`.
     */
    @Test
    fun extraSpacingAfterTheBulletMovesTheBox() {
        assertEquals("-   [x] milk", MarkdownScanner.toggleTaskAt(SPACED, 5))
        assertNull("offset 2 is a space between the bullet and the box", MarkdownScanner.toggleTaskAt(SPACED, 2))
    }

    /** Markers stack, and the box's offsets are absolute into the whole line, not the substring. */
    @Test
    fun aTaskInsideAQuoteKeepsItsBoxOffsets() =
        assertEquals("> - [x] milk", MarkdownScanner.toggleTaskAt("> - [ ] milk", 5))

    // --- the guard is the box, not the line -------------------------------------

    /** **P4's target.** A tap on the word is a caret placement; toggling would make it unselectable. */
    @Test
    fun aTapOnTheTaskTextDoesNotToggle() {
        assertNull("offset 6 is the `m` of milk", MarkdownScanner.toggleTaskAt(TASK, 6))
        assertNull("offset 0 is the bullet", MarkdownScanner.toggleTaskAt(TASK, 0))
        assertNull("offset 5 is the space after `]`", MarkdownScanner.toggleTaskAt(TASK, 5))
    }

    @Test
    fun aPlainBulletHasNoBox() = assertNull(MarkdownScanner.toggleTaskAt("- milk", 2))

    @Test
    fun anOffsetPastTheEndTogglesNothing() = assertNull(MarkdownScanner.toggleTaskAt(TASK, 99))

    // --- fence-correctness, inherited from the carry -----------------------------

    /** **P3's target.** A fenced line never reaches `scanBlockPrefix`, so it has no box to find. */
    @Test
    fun aTaskInsideAFenceIsNotTogglable() {
        val body = "```\n- [ ] milk\n```"
        assertNull("a fenced `- [ ]` is code, not a task", MarkdownScanner.toggleTaskAt(body, FENCED_BOX))
    }

    @Test
    fun aTaskAfterAClosedFenceIsTogglable() {
        val body = "```\ncode\n```\n- [ ] milk"
        val at = body.indexOf("- [ ]") + 3
        assertEquals("```\ncode\n```\n- [x] milk", MarkdownScanner.toggleTaskAt(body, at))
    }

    // --- the note, not the line --------------------------------------------------

    @Test
    fun onlyTheAddressedTaskChanges() {
        val body = "- [ ] one\n- [ ] two\n- [ ] three"
        assertEquals(
            "- [ ] one\n- [x] two\n- [ ] three",
            MarkdownScanner.toggleTaskAt(body, body.indexOf("- [ ] two") + 3),
        )
    }

    @Test
    fun taskOffsetsPreserveEveryLineSeparator() {
        listOf(
            "نص😀\n- [ ] أول\n- [ ] ثان" to "نص😀\n- [ ] أول\n- [x] ثان",
            "نص😀\r\n- [ ] أول\r\n- [ ] ثان" to "نص😀\r\n- [ ] أول\r\n- [x] ثان",
            "نص😀\r- [ ] أول\r- [ ] ثان" to "نص😀\r- [ ] أول\r- [x] ثان",
            "نص😀\r\n- [ ] أول\r- [ ] ثان\nنهاية" to "نص😀\r\n- [ ] أول\r- [x] ثان\nنهاية",
        ).forEach { (body, expected) ->
            assertEquals(expected, MarkdownScanner.toggleTaskAt(body, body.lastIndexOf("[ ]") + 1))
        }
    }

    @Test
    fun crlfFenceCarrySkipsCodeAndReachesTheFollowingTask() {
        val body = "تم😀\r\n```\r\n- [ ] code\r\n```\r\n- [ ] حقيقي"
        assertNull(MarkdownScanner.toggleTaskAt(body, body.indexOf("[ ]") + 1))
        assertEquals(
            "تم😀\r\n```\r\n- [ ] code\r\n```\r\n- [x] حقيقي",
            MarkdownScanner.toggleTaskAt(body, body.lastIndexOf("[ ]") + 1),
        )
    }

    @Test
    fun unmatchedFenceAndInlineCodeKeepTaskTextLiteral() {
        val fenced = "نص😀\r\n~~~\n- [ ] code\rmore"
        assertNull(MarkdownScanner.toggleTaskAt(fenced, fenced.indexOf("[ ]") + 1))
        val inline = "نص😀\r\n`- [ ] code`"
        assertNull(MarkdownScanner.toggleTaskAt(inline, inline.indexOf("[ ]") + 1))
    }

    /**
     * **Round trip through the real parser**, not a string comparison — the shape
     * `MarkdownFormatTest.everyMarkerScansBackAsItsOwnKind` and `TagScanner.isValidName` both use.
     * The output is fed back to the scanner and asserted to be the opposite kind, so the toggle and
     * the reader cannot drift apart on what `[x]` means.
     */
    @Test
    fun aToggledLineScansBackAsTheOppositeKind() {
        val done = MarkdownScanner.toggleTaskAt(TASK, 3)
        assertEquals(listOf(TokenKind.TASK_DONE), kindsOf(done))
        assertEquals(listOf(TokenKind.TASK_OPEN), kindsOf(MarkdownScanner.toggleTaskAt(done!!, 3)))
    }

    private fun kindsOf(line: String?) =
        MarkdownScanner.scanLine(line!!, 0, LineCarry()).first.map { it.kind }

    private companion object {
        const val TASK = "- [ ] milk"
        const val BOX_START = 2
        const val BOX_END = 4
        const val SPACED = "-   [ ] milk"

        /** The state char of the fenced `- [ ]`: four for "```\n", then past "- [". */
        const val FENCED_BOX = 4 + 3
    }
}

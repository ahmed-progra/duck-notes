package app.duck.notes.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tag-syntax contract, case by case. `#` is overloaded across headings, code, URL fragments and
 * anchors, so each rejection below is a distinct way a naive parser writes a junk tag row.
 */
class ParseTagsTest {

    private val parseTags = ParseTags()

    private fun assertTags(expected: List<String>, body: String) =
        assertEquals("body=<$body>", expected, parseTags(body))

    // --- The three documented forms -----------------------------------------

    @Test
    fun simpleTag() = assertTags(listOf("tag"), "#tag")

    @Test
    fun multiWordTagClosedByHash() = assertTags(listOf("multi word tag"), "#multi word tag#")

    @Test
    fun nestedTagStaysWhole() = assertTags(listOf("projects/duck/ui"), "#projects/duck/ui")

    @Test
    fun tagAmongProse() =
        assertTags(listOf("duck", "notes"), "Feeding the #duck while taking #notes today.")

    // --- Closed vs open form disambiguation ---------------------------------

    @Test
    fun twoAdjacentTagsAreNotOneClosedTag() = assertTags(listOf("a", "b"), "#a #b")

    @Test
    fun closedFormRequiresNoSpaceBeforeClosingHash() =
        assertTags(listOf("multi word tag", "single"), "#multi word tag# and #single")

    @Test
    fun closedFormDoesNotSpanLines() = assertTags(listOf("multi"), "#multi\nword#")

    @Test
    fun closedFormCollapsesInnerWhitespace() = assertTags(listOf("a b"), "#a   b#")

    // --- Headings ------------------------------------------------------------

    @Test
    fun atxHeadingIsNotATag() = assertTags(emptyList(), "# Heading")

    @Test
    fun indentedHeadingIsNotATag() = assertTags(emptyList(), "   # Indented heading")

    @Test
    fun headingRestOfLineIsStillScanned() = assertTags(listOf("q3"), "## Sprint #q3")

    @Test
    fun bareHashesAreNotTags() = assertTags(emptyList(), "#\n# \n##\n### ")

    // --- Code ---------------------------------------------------------------

    @Test
    fun inlineCodeIsMasked() = assertTags(emptyList(), "`#code`")

    @Test
    fun tagOutsideInlineCodeStillCounts() = assertTags(listOf("b"), "`#a` #b")

    @Test
    fun backtickFenceIsMasked() = assertTags(emptyList(), "```\n#x\n```")

    @Test
    fun tildeFenceIsMasked() = assertTags(emptyList(), "~~~\n#y\n~~~")

    @Test
    fun tagsSurvivingAroundAFence() = assertTags(listOf("a", "c"), "#a\n```\n#b\n```\n#c")

    @Test
    fun unclosedBacktickMasksNothing() = assertTags(listOf("real"), "` #real")

    // --- URLs, anchors, escapes ---------------------------------------------

    @Test
    fun urlFragmentIsNotATag() = assertTags(emptyList(), "see https://x.com/page#frag")

    @Test
    fun markdownLinkAnchorIsNotATag() = assertTags(emptyList(), "[Jump to notes](#introduction)")

    @Test
    fun imageAnchorIsNotATag() = assertTags(emptyList(), "![diagram](#figure-one)")

    @Test
    fun bareParenthesisStillOpensATag() = assertTags(listOf("sidenote"), "(#sidenote)")

    @Test
    fun bracketStillOpensATag() = assertTags(listOf("bracketed"), "[#bracketed]")

    @Test
    fun hashInsideAWordIsNotATag() = assertTags(emptyList(), "email me at a#b")

    @Test
    fun escapedHashIsNotATag() = assertTags(emptyList(), "\\#literal")

    // --- Normalization -------------------------------------------------------

    @Test
    fun caseCollapsesToOneTag() = assertTags(listOf("work"), "#Work and #work")

    @Test
    fun duplicatesCollapse() = assertTags(listOf("dup"), "#dup #dup")

    @Test
    fun firstAppearanceOrderIsPreserved() = assertTags(listOf("zebra", "apple"), "#zebra #apple")

    @Test
    fun allDigitTagsAreRejected() = assertTags(emptyList(), "see issue #1 filed in #2024")

    @Test
    fun trailingSeparatorIsTrimmed() = assertTags(listOf("trailing"), "#trailing/")

    @Test
    fun punctuationTerminatesAnOpenTag() = assertTags(listOf("tag", "two"), "#tag, #two.")

    @Test
    fun dashesAndUnderscoresAreTagChars() =
        assertTags(listOf("tag-name", "tag_name"), "#tag-name #tag_name")

    @Test
    fun unicodeLettersAreTagChars() = assertTags(listOf("日本語"), "#日本語")

    // --- Nothing to find -----------------------------------------------------

    @Test
    fun emptyBody() = assertTags(emptyList(), "")

    @Test
    fun whitespaceOnlyBody() = assertTags(emptyList(), "   \n\t\n")

    @Test
    fun proseWithoutTags() = assertTags(emptyList(), "no tags here at all")

    @Test
    fun tagsAcrossMultipleLines() = assertTags(listOf("one", "two"), "text #one\ntext #two")

    // --- The caret: suppressing the tag being typed --------------------------
    //
    // Everything above is the syntax and is unchanged by these — a caret filters what `TagScanner`
    // found, it never changes what counts as a tag. If any case above ever needs editing to
    // accommodate a caret, the suppression has leaked into the syntax and the design is wrong.

    private fun assertTags(expected: List<String>, body: String, caret: Int) =
        assertEquals("body=<$body> caret=$caret", expected, parseTags(body, caret))

    @Test
    fun aTagEndingAtTheCaretIsStillBeingTyped() = assertTags(emptyList(), "#work", caret = 5)

    @Test
    fun everyPrefixOfATagIsSuppressedAsItIsTyped() =
        listOf("#w", "#wo", "#wor", "#work").forEach { assertTags(emptyList(), it, it.length) }

    /** One character past the tag and it is finished — this is what makes the tag ever appear. */
    @Test
    fun aTagFollowedByASpaceIsNotSuppressed() = assertTags(listOf("work"), "#work ", caret = 6)

    @Test
    fun onlyTheTagAtTheCaretIsSuppressed() =
        assertTags(listOf("work"), "#work and #home", caret = 15)

    @Test
    fun aCaretInsideATagButNotAtItsEndSuppressesNothing() =
        assertTags(listOf("work"), "#work", caret = 3)

    /** The closed form ends at its `#`, so the caret has to be past that, not past the name. */
    @Test
    fun aClosedMultiWordTagIsSuppressedAtItsClosingHash() =
        assertTags(emptyList(), "#multi word tag#", caret = 16)

    /**
     * The ceiling, pinned so it cannot regress silently: while the closed form is still open, its
     * prefix scans as a complete open-form tag ending at the space, not at the caret.
     */
    @Test
    fun anUnterminatedMultiWordTagStillMintsItsFirstWord() =
        assertTags(listOf("multi"), "#multi word", caret = 11)

    // --- The caret is total: no bounds guard exists, and none is needed ------

    @Test
    fun aCaretPastTheBodyParsesAsThoughItWereAbsent() =
        assertTags(listOf("work"), "#work", caret = 99)

    @Test
    fun aNegativeCaretParsesAsThoughItWereAbsent() =
        assertTags(listOf("work"), "#work", caret = -1)

    @Test
    fun anExplicitNullCaretMatchesTheSingleArgumentForm() {
        val bodies = listOf("#tag", "#multi word tag#", "## Sprint #q3", "`#code`", "/x#frag", "")
        bodies.forEach { assertEquals(it, parseTags(it), parseTags(it, caret = null)) }
    }
}

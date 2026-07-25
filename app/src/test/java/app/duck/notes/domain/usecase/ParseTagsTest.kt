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
}

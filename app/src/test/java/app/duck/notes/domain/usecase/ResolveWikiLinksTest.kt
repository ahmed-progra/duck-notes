package app.duck.notes.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveWikiLinksTest {

    private val resolveWikiLinks = ResolveWikiLinks()

    @Test
    fun extractsALink() = assertEquals(listOf("Duck plans"), resolveWikiLinks("See [[Duck plans]]."))

    @Test
    fun extractsSeveralInOrder() =
        assertEquals(listOf("First", "Second"), resolveWikiLinks("[[First]] then [[Second]]"))

    @Test
    fun dedupes() = assertEquals(listOf("Same"), resolveWikiLinks("[[Same]] and [[Same]]"))

    @Test
    fun trimsSurroundingSpace() = assertEquals(listOf("Padded"), resolveWikiLinks("[[  Padded  ]]"))

    @Test
    fun preservesCasingForTitleMatching() =
        assertEquals(listOf("Duck Plans"), resolveWikiLinks("[[Duck Plans]]"))

    @Test
    fun ignoresEmptyBrackets() = assertEquals(emptyList<String>(), resolveWikiLinks("[[]] and [[   ]]"))

    @Test
    fun ignoresAMarkdownLink() =
        assertEquals(emptyList<String>(), resolveWikiLinks("[text](https://example.com)"))

    @Test
    fun ignoresASingleBracketPair() = assertEquals(emptyList<String>(), resolveWikiLinks("[not a link]"))

    @Test
    fun spansLines() = assertEquals(listOf("A", "B"), resolveWikiLinks("[[A]]\n\n[[B]]"))

    @Test
    fun emptyBody() = assertEquals(emptyList<String>(), resolveWikiLinks(""))
}

package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/** Plant WIKI_SUFFIX_RESCAN: restore matchAt's suffix-wide indexOf before its bracket check. */
class WikiLinkAvailabilityTest {
    @Test(timeout = 3_000)
    fun malformedBracketsDoNotBlockTheRealEditorScan() {
        val body = "[".repeat(512_000) + "[[Pond]]"
        val scan = MarkdownTextCache().scan(body)
        assertEquals(1, scan.tokens.count { it.kind == TokenKind.WIKI_LINK })
    }

    /** Plant LINK_SUFFIX_RESCAN: restore linkAt's independent indexOf searches. */
    @Test(timeout = 3_000)
    fun malformedRegularLinksDoNotBlockTheRealEditorScan() {
        val body = "[x".repeat(512_000) + "]! [[Pond]]"
        assertEquals(1, MarkdownTextCache().scan(body).tokens.count { it.kind == TokenKind.WIKI_LINK })
    }

    @Test(timeout = 3_000)
    fun missingDestinationClosersDoNotBlockTheRealEditorScan() {
        val body = "[x](".repeat(512_000) + " [[Pond]]"
        assertEquals(1, MarkdownTextCache().scan(body).tokens.count { it.kind == TokenKind.WIKI_LINK })
    }

    @Test fun advancingClosersPreservesExistingLinkAndImageGrammar() {
        val body = ") [bad] nope [good](ok) ![image](file) [outer [inner](dest)"
        assertEquals(listOf("[good](ok)", "![image](file)", "[outer [inner](dest)"),
            MarkdownTextCache().scan(body).tokens.filter { it.kind == TokenKind.LINK || it.kind == TokenKind.IMAGE }
                .map { body.substring(it.start, it.end) })
    }

    @Test(timeout = 3_000)
    fun malformedBracketsDoNotBlockSavedLinkExtraction() {
        assertEquals(listOf("Pond"), WikiLinkScanner.titlesIn("[".repeat(512_000) + "[[Pond]]"))
    }

    @Test(timeout = 3_000)
    fun malformedBracketsDoNotBlockTitleStripping() {
        val prefix = "[".repeat(512_000)
        assertEquals(prefix + "Pond", WikiLinkScanner.stripInline(prefix + "[[Pond]]"))
    }
}

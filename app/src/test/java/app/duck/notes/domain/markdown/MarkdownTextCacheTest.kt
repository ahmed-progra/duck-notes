package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The incremental scan has one job: be indistinguishable from a full rescan. Everything here either
 * proves that equivalence or proves the cache is safe to call from inside an `OutputTransformation`.
 */
class MarkdownTextCacheTest {

    private fun fullScan(text: String) = MarkdownTextCache.rescan(ScanResult.Empty, text)

    private fun assertMatchesFullScan(cache: MarkdownTextCache, text: String) {
        val incremental = cache.scan(text)
        val full = fullScan(text)
        assertEquals("text=<$text>", full.tokens, incremental.tokens)
        assertEquals("carry for <$text>", full.lineCarry, incremental.lineCarry)
        assertEquals("offsets for <$text>", full.lineStarts, incremental.lineStarts)
    }

    // --- Equivalence across an edit sequence ---------------------------------

    @Test
    fun incrementalMatchesFullScanAcrossTyping() {
        val cache = MarkdownTextCache()
        val steps = listOf(
            "",
            "#",
            "# H",
            "# Heading",
            "# Heading\n",
            "# Heading\nsome **bold** text",
            "# Heading\nsome **bold** text\n- [ ] a task",
            "# Heading\nsome **bold** and _italic_ text\n- [ ] a task",
            "# Heading\nsome **bold** and _italic_ text\n- [x] a task\n#tag/nested",
        )
        steps.forEach { assertMatchesFullScan(cache, it) }
    }

    @Test
    fun openingAFenceRestylesEverythingAfterIt() {
        val cache = MarkdownTextCache()
        cache.scan("#a\n**x**\n#b")
        // Typing an opening fence must ripple: the lines below are now fence bodies, not markdown.
        assertMatchesFullScan(cache, "#a\n```\n**x**\n#b")
    }

    @Test
    fun closingAFenceRestylesEverythingAfterIt() {
        val cache = MarkdownTextCache()
        cache.scan("```\n**x**\n#b")
        assertMatchesFullScan(cache, "```\n**x**\n```\n#b")
    }

    @Test
    fun editingDeepInADocumentMatchesFullScan() {
        val body = (1..50).joinToString("\n") { "line $it with **bold** and #tag$it" }
        val cache = MarkdownTextCache()
        cache.scan(body)
        assertMatchesFullScan(cache, body.replace("line 30", "line 30 edited"))
    }

    @Test
    fun deletingLinesMatchesFullScan() {
        val cache = MarkdownTextCache()
        cache.scan("#a\n#b\n#c\n#d")
        assertMatchesFullScan(cache, "#a\n#d")
    }

    @Test
    fun appendingLinesMatchesFullScan() {
        val cache = MarkdownTextCache()
        cache.scan("#a\n#b")
        assertMatchesFullScan(cache, "#a\n#b\n#c\n#d")
    }

    // --- Offsets -------------------------------------------------------------

    @Test
    fun crlfKeepsOffsetsAligned() {
        val text = "#a\r\n**bold**\r\n#c"
        val result = fullScan(text)
        result.tokens.forEach { token ->
            assertTrue("token $token out of bounds for length ${text.length}", token.end <= text.length)
        }
        // The bold node must land exactly on the literal `**bold**` in the source.
        val bold = result.tokens.single { it.kind == TokenKind.BOLD }
        assertEquals("**bold**", text.substring(bold.start, bold.end))
    }

    @Test
    fun crlfIncrementalMatchesFullScan() {
        val cache = MarkdownTextCache()
        cache.scan("#a\r\n**bold**\r\n#c")
        assertMatchesFullScan(cache, "#a\r\n**bolder**\r\n#c")
    }

    @Test
    fun everyTokenIsWithinBounds() {
        val text = "# H\n> quote\n- [ ] task\n`code` **b** _i_ ==h== ~~s~~\n[l](u) ![i](u) [[w]] #t\n---"
        fullScan(text).tokens.forEach {
            assertTrue("$it start", it.start in 0..text.length)
            assertTrue("$it end", it.end in it.start..text.length)
            it.markers.forEach { m ->
                assertTrue("$it marker $m", m.first >= it.start && m.last < it.end)
            }
        }
    }

    // --- Cache purity (safe to call from inside transformOutput) -------------

    @Test
    fun repeatCallWithIdenticalTextReturnsTheSameInstance() {
        val cache = MarkdownTextCache()
        val first = cache.scan("# Heading\n**bold**")
        val second = cache.scan("# Heading\n**bold**")
        // Identical, not merely equal: a repeat call must not diff, rescan or publish anything.
        assertSame(first, second)
        assertSame(first, cache.scan("# Heading\n**bold**"))
    }

    @Test
    fun changedTextPublishesANewImmutableResult() {
        val cache = MarkdownTextCache()
        val first = cache.scan("#a")
        val second = cache.scan("#b")
        assertNotSame(first, second)
        // The old result is untouched — nothing mutates in place.
        assertEquals("#a", first.text)
    }

    @Test
    fun rescanIsPure() {
        val previous = fullScan("#a\n**b**")
        val once = MarkdownTextCache.rescan(previous, "#a\n**c**")
        val twice = MarkdownTextCache.rescan(previous, "#a\n**c**")
        assertEquals(once, twice)
        assertEquals("#a\n**b**", previous.text)
    }
}

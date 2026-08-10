package app.duck.notes.domain.markdown

import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.ResolveWikiLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CommonMark extension and `ParseTags` must return the same tags for the same body.
 *
 * `TagScanner` already makes that true by construction — both call it. This suite exists because
 * "by construction" is only as good as the wrapper around it: the inline parser has to hand the
 * scanner the right text, with the right preceding context, and consume exactly what it claims.
 * Those are the ways agreement can break without anyone reimplementing tag syntax, and they are what
 * these cases catch.
 *
 * The bodies are the 37 pinned cases from `ParseTagsTest` — the tag-syntax contract — plus every
 * sample the editor's golden suite runs on.
 */
class TagAgreementTest {

    private val parseTags = ParseTags()
    private val resolveWikiLinks = ResolveWikiLinks()

    /** Verbatim from ParseTagsTest. Kept as data so both parsers face the identical contract. */
    private val pinnedBodies = listOf(
        "#tag",
        "#multi word tag#",
        "#projects/duck/ui",
        "Feeding the #duck while taking #notes today.",
        "#a #b",
        "#multi word tag# and #single",
        "#multi\nword#",
        "#a   b#",
        "# Heading",
        "   # Indented heading",
        "## Sprint #q3",
        "#\n# \n##\n### ",
        "`#code`",
        "`#a` #b",
        "```\n#x\n```",
        "~~~\n#y\n~~~",
        "#a\n```\n#b\n```\n#c",
        "` #real",
        "see https://x.com/page#frag",
        "[Jump to notes](#introduction)",
        "![diagram](#figure-one)",
        "(#sidenote)",
        "[#bracketed]",
        "email me at a#b",
        "\\#literal",
        "#Work and #work",
        "#dup #dup",
        "#zebra #apple",
        "see issue #1 filed in #2024",
        "#trailing/",
        "#tag, #two.",
        "#tag-name #tag_name",
        "#日本語",
        "",
        "   \n\t\n",
        "no tags here at all",
        "text #one\ntext #two",
    )

    @Test
    fun theCommonMarkExtensionAgreesWithParseTagsOnEveryPinnedBody() {
        pinnedBodies.forEach { body ->
            assertEquals(
                "tags disagree for body=<$body>",
                parseTags(body),
                DuckMarkdown.tags(body),
            )
        }
    }

    @Test
    fun theCommonMarkExtensionAgreesWithParseTagsOnTheGoldenCorpus() {
        // The assertion is inside the loop, so an empty corpus would report green over nothing.
        assertTrue("the golden corpus is empty", MarkdownCorpus.samples.isNotEmpty())
        MarkdownCorpus.samples.forEach { (name, body) ->
            assertEquals(
                "tags disagree for sample=$name body=<$body>",
                parseTags(body),
                DuckMarkdown.tags(body),
            )
        }
    }

    @Test
    fun theCommonMarkExtensionAgreesOnALargeSyntheticNote() {
        val body = MarkdownCorpus.synthetic(2_000)
        assertEquals(parseTags(body), DuckMarkdown.tags(body))
    }

    @Test
    fun theEditorScannerAgreesWithParseTagsToo() {
        // The third view: what gets painted accent-red must be what gets written to note_tags.
        // MarkdownScanner and ParseTags reach TagScanner by different routes — the editor splits
        // lines and tracks fences itself, TagScanner.scan does its own. This checks those two
        // traversals surface the same tags, which is not implied by them sharing the syntax rules.
        // Names are resolved by handing each painted range back to TagScanner rather than
        // re-normalising by hand: `#trailing/` is painted whole but names to `trailing`.
        (pinnedBodies + MarkdownCorpus.samples.map { it.second }).forEach { body ->
            val painted = MarkdownTextCache.rescan(ScanResult.Empty, body).tokens
                .filter { it.kind == TokenKind.TAG }
                .mapNotNull { TagScanner.scan(body.substring(it.start, it.end)).firstOrNull()?.name }
                .distinct()
            assertEquals("editor vs ParseTags for body=<$body>", parseTags(body), painted)
        }
    }

    // --- Wiki links ----------------------------------------------------------

    /** What the editor actually paints as a wiki link, by name, in source order. */
    private fun painted(body: String): List<String> =
        MarkdownTextCache.rescan(ScanResult.Empty, body).tokens
            .filter { it.kind == TokenKind.WIKI_LINK }
            .map { body.substring(it.start + 2, it.end - 2).trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * The editor paints exactly the wiki links `ResolveWikiLinks` finds — across the **whole**
     * corpus, code fences and inline spans included.
     *
     * Both now route through `WikiLinkScanner`, so what gets written to `note_links` on save and what
     * gets painted accent-red can no longer disagree. This test once excluded `wikiLinkInFence` and
     * `wikiLinkInCodeSpan` behind a `WIKI_LINK_CODE_MASKING_DEFECT` set, and a companion test pinned
     * that divergence as a DEFECT: the bare regex behind `ResolveWikiLinks` (and its byte-identical
     * copy in `DeriveTitle`) resolved a `[[link]]` inside code that the editor correctly refused to
     * paint — the Phase 4 defect, one rule with three implementations. Extracting `WikiLinkScanner`
     * beside `TagScanner` retired all three copies; the exclusion, the set and the pin went with them,
     * and the corpus regained full coverage here with no other edit — exactly as the pin's KDoc said.
     */
    @Test
    fun theEditorPaintsTheWikiLinksResolveWikiLinksFinds() {
        val bodies = listOf(
            "see [[Another Note]] here",
            "[[A]] and [[B]] and [[A]]",
            "[[  padded  ]]",
            "not a link: [single]",
            "unclosed [[oops",
            "empty [[]]",
            "nested [[a[b]]",
            "a [link](url) is not a wiki link",
        ) + MarkdownCorpus.samples.map { it.second }

        bodies.forEach { body ->
            assertEquals("editor vs ResolveWikiLinks for body=<$body>", resolveWikiLinks(body), painted(body))
        }
    }
}

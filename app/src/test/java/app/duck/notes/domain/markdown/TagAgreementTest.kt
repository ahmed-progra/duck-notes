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
     * The editor paints the wiki links `ResolveWikiLinks` finds — **everywhere the two can agree.**
     *
     * `MarkdownScanner` reimplements the bracket rule for highlighting, so this is worth holding.
     * What it is *not* is a check that the two implementations agree in general: they provably do
     * not, and [WIKI_LINK_CODE_MASKING_DEFECT] names the cases and carries the argument.
     *
     * Excluding them here rather than weakening the assertion is deliberate. A test that reported
     * agreement over inputs chosen to avoid the disagreement is what this file did until an audit
     * noticed, and it is worse than one that says plainly which cases are excluded and why.
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
        ) + MarkdownCorpus.samples
            .filterNot { it.first in WIKI_LINK_CODE_MASKING_DEFECT }
            .map { it.second }

        bodies.forEach { body ->
            assertEquals("editor vs ResolveWikiLinks for body=<$body>", resolveWikiLinks(body), painted(body))
        }
    }

    /**
     * **The known divergence, pinned rather than hidden. This test asserts a DEFECT.**
     *
     * `ResolveWikiLinks` is a bare `Regex("\\[\\[([^\\[\\]]+)]]")` over the whole body — no fence
     * tracking, no `codeSpanMask`. `MarkdownScanner` refuses both cases correctly. So a wiki link
     * written inside a code sample is **not painted** as a link and **is** resolved as one, which
     * from Phase 9 onward means a `note_links` row the user can neither see nor click.
     *
     * This is the exact failure `TagScanner` was extracted to prevent, in the other syntax: one rule,
     * three implementations. There are **three** call sites to retire, not the two the earlier note
     * claimed — `ResolveWikiLinks.kt:22`, `MarkdownScanner.wikiLinkAt`, and a byte-identical third
     * copy in `DeriveTitle.kt:44`, which mis-strips titles the same way.
     *
     * **It goes red when the defect is fixed, and that is when to delete it** — along with
     * [WIKI_LINK_CODE_MASKING_DEFECT], which restores full corpus coverage above with no other edit.
     * Same shape as `PartialTagMintingTest`: pin the behaviour that exists so the change that alters
     * it cannot be silent. Do **not** "fix" a red here by loosening the assertion.
     *
     * Closes when Phase 9 extracts `WikiLinkScanner` beside `TagScanner`, reusing `codeSpanMask` and
     * the fence carry, with `ResolveWikiLinks` and `MarkdownScanner` as thin views over it. Not before.
     */
    @Test
    fun aWikiLinkInsideCodeIsResolvedButNotPainted() {
        val cases = MarkdownCorpus.samples.filter { it.first in WIKI_LINK_CODE_MASKING_DEFECT }
        assertEquals("the defect's corpus samples went missing", 2, cases.size)

        cases.forEach { (name, body) ->
            assertEquals(
                "$name: ResolveWikiLinks still resolves a masked link — if this is now empty the " +
                    "defect is FIXED, so delete this test and WIKI_LINK_CODE_MASKING_DEFECT",
                listOf("Hidden"),
                resolveWikiLinks(body),
            )
            assertEquals(
                "$name: the editor correctly refuses to paint it",
                emptyList<String>(),
                painted(body),
            )
        }
    }

    private companion object {
        /**
         * Corpus samples where `ResolveWikiLinks` and the editor **disagree**, by name.
         *
         * One list, two consumers — [theEditorPaintsTheWikiLinksResolveWikiLinksFinds] excludes them
         * and [aWikiLinkInsideCodeIsResolvedButNotPainted] asserts them — so the exclusion and the
         * pin cannot drift apart, and deleting this list in Phase 9 makes both tests do the right
         * thing with no further edit.
         */
        val WIKI_LINK_CODE_MASKING_DEFECT = setOf("wikiLinkInFence", "wikiLinkInCodeSpan")
    }
}

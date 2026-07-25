package app.duck.notes.domain.markdown

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.front.matter.YamlFrontMatterExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Node
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/**
 * The real CommonMark parser — Duck's dialect, full AST, source spans included.
 *
 * **Cold path only.** This is for export (Phase 11) and AST queries like the table of contents and
 * backlinks (Phase 9). It is never called while typing: a full parse of a 60k-character note cannot
 * fit a 16.6 ms frame, which is why the editor runs [MarkdownScanner] instead. `DeriveTitle` also
 * stays hand-rolled for the same reason — it sits on the save path, and Phase 8 autosaves every
 * 400 ms.
 *
 * The parser is stateless and thread-safe once built, so one instance is shared.
 */
object DuckMarkdown {

    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
                AutolinkExtension.create(),
                FootnotesExtension.create(),
                YamlFrontMatterExtension.create(),
                DuckSyntaxExtension.create(),
            )
        )
        // Phase 9 needs to map a heading back to its offset for the table of contents, and Phase 11
        // needs it for round-tripping. Cheap here, impossible to add retroactively.
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build()

    fun parse(markdown: String): Node = parser.parse(markdown)

    /** Tag names in the document, deduped in first-appearance order — the AST's view of `ParseTags`. */
    fun tags(markdown: String): List<String> = collect<TagNodeMd>(parse(markdown)).map { it.name }.distinct()

    // No wikiLinks() here on purpose: `ResolveWikiLinks` already owns that syntax and nothing yet
    // reads a wiki-link AST node. See DuckSyntaxExtension for why adding one is Phase 9 work.

    private inline fun <reified T : Node> collect(root: Node): List<T> =
        buildList { walk(root.firstChild) { if (it is T) add(it) } }

    /** Depth-first over the AST in source order, which is what makes "first appearance" meaningful. */
    private fun walk(start: Node?, visit: (Node) -> Unit) {
        var node = start
        while (node != null) {
            visit(node)
            walk(node.firstChild, visit)
            node = node.next
        }
    }
}

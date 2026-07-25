package app.duck.notes.domain.markdown

import org.commonmark.node.CustomNode
import org.commonmark.parser.Parser
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Scanner

/** A `#tag` in the AST. [name] is already normalised by [TagScanner] — lowercased, whitespace collapsed. */
class TagNodeMd(val name: String) : CustomNode()

/**
 * Teaches CommonMark the tag syntax Duck adds.
 *
 * The parser here does not decide what a tag *is* — it hands the text to [TagScanner] and accepts
 * its answer. `ParseTags`, the editor highlighter and this extension are three views of one
 * implementation, so `TagAgreementTest` confirms a structural property rather than policing rival
 * parsers.
 *
 * **`[[WikiLink]]` is deliberately not here.** `[` is CommonMark's own bracket machinery, so a
 * custom inline parser on it never runs; changing bracket meaning requires a `LinkProcessor`. That
 * work is worth doing when something reads the resulting node — Phase 9's backlinks and table of
 * contents — and not before. Until then `ResolveWikiLinks` is the single implementation and the only
 * one that needs testing.
 */
class DuckSyntaxExtension private constructor() : Parser.ParserExtension {

    override fun extend(builder: Parser.Builder) {
        builder.customInlineContentParserFactory(TagInlineParserFactory)
    }

    companion object {
        fun create(): DuckSyntaxExtension = DuckSyntaxExtension()
    }
}

private object TagInlineParserFactory : InlineContentParserFactory {
    override fun getTriggerCharacters(): Set<Char> = setOf('#')
    override fun create(): InlineContentParser = TagInlineParser()
}

/**
 * Asks [TagScanner] whether a tag starts here, and consumes exactly what it says.
 *
 * CommonMark positions us on a trigger `#`. We read the rest of the line, rewind, and let the shared
 * scanner rule on it. Anything it rejects — `#1`, `a#b`, a bare `#` — falls through to ordinary
 * inline parsing, which is what makes this agree with `ParseTags` by construction rather than by
 * coincidence.
 *
 * One character of preceding context is prepended before scanning, because [TagScanner] gates a tag
 * on what comes before the `#`. The two-character `](` case it also handles cannot arise here:
 * CommonMark resolves `[text](dest)` into a link before custom inline parsers run, and never scans a
 * link destination for inline content.
 */
private class TagInlineParser : InlineContentParser {

    // Nullable: ParsedInline.none() is documented to return null, so a non-null Kotlin return type
    // turns "did not match" into a NullPointerException.
    override fun tryParse(state: InlineParserState): ParsedInline? {
        val scanner = state.scanner()
        val start = scanner.position()

        val previous = scanner.peekPreviousCodePoint()
        val prefix = when {
            previous == Scanner.END.code || previous < 0 -> ""
            else -> String(Character.toChars(previous))
        }

        // Read to end of line, then rewind — we only consume once the scanner has ruled.
        while (scanner.hasNext() && scanner.peek() != '\n') scanner.next()
        val rest = scanner.getSource(start, scanner.position()).content
        scanner.setPosition(start)

        val spans = mutableListOf<TagSpan>()
        TagScanner.scanLine(prefix + rest, 0, spans)
        val tag = spans.firstOrNull { it.start == prefix.length } ?: return ParsedInline.none()

        repeat(tag.end - tag.start) { scanner.next() }
        return ParsedInline.of(TagNodeMd(tag.name), scanner.position())
    }
}

package app.duck.notes.domain.markdown

/**
 * Word and character counts for the info panel — **over raw source, deliberately.**
 *
 * ## Why the markdown is counted rather than stripped
 *
 * `**bold**` is one word and eight characters. `# Heading` is two words and nine. `[[Pond]]`'s
 * brackets count. An `![alt](attachment:id)` counts as its whole markup. That is what the user typed,
 * and it is the only rule with nothing to keep in sync.
 *
 * The alternative — count some stripped form — is a **second implementation of markdown**, which is
 * the defect `MarkdownSyntaxSourceTest` exists to forbid and the one that split `[[WikiLink]]` three
 * ways until Phase 9a. A stripped count could only legally be built off the token stream, and would
 * then owe answers to questions nobody has asked: does a concealed marker count, does a folded
 * section count, does a link's destination count. Raw source owes none of them.
 *
 * **`DeriveTitle` is not reused, and that is not two answers to one question.** It answers *"what is
 * the one-line label for this note"* — it drops link destinations, keeps link text, collapses runs of
 * whitespace and stops at the first non-empty line. Every one of those is wrong here:
 * `[the docs](https://duck.example)` would count as two words while the user typed five words' worth
 * of characters.
 *
 * Lives in `domain/` rather than beside the panel for two reasons: `MarkdownSyntaxSourceTest` bans
 * `Regex(` above the scanner, and `gradlew test` has to be able to drive this with no emulator.
 *
 * It is **not** in [MarkdownScanner] despite sharing a package. That object owns Duck's syntax; a
 * counter that is deliberately blind to syntax would read there as a claim it does not make.
 */
object NoteStats {

    /**
     * Runs of non-whitespace in [body].
     *
     * A hand-rolled single pass rather than `split(Regex("\\s+"))`: the regex form allocates a list
     * the size of the note on every keystroke the panel is open, and it needs a filter afterwards
     * because splitting `"  a  "` yields empty leading and trailing entries. This allocates nothing.
     *
     * `Char.isWhitespace` rather than a literal set, so a non-breaking space or an ideographic space
     * separates words the way the reader expects.
     */
    fun words(body: String): Int {
        var count = 0
        var inWord = false
        for (char in body) {
            if (char.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }

    /**
     * Characters in [body], counted as **code points**.
     *
     * `body.length` is UTF-16 units, so it reports the duck emoji as 2 and every non-BMP character
     * the same way — a visibly wrong number on a surface whose whole job is to report a number. Java
     * interop is the shortest correct form here and there is no Kotlin stdlib equivalent.
     */
    fun characters(body: String): Int = body.codePointCount(0, body.length)
}

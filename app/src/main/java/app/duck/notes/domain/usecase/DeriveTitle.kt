package app.duck.notes.domain.usecase

import javax.inject.Inject

/**
 * The note title: first non-empty line with its markdown stripped, or `Untitled`.
 *
 * Hand-rolled rather than routed through a parser — CommonMark arrives in Phase 4 and can replace
 * this wholesale. It only has to handle the leading block marker and inline emphasis of one line.
 */
class DeriveTitle @Inject constructor() {

    operator fun invoke(body: String): String {
        for (line in body.lineSequence()) {
            val title = strip(line)
            if (title.isNotEmpty()) return title
        }
        return UNTITLED
    }

    private fun strip(line: String): String {
        // Markers stack — `> - [ ] text` is a task inside a quote — so peel until nothing changes.
        var text = line.trim()
        while (true) {
            val before = text
            text = text.removePrefix(">").trimStart()
            text = text.replace(BLOCK_MARKER, "")
            text = text.replace(TASK_MARKER, "")
            if (text == before) break
        }
        // Images before links: running the link rule first would leave `![alt](url)` as `!alt`.
        text = text.replace(IMAGE, "$1")
        text = text.replace(WIKI_LINK, "$1")
        text = text.replace(LINK, "$1")
        text = text.replace(EMPHASIS, "")
        return text.replace(WHITESPACE, " ").trim()
    }

    private companion object {
        const val UNTITLED = "Untitled"
        val BLOCK_MARKER = Regex("^(#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+)")
        val TASK_MARKER = Regex("^\\[[ xX]]\\s*")
        val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
        val WIKI_LINK = Regex("\\[\\[([^\\[\\]]+)]]")
        val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
        val EMPHASIS = Regex("\\*\\*|__|~~|==|[*_`]")
        val WHITESPACE = Regex("\\s+")
    }
}

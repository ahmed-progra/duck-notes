package app.duck.notes.domain.markdown

/**
 * The 20 samples the golden and invariant suites both run over.
 *
 * Chosen so that every token kind appears at least once, and so that the awkward cases are pinned:
 * markers at offset 0, markers at end-of-text, stacked block markers, unclosed delimiters, code
 * masking, and the tag forms that `ParseTagsTest` already pins from the other direction.
 */
object MarkdownCorpus {

    val samples: List<Pair<String, String>> = listOf(
        "plain" to "just some prose with no markup at all",
        "heading" to "# A heading",
        "headingDeep" to "### Third level",
        "bold" to "some **bold** text",
        "italicStar" to "some *italic* text",
        "italicUnderscore" to "some _italic_ text",
        "strike" to "some ~~struck~~ text",
        "highlight" to "some ==marked== text",
        "codeSpan" to "call `duck()` now",
        "link" to "see [the docs](https://duck.example) here",
        "image" to "![a duck](duck.png) inline",
        "wikiLink" to "see [[Another Note]] here",
        "tag" to "filed under #projects/duck today",
        "tagClosed" to "filed under #multi word tag# today",
        "quote" to "> a quoted line",
        "bullet" to "- a bullet item",
        "ordered" to "1. a numbered item",
        "taskOpen" to "- [ ] unfinished",
        "taskDone" to "- [x] finished",
        "divider" to "---",
        "fence" to "```\nnot **markdown** in here\n```",
        "stacked" to "> - [ ] a task inside a quote",
        "mixed" to "# Notes on **ducks**\n\nSee [[Pond]] and #birds, or `code`.\n\n- [x] feed them\n- [ ] count them",
        "unclosed" to "a lone ** marker and a lone ` tick",
        "adjacent" to "**a**_b_==c==",
        "markerAtStart" to "**bold at the very start**",
        "emptyLines" to "\n\n# after blanks\n\n",
    )

    /** A body of roughly [words] words with a realistic spread of markup. */
    fun synthetic(words: Int): String {
        val builder = StringBuilder()
        var produced = 0
        var paragraph = 0
        while (produced < words) {
            when (paragraph % 6) {
                0 -> { builder.append("## Section ").append(paragraph).append('\n'); produced += 3 }
                1 -> {
                    builder.append("Some prose about **ducks** and _ponds_ with a #tag").append(paragraph)
                        .append(" and a [link](https://duck.example/").append(paragraph).append(") in it.\n")
                    produced += 18
                }
                2 -> { builder.append("- [ ] task ").append(paragraph).append(" to do\n"); produced += 5 }
                3 -> { builder.append("> quoted remark number ").append(paragraph).append('\n'); produced += 5 }
                4 -> {
                    builder.append("More prose with `inline code`, ~~a correction~~ and [[A Wiki Link]].\n")
                    produced += 11
                }
                else -> { builder.append("Plain filler line number ").append(paragraph).append(".\n"); produced += 6 }
            }
            paragraph++
        }
        return builder.toString()
    }
}

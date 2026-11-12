package app.duck.notes.startup

/** Ordinary editable notes, saved in this order so the hub's targets already exist. */
internal object WelcomeNotes {
    // Explicit escapes keep the stored text LF-only regardless of checkout line endings.
    val bodies: List<String> = listOf(
        "# Get started\n\n" +
            "Make room for your first idea. These are ordinary notes: edit them, keep them, or delete them.\n\n" +
            "## Try Markdown\n\n" +
            "Write **bold words**, _italic words_, ~~finished words~~, and ==highlighted words==. " +
            "Use `inline code` for literal text. The formatting toolbar above the keyboard offers shortcuts; " +
            "you can also type the markers yourself. Move the caret away to see formatted text.\n\n" +
            "> A small thought can become a useful note.\n\n" +
            "- Keep one idea on each line.\n" +
            "1. Put a first step in order.\n" +
            "- [ ] Try tapping this checkbox.\n" +
            "- [x] You have opened your first guide.\n\n" +
            "---\n\n" +
            "A fenced block keeps its contents literal:\n\n" +
            "```text\n" +
            "Code stays literal here.\n" +
            "```\n\n" +
            "## Look inside a note\n\n" +
            "Open the Info panel with the toolbar's Backlinks button to see word and character counts for the raw Markdown source, " +
            "creation and modification dates, contents, and backlinks. Tap a heading in contents to jump " +
            "to it. Backlinks list notes that link to this note; tap one to open it.\n\n" +
            "#duck/welcome\n",

        "# Organize with tags\n\n" +
            "Give each idea a place. Add a tag to a note, then select that tag in the sidebar to see its notes.\n\n" +
            "## Tags that fit your notes\n\n" +
            "Type a tag with a hash sign. A slash creates a nested tag, such as `#projects/ideas`. " +
            "Close a tag containing spaces with another hash sign, as in `#weekend plans#`. " +
            "These code examples are literal text and do not add tags to this guide.\n\n" +
            "A fenced example also stays literal:\n\n" +
            "```text\n" +
            "#reference/later\n" +
            "```\n\n" +
            "The shared welcome tag gathers these guides. Select it in the sidebar to see them. " +
            "Deleting a tag keeps its notes.\n\n" +
            "## Find unfinished tasks\n\n" +
            "Choose Todo in the sidebar to show notes with an unchecked task. " +
            "The checkbox example in Get started gives you one to try.\n\n" +
            "#duck/welcome\n",

        "# Work faster\n\n" +
            "Connect a thought to another note. Type a note's title inside double square brackets, " +
            "as in `[[Get started]]`. Choose the existing title from suggestions, then tap the finished " +
            "link to open that note. This inline example is literal text.\n\n" +
            "A fenced link example also stays literal:\n\n" +
            "```text\n" +
            "[[Organize with tags]]\n" +
            "```\n\n" +
            "## Recognize your tags\n\n" +
            "Long-press a tag in the sidebar and choose Change icon to pick its icon. " +
            "Automatic icons use a small curated set of matching words. For nested tags, Duck tries " +
            "the last segment first, then earlier segments; unmatched names use the generic tag icon.\n\n" +
            "#duck/welcome\n",

        "# Welcome to Duck\n\n" +
            "A quiet place for your notes. Everything here works offline, and these guides are yours to change.\n\n" +
            "- [[Get started]] - format text and explore Info.\n" +
            "- [[Organize with tags]] - group notes with tags and find unfinished tasks.\n" +
            "- [[Work faster]] - connect notes and choose tag icons.\n\n" +
            "Select the welcome tag in the sidebar whenever you want to find these guides again. " +
            "They are added once; deleting them does not bring them back.\n\n" +
            "#duck/welcome\n",
    )
}

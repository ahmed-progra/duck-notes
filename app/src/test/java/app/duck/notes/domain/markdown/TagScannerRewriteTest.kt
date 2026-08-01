package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rewriting half of [TagScanner] — Phase 7's rename and delete.
 *
 * `ParseTagsTest`'s 37 cases pin what a tag *is*; these pin what happens when one is rewritten.
 * They are a specification too, and the reason is sharper here than for scanning: **this is the
 * only code in the project that edits the user's prose.** Everything else replaces a body the user
 * just typed. A wrong offset or a dropped closing `#` corrupts notes nobody is looking at.
 *
 * The fence and code-span cases are not defensive duplication of `ParseTagsTest`. They assert the
 * property that makes rename safe *as a rewrite*: a `#tag` the editor does not paint and `ParseTags`
 * does not store must also be text that rename does not touch. One implementation, so the three
 * agree by construction — these prove it stays that way.
 */
class TagScannerRewriteTest {

    // --- rename: the basic shapes -------------------------------------------

    @Test
    fun `renames a plain tag`() {
        assertEquals("see #job today", TagScanner.rename("see #work today", "work", "job"))
    }

    @Test
    fun `renames every occurrence`() {
        assertEquals(
            "#job and #job again",
            TagScanner.rename("#work and #work again", "work", "job"),
        )
    }

    @Test
    fun `leaves a body without the tag untouched, identically`() {
        val body = "nothing to do here #other"
        assertEquals(body, TagScanner.rename(body, "work", "job"))
    }

    /** `#working` is a different tag. A prefix match without the separator would eat it. */
    @Test
    fun `does not rename a tag that merely starts with the same letters`() {
        assertEquals("#working", TagScanner.rename("#working", "work", "job"))
    }

    // --- rename: descendants -------------------------------------------------

    @Test
    fun `renames descendants, splicing the remainder of the path`() {
        assertEquals(
            "#work #work/duck #work/duck/ui",
            TagScanner.rename("#projects #projects/duck #projects/duck/ui", "projects", "work"),
        )
    }

    @Test
    fun `renames a descendant without touching its parent`() {
        assertEquals(
            "#projects #projects/quack",
            TagScanner.rename("#projects #projects/duck", "projects/duck", "projects/quack"),
        )
    }

    /** Moving a tag under itself is legal and must terminate — one pass over the spans, not a loop. */
    @Test
    fun `renaming a tag into its own subtree terminates`() {
        assertEquals(
            "#a/x #a/x/b",
            TagScanner.rename("#a #a/b", "a", "a/x"),
        )
    }

    // --- rename: token form --------------------------------------------------

    @Test
    fun `preserves the closed form`() {
        assertEquals(
            "#multi word tag#",
            TagScanner.rename("#multi word tag#", "multi word tag", "multi word tag"),
        )
    }

    @Test
    fun `keeps a closed tag closed when renamed to a single word`() {
        assertEquals("#job#", TagScanner.rename("#multi word tag#", "multi word tag", "job"))
    }

    /**
     * The corrupting case: an open tag renamed to a name with a space **must** close, or the next
     * scan reads only the first word and the rest of the name becomes prose.
     */
    @Test
    fun `forces the closed form when the new name contains a space`() {
        val out = TagScanner.rename("#work today", "work", "day job")
        assertEquals("#day job# today", out)
        assertEquals(listOf("day job"), TagScanner.scan(out).map(TagSpan::name))
    }

    // --- rename: the offset trap --------------------------------------------

    /**
     * Two tags on one line. Rewriting forwards shifts the second span's offsets by the length
     * difference of the first, so this goes wrong only when the replacement changes length — which is
     * why the names here differ in length from their replacements in opposite directions.
     */
    @Test
    fun `rewrites multiple spans on one line without shifting later offsets`() {
        assertEquals(
            "#j start #j middle #j end",
            TagScanner.rename("#work start #work middle #work end", "work", "j"),
        )
    }

    @Test
    fun `rewrites correctly when the replacement is longer than the original`() {
        assertEquals(
            "#a-much-longer-name x #a-much-longer-name y",
            TagScanner.rename("#w x #w y", "w", "a-much-longer-name"),
        )
    }

    // --- rename: what it must not touch --------------------------------------

    @Test
    fun `does not rename inside a fenced block`() {
        val body = "#work\n\n```\n#work\n```\n\n#work"
        assertEquals("#job\n\n```\n#work\n```\n\n#job", TagScanner.rename(body, "work", "job"))
    }

    @Test
    fun `does not rename inside an inline code span`() {
        assertEquals(
            "#job and `#work`",
            TagScanner.rename("#work and `#work`", "work", "job"),
        )
    }

    @Test
    fun `does not rename a URL fragment`() {
        val body = "https://x.example/p#work"
        assertEquals(body, TagScanner.rename(body, "work", "job"))
    }

    @Test
    fun `does not rename an escaped hash`() {
        assertEquals("""\#work""", TagScanner.rename("""\#work""", "work", "job"))
    }

    @Test
    fun `does not rename an ATX heading marker`() {
        // The heading marker is not a tag; the tag later on the line is.
        assertEquals("# Heading #job", TagScanner.rename("# Heading #work", "work", "job"))
    }

    // --- remove --------------------------------------------------------------

    @Test
    fun `removes the tag and the space before it`() {
        assertEquals("buy milk", TagScanner.remove("buy milk #work", "work"))
    }

    @Test
    fun `removes a tag from mid-line without leaving a double space`() {
        assertEquals("a b", TagScanner.remove("a #work b", "work"))
    }

    @Test
    fun `removes a tag alone on its line, leaving an empty line rather than spaces`() {
        assertEquals("a\n\nb", TagScanner.remove("a\n#work\nb", "work"))
    }

    @Test
    fun `removes the closed form whole`() {
        assertEquals("a", TagScanner.remove("a #multi word tag#", "multi word tag"))
    }

    /**
     * Delete does not cascade. Rename must, because a child's name contains its parent's; delete
     * need not, because `projects/duck` stays a valid name once `projects` is gone.
     */
    @Test
    fun `removes only the exact tag, never its descendants`() {
        assertEquals(
            "#projects/duck #projects/duck/ui",
            TagScanner.remove("#projects #projects/duck #projects/duck/ui", "projects"),
        )
    }

    @Test
    fun `does not remove from a fenced block`() {
        assertEquals("```\n#work\n```", TagScanner.remove("```\n#work\n```", "work"))
    }

    /** Removing every tag from a body must leave a body `ParseTags` reads as untagged. */
    @Test
    fun `removing the last tag leaves nothing for ParseTags to find`() {
        val out = TagScanner.remove("note text #work", "work")
        assertEquals(emptyList<String>(), TagScanner.scan(out).map(TagSpan::name))
    }

    // --- token ---------------------------------------------------------------

    @Test
    fun `token uses the open form for a single word and the closed form for a phrase`() {
        assertEquals("#work", TagScanner.token("work"))
        assertEquals("#a/b/c", TagScanner.token("a/b/c"))
        assertEquals("#multi word tag#", TagScanner.token("multi word tag"))
    }

    @Test
    fun `every token round-trips to the name it was built from`() {
        listOf("work", "a/b/c", "multi word tag", "a-b_c").forEach { name ->
            assertEquals(name, TagScanner.scan(TagScanner.token(name)).single().name)
        }
    }

    // --- isValidName ---------------------------------------------------------

    @Test
    fun `accepts the names the scanner can produce`() {
        listOf("a", "work", "a/b/c", "multi word tag", "a-b_c", "duck2").forEach {
            assertTrue("expected $it to be valid", TagScanner.isValidName(it))
        }
    }

    /**
     * Every rejection here is inherited from [TagScanner.scan] rather than restated, which is the
     * point of validating by round trip. Uppercase is the one worth naming: names are lowercased for
     * storage because `tags.name` is a byte-comparison UNIQUE index, so `Work` cannot round-trip and
     * the validator enforces the storage rule without knowing it exists.
     */
    @Test
    fun `rejects the names it cannot`() {
        listOf(
            "",            // nothing
            "   ",         // whitespace only
            "123",         // all-digit: `see issue #1` must not be a tag
            "Work",        // normalize lowercases, so this cannot round-trip
            "a/",          // trailing separator is trimmed
            "a-",          // trailing dash is trimmed
            "a#b",         // closes the tag early
            " a",          // trimmed
            "a\nb",        // a name cannot span lines
        ).forEach {
            assertFalse("expected ${'"'}$it${'"'} to be invalid", TagScanner.isValidName(it))
        }
    }

    // --- the property the whole phase leans on -------------------------------

    /**
     * Rename is reversible on the body when no merge happened, which is what makes the undo snapshot
     * an honest inverse rather than an approximation.
     *
     * Not asserted as a general law — a rename **into an existing tag** genuinely is not reversible
     * by renaming back, which is exactly why undo restores bodies instead of applying an inverse
     * rename. This pins the case that holds so the distinction stays visible.
     */
    @Test
    fun `renaming back restores the body when the target did not already exist`() {
        val body = "#work start\n\n```\n#work\n```\n\n#work/x and `#work`"
        val renamed = TagScanner.rename(body, "work", "job")
        assertEquals(body, TagScanner.rename(renamed, "job", "work"))
    }
}

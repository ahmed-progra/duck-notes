package app.duck.notes.core.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tag → icon rule, and the specification for it.
 *
 * Three of the assertions here are **absence** claims — no duplicate keyword, never null, nothing
 * unmapped — and every one of them reports green if the mechanism underneath it is dead rather than
 * clean. Each therefore carries a positive control. The sharpest is
 * [aMatchResolvesToItsOwnIconNotTheFallback]: a resolver that returned [DuckIcon.Tag] for every input
 * would satisfy totality perfectly and be completely broken.
 */
class DuckIconTest {

    // --- resolution ---------------------------------------------------------

    @Test
    fun anUnmatchedTagFallsBackToTag() {
        assertEquals(DuckIcon.Tag, DuckIcon.forTag("zzzznotathing"))
        assertEquals(DuckIcon.Tag, DuckIcon.forTag(""))
        assertEquals(DuckIcon.Tag, DuckIcon.forTag("///"))
        assertEquals(DuckIcon.Tag, DuckIcon.forTag("   "))
    }

    /**
     * The positive control for totality. Without it, `forTag` could return [DuckIcon.Tag]
     * unconditionally and every other test in this file would still pass.
     */
    @Test
    fun aMatchResolvesToItsOwnIconNotTheFallback() {
        assertEquals(DuckIcon.Todo, DuckIcon.forTag("todo"))
        assertEquals(DuckIcon.Work, DuckIcon.forTag("office"))
        assertEquals(DuckIcon.Book, DuckIcon.forTag("reading"))
        assertEquals(DuckIcon.Code, DuckIcon.forTag("git"))
        assertNotEquals(DuckIcon.Tag, DuckIcon.forTag("todo"))
    }

    @Test
    fun canonicalizationMatchesSeparatorVariants() {
        listOf("todo", "TODO", "To-Do", "to_do", "to do", " todo ").forEach {
            assertEquals("«$it» should canonicalize to todo", DuckIcon.Todo, DuckIcon.forTag(it))
        }
    }

    /** Plurals and synonyms are listed, not stemmed — so both spellings must be present. */
    @Test
    fun listedSynonymsResolveButUnlistedInflectionsDoNot() {
        assertEquals(DuckIcon.Todo, DuckIcon.forTag("task"))
        assertEquals(DuckIcon.Todo, DuckIcon.forTag("tasks"))
        // "tasking" is not in the table, and nothing infers it. That is the design, not a gap.
        assertEquals(DuckIcon.Tag, DuckIcon.forTag("tasking"))
    }

    @Test
    fun nestedTagsResolveLeafFirstThenWalkUp() {
        // Leaf wins when it matches.
        assertEquals(DuckIcon.Code, DuckIcon.forTag("work/code"))
        // Leaf is a proper noun, so the parent's icon is more informative than the generic glyph.
        assertEquals(DuckIcon.Work, DuckIcon.forTag("work/acmecorp"))
        // Walks up more than one level.
        assertEquals(DuckIcon.Work, DuckIcon.forTag("work/acmecorp/q3"))
        // Nothing anywhere in the path matches.
        assertEquals(DuckIcon.Tag, DuckIcon.forTag("alpha/beta/gamma"))
    }

    @Test
    fun anExplicitIconKeyShortCircuitsTheWalk() {
        assertEquals(DuckIcon.Home, DuckIcon.forTag("work/code", iconKey = "Home"))
        assertEquals(DuckIcon.Home, DuckIcon.forTag("work/code", iconKey = "home"))
        // A key naming nothing falls through to the keyword walk rather than to the fallback.
        assertEquals(DuckIcon.Code, DuckIcon.forTag("work/code", iconKey = "nosuchicon"))
    }

    // --- the table proves it is populated -----------------------------------

    /**
     * Positive control for [noKeywordIsClaimedByTwoIcons]: that test compares a count against a set
     * size, and `0 == 0` is a pass. If the lazy index ever built empty — a refactor that dropped the
     * `flatMap`, say — the duplicate check would go green over nothing.
     */
    @Test
    fun theKeywordIndexIsPopulated() {
        val index = DuckIcon.keywordIndex
        assertTrue(
            "keyword index holds ${index.size} entries — a count this low means the index is not " +
                "being built, not that the icon set shrank",
            index.size >= 30,
        )
        assertEquals(DuckIcon.Todo, index["todo"])
    }

    @Test
    fun noKeywordIsClaimedByTwoIcons() {
        val all = DuckIcon.entries.flatMap { it.keywords }
        val duplicates = all.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals("a keyword resolving to two icons is ambiguous", emptySet<String>(), duplicates)
        // ...and the index must not have silently collapsed those duplicates away.
        assertEquals(all.size, DuckIcon.keywordIndex.size)
    }

    @Test
    fun theIconSetIsTheSizeWeThinkItIs() {
        assertEquals(
            "8 drawables was a deliberate starting point — update this and add the drawable together",
            8,
            DuckIcon.entries.size,
        )
    }

    @Test
    fun onlyTheFallbackHasNoKeywords() {
        val empty = DuckIcon.entries.filter { it.keywords.isEmpty() }
        assertEquals(listOf(DuckIcon.Tag), empty)
    }

    @Test
    fun keywordsAreAlreadyCanonical() {
        // A keyword the canonicalizer would rewrite could never be matched. Cheap, catches a typo
        // like "to-do" being added to the table instead of "todo".
        val offenders = DuckIcon.entries.flatMap { it.keywords }
            .filter { it != it.lowercase().filter(Char::isLetterOrDigit) }
        assertEquals(emptyList<String>(), offenders)
    }
}

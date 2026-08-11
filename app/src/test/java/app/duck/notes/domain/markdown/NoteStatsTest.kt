package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 9e's only new domain logic, and therefore its only JVM guard.
 *
 * Both tests drive the real [NoteStats] rather than rebuilding a counter locally — the rule
 * CLAUDE.md's Guards section states, after `debounceAloneNeverFires` proved `kotlinx.coroutines`
 * works and nothing about Duck.
 *
 * Everything else 9e ships is composition and one scroll effect, which is why the rest of the
 * phase's guards are instrumented. There is deliberately **no** test here that a `# heading` inside a
 * code fence stays out of the contents: that is `MarkdownScanner`'s carry, already held by
 * `SectionFoldingTest` and `GoldenSpanTest.fencedBlockIsNotStyledAsMarkdown`, and a third copy would
 * be a fourth dressing of one check with no plant of its own.
 */
class NoteStatsTest {

    /**
     * Words are runs of non-whitespace, so markdown syntax counts as part of the word it decorates.
     *
     * The `**bold**` case is the phase decision, not an incidental: counting it as one word is what
     * "counts run over raw source" means at the smallest scale. The blank cases are the boundary
     * bookkeeping — a body that is only spaces has no words, and neither does an empty one.
     *
     * **Plant:** invert `if (!inWord)` to `if (inWord)` in [NoteStats.words]. One token, no new
     * symbol, and it cannot fail on its own terms.
     */
    @Test
    fun wordsCountsRunsOfNonWhitespace() {
        assertEquals(2, NoteStats.words("**bold** text"))
        assertEquals(2, NoteStats.words("a  b"))
        assertEquals(3, NoteStats.words("  leading and trailing  "))
        assertEquals(4, NoteStats.words("# A heading here"))
        assertEquals(0, NoteStats.words(""))
        assertEquals(0, NoteStats.words("   \n\t "))
    }

    /**
     * Characters are code points, so the mascot is one character rather than two.
     *
     * `body.length` is UTF-16 units and reports `"🦆"` as 2. The ASCII case is the positive control:
     * without it, a broken counter that returned 1 for everything would pass the emoji assertion.
     *
     * **Plant:** replace `codePointCount(0, body.length)` with `length` in [NoteStats.characters].
     * One expression, and the ASCII control stays green while the emoji case goes red — which is what
     * makes the pair worth writing rather than either alone.
     */
    @Test
    fun charactersCountsCodePointsNotUtf16Units() {
        assertEquals(3, NoteStats.characters("abc"))
        assertEquals(1, NoteStats.characters("🦆"))
        assertEquals(8, NoteStats.characters("**bold**"))
        assertEquals(0, NoteStats.characters(""))
    }
}

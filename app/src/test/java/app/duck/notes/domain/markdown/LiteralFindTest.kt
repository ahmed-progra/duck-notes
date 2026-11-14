package app.duck.notes.domain.markdown

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LiteralFindTest {
    /** Plant: return no matches; literal punctuation and Arabic controls both fail. */
    @Test fun searchesLiteralPunctuationAndArabic() {
        assertArrayEquals(intArrayOf(0, 7), LiteralFind.matches("a.b .* a.b", "a.b", true))
        assertArrayEquals(intArrayOf(0, 6), LiteralFind.matches("كتاب، كتاب", "كتاب", true))
    }
    @Test fun respectsCaseAndDoesNotOverlap() {
        assertArrayEquals(intArrayOf(0, 2), LiteralFind.matches("AaAA", "aa", false))
        assertArrayEquals(intArrayOf(2), LiteralFind.matches("AaAA", "AA", true))
        assertArrayEquals(intArrayOf(0), LiteralFind.matches("aaa", "aa", true))
        assertArrayEquals(intArrayOf(), LiteralFind.matches("text", "", true))
        assertArrayEquals(intArrayOf(), LiteralFind.matches("text", "missing", false))
    }
    @Test fun longNearMatchesStillFindTheRealEnding() {
        val text = "a".repeat(100000) + "b"
        assertArrayEquals(intArrayOf(99901), LiteralFind.matches(text, "a".repeat(99) + "b", true))
    }
}

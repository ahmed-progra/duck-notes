package app.duck.notes.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/** First non-empty line, markdown stripped, `Untitled` when there is nothing to strip down to. */
class DeriveTitleTest {

    private val deriveTitle = DeriveTitle()

    @Test
    fun headingBecomesTitle() =
        assertEquals("Welcome to Duck", deriveTitle("# Welcome to Duck\n\nSome body text."))

    @Test
    fun plainFirstLine() = assertEquals("Just text", deriveTitle("Just text\nmore"))

    @Test
    fun leadingBlankLinesAreSkipped() =
        assertEquals("Second line has it", deriveTitle("\n\n   \nSecond line has it"))

    @Test
    fun emptyBodyIsUntitled() = assertEquals("Untitled", deriveTitle(""))

    @Test
    fun whitespaceOnlyBodyIsUntitled() = assertEquals("Untitled", deriveTitle("   \n\t\n  "))

    @Test
    fun taskMarkerIsStripped() = assertEquals("Buy duck food", deriveTitle("- [ ] Buy duck food"))

    @Test
    fun checkedTaskMarkerIsStripped() = assertEquals("Done thing", deriveTitle("- [x] Done thing"))

    @Test
    fun bulletMarkerIsStripped() = assertEquals("A bullet", deriveTitle("* A bullet"))

    @Test
    fun orderedMarkerIsStripped() = assertEquals("Ordered item", deriveTitle("1. Ordered item"))

    @Test
    fun blockquoteMarkerIsStripped() = assertEquals("Quoted", deriveTitle("> Quoted"))

    @Test
    fun boldIsStripped() = assertEquals("Bold title", deriveTitle("**Bold title**"))

    @Test
    fun italicAndCodeAreStripped() = assertEquals("mixed title", deriveTitle("_mixed_ `title`"))

    @Test
    fun strikethroughIsStripped() = assertEquals("struck", deriveTitle("~~struck~~"))

    @Test
    fun wikiLinkKeepsItsText() = assertEquals("Wiki Note", deriveTitle("[[Wiki Note]]"))

    @Test
    fun linkKeepsItsText() = assertEquals("Duck docs", deriveTitle("[Duck docs](https://example.com)"))

    @Test
    fun imageKeepsItsAltText() = assertEquals("a diagram", deriveTitle("![a diagram](duck.png)"))

    @Test
    fun innerWhitespaceIsCollapsed() = assertEquals("spaced out", deriveTitle("  spaced   out  "))

    @Test
    fun stackedMarkersArePeeled() = assertEquals("Nested task", deriveTitle("> - [ ] Nested task"))

    @Test
    fun aTagOnlyLineStillTitles() = assertEquals("#duck", deriveTitle("#duck"))
}

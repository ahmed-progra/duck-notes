package app.duck.notes.presentation.markdown

import androidx.compose.foundation.text.input.TextFieldState
import app.duck.notes.domain.markdown.WikiLinkScanner
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What choosing a suggestion actually does to the buffer — Phase 9c.
 *
 * Drives the **real** `completeWikiLink`, which is `internal` for exactly that reason: a test that
 * re-typed its two lines would pass while the shipped one rotted, the failure mode an audit of this
 * suite already found once (`EditorSaverSourceTest`, `MotionTest`).
 *
 * **Where the caret lands is the point.** A completion that parks it somewhere surprising is a defect
 * a green suite does not see — the note still says the right thing, and the next keystroke goes
 * somewhere the user did not look. So the caret is asserted in every case here, against literals.
 *
 * All offsets are **source** offsets. `TextFieldState.edit` indexes the raw buffer, which is the same
 * string `WikiLinkScanner.activeAt` walked; `OutputTransformation` only changes what is drawn. Nothing
 * in this phase rebases anything, which is why none of this needs a device.
 */
class WikiLinkCompletionTest {

    private fun complete(body: String, caret: Int, title: String): Pair<String, Int> {
        val state = TextFieldState(body, androidx.compose.ui.text.TextRange(caret))
        val active = requireNotNull(WikiLinkScanner.activeAt(body, caret)) {
            "no active context in <$body> at $caret"
        }
        state.completeWikiLink(active, title)
        return state.text.toString() to state.selection.start
    }

    @Test
    fun completingWritesTheWholeLinkAndLeavesTheCaretAfterIt() {
        val (body, caret) = complete("see [[Pon", 9, "Pond")

        assertEquals("see [[Pond]]", body)
        assertEquals("the caret must sit past the closing brackets", 12, caret)
    }

    /**
     * The caret lands **after** `]]`, not inside it.
     *
     * Typing continues outside the link rather than extending its title, which is the difference
     * between `[[Pond]] and more` and `[[Pond and more]]`. Setting the caret to
     * `active.first + 2 + title.length` — inside the brackets — is the plant, and it leaves the body
     * identical, so only this assertion catches it.
     */
    @Test
    fun theCaretIsOutsideTheBracketsNotInsideThem() {
        val (body, caret) = complete("[[Po", 4, "Pond")

        assertEquals("[[Pond]]", body)
        assertEquals(8, caret)
        assertEquals(
            "the caret must be past the final bracket, so the next keystroke is outside the link",
            body.length,
            caret,
        )
    }

    /** An empty prefix completes just as well — `[[` then a tap. */
    @Test
    fun completingFromBareBracketsWorks() {
        assertEquals("[[Pond]]" to 8, complete("[[", 2, "Pond"))
    }

    /**
     * Text after the caret is left alone, so `[[Pon|d` becomes `[[Pond]]d`.
     *
     * Stated and accepted rather than guessed at: the alternative is inventing a rule for where the
     * user meant their title to end, and a title may contain spaces, so there is no safe delimiter to
     * find. The caret still lands past the brackets, which is what keeps the result editable.
     */
    @Test
    fun trailingTextIsLeftAlone() {
        val (body, caret) = complete("[[Pond", 4, "Pond")

        assertEquals("[[Pond]]nd", body)
        assertEquals(8, caret)
    }

    /** The completion is exactly what `linkTo` writes — one home for the brackets, one round trip. */
    @Test
    fun theInsertedTextIsWhatLinkToWrites() {
        val (body, _) = complete("see [[Pon", 9, "Pond")

        assertEquals("see " + WikiLinkScanner.linkTo("Pond"), body)
    }

    /**
     * A title chosen case-insensitively is inserted **verbatim**, so exact resolution at save time
     * matches it — the agreement `WikiLinkScanner.suggest`'s KDoc argues for, asserted end to end.
     */
    @Test
    fun aTitleMatchedCaseInsensitivelyIsInsertedWithItsOwnCasing() {
        val suggestions = WikiLinkScanner.suggest(listOf("Pond"), "pon")
        val (body, _) = complete("see [[pon", 9, suggestions.single())

        assertEquals("see [[Pond]]", body)
        assertEquals(listOf("Pond"), WikiLinkScanner.titlesIn(body))
    }

    /** Right-to-left text around the link: the completion lands on source offsets, not visual ones. */
    @Test
    fun completingAfterRightToLeftTextLandsOnSourceOffsets() {
        val body = "# הערות\n\nראו [[Pon"
        assertEquals("fixture check", 13, body.indexOf("[["))

        val (completed, caret) = complete(body, 18, "Pond")

        assertEquals("# הערות\n\nראו [[Pond]]", completed)
        assertEquals("13 + the eight characters of [[Pond]]", 21, caret)
    }

    /** A surrogate pair before the link shifts nothing, because every index here is a code unit. */
    @Test
    fun completingAfterANonBmpCharacterLandsOnSourceOffsets() {
        val (completed, caret) = complete("🦆 notes [[Po", 13, "Pond")

        assertEquals("🦆 notes [[Pond]]", completed)
        assertEquals("9 + the eight characters of [[Pond]]", 17, caret)
    }
}

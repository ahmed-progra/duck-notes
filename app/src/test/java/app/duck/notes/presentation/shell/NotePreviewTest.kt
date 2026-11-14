package app.duck.notes.presentation.shell

import app.duck.notes.domain.model.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotePreviewTest {
    private fun preview(body: String) = Note("id", "Title", body, 0L, 0L).previewLine()

    /** Plant: return the unparsed preview; the syntax assertion fails. */
    @Test fun previewKeepsContentWithoutMarkdownSyntax() {
        assertEquals("Bold and example and Target", preview("# Title\n\n**Bold** and [example](https://example.com) and [[Target]]"))
    }

    /** Plant: raise production PREVIEW_CHARS from 200 to 201; the literal bound must fail. */
    @Test fun previewIsBoundedAndKeepsArabic() {
        assertTrue(preview("Title\n" + "ملاحظة ".repeat(20000)).length <= 200)
        assertEquals("ملاحظة عربية", preview("Title\nملاحظة عربية"))
    }
}

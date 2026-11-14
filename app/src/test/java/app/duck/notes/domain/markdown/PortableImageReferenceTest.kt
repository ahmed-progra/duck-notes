package app.duck.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class PortableImageReferenceTest {
    /** Plant: replace all matching text; fenced and inline code examples must remain unchanged. */
    @Test fun rewritesOnlyParsedImageDestinations() {
        val body = "![outside](attachment:a)\n`![code](attachment:a)`\n```md\n![fenced](attachment:a)\n```\n[ordinary](attachment:a)"
        val expected = "![outside](assets/a.png)\n`![code](attachment:a)`\n```md\n![fenced](attachment:a)\n```\n[ordinary](attachment:a)"
        assertEquals(expected, MarkdownScanner.rewriteImageDestinations(body, mapOf("attachment:a" to "assets/a.png")))
    }

    @Test fun reverseEditsKeepAllOffsetsAndImportIsTheInverse() {
        val body = "![first](attachment:a) and ![second](attachment:b) and ![again](attachment:a)"
        val map = mapOf("attachment:a" to "assets/a.png", "attachment:b" to "assets/a-much-longer-name.webp")
        val portable = "![first](assets/a.png) and ![second](assets/a-much-longer-name.webp) and ![again](assets/a.png)"
        assertEquals(portable, MarkdownScanner.rewriteImageDestinations(body, map))
        assertEquals(body, MarkdownScanner.rewriteImageDestinations(portable, map.entries.associate { it.value to it.key }))
    }
}

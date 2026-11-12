package app.duck.notes.startup

import app.duck.notes.domain.markdown.LineCarry
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.markdown.TagScanner
import app.duck.notes.domain.markdown.Token
import app.duck.notes.domain.markdown.TokenKind
import app.duck.notes.domain.markdown.WikiLinkScanner
import app.duck.notes.domain.usecase.DeriveTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production content, scanned by the same scanners used when saving and rendering a note.
 * Plants: WELCOME_LINK_TYPO, WELCOME_INLINE_TAG_LIVE, WELCOME_INLINE_WIKI_LIVE,
 * WELCOME_FENCE_TAG_LIVE, WELCOME_FENCE_WIKI_LIVE, WELCOME_STYLE_HEADING,
 * WELCOME_BRAND_WORD, WELCOME_CRLF, WELCOME_EXTERNAL_LINK, WELCOME_IMAGE_REF.
 * Each style plant changes only the named sample's marker in WelcomeNotes, not a scanner or fixture.
 */
class WelcomeContentTest {
    @Test
    fun fourTitlesAndOnlyTheThreeHubLinks() {
        val bodies = WelcomeNotes.bodies
        assertEquals(4, bodies.size)
        assertEquals(
            listOf("Get started", "Organize with tags", "Work faster", "Welcome to Duck"),
            bodies.map(DeriveTitle()::invoke),
        )
        bodies.take(3).forEach { assertEquals(emptyList<String>(), WikiLinkScanner.titlesIn(it)) }
        assertEquals(
            listOf("Get started", "Organize with tags", "Work faster"),
            WikiLinkScanner.titlesIn(bodies[3]),
        )
        assertEquals(3, bodies.sumOf { scan(it).count { token -> token.kind == TokenKind.WIKI_LINK } })
    }

    @Test
    fun exactlyOneSharedTagInEachBody() {
        assertEquals(4, WelcomeNotes.bodies.size)
        WelcomeNotes.bodies.forEach { body ->
            assertEquals(listOf("duck/welcome"), TagScanner.scan(body).map { it.name })
            assertEquals(1, scan(body).count { it.kind == TokenKind.TAG })
        }
    }

    @Test
    fun inlineAndFencedExamplesHaveIndependentLiveControls() {
        // Controls exercise each suppressing branch, not just the ordinary live branch.
        val organize = WelcomeNotes.bodies[1]
        assertTrue(organize.contains("`#projects/ideas`"))
        assertTrue(organize.contains("`#weekend plans#`"))
        assertEquals(
            listOf("projects/ideas", "duck/welcome"),
            TagScanner.scan(organize.replace("`#projects/ideas`", "#projects/ideas")).map { it.name },
        )
        assertEquals(
            listOf("weekend plans", "duck/welcome"),
            TagScanner.scan(organize.replace("`#weekend plans#`", "#weekend plans#")).map { it.name },
        )
        assertTrue(organize.contains("```text\n#reference/later\n```"))
        assertEquals(
            listOf("reference/later", "duck/welcome"),
            TagScanner.scan(organize.replace("```text\n#reference/later\n```", "#reference/later"))
                .map { it.name },
        )

        val faster = WelcomeNotes.bodies[2]
        assertTrue(faster.contains("`[[Get started]]`"))
        assertEquals(
            listOf("Get started"),
            WikiLinkScanner.titlesIn(faster.replace("`[[Get started]]`", "[[Get started]]")),
        )
        assertTrue(faster.contains("```text\n[[Organize with tags]]\n```"))
        assertEquals(
            listOf("Organize with tags"),
            WikiLinkScanner.titlesIn(
                faster.replace("```text\n[[Organize with tags]]\n```", "[[Organize with tags]]"),
            ),
        )
    }

    @Test
    fun everyFormattingClaimHasItsOwnExactSampleRange() {
        val body = WelcomeNotes.bodies[0]
        val tokens = scan(body)
        val samples = listOf(
            TokenKind.HEADING to "Try Markdown",
            TokenKind.QUOTE to "A small thought can become a useful note.",
            TokenKind.BULLET to "Keep one idea on each line.",
            TokenKind.ORDERED to "Put a first step in order.",
            TokenKind.TASK_OPEN to "Try tapping this checkbox.",
            TokenKind.TASK_DONE to "You have opened your first guide.",
            TokenKind.BOLD to "bold words",
            TokenKind.ITALIC to "italic words",
            TokenKind.STRIKE to "finished words",
            TokenKind.HIGHLIGHT to "highlighted words",
            TokenKind.CODE_SPAN to "inline code",
            TokenKind.FENCE_BODY to "Code stays literal here.",
        )
        samples.forEach { (kind, sample) ->
            val start = body.indexOf(sample)
            assertTrue("$kind designated sample is missing", start >= 0)
            assertTrue(
                "$kind not emitted over its designated sample range",
                tokens.any { it.kind == kind && it.start <= start && it.end >= start + sample.length },
            )
        }
        val dividerEnd = body.indexOf("A fenced block keeps its contents literal:")
        assertTrue("divider anchor is missing", dividerEnd >= 0)
        val beforeDividerAnchor = body.substring(0, dividerEnd).trimEnd('\n')
        val dividerStart = beforeDividerAnchor.lastIndexOf('\n') + 1
        assertTrue(
            "DIVIDER not emitted over the line immediately before its description",
            tokens.any {
                it.kind == TokenKind.DIVIDER && it.start == dividerStart && it.end == beforeDividerAnchor.length
            },
        )
        val codeStart = body.indexOf("Code stays literal here.")
        val fenceEnd = body.lastIndexOf('\n', codeStart - 1)
        val fenceStart = body.lastIndexOf('\n', fenceEnd - 1) + 1
        assertTrue(
            "FENCE_MARKER not emitted over the opening line immediately before its code sample",
            tokens.any { it.kind == TokenKind.FENCE_MARKER && it.start == fenceStart && it.end == fenceEnd },
        )
        val closingStart = body.indexOf('\n', codeStart) + 1
        val closingEnd = body.indexOf('\n', closingStart)
        assertTrue(
            "closing FENCE_MARKER not emitted immediately after its code sample",
            tokens.any { it.kind == TokenKind.FENCE_MARKER && it.start == closingStart && it.end == closingEnd },
        )
        assertTrue(MarkdownScanner.hasOpenTask(body))
    }

    @Test
    fun noForbiddenBrandingInActualBodiesDerivedTitlesOrTags() {
        assertEquals(4, WelcomeNotes.bodies.size)
        val checked = WelcomeNotes.bodies.flatMap { body ->
            listOf(body, DeriveTitle()(body)) + TagScanner.scan(body).map { it.name }
        }
        assertEquals(12, checked.size)
        checked.forEach {
            assertTrue("branding scan received empty text", it.isNotBlank())
            assertFalse("forbidden branding in welcome content", forbiddenBrand(it))
        }
        listOf("bear", "BEAR", "bearing", "bear notes\u0000bearingAccuracy", "bearingAccuracy\u0000BEAR")
            .forEach { assertTrue("branding positive control missed: $it", forbiddenBrand(it)) }
        assertFalse(forbiddenBrand("Duck Notes"))
    }

    @Test
    fun bodiesContainLfAndNoCarriageReturnWithoutNormalization() {
        assertEquals(4, WelcomeNotes.bodies.size)
        WelcomeNotes.bodies.forEach {
            assertTrue(it.isNotBlank())
            assertTrue(it.contains('\n'))
            assertFalse("welcome content contains CR", containsCr(it))
        }
        assertFalse(containsCr("one\ntwo"))
        assertTrue(containsCr("one\r\ntwo"))
        assertTrue(containsCr("one\rtwo"))
    }

    @Test
    fun noExternalLinksImagesOrAttachmentReferences() {
        assertEquals(4, WelcomeNotes.bodies.size)
        WelcomeNotes.bodies.forEach {
            assertFalse("ordinary link or image in welcome content", hasOrdinaryLinkOrImage(it))
            assertFalse("external URL in welcome content", hasExternalUrl(it))
            assertEquals(emptySet<String>(), MarkdownScanner.attachmentIdsIn(it))
        }
        assertTrue(hasOrdinaryLinkOrImage("[Duck](https://example.com)"))
        assertTrue(hasOrdinaryLinkOrImage("![Duck](attachment:sample)"))
        assertTrue(hasExternalUrl("https://example.com"))
        assertTrue(hasExternalUrl("HTTP://example.com"))
        assertTrue(hasExternalUrl("www.example.com"))
        assertTrue(hasExternalUrl("mailto:hello@example.com"))
        assertEquals(setOf("sample"), MarkdownScanner.attachmentIdsIn("![Duck](attachment:sample)"))
        assertFalse(hasOrdinaryLinkOrImage("plain Duck text"))
        assertFalse(hasExternalUrl("plain Duck text"))
    }

    private fun forbiddenBrand(text: String) = Regex("bear", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun containsCr(text: String) = '\r' in text

    private fun hasExternalUrl(text: String) =
        Regex("(?:[a-z][a-z0-9+.-]*://|www\\.|mailto:)", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun hasOrdinaryLinkOrImage(text: String) =
        scan(text).any { it.kind == TokenKind.LINK || it.kind == TokenKind.IMAGE }

    private fun scan(body: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var carry = LineCarry()
        var base = 0
        body.split('\n').forEach { line ->
            val (found, next) = MarkdownScanner.scanLine(line, base, carry)
            tokens += found
            carry = next
            base += line.length + 1
        }
        return tokens
    }
}

package app.duck.notes.core.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import app.duck.notes.core.theme.DuckAccent
import app.duck.notes.core.theme.DuckDivider
import app.duck.notes.core.theme.DuckTextMuted
import app.duck.notes.domain.markdown.StyleKey

/**
 * The only place a [StyleKey] becomes a real colour or weight.
 *
 * Sizes are relative (`em`), not absolute, so the editor inherits whatever body size the user picks
 * in Phase 14 rather than pinning headings to a fixed sp. Colours come from the brand tokens; no
 * literal is written here.
 *
 * Tag chips are a flat background rectangle for now — `SpanStyle.background` cannot round its
 * corners, and Bear's pill needs custom drawing. That lands with the design system in Phase 5.
 */
object MarkdownStyles {

    fun spanStyle(key: StyleKey, level: Int): SpanStyle = when (key) {
        StyleKey.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingSize(level))
        StyleKey.QUOTE -> SpanStyle(color = DuckTextMuted, fontStyle = FontStyle.Italic)
        StyleKey.BULLET, StyleKey.ORDERED -> SpanStyle()
        StyleKey.TASK_OPEN -> SpanStyle()
        StyleKey.TASK_DONE -> SpanStyle(color = DuckTextMuted, textDecoration = TextDecoration.LineThrough)
        StyleKey.DIVIDER -> SpanStyle(color = DuckDivider)
        StyleKey.CODE_BLOCK, StyleKey.CODE_SPAN ->
            SpanStyle(fontFamily = FontFamily.Monospace, background = CodeTint)
        StyleKey.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        StyleKey.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        StyleKey.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        StyleKey.HIGHLIGHT -> SpanStyle(background = HighlightTint)
        StyleKey.LINK, StyleKey.WIKI_LINK ->
            SpanStyle(color = DuckAccent, textDecoration = TextDecoration.Underline)
        StyleKey.IMAGE -> SpanStyle(color = DuckTextMuted, fontFamily = FontFamily.Monospace)
        StyleKey.TAG -> SpanStyle(color = DuckAccent, background = TagTint)
        // Revealed syntax is de-emphasised so the prose still reads as the content. Note this does
        // NOT avoid reflow: revealing re-inserts the marker characters, so the line rewraps as the
        // caret enters a node. Avoiding that would mean rendering markers at zero width rather than
        // deleting them, which OutputTransformation cannot express.
        StyleKey.MARKER -> SpanStyle(color = DuckTextMuted, fontWeight = FontWeight.Normal)
    }

    private fun headingSize(level: Int): TextUnit = when (level) {
        1 -> 1.6.em
        2 -> 1.4.em
        3 -> 1.2.em
        else -> 1.1.em
    }

    private val TagTint = DuckAccent.copy(alpha = 0.12f)
    private val HighlightTint = DuckAccent.copy(alpha = 0.22f)
    private val CodeTint = Color(0x14000000)
}

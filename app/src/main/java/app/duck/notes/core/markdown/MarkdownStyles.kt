package app.duck.notes.core.markdown

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import app.duck.notes.core.theme.DuckColors
import app.duck.notes.core.theme.DuckType
import app.duck.notes.domain.markdown.StyleKey

/**
 * The only place a [StyleKey] becomes a real colour or weight.
 *
 * Holds no colour of its own — it is a view over [DuckColors] and [DuckType], rebuilt when the theme
 * changes and never otherwise. Phase 4 made this an `object` reading top-level `val`s, which pinned
 * the editor to Duck Red under every theme; three of its private tints were also wrong in ways only
 * a contrast test would find:
 *
 * - `CodeTint = Color(0x14000000)` was an 8% **black** overlay. On `#1C1C1E` it composites to
 *   `#1A1A1C` — **1.03:1** — so dark-mode code blocks had no visible background at all.
 * - `TagTint` and `HighlightTint` were `DuckAccent.copy(alpha = …)` evaluated at class-init, so they
 *   stayed red under Graphite, Sepia and Midnight.
 *
 * All three are now opaque, pre-composited palette slots, which is what makes them measurable.
 *
 * Sizes stay relative (`em`), not absolute, so headings scale with whatever body size the user picks
 * in Phase 14 rather than being pinned to `sp` here.
 *
 * Tag chips remain a flat background rectangle in the editor: `SpanStyle.background` cannot round its
 * corners, and a real pill inside `BasicTextField` needs custom drawing that `OutputTransformation`
 * cannot express. `DuckTagChip` — the sidebar and filter-row chip — is Phase 5b; the **in-editor**
 * pill is Phase 8, where the editor gains a draw layer.
 */
class MarkdownStyles(
    private val colors: DuckColors,
    private val type: DuckType,
) {

    fun spanStyle(key: StyleKey, level: Int): SpanStyle = when (key) {
        StyleKey.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingSize(level))
        StyleKey.QUOTE -> SpanStyle(color = colors.proseMuted, fontStyle = FontStyle.Italic)
        StyleKey.BULLET, StyleKey.ORDERED -> SpanStyle()
        StyleKey.TASK_OPEN -> SpanStyle()
        StyleKey.TASK_DONE ->
            SpanStyle(color = colors.proseMuted, textDecoration = TextDecoration.LineThrough)
        // The box is painted on top of the task's own span, so these two overrule it. `onSurface` is
        // the colour body prose already has — stated rather than left to inherit, because the box is
        // a control and must not drift with whatever TASK_OPEN grows into.
        StyleKey.TASK_BOX_OPEN -> SpanStyle(color = colors.onSurface)
        // `TextDecoration.None` is load-bearing, not tidiness. SpanStyle merge keeps the underlying
        // value wherever the overriding one is null, so leaving it unset lets TASK_DONE's LineThrough
        // through and paints a struck-through checkbox — the exact bug this key exists to prevent.
        //
        // accentText, not accent: 4.25:1 on white is a fill value, and this is a glyph in a text run.
        // The contract's "checkbox fill" clause governs DuckCheckbox's drawn box, not a character.
        StyleKey.TASK_BOX_DONE ->
            SpanStyle(color = colors.accentText, textDecoration = TextDecoration.None)
        StyleKey.DIVIDER -> SpanStyle(color = colors.divider)
        StyleKey.CODE_BLOCK, StyleKey.CODE_SPAN ->
            SpanStyle(
                color = colors.codeOnSurface,
                fontFamily = type.mono,
                background = colors.codeSurface,
            )
        StyleKey.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        StyleKey.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        StyleKey.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        StyleKey.HIGHLIGHT -> SpanStyle(background = colors.highlight)
        // accentText, not accent: links are prose, and the brand red is 4.25:1 on white.
        StyleKey.LINK, StyleKey.WIKI_LINK ->
            SpanStyle(color = colors.accentText, textDecoration = TextDecoration.Underline)
        StyleKey.IMAGE -> SpanStyle(color = colors.proseMuted, fontFamily = type.mono)
        StyleKey.TAG -> SpanStyle(color = colors.accentText, background = colors.tagSurface)
        // Revealed syntax is de-emphasised so the prose still reads as the content. Note this does
        // NOT avoid reflow: revealing re-inserts the marker characters, so the line rewraps as the
        // caret enters a node. Avoiding that would mean rendering markers at zero width rather than
        // deleting them, which OutputTransformation cannot express.
        //
        // proseMuted, not chromeMuted: a revealed backtick sits on codeSurface, which is why
        // proseMuted is tuned against codeSurface rather than against the page.
        StyleKey.MARKER -> SpanStyle(color = colors.proseMuted, fontWeight = FontWeight.Normal)
        // Focus mode — everything outside the caret's paragraph. Colour only: it overlays whatever
        // the token already painted, so weight/decoration survive and only the colour recedes.
        // proseMuted, reused rather than a new token, so no pairing is added to ContrastTest.
        StyleKey.DIMMED -> SpanStyle(color = colors.proseMuted)
    }

    private fun headingSize(level: Int): TextUnit = when (level) {
        1 -> 1.6.em
        2 -> 1.4.em
        3 -> 1.2.em
        else -> 1.1.em
    }
}

package app.duck.notes.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Every colour the app may paint, named by the role it plays rather than by what it looks like.
 *
 * **No slot has a default.** A fifth theme cannot compile until it answers all sixteen — totality by
 * construction. `ContrastTest` then enforces the values and `TokenTotalityTest` the Material
 * projection, so a palette can be neither incomplete nor illegible.
 *
 * Two pairs look redundant and are not:
 *
 * - [accent] vs [accentText]. The brand red `#D7494C` measures **4.25:1** on white and **4.01:1** on
 *   `#1C1C1E` — under WCAG AA's 4.5:1 for body text, over 1.4.11's 3:1 for non-text. So [accent]
 *   paints fills only (tag chip, cursor, active sidebar row, note-list bar, checkbox fill — exactly
 *   the uses the GLOBAL CONTRACT names) and [accentText] paints glyphs (links, wiki links, tag text).
 *   The contract's hex is never overridden; a derived slot is added beside it.
 * - [chromeMuted] vs [proseMuted]. The contract's `textMuted #8E8E93` is **3.26:1** on white: correct
 *   for timestamps, counts and borders, wrong for prose. [proseMuted] is the darker variant carrying
 *   quotes, revealed markers, completed tasks and image placeholders.
 *
 * In **light DuckRed, [accentText] and [accentPress] are both `#B93C3F`. That is intent, not
 * duplication** — one is a prose glyph, the other a pressed fill. They diverge in dark, where
 * [accentText] goes *lighter* than [accent] and [accentPress] stays darker. Do not collapse them.
 *
 * Tints ([codeSurface], [tagSurface], [highlight], [selection]) are stored **pre-composited and
 * opaque**. Nothing applies alpha at the use site, because an alpha tint cannot be measured by a
 * contrast test — and because `Color(0x14000000)`, the 8% black overlay Phase 4 used, composites to
 * `#1A1A1C` on `#1C1C1E`: a ratio of **1.03:1**, i.e. an invisible dark-mode code background.
 */
@Immutable
data class DuckColors(
    /** Fills only, never a glyph: tag chip, cursor, active sidebar row, note-list bar, checkbox. */
    val accent: Color,
    /** Pressed state of an [accent] fill. */
    val accentPress: Color,
    /** Accent-coloured *text*: links, wiki links, tag labels. Clears 4.5:1. */
    val accentText: Color,
    /** The page. */
    val surface: Color,
    /** Cards, sheets, sidebar — and M3's `surfaceVariant` and container ladder. */
    val surfaceElevated: Color,
    /** Body prose and headings. */
    val onSurface: Color,
    /** Muted *prose*: quotes, revealed markers, completed tasks, image placeholders. Clears 4.5:1. */
    val proseMuted: Color,
    /** Muted *chrome*: timestamps, counts, placeholders, and M3's `outline`. Clears 3:1, not 4.5:1. */
    val chromeMuted: Color,
    /** Decorative separators. Exempt from a contrast floor under WCAG 1.4.11; measured and recorded. */
    val divider: Color,
    /** Background of code spans and fenced blocks. */
    val codeSurface: Color,
    /** Text inside code. */
    val codeOnSurface: Color,
    /** Background of an inline `#tag`. */
    val tagSurface: Color,
    /** Background of `==highlighted==` text. */
    val highlight: Color,
    /** Text selection background. */
    val selection: Color,
    /** Error text. Tuned against [errorSurface], which is the binding constraint, not [surface]. */
    val error: Color,
    /** Error container fill. */
    val errorSurface: Color,
)

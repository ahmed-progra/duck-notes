package app.duck.notes.core.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The three families — and **the only thing Phase 14 replaces.**
 *
 * Serif for note prose, sans for chrome, mono for code. Phase 14 swaps real `.ttf`s in here and every
 * role below follows, because roles are *derived* from this by [duckType] rather than written as
 * literals with a family baked in. No call site anywhere names a `FontFamily`; `TokenSourceTest` fails
 * the build if one does.
 */
@Immutable
data class DuckFonts(
    val reading: FontFamily,
    val ui: FontFamily,
    val mono: FontFamily,
)

/** Platform generics. Zero assets, and the shape Phase 14 replaces wholesale. */
val DuckPlatformFonts = DuckFonts(
    reading = FontFamily.Serif,
    ui = FontFamily.SansSerif,
    mono = FontFamily.Monospace,
)

/**
 * The six type roles, plus the mono family for `SpanStyle` consumers.
 *
 * There are deliberately **no heading roles**. `MarkdownStyles` sizes headings as `em` multiples of
 * the inherited body style, so they scale with whatever [noteBody] is; absolute heading slots would
 * pin them to `sp` and break both that and Phase 14's font-size setting.
 */
@Immutable
data class DuckType(
    /** Editor body prose. The base the markdown engine's `em` heading multiples resolve against. */
    val noteBody: TextStyle,
    /** A note's title line. */
    val noteTitle: TextStyle,
    /** Sidebar section headers, pane titles. */
    val uiTitle: TextStyle,
    /** Sidebar tag rows, note-list card title. */
    val uiBody: TextStyle,
    /** Note-list two-line preview. */
    val uiSecondary: TextStyle,
    /** Timestamps, tag counts, chip text, empty-state caption. */
    val uiLabel: TextStyle,
    /** Family only — `SpanStyle` takes a family, not a `TextStyle`. */
    val mono: FontFamily,
)

/** Builds every role from [fonts]. Called in exactly one place, [DuckTheme]. */
fun duckType(fonts: DuckFonts = DuckPlatformFonts): DuckType = DuckType(
    noteBody = TextStyle(fontFamily = fonts.reading, fontSize = 17.sp, lineHeight = 26.sp),
    noteTitle = TextStyle(
        fontFamily = fonts.reading,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    uiTitle = TextStyle(
        fontFamily = fonts.ui,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    uiBody = TextStyle(fontFamily = fonts.ui, fontSize = 15.sp, lineHeight = 20.sp),
    uiSecondary = TextStyle(fontFamily = fonts.ui, fontSize = 14.sp, lineHeight = 19.sp),
    uiLabel = TextStyle(fontFamily = fonts.ui, fontSize = 12.sp, lineHeight = 16.sp),
    mono = fonts.mono,
)

/**
 * Material's own type scale, re-familied onto [DuckFonts.ui].
 *
 * A no-op today, since `ui` *is* `FontFamily.SansSerif`. It exists for Phase 14: the moment `ui`
 * becomes a real font, every Material widget has to follow or the chrome splits into two typefaces.
 * Exhaustive over all fifteen M3 roles for the same reason the colour projection is exhaustive.
 */
internal fun duckMaterialTypography(fonts: DuckFonts): Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = fonts.ui),
        displayMedium = displayMedium.copy(fontFamily = fonts.ui),
        displaySmall = displaySmall.copy(fontFamily = fonts.ui),
        headlineLarge = headlineLarge.copy(fontFamily = fonts.ui),
        headlineMedium = headlineMedium.copy(fontFamily = fonts.ui),
        headlineSmall = headlineSmall.copy(fontFamily = fonts.ui),
        titleLarge = titleLarge.copy(fontFamily = fonts.ui),
        titleMedium = titleMedium.copy(fontFamily = fonts.ui),
        titleSmall = titleSmall.copy(fontFamily = fonts.ui),
        bodyLarge = bodyLarge.copy(fontFamily = fonts.ui),
        bodyMedium = bodyMedium.copy(fontFamily = fonts.ui),
        bodySmall = bodySmall.copy(fontFamily = fonts.ui),
        labelLarge = labelLarge.copy(fontFamily = fonts.ui),
        labelMedium = labelMedium.copy(fontFamily = fonts.ui),
        labelSmall = labelSmall.copy(fontFamily = fonts.ui),
    )
}

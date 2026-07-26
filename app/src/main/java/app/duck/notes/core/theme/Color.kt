package app.duck.notes.core.theme

import androidx.compose.ui.graphics.Color

/**
 * The GLOBAL CONTRACT's brand tokens, verbatim.
 *
 * This file and [DuckPalettes] are the only places a colour literal may be written — `TokenSourceTest`
 * fails the build on any other. Everything else in the app reads a [DuckColors] slot.
 *
 * These keep the contract's own names. The *slots* built from them carry role names instead
 * (`DuckTextMuted` becomes `DuckColors.chromeMuted`), so that a slot rename can never be mistaken for
 * a change to the brand.
 */
val DuckAccent = Color(0xFFD7494C)
val DuckAccentPress = Color(0xFFB93C3F)
val DuckSurface = Color(0xFFFFFFFF)
val DuckSurfaceDark = Color(0xFF1C1C1E)
val DuckText = Color(0xFF2C2C2E)
val DuckTextMuted = Color(0xFF8E8E93)
val DuckDivider = Color(0xFFE5E5EA)

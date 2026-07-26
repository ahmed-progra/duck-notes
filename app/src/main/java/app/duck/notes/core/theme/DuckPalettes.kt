package app.duck.notes.core.theme

import androidx.compose.ui.graphics.Color

/**
 * The eight palettes: four themes × two modes.
 *
 * **Authored against `ContrastTest`, not picked by eye.** Every value below satisfies the floor table
 * in that test; several were moved by a tenth of a step to get there, and the two constraints that
 * did the moving are worth knowing before editing anything here:
 *
 * 1. **A tint's container is the binding constraint, not the page.** [DuckColors.proseMuted] must
 *    clear 4.5:1 on [DuckColors.codeSurface] — which happens for real when the caret enters a code
 *    span and its backticks are revealed as `MARKER` over the code background. `#767676` clears 4.5
 *    on white but gives only 3.78:1 on `#EAEAEA`, so the light-mode value is `#6A6A6A`. Same for
 *    [DuckColors.error] against [DuckColors.errorSurface].
 * 2. **Tints must stay ≥1.2:1 from the page.** That is a Duck rule, not a WCAG one: it is what stops
 *    a code block from being invisible. Deepening a tint to satisfy it tightens rule 1 from the other
 *    side, which is why several foregrounds here are a shade darker than they look like they need.
 *
 * Only DuckRed carries the GLOBAL CONTRACT's hexes. The other three are authored — `Sepia` and
 * `Midnight` are *not* Solarized and Dieci retuned, they are different palettes, named for what they
 * are rather than borrowing a name from a palette that would not pass AA unmodified.
 */

// --- DuckRed — the brand. Every contract hex appears here unmodified. --------------------------

internal val DuckRedLight = DuckColors(
    accent = BrandAccent,
    accentPress = BrandAccentPress,
    accentText = BrandAccentPress, // see DuckColors KDoc — intentional, not duplication
    surface = BrandSurface,
    surfaceElevated = Color(0xFFF7F7F8),
    onSurface = BrandText,
    // #6A6A6A measured 4.5019 against the 4.5 floor on codeSurface — one float op from red, and
    // Kotlin and Python do not share a pow implementation. #686868 is 4.6380.
    proseMuted = Color(0xFF686868),
    chromeMuted = BrandTextMuted,
    divider = BrandDivider,
    codeSurface = Color(0xFFEAEAEC),
    codeOnSurface = BrandText,
    tagSurface = Color(0xFFF5E4E5),
    highlight = Color(0xFFF8E9A3),
    selection = Color(0xFFF7D9DA),
    error = Color(0xFFB3261E),
    errorSurface = Color(0xFFFBEAE7),
)

internal val DuckRedDark = DuckColors(
    accent = BrandAccent,
    accentPress = BrandAccentPress,
    accentText = Color(0xFFF08386), // lighter than accent, the opposite direction from light mode
    surface = BrandSurfaceDark,
    surfaceElevated = Color(0xFF2A2A2D),
    onSurface = Color(0xFFF2F2F5),
    proseMuted = Color(0xFFA3A3A9),
    chromeMuted = BrandTextMuted,
    divider = Color(0xFF3A3A3D),
    codeSurface = Color(0xFF2B2B2E),
    codeOnSurface = Color(0xFFF2F2F5),
    tagSurface = Color(0xFF3D2729),
    highlight = Color(0xFF4A421A),
    selection = Color(0xFF4A2A2C),
    error = Color(0xFFF2857C),
    errorSurface = Color(0xFF3A211F),
)

// --- Graphite — neutral greys, desaturated accent. ---------------------------------------------

internal val GraphiteLight = DuckColors(
    accent = Color(0xFF5A6270),
    accentPress = Color(0xFF454C57),
    accentText = Color(0xFF4A515C),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF6F6F7),
    onSurface = Color(0xFF26262A),
    proseMuted = Color(0xFF666A70),
    chromeMuted = Color(0xFF8A8D93),
    divider = Color(0xFFE3E4E7),
    codeSurface = Color(0xFFE9EAEC),
    codeOnSurface = Color(0xFF26262A),
    tagSurface = Color(0xFFE7E9ED),
    highlight = Color(0xFFF8E9A3),
    selection = Color(0xFFDDE0E6),
    error = Color(0xFFB3261E),
    errorSurface = Color(0xFFFBEAE7),
)

internal val GraphiteDark = DuckColors(
    accent = Color(0xFF8B94A3),
    accentPress = Color(0xFF6F7887),
    accentText = Color(0xFFAEB6C4),
    surface = Color(0xFF1A1B1D),
    surfaceElevated = Color(0xFF27282B),
    onSurface = Color(0xFFEEEFF2),
    proseMuted = Color(0xFFA2A6AD),
    chromeMuted = Color(0xFF8A8D93),
    divider = Color(0xFF37383C),
    codeSurface = Color(0xFF292A2D),
    codeOnSurface = Color(0xFFEEEFF2),
    tagSurface = Color(0xFF2E3238),
    highlight = Color(0xFF48401A),
    selection = Color(0xFF333941),
    error = Color(0xFFF2857C),
    errorSurface = Color(0xFF38201E),
)

// --- Sepia — warm cream light, cold slate dark. ------------------------------------------------

internal val SepiaLight = DuckColors(
    accent = Color(0xFFB5793C),
    accentPress = Color(0xFF96612F),
    accentText = Color(0xFF86582A),
    surface = Color(0xFFFBF5E6),
    surfaceElevated = Color(0xFFF5EEDC),
    onSurface = Color(0xFF33302A),
    proseMuted = Color(0xFF686257),
    chromeMuted = Color(0xFF8C857A),
    divider = Color(0xFFE6DCC6),
    codeSurface = Color(0xFFE7DFCB),
    codeOnSurface = Color(0xFF33302A),
    tagSurface = Color(0xFFEBDDC5),
    highlight = Color(0xFFF5E07A),
    selection = Color(0xFFEADFC0),
    error = Color(0xFFA3301F),
    errorSurface = Color(0xFFF5E2DA),
)

internal val SepiaDark = DuckColors(
    accent = Color(0xFFC08A4E),
    accentPress = Color(0xFF9E6E3B),
    accentText = Color(0xFFDCA867),
    surface = Color(0xFF15221F),
    surfaceElevated = Color(0xFF1E2C29),
    onSurface = Color(0xFFE8E2D2),
    proseMuted = Color(0xFF9BA49E),
    chromeMuted = Color(0xFF84908B),
    divider = Color(0xFF2C3A36),
    codeSurface = Color(0xFF24322F),
    codeOnSurface = Color(0xFFE8E2D2),
    tagSurface = Color(0xFF2A342C),
    highlight = Color(0xFF4A421A),
    selection = Color(0xFF2B3A37),
    error = Color(0xFFF0897C),
    errorSurface = Color(0xFF3A211F),
)

// --- Midnight — dark blue-grey, cool accent. ---------------------------------------------------

internal val MidnightLight = DuckColors(
    accent = Color(0xFF3D6CB9),
    accentPress = Color(0xFF2F5595),
    accentText = Color(0xFF2F5595),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF5F7FA),
    onSurface = Color(0xFF22262E),
    proseMuted = Color(0xFF5F6672),
    chromeMuted = Color(0xFF868D99),
    divider = Color(0xFFE1E5EC),
    codeSurface = Color(0xFFE4E8EE),
    codeOnSurface = Color(0xFF22262E),
    tagSurface = Color(0xFFE2E9F5),
    highlight = Color(0xFFF8E9A3),
    selection = Color(0xFFD9E3F3),
    error = Color(0xFFB3261E),
    errorSurface = Color(0xFFFBEAE7),
)

internal val MidnightDark = DuckColors(
    accent = Color(0xFF5B8BD9),
    accentPress = Color(0xFF456FB4),
    accentText = Color(0xFF8FB4EC),
    surface = Color(0xFF14181F),
    surfaceElevated = Color(0xFF1F2530),
    onSurface = Color(0xFFE7ECF4),
    proseMuted = Color(0xFF9AA3B2),
    chromeMuted = Color(0xFF828B9A),
    divider = Color(0xFF2E3543),
    codeSurface = Color(0xFF242A35),
    codeOnSurface = Color(0xFFE7ECF4),
    tagSurface = Color(0xFF253041),
    highlight = Color(0xFF46401C),
    selection = Color(0xFF2A3648),
    error = Color(0xFFF2857C),
    errorSurface = Color(0xFF38201E),
)

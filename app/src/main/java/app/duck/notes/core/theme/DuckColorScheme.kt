package app.duck.notes.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Projects [DuckColors] onto Material's scheme. One-directional: Duck tokens are the source, this is
 * a view. Nothing reads a colour back out of here.
 *
 * **Exhaustive on purpose.** Before Phase 5 only seven slots were assigned, which left `secondary`,
 * `tertiary`, `primaryContainer`, `surfaceVariant` and `error` on M3's baseline **purple** — a colour
 * no one chose, shipping in any widget that touched them.
 *
 * Three decisions here are load-bearing:
 *
 * - **`primary` is [DuckColors.accentText], not [DuckColors.accent].** M3 paints `primary` as *text* —
 *   TextButton and OutlinedButton labels, selected nav labels, TabRow content, Snackbar actions — so
 *   the brand red would have been 4.25:1 prose. It also fixes white-on-`primary` for FilledButton,
 *   which is 4.25:1 against `#D7494C` and 5.55:1 against `#B93C3F`.
 * - **`surfaceTint` is [DuckColors.surface], making the tonal overlay an identity operation.**
 *   `surfaceColorAtElevation` blends `surfaceTint` into every elevated surface at 5–14%, so an accent
 *   here would reach Cards, Menus and sheets through a path `ContrastTest` cannot measure — and would
 *   wash Graphite and Midnight red. Elevation is carried by the explicit [DuckColors.surfaceElevated]
 *   slot instead, which *is* measured. Consequently **[DuckColors.accent] maps to no Material slot at
 *   all**: brand red reaches the screen only through Duck components, which is where every use the
 *   GLOBAL CONTRACT names already lives.
 * - **`outline` is [DuckColors.chromeMuted], `outlineVariant` is [DuckColors.divider].** An
 *   OutlinedTextField border is a WCAG 1.4.11 UI component needing 3:1; `divider` is `#E5E5EA`, about
 *   1.2:1 on white, and belongs in M3's decorative separator slot.
 *
 * The `inverse*` trio reaches into the *opposite* mode's palette, so `inversePrimary` on
 * `inverseSurface` is the other palette's already-asserted `accentText`-on-`surface` pair — covered by
 * construction rather than by a separate hex.
 */
internal fun duckColorScheme(spec: DuckThemeSpec, dark: Boolean): ColorScheme {
    val c = spec.colors(dark)
    val inverse = spec.colors(!dark)

    // Mode-matching base even though every slot below is overridden: if a future Compose BOM adds a
    // slot this does not list, its default is at least mode-appropriate while TokenTotalityTest's
    // pinned count fails and forces it to be mapped. Named arguments throughout — a positional list
    // this long silently mis-assigns the day the two builders' parameter orders diverge.
    val base = if (dark) darkColorScheme() else lightColorScheme()

    return base.copy(
        primary = c.accentText,
        onPrimary = c.surface,
        primaryContainer = c.tagSurface,
        onPrimaryContainer = c.accentText,
        inversePrimary = inverse.accentText,
        secondary = c.accentText,
        onSecondary = c.surface,
        secondaryContainer = c.tagSurface,
        onSecondaryContainer = c.accentText,
        tertiary = c.accentText,
        onTertiary = c.surface,
        tertiaryContainer = c.tagSurface,
        onTertiaryContainer = c.accentText,
        background = c.surface,
        onBackground = c.onSurface,
        surface = c.surface,
        onSurface = c.onSurface,
        surfaceVariant = c.surfaceElevated,
        onSurfaceVariant = c.proseMuted,
        surfaceTint = c.surface,
        inverseSurface = inverse.surface,
        inverseOnSurface = inverse.onSurface,
        error = c.error,
        onError = c.surface,
        errorContainer = c.errorSurface,
        onErrorContainer = c.error,
        outline = c.chromeMuted,
        outlineVariant = c.divider,
        scrim = Color.Black,
        surfaceBright = c.surface,
        surfaceDim = c.surfaceElevated,
        surfaceContainer = c.surfaceElevated,
        surfaceContainerHigh = c.surfaceElevated,
        surfaceContainerHighest = c.surfaceElevated,
        surfaceContainerLow = c.surface,
        surfaceContainerLowest = c.surface,

        // M3's "fixed" accent roles: the same colour in light and dark, so a surface can keep its
        // accent across a mode flip. They are read from the LIGHT palette in both modes, which is
        // what "fixed" means. Nothing in Duck paints them yet — they are assigned because leaving
        // them out left them on M3's baseline purple, which is what ContrastTest caught.
        // Duck has no dimmer-accent-container token, so Fixed and FixedDim share a value; likewise
        // the two on* emphases. Both are safe: they collapse onto pairs already asserted.
        primaryFixed = spec.light.tagSurface,
        primaryFixedDim = spec.light.tagSurface,
        onPrimaryFixed = spec.light.accentText,
        onPrimaryFixedVariant = spec.light.accentText,
        secondaryFixed = spec.light.tagSurface,
        secondaryFixedDim = spec.light.tagSurface,
        onSecondaryFixed = spec.light.accentText,
        onSecondaryFixedVariant = spec.light.accentText,
        tertiaryFixed = spec.light.tagSurface,
        tertiaryFixedDim = spec.light.tagSurface,
        onTertiaryFixed = spec.light.accentText,
        onTertiaryFixedVariant = spec.light.accentText,
    )
}

package app.duck.notes.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val DefaultSpacing = DuckSpacing()
private val DefaultShapes = DuckShapes()
private val DefaultType = duckType()
private val DefaultMotion = DuckMotion()

val LocalDuckColors = staticCompositionLocalOf<DuckColors> {
    error("No DuckColors — wrap the content in DuckTheme { }")
}
val LocalDuckType = staticCompositionLocalOf { DefaultType }
val LocalDuckSpacing = staticCompositionLocalOf { DefaultSpacing }
val LocalDuckShapes = staticCompositionLocalOf { DefaultShapes }

/**
 * Static like the three above, though unlike them it **does** change at runtime — when the system
 * reduce-motion setting flips. That costs a recomposition of everything under the provider, because
 * a static local does not track individual reads; for a setting a user touches approximately never,
 * that is cheaper than tracking every read of a duration for the life of the app.
 */
val LocalDuckMotion = staticCompositionLocalOf { DefaultMotion }

/**
 * Duck's design system. **Wraps** `MaterialTheme` rather than replacing it or sitting beside it.
 *
 * Wrapping, because Material 3 widgets already in the tree — `Scaffold` now, `ModalNavigationDrawer`
 * in Phase 6, `ModalBottomSheet` in Phase 9 — read `MaterialTheme.colorScheme`, and replacing it
 * would strand them on the baseline purple. But Material's scheme cannot name `tagSurface`,
 * `codeSurface` or `highlight`, so Duck tokens own their own locals and the Material scheme is a
 * derived, one-directional projection of them. *Beside* would mean two independent sources of colour,
 * which is exactly what "one source of truth" rules out.
 *
 * Dynamic colour / Material You is deliberately absent — not a flag left switched off. The contract
 * fixes one accent for tag chips, links, cursor and selection, wallpaper colour would override
 * precisely that, and an unbounded space of generated schemes cannot be pinned by `ContrastTest`.
 *
 * [spec] is a plain parameter, not a persisted setting: Phase 14 owns DataStore. Until then the debug
 * gallery drives it from an in-memory state so the switch is exercisable at all.
 */
@Composable
fun DuckTheme(
    spec: DuckThemeSpec = DuckThemeSpec.DuckRed,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalDuckColors provides spec.colors(dark),
        LocalDuckType provides DefaultType,
        LocalDuckSpacing provides DefaultSpacing,
        LocalDuckShapes provides DefaultShapes,
        // The one token family that answers to the system rather than to [spec].
        LocalDuckMotion provides if (rememberMotionEnabled()) DefaultMotion else DuckMotion.none,
    ) {
        MaterialTheme(
            colorScheme = duckColorScheme(spec, dark),
            typography = duckMaterialTypography(DuckPlatformFonts),
            content = content,
        )
    }
}

/** Accessor for the Duck tokens, mirroring how `MaterialTheme` exposes its own. */
object DuckTheme {
    val colors: DuckColors
        @Composable @ReadOnlyComposable get() = LocalDuckColors.current

    val type: DuckType
        @Composable @ReadOnlyComposable get() = LocalDuckType.current

    val spacing: DuckSpacing
        @Composable @ReadOnlyComposable get() = LocalDuckSpacing.current

    val shapes: DuckShapes
        @Composable @ReadOnlyComposable get() = LocalDuckShapes.current

    val motion: DuckMotion
        @Composable @ReadOnlyComposable get() = LocalDuckMotion.current
}

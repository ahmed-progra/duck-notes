package app.duck.notes.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Minimal Material 3 schemes seeded from the accent. The full Duck design system (named themes,
// contrast tuning, dynamic switching) is Phase 5 — kept intentionally small here.
private val DuckLightColors = lightColorScheme(
    primary = DuckAccent,
    onPrimary = DuckSurface,
    surface = DuckSurface,
    onSurface = DuckText,
    background = DuckSurface,
    onBackground = DuckText,
    outline = DuckDivider,
)

private val DuckDarkColors = darkColorScheme(
    primary = DuckAccent,
    onPrimary = DuckSurface,
    surface = DuckSurfaceDark,
    onSurface = DuckSurface,
    background = DuckSurfaceDark,
    onBackground = DuckSurface,
    outline = DuckTextMuted,
)

@Composable
fun DuckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DuckDarkColors else DuckLightColors,
        typography = DuckTypography,
        content = content,
    )
}

package app.duck.notes.core.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.duck.notes.core.icons.DuckIcon
import app.duck.notes.core.icons.DuckIconImage
import app.duck.notes.core.theme.DuckTheme

/**
 * A tappable icon. Pane chrome in Phase 6, and the format toolbar in Phase 8.
 *
 * The glyph is `onSurface`, which clears 4.5 on both `surface` (12.09) and `surfaceElevated` (11.22).
 * An icon is a 1.4.11 graphic owing only 3:1, so there is headroom to spare — but there is no reason to
 * spend it, and Phase 6 puts these in the sidebar where the container is elevated.
 *
 * Disabled uses `chromeMuted`: a disabled control is the one use of that slot WCAG exempts, and the
 * only one left after the slot was narrowed out of carrying text.
 *
 * `IconButton` supplies the 48dp touch target and the ripple.
 */
@Composable
fun DuckIconButton(
    icon: DuckIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = DuckTheme.colors

    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        DuckIconImage(
            icon = icon,
            tint = if (enabled) colors.onSurface else colors.chromeMuted,
            contentDescription = contentDescription,
            modifier = Modifier.size(GLYPH),
        )
    }
}

private val GLYPH = 24.dp

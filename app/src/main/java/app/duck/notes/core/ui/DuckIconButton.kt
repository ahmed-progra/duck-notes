package app.duck.notes.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.duck.notes.core.icons.DuckIcon
import app.duck.notes.core.icons.DuckIconImage
import app.duck.notes.core.icons.drawable
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
    DuckIconButton(icon.drawable(), contentDescription, onClick, modifier, enabled)
}

/**
 * The same button for a glyph outside the tag icon set — Phase 8c's format toolbar.
 *
 * Deliberately the same function rather than a `FormatIconButton` beside it: the enabled/disabled
 * colour rule and the 48dp target are the thing worth having in one place, and `ContrastTest`'s
 * `"icon button glyph"` distinguishability row covers this button, not a component name. A second
 * button type would owe its own row.
 */
@Composable
fun DuckIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = DuckTheme.colors

    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        DuckIconImage(
            id = icon,
            tint = if (enabled) colors.onSurface else colors.chromeMuted,
            contentDescription = contentDescription,
            modifier = Modifier.size(GLYPH),
        )
    }
}

private val GLYPH = 24.dp

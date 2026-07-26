package app.duck.notes.core.ui

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.duck.notes.core.theme.DuckTheme

/**
 * A hairline rule. The sidebar's separator between nav rows and pinned tags.
 *
 * Names its colour explicitly rather than letting `HorizontalDivider` inherit
 * `MaterialTheme.outlineVariant`. Those are the same value today — the projection maps
 * `outlineVariant` to `divider` — but this way a future remap of a Material slot cannot silently
 * change every rule in the app.
 *
 * `divider` is the one slot with **no contrast floor**: WCAG 1.4.11 exempts purely decorative
 * separators, and `#E5E5EA` cannot reach 3:1 on white without becoming a heavy grey line. Its measured
 * value is pinned in `ContrastTest` so a change shows up as a diff. If a rule ever needs to be a
 * *meaningful* boundary, it wants `chromeMuted`, not this.
 */
@Composable
fun DuckDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = thickness,
        color = DuckTheme.colors.divider,
    )
}

package app.duck.notes.core.ui

import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.duck.notes.core.theme.DuckTheme

/**
 * A task checkbox. Phase 8 wires it to `- [ ]` / `- [x]` toggling.
 *
 * The tick is a **WCAG 1.4.11 meaningful graphic owing 3:1, not 1.4.3 text owing 4.5**, which is why
 * `surface` on `accent` is fine here (3.35 min) even though the same pair as a chip *label* was
 * rejected. Deliberately not over-tuned into a floor it does not owe.
 *
 * The checked fill is measured against both containers — `accent` is 3.35 on `surface` and 3.15 on
 * `surfaceElevated` — because Phase 6 puts task rows in a list on an elevated pane, and a fill whose
 * edge vanishes against its container is not perceivable regardless of what the tick does.
 *
 * Material's `Checkbox` supplies the ripple, the animation and the 48dp target; only the three colours
 * are ours. Indeterminate state is skipped — nothing needs it.
 */
@Composable
fun DuckCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = DuckTheme.colors

    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = colors.accent,
            uncheckedColor = colors.chromeMuted,
            checkmarkColor = colors.surface,
            // chromeMuted is the one legitimate text-free use of the slot: a disabled control.
            disabledCheckedColor = colors.chromeMuted,
            disabledUncheckedColor = colors.chromeMuted,
        ),
    )
}

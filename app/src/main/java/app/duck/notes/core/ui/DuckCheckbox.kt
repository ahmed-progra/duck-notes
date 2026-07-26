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
 *
 * **Disabled + checked is a known open defect, not an oversight.** M3 1.4 has no
 * `disabledCheckedCheckmarkColor`, so the tick stays `surface` whatever `enabled` says. Keeping the
 * tick readable therefore forces the disabled fill to stay dark, while looking inactive requires it to
 * go light — directly opposed, so no token satisfies both. All 16 slots were searched: only
 * `accentPress`, `accentText`, `onSurface` and `codeOnSurface` clear both numerically, and every one of
 * them is *darker* than `accent`, which reads as more prominent rather than less. `chromeMuted` is kept
 * because it at least keeps the tick legible (3.26 worst), leaving the state distinguished by hue alone
 * — 1.00 luminance ratio in SepiaLight. Escaping it means dropping M3's `Checkbox` or not disabling
 * checked boxes at all; see the Phase 6 prerequisite in `DUCK_NOTES_BUILD_PROMPT.md`.
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
            // divider, NOT chromeMuted. An unchecked box is a border and nothing else, so
            // disabledUncheckedColor == uncheckedColor made the disabled control render *identically*
            // to the enabled one — 1.0000 in all eight palettes, no signal at all. WCAG exempts an
            // inactive component from contrast, but it still has to look inactive: a control that
            // looks live and does nothing is worse than a dim one. divider is 2.60 from chromeMuted at
            // worst, and recedes, which is the direction a disabled control should move.
            disabledUncheckedColor = colors.divider,
        ),
    )
}

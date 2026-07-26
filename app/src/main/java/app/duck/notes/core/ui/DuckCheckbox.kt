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
 * **Disabled + checked is deferred, not impossible — and it already works in half the palettes.**
 * `chromeMuted` against an enabled `accent` clears the 1.2 distinguishability rule in four of eight and
 * fails in `SepiaLight` (1.0024), `GraphiteDark` (1.0872), `SepiaDark` (1.1014) and `MidnightDark`
 * (1.0051). M3 1.4 has no `disabledCheckedCheckmarkColor`, so the tick stays `checkmarkColor` whatever
 * `enabled` says, and the disabled fill must both recede toward the page and keep that tick legible.
 * **Those pull in opposite directions per mode, not absolutely** — on a dark page receding means
 * darker, on a light page lighter:
 *
 * - **Dark mode is solved by `accentPress`**, which is the only palette slot inside the window in all
 *   four dark palettes (1.31–1.47 from accent, tick 3.06–3.87).
 * - **Three light palettes are already correct.** `chromeMuted` is *lighter* than `accent` in
 *   DuckRedLight, GraphiteLight and MidnightLight, so it recedes and clears both constraints today.
 * - **`SepiaLight` alone has an empty window**, by 0.0241, because its `surface` is cream `#FBF5E6`
 *   rather than white: the tick ceiling (lum 0.2718) falls below the distinguishability floor (0.2959).
 *   A dedicated `onAccent` tick closes it on existing values — `onSurface` is 3.61 on `accent`, with
 *   `divider` as the lighter fill at 2.67.
 *
 * So the fix costs one new slot for seven palettes, or two for all eight. It is **not built** because
 * nothing disables a checked box yet, and a state with no caller does not justify a 17th slot and eight
 * hand-tuned values. See the Phase 6 prerequisite in `DUCK_NOTES_BUILD_PROMPT.md` for the trigger that
 * changes that answer, and `ContrastTest.theDisabledCheckedFillIsRecordedPendingACaller` for the
 * numbers.
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

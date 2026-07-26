package app.duck.notes.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.duck.notes.core.icons.DuckIcon
import app.duck.notes.core.icons.DuckIconImage
import app.duck.notes.core.theme.DuckTheme

/**
 * A tag, as a chip. Sidebar rows and filter bars.
 *
 * **Paints the same pair `MarkdownStyles` already paints for an inline `#tag`** — `accentText` on
 * `tagSurface`, never a `surface` label on an `accent` fill. That second pattern is 4.25:1 in
 * DuckRedLight and 3.35:1 in SepiaLight, and it is what the Phase 5a gallery harness shipped before
 * this component replaced it. The editor and the chip now agree, for consistency as much as contrast.
 *
 * State is carried by **three simultaneous signals**, so it never depends on colour alone:
 *
 * | | fill | border | label |
 * |---|---|---|---|
 * | unselected | none | `chromeMuted` | `accentText`, Normal |
 * | selected | `tagSurface` | `accentText` | `accentText`, SemiBold |
 *
 * The border exists in **both** states on purpose. Phase 6 puts this in the sidebar, which is
 * `surfaceElevated`, and there the selected `tagSurface` fill is only 1.04–1.16 against its container —
 * under the project's own visibly-distinct rule in all eight palettes, and unsatisfiable in two of
 * them without moving a contract-pinned hex. The boundary is what makes the chip perceivable, and a
 * border does not care what it sits on.
 *
 * Built on `FilterChip` rather than a `Box` with a click handler: Material's chip announces its
 * selected state to TalkBack and supplies the 48dp touch target, neither of which is worth
 * re-implementing to save an import.
 */
@Composable
fun DuckTagChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: DuckIcon = DuckIcon.forTag(label),
) {
    val colors = DuckTheme.colors

    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        shape = DuckTheme.shapes.small,
        label = {
            Text(
                text = label,
                style = DuckTheme.type.uiLabel,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        leadingIcon = {
            DuckIconImage(
                icon = icon,
                // accentText, not accent: accent on tagSurface is 2.72 in SepiaLight, under the 3:1
                // a 1.4.11 graphic owes. The label colour clears it in every palette.
                tint = colors.accentText,
                contentDescription = null,
                modifier = Modifier.size(ICON),
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = colors.accentText,
            selectedContainerColor = colors.tagSurface,
            selectedLabelColor = colors.accentText,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) colors.accentText else colors.chromeMuted,
        ),
    )
}

private val ICON = 16.dp

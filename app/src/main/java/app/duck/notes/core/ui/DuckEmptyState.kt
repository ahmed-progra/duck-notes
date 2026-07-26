package app.duck.notes.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme

/**
 * The duck, and a line of text. Empty note lists, empty search, no-note-selected panes.
 *
 * The caption is **`proseMuted`, not `chromeMuted`**. `uiLabel` is 12sp, and WCAG 1.4.3's large-text
 * exemption starts at 18pt (14pt bold) — so a caption is normal text owing 4.5, which `chromeMuted`
 * misses in all four light palettes at 3.26–3.36. `proseMuted` is 5.44 on `surface` and 5.04 on
 * `surfaceElevated`.
 *
 * The mascot is a 1.4.11 graphic at 3:1, tinted `chromeMuted` (3.26 / 3.05) — decorative support for
 * the caption rather than the thing carrying the message, which is also why it is allowed to be quiet.
 *
 * The artwork is an **acknowledged placeholder**, on the same terms as the launcher icon. Phase 5's
 * "reads as a duck at 1x" criterion was dropped for that reason: it is not checkable against a shape
 * that is admittedly not the real illustration.
 */
@Composable
fun DuckEmptyState(
    caption: String,
    modifier: Modifier = Modifier,
) {
    val colors = DuckTheme.colors
    val spacing = DuckTheme.spacing

    Column(
        modifier = modifier.fillMaxSize().padding(spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.lg, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.duck_mascot),
            contentDescription = null, // the caption carries the meaning
            modifier = Modifier.size(MASCOT),
            colorFilter = ColorFilter.tint(colors.chromeMuted),
        )
        Text(
            text = caption,
            style = DuckTheme.type.uiLabel,
            color = colors.proseMuted,
            textAlign = TextAlign.Center,
        )
    }
}

private val MASCOT = 96.dp

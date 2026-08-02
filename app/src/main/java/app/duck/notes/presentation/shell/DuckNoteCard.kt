package app.duck.notes.presentation.shell

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme

/**
 * One row of the note list: title, two-line preview, timestamp — and an `accent` bar when it is the
 * note the editor has open.
 *
 * **The active row is a bar plus weight, not a tint, and that is a measured decision.** The obvious
 * build fills the row with `tagSurface`, and Phase 6's pair enumeration killed it twice over:
 * `proseMuted` on `tagSurface` is **4.4751 in GraphiteLight**, under the 4.5 a 12sp timestamp owes
 * as normal text under WCAG 1.4.3 (and 4.5404 / 4.5148 in two more palettes, which is no margin);
 * and `accent` on `tagSurface` is **2.7201 in SepiaLight**, under the 3:1 a 1.4.11 graphic owes —
 * the same number `DuckTagChip` already records for its leading icon. With no tint, the bar sits on
 * the pane and the text sits on the pane, and both pairs are ones already floored.
 * `ContrastTest.tintedActiveRowsAreRecordedPendingAPaletteInput` pins all three columns.
 *
 * Two signals, never colour alone: the bar and the SemiBold title. `Modifier.selectable` supplies
 * the ripple and the selected state TalkBack reads.
 *
 * Timestamps go through [DateUtils], so "5 minutes ago" / "Yesterday" are the platform's strings in
 * the platform's locale rather than a format we would have to localise ourselves.
 */
@Composable
fun DuckNoteCard(
    title: String,
    preview: String,
    modifiedAt: Long,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAddTag: (() -> Unit)? = null,
) {
    val colors = DuckTheme.colors
    val spacing = DuckTheme.spacing
    val addTagLabel = stringResource(R.string.tag_add_to_note)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(
                if (onAddTag == null) {
                    Modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick)
                } else {
                    // Long-press adds a tag. This is where "drag note onto tag" landed: on Compact
                    // the sidebar lives inside a ModalNavigationDrawer, so the drop target is not
                    // on screen while the drag source is — a real drag would be a tablet-only
                    // gesture that still needs this menu as its phone fallback, i.e. this plus a
                    // second implementation. Same append-to-body outcome, through the same SaveNote
                    // write path. onLongClickLabel is what puts it in TalkBack's action list.
                    Modifier
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = onAddTag,
                            onLongClickLabel = addTagLabel,
                            role = Role.Tab,
                        )
                        .semantics { this.selected = selected }
                },
            ),
    ) {
        // Flush to the leading edge and outside the text block. Always laid out, never absent, so an
        // inactive row's text does not shift sideways by 3dp — the bar hides by scaling to nothing,
        // which keeps that true through the transition as well as at either end of it.
        //
        // scaleY rather than alpha: a fade would paint `accent` below the 3:1 a 1.4.11 graphic owes
        // for the whole 120ms. Scaling keeps every painted frame at full `accent`. See DuckSidebar.
        val barScale by animateFloatAsState(
            targetValue = if (selected) 1f else 0f,
            animationSpec = DuckTheme.motion.activeBarSpec(selected),
            label = "noteCardActiveBar",
        )
        Box(
            Modifier
                .width(BAR)
                .fillMaxHeight()
                .graphicsLayer { scaleY = barScale }
                .background(colors.accent),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            Text(
                text = title,
                style = DuckTheme.type.uiBody,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    style = DuckTheme.type.uiSecondary,
                    // proseMuted, not chromeMuted: uiSecondary is 14sp, which is normal text under
                    // 1.4.3 — the large-text exemption starts at 18pt, or 14pt bold.
                    color = colors.proseMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
            Text(
                text = DateUtils.getRelativeTimeSpanString(modifiedAt).toString(),
                style = DuckTheme.type.uiLabel,
                // Likewise proseMuted. A 12sp timestamp is normal text and owes 4.5; chromeMuted is
                // 3.26-3.36 on the four light surfaces, which is why DuckColors narrowed that slot
                // out of carrying timestamps at all.
                color = colors.proseMuted,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}

private val BAR = 3.dp

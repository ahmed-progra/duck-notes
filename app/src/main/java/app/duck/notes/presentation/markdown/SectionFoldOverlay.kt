package app.duck.notes.presentation.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.markdown.FoldSection
import app.duck.notes.domain.markdown.SpanPlan
import app.duck.notes.domain.markdown.SpanPlanner
import kotlin.math.roundToInt

/**
 * The heading's disclosure control, living in the editor's leading gutter column so it never steals
 * caret taps.
 *
 * It uses the heading's rendered offset after all preceding folds, so it stays attached to its
 * heading when an earlier section hides its lines.
 *
 * ## Only the y comes from the text layout, and that is the fix for a shipped defect
 *
 * 8c-c positioned this as an absolutely-positioned child of the **text** Box at
 * `bounds.left - 24.dp`. For a heading at column 0 — the normal case — `bounds.left` is 0, so the
 * control's parent-space bounds were `[-24dp, 0dp]`: entirely left of its parent's origin. Compose
 * hit-tests within ancestor layout bounds, so **no touch could ever reach it**, while its semantics
 * node stayed perfect and TalkBack could. Measured on device at -3.81dp against an editor starting
 * at 20.19dp. Section folding shipped in the release APK and did nothing.
 *
 * The control now sits in its own layout slot, so its x is simply 0 inside a parent that contains
 * it. **Do not reintroduce an x offset here.** Horizontal placement is the gutter's job; this
 * composable's only claim on the text layout is the line's `top`, which is what keeps it aligned
 * with its heading. `SectionFoldUiTest.theChevronStaysInsideTheGutter` is the guard.
 *
 * The target is [FOLD_GUTTER] square — 48dp, Android's documented minimum — rather than the glyph's
 * own 20dp. Reachability and target *size* are different properties: the guard above proves the
 * first, and Compose's `click()` lands on exact bounds, so nothing in the suite would notice a
 * target too small for a thumb.
 */
@Composable
internal fun SectionFoldOverlay(
    section: FoldSection,
    plan: SpanPlan,
    layout: TextLayoutResult,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    val cuts = plan.conceal.sortedBy { it.first }
    val rendered = SpanPlanner.renderedOffset(section.headingStart, cuts)
    if (rendered >= layout.layoutInput.text.length) return

    val bounds = layout.getBoundingBox(rendered)
    val label = stringResource(
        if (collapsed) R.string.editor_expand_section else R.string.editor_collapse_section,
        section.title,
    )

    Box(
        modifier = Modifier
            .offset { IntOffset(0, bounds.top.roundToInt()) }
            // Sized from the gutter itself, so the touch target fills the column rather than
            // sitting in one corner of it, and so the two numbers cannot drift apart.
            .size(FOLD_GUTTER)
            .selectable(selected = collapsed, role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = label },
        // The glyph is 20dp inside a 48dp target: centred, not pinned to the corner the way an
        // unaligned Box would leave it.
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (collapsed) {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            } else {
                Icons.Filled.KeyboardArrowDown
            },
            contentDescription = null,
            tint = DuckTheme.colors.proseMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

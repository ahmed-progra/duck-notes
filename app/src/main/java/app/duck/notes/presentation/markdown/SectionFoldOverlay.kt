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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
 * The heading's disclosure control, placed in the editor gutter so it never steals caret taps.
 *
 * It is inside the same scrolling Box as the text and uses the heading's rendered offset after all
 * preceding folds. That keeps it attached to the heading when a parent section hides its children.
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
    val density = LocalDensity.current
    val label = stringResource(
        if (collapsed) R.string.editor_expand_section else R.string.editor_collapse_section,
        section.title,
    )

    Box(
        Modifier
            .offset {
                IntOffset(
                    (bounds.left - with(density) { 24.dp.toPx() }).roundToInt(),
                    bounds.top.roundToInt(),
                )
            }
            .size(24.dp)
            .selectable(selected = collapsed, role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = label },
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

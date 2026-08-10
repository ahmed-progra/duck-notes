package app.duck.notes.presentation.markdown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import app.duck.notes.domain.markdown.WikiLinkTarget
import kotlin.math.roundToInt

/**
 * The tappable half of a rendered `[[WikiLink]]` — transparent, on the title's own glyphs.
 *
 * ## This is where the tap stops being a coordinate, and it stops by never becoming one
 *
 * The overlay is placed at [target]'s **rendered** offsets, which `SpanPlanner.wikiLinkTargets`
 * produced by mapping the source span *forward* through `SpanPlanner.renderedOffset`. A tap that
 * lands here already knows which link it hit, because the composable closes over
 * [WikiLinkTarget.title]. **Nothing inverts anything.** There is no reverse of `renderedOffset` in
 * this codebase and 9d does not add one: an inverse would be a second implementation of a mapping
 * that already has a home, which is the defect `WikiLinkScanner` was extracted to close.
 *
 * The alternative — `detectTapGestures` plus `getOffsetForPosition` — is rejected for the three
 * reasons [TaskBoxOverlay] already writes down: raw interception must hand-roll slop and timeout or
 * it eats scrolls, it has to decide by itself not to move the caret, and it produces **no
 * accessibility node at all**. `Modifier.clickable` answers all three.
 *
 * ## Its absence is the routing rule
 *
 * `wikiLinkTargets` emits nothing for a link whose brackets are revealed, so a caret inside the link
 * removes this overlay and the tap falls through to `BasicTextField` and places the caret. That is
 * how a link is edited at all: tap beside it — endpoints count as touching — then tap into it. There
 * is no event consumption to get wrong, because the decision is whether the target exists.
 *
 * ## One Box per line, and why not one per range
 *
 * A title can wrap. [TaskBoxOverlay] gets away with a single rect because `[ ]` is three characters
 * at the start of a line; a title is as long as the user made it. So this emits one Box per line the
 * rendered range covers, each sized from the min/max of its own characters' bounding boxes — which is
 * bidi-correct, because `getBoundingBox` reports where bidi actually put each glyph.
 *
 * **Stated ceiling:** a title containing a *direction change inside itself* can be laid out as two
 * visually separated runs on one line, and min/max would then also cover the text between them. It
 * needs mixed direction within the title, not merely an RTL paragraph around it, so it is rare — and
 * over-covering steals adjacent taps rather than mis-navigating. Recorded rather than silently wrong.
 *
 * `absoluteOffset`, not `offset`, for the reason [TaskBoxOverlay] gives at length: these coordinates
 * come from the text layout and are already resolved for direction, and `offset` would mirror them a
 * second time under RTL. That was a shipped defect in 8d-a, and it is re-planted against
 * `RtlLayoutTest.theWikiLinkOverlayTracksItsGlyphsInBothDirections`.
 *
 * **Count:** one Box per line per link, not windowed to the viewport — the same ceiling
 * [TaskBoxOverlay] records, with the same upgrade path.
 */
@Composable
internal fun WikiLinkOverlay(
    target: WikiLinkTarget,
    layout: TextLayoutResult,
    onOpen: () -> Unit,
) {
    val density = LocalDensity.current
    val firstLine = layout.getLineForOffset(target.rendered.first)
    val lastLine = layout.getLineForOffset(target.rendered.last)

    for (line in firstLine..lastLine) {
        // Clamp this line's slice of the range to the line's own offsets, so a wrapped title
        // contributes only the characters actually on this line.
        val from = maxOf(target.rendered.first, layout.getLineStart(line))
        val to = minOf(target.rendered.last, layout.getLineEnd(line, visibleEnd = true) - 1)
        if (to < from) continue

        var left = Float.MAX_VALUE
        var right = Float.MIN_VALUE
        for (offset in from..to) {
            val glyph = layout.getBoundingBox(offset)
            left = minOf(left, glyph.left)
            right = maxOf(right, glyph.right)
        }
        val top = layout.getLineTop(line)
        val height = layout.getLineBottom(line) - top

        Box(
            Modifier
                .absoluteOffset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { (right - left).toDp() }, with(density) { height.toDp() })
                // No background: the title is already painted accent by MarkdownStyles' WIKI_LINK key.
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = target.title },
        )
    }
}

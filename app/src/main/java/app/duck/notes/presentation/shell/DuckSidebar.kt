package app.duck.notes.presentation.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.duck.notes.R
import app.duck.notes.core.icons.DuckIcon
import app.duck.notes.core.icons.DuckIconImage
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.ui.DuckDivider

/**
 * The leading pane: fixed rows, pinned tags, the derived tag tree, then Trash.
 *
 * **This is `surfaceElevated`, which is what makes it the phase's real test of the token layer.**
 * Phase 5's `DuckColors`, `DuckCheckbox`, `DuckIconButton` and `ContrastTest` all carry the sentence
 * "Phase 6 puts this in the sidebar", and 5b added `accent on surfaceElevated` and
 * `chromeMuted on surfaceElevated` in anticipation. Enumerating the 21 pairs this shell paints found
 * every one of them already floored or recorded — that foresight is why, not luck.
 *
 * **Rows are `Modifier.selectable`, deliberately not `NavigationDrawerItem`.** M3's item defaults
 * `selectedContainerColor` to `secondaryContainer`, which our projection maps to `tagSurface` — and
 * `tagSurface` on `surfaceElevated` measures **1.04–1.16**, already recorded as a ghost tint. The
 * component would have to be overridden in both states to be safe, at which point it is supplying
 * only the touch target, the ripple and the selected semantics, all of which `selectable` gives
 * directly with fewer defaults to fight. The one M3 container that does reach the screen is
 * `ModalDrawerSheet`'s, and `DuckShell` passes that explicitly.
 *
 * Selection is an `accent` bar plus SemiBold, the same two signals the note card uses, and for the
 * same measured reason — see `DuckNoteCard`.
 *
 * Fixed rows carry no icon. `DuckIcons` is a **tag** icon set of eight glyphs whose table grows when
 * a designer adds one; inventing five chrome glyphs for Notes/Today/Locked/Archive/Trash would be
 * art work smuggled into a structural phase. Tag rows do get `DuckIcon.forTag`, which is the set's
 * actual job.
 */
@Composable
fun DuckSidebar(
    filter: ShellFilter,
    pinnedTags: List<TagRow>,
    tagRows: List<TagRow>,
    onFilter: (ShellFilter) -> Unit,
    onToggleTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DuckTheme.colors
    val spacing = DuckTheme.spacing

    LazyColumn(modifier.fillMaxSize().background(colors.surfaceElevated)) {
        items(ShellFilter.navRows, key = { it.key }) { row ->
            SidebarRow(
                label = row.label(),
                selected = filter == row,
                onClick = { onFilter(row) },
            )
        }

        item { DuckDivider(Modifier.padding(vertical = spacing.sm)) }

        if (pinnedTags.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.shell_pinned)) }
            items(pinnedTags, key = { "pinned:${ShellFilter.Tag(it.path).key}" }) { row ->
                TagRowItem(row, filter, onFilter, onToggleTag)
            }
            item { DuckDivider(Modifier.padding(vertical = spacing.sm)) }
        }

        // Keys go through ShellFilter.Tag, NOT the raw path. A note tagged #todo produces a tag row
        // whose path is "todo", which is also ShellFilter.Todo's key — one LazyColumn, two identical
        // keys, and Compose throws. ShellFilter.Tag already namespaces with a "tag:" prefix for
        // exactly this reason; the sidebar just has to use it. Pinned rows need their own prefix
        // too, since a pinned tag also appears in the tree below.
        item { SectionHeader(stringResource(R.string.shell_tags)) }
        if (tagRows.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.shell_empty_tags),
                    style = DuckTheme.type.uiLabel,
                    color = colors.proseMuted,
                    modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
                )
            }
        } else {
            items(tagRows, key = { ShellFilter.Tag(it.path).key }) { row ->
                TagRowItem(row, filter, onFilter, onToggleTag)
            }
        }

        item { DuckDivider(Modifier.padding(vertical = spacing.sm)) }
        item {
            SidebarRow(
                label = ShellFilter.Trash.label(),
                selected = filter == ShellFilter.Trash,
                onClick = { onFilter(ShellFilter.Trash) },
            )
        }
    }
}

/**
 * One tag row: chevron, icon, label, indented by depth.
 *
 * An intermediate segment nobody tagged directly ([TagRow.selectable] false) can be expanded but not
 * filtered by — with only `a/b` stored there is no `a` row to select. Tapping its label toggles it
 * instead, so the row is never inert.
 */
@Composable
private fun TagRowItem(
    row: TagRow,
    filter: ShellFilter,
    onFilter: (ShellFilter) -> Unit,
    onToggleTag: (String) -> Unit,
) {
    SidebarRow(
        label = row.segment,
        selected = filter == ShellFilter.Tag(row.path),
        onClick = { if (row.selectable) onFilter(ShellFilter.Tag(row.path)) else onToggleTag(row.path) },
        indent = row.depth,
        chevron = if (row.hasChildren) row else null,
        onChevron = { onToggleTag(row.path) },
        icon = DuckIcon.forTag(row.path),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = DuckTheme.type.uiLabel,
        color = DuckTheme.colors.proseMuted,
        modifier = Modifier.padding(
            start = DuckTheme.spacing.lg,
            end = DuckTheme.spacing.lg,
            bottom = DuckTheme.spacing.xs,
        ),
    )
}

/**
 * The shared row shape. 48dp minimum, `selectable` for the ripple and the TalkBack selected state.
 *
 * Indentation uses `start` padding rather than `left`, and the bar is the row's *leading* edge, so
 * the whole sidebar mirrors correctly under RTL without a second layout.
 */
@Composable
private fun SidebarRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    indent: Int = 0,
    chevron: TagRow? = null,
    onChevron: () -> Unit = {},
    icon: DuckIcon? = null,
) {
    val colors = DuckTheme.colors
    val spacing = DuckTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(BAR)
                .fillMaxHeight()
                .heightIn(min = ROW_HEIGHT)
                .background(if (selected) colors.accent else Color.Transparent),
        )
        Box(Modifier.width(spacing.md + INDENT * indent))

        if (chevron != null) {
            Icon(
                imageVector = if (chevron.expanded) {
                    Icons.Filled.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                // Two distinct glyphs, not one rotated: the states have to differ in shape, not only
                // in orientation, for the difference to survive a small target and a quick glance.
                contentDescription = stringResource(
                    if (chevron.expanded) R.string.shell_collapse_tag else R.string.shell_expand_tag,
                    chevron.segment,
                ),
                tint = colors.proseMuted,
                modifier = Modifier
                    .size(CHEVRON)
                    .selectable(selected = chevron.expanded, role = Role.Button, onClick = onChevron),
            )
        } else if (indent > 0 || icon != null) {
            Box(Modifier.size(CHEVRON))
        }

        if (icon != null) {
            DuckIconImage(
                icon = icon,
                // proseMuted rather than onSurface: quieter than the label it sits beside, and still
                // 5.04-6.05 on surfaceElevated where a 1.4.11 graphic owes only 3.
                tint = colors.proseMuted,
                contentDescription = null, // the label carries the meaning
                modifier = Modifier
                    .padding(start = spacing.xs)
                    .size(GLYPH),
            )
        }

        Text(
            text = label,
            style = DuckTheme.type.uiBody,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = spacing.sm),
        )
    }
}

/** The seven fixed rows' labels. Kept beside the sidebar because that is the only thing that names them. */
@Composable
fun ShellFilter.label(): String = when (this) {
    ShellFilter.All -> stringResource(R.string.shell_notes)
    ShellFilter.Untagged -> stringResource(R.string.shell_untagged)
    ShellFilter.Todo -> stringResource(R.string.shell_todo)
    ShellFilter.Today -> stringResource(R.string.shell_today)
    ShellFilter.Locked -> stringResource(R.string.shell_locked)
    ShellFilter.Archive -> stringResource(R.string.shell_archive)
    ShellFilter.Trash -> stringResource(R.string.shell_trash)
    is ShellFilter.Tag -> path
}

private val ROW_HEIGHT = 48.dp
private val BAR = 3.dp
private val INDENT = 16.dp
private val CHEVRON = 20.dp
private val GLYPH = 18.dp

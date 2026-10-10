package app.duck.notes.presentation.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    onTagMenu: (TagRow, TagMenuAction) -> Unit = { _, _ -> },
    onSettings: (() -> Unit)? = null,
) {
    val colors = DuckTheme.colors
    val spacing = DuckTheme.spacing
    val motion = DuckTheme.motion

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
                TagRowItem(row, filter, onFilter, onToggleTag, onTagMenu)
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
                // Expanding a tag is an item *insertion* — `flatten` emits descendants only while
                // the parent is expanded — so the same modifier that animates a list change animates
                // the tree opening. No AnimatedVisibility, and no second mechanism to keep in step.
                TagRowItem(
                    row, filter, onFilter, onToggleTag, onTagMenu,
                    modifier = Modifier.animateItem(
                        fadeInSpec = motion.mediumSpec(),
                        placementSpec = motion.placementSpec(),
                        fadeOutSpec = motion.exitSpec(),
                    ),
                )
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
        if (onSettings != null) {
            item {
                SidebarRow(
                    label = stringResource(R.string.settings_title),
                    selected = false,
                    onClick = onSettings,
                )
            }
        }
    }
}

/** What a tag row's long-press menu can ask for. The shell decides what each one opens. */
enum class TagMenuAction { Pin, Rename, Icon, Delete }

/**
 * One tag row: chevron, icon, label, count, indented by depth.
 *
 * An intermediate segment nobody tagged directly ([TagRow.selectable] false) can be expanded but not
 * filtered by — with only `a/b` stored there is no `a` row to select. Tapping its label toggles it
 * instead, so the row is never inert.
 *
 * **That same row gets no menu.** There is no `tags` row behind it to pin, rename, icon or delete,
 * so the honest affordance is absence rather than four disabled items — which would also be the only
 * menu in the app where every entry is dead.
 */
@Composable
private fun TagRowItem(
    row: TagRow,
    filter: ShellFilter,
    onFilter: (ShellFilter) -> Unit,
    onToggleTag: (String) -> Unit,
    onTagMenu: (TagRow, TagMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier) {
        SidebarRow(
            label = row.segment,
            selected = filter == ShellFilter.Tag(row.path),
            onClick = {
                if (row.selectable) onFilter(ShellFilter.Tag(row.path)) else onToggleTag(row.path)
            },
            indent = row.depth,
            chevron = if (row.hasChildren) row else null,
            onChevron = { onToggleTag(row.path) },
            // The row's own iconKey, or the nearest ancestor's — resolved in `flatten`, because
            // inheriting a stored key needs the whole tree and `forTag` sees one name.
            icon = DuckIcon.forTag(row.path, row.iconKey),
            count = row.count,
            onLongClick = if (row.selectable) ({ menuOpen = true }) else null,
        )
        if (row.selectable) {
            TagRowMenu(
                row = row,
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onPin = { onTagMenu(row, TagMenuAction.Pin) },
                onRename = { onTagMenu(row, TagMenuAction.Rename) },
                onIcon = { onTagMenu(row, TagMenuAction.Icon) },
                onDelete = { onTagMenu(row, TagMenuAction.Delete) },
            )
        }
    }
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
    count: Int = 0,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = DuckTheme.colors
    val spacing = DuckTheme.spacing
    val menuLabel = stringResource(R.string.tag_actions, label)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .then(
                if (onLongClick == null) {
                    Modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick)
                } else {
                    // combinedClickable rather than selectable, for the long press. The selected
                    // state selectable would have announced is restored explicitly below, and
                    // onLongClickLabel is what puts the menu in TalkBack's actions list — a gesture
                    // a screen-reader user cannot perform is not an affordance.
                    Modifier
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = onLongClick,
                            onLongClickLabel = menuLabel,
                            role = Role.Tab,
                        )
                        .semantics { this.selected = selected }
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // scaleY, not alpha, and the distinction is a contract one rather than a taste one. Fading
        // the bar would render `accent` at partial opacity for the whole transition, i.e. below the
        // 3:1 a 1.4.11 graphic owes, on the most-tapped element in the app. Growing it from its
        // centre keeps every painted frame at full `accent`, so the ratio is invariant and
        // `duckPairs` needs no exemption for it.
        //
        // Both directions are specified: see DuckMotion.activeBarSpec. The row losing selection
        // shrinks out as deliberately as the row gaining it grows in.
        val barScale by animateFloatAsState(
            targetValue = if (selected) 1f else 0f,
            animationSpec = DuckTheme.motion.activeBarSpec(selected),
            label = "sidebarActiveBar",
        )
        Box(
            Modifier
                .width(BAR)
                .fillMaxHeight()
                .heightIn(min = ROW_HEIGHT)
                .graphicsLayer { scaleY = barScale }
                .background(colors.accent),
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
            // weight(1f) with fill, and no Spacer after it: the label absorbs all leftover space so
            // the count lands on the right edge. `weight(1f, fill = false)` plus a `weight(1f)`
            // Spacer splits the remainder in half instead, which puts the count at
            // *label width + half the rest* — visibly ragged down a column of mixed-length tags,
            // and the one thing criterion 27 asks of the count.
            modifier = Modifier.weight(1f).padding(horizontal = spacing.sm),
        )

        if (count > 0) {
            Text(
                text = stringResource(R.string.tag_note_count, count),
                style = DuckTheme.type.uiLabel,
                // proseMuted, not chromeMuted. A 12sp count is normal text under WCAG 1.4.3 and
                // owes 4.5 — the large-text exemption starts at 18pt. Same call DuckNoteCard makes
                // for its timestamp, and the reason DuckColors narrowed chromeMuted out of text.
                color = colors.proseMuted,
                maxLines = 1,
                modifier = Modifier.padding(end = spacing.md),
            )
        }
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

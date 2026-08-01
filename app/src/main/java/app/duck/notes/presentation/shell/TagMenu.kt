package app.duck.notes.presentation.shell

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.duck.notes.R
import app.duck.notes.core.icons.DuckIcon
import app.duck.notes.core.icons.DuckIconImage
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.markdown.TagScanner
import java.util.Locale

/**
 * The tag row's long-press menu.
 *
 * ## The border is not decoration
 *
 * `DropdownMenu` takes `MenuDefaults.containerColor`, which is `surfaceContainer` — and
 * `duckColorScheme` maps that to `surfaceElevated`. **The sidebar is also `surfaceElevated`**, so
 * this menu opens over a background it measures 1.00:1 against, separated by nothing but M3's
 * elevation shadow. That is Phase 6's SepiaLight finding exactly, where `surfaceElevated on surface`
 * is 1.06 and the panes do not read as planes at all.
 *
 * So it carries an explicit 1dp `chromeMuted` border. **`chromeMuted`, not `divider`:** the border is
 * a real WCAG 1.4.11 boundary at 3:1, and `chromeMuted on surfaceElevated` is already floored at 3.0
 * in `ContrastTest`, while `divider` measures 1.17–1.26 there and would need the pane-rule exemption
 * argued a second time for a case that does not deserve it. Same shape as `DuckTagChip`'s
 * border-in-both-states, and free.
 *
 * ## No item is ever disabled
 *
 * An intermediate segment nobody tagged directly has no `tags` row to pin, rename or delete — so it
 * gets **no menu at all** rather than a menu of dead items. `DuckSidebar` decides that by
 * [TagRow.selectable]; a menu whose every item is greyed is worse than no menu.
 */
@Composable
fun TagRowMenu(
    row: TagRow,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onIcon: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = DuckTheme.colors

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceElevated,
        modifier = Modifier.border(1.dp, colors.chromeMuted, DuckTheme.shapes.small),
    ) {
        MenuItem(if (row.pinned) R.string.tag_unpin else R.string.tag_pin) { onPin(); onDismiss() }
        MenuItem(R.string.tag_rename) { onRename(); onDismiss() }
        MenuItem(R.string.tag_change_icon) { onIcon(); onDismiss() }
        MenuItem(R.string.tag_delete) { onDelete(); onDismiss() }
    }
}

@Composable
private fun MenuItem(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(label),
                style = DuckTheme.type.uiBody,
                color = DuckTheme.colors.onSurface,
            )
        },
        onClick = onClick,
        colors = MenuDefaults.itemColors(textColor = DuckTheme.colors.onSurface),
    )
}

/**
 * Rename, with live validation and the merge named before it happens.
 *
 * **The confirm button is the one control this phase gives an `enabled` to**, which fires the
 * distinguishability duty. Both halves of it are discharged here and in `ContrastTest`:
 *
 * 1. `disabledContentColor` is passed **opaque** as `chromeMuted`. Left to Material it would be
 *    `contentColor × 0.38`, a composite that is on screen, in no palette, and unmeasurable by
 *    construction — so "is this still visible?" becomes unanswerable rather than merely unanswered.
 * 2. `ContrastTest.distinguishability` gains a row for the one element a `TextButton` paints. It
 *    measures **1.288 at worst in DuckRedDark — the thinnest row in that list**, so it is the first
 *    thing a palette edit will squeeze.
 *
 * Validation is [TagScanner.isValidName], the same round trip the use case repeats, so a name the
 * dialog accepts can never be one the rewrite rejects.
 */
@Composable
fun RenameTagDialog(
    path: String,
    existingPaths: List<String>,
    counts: Map<String, Int>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DuckTheme.colors
    var input by rememberSaveable(path) { mutableStateOf(path) }

    // Lowercased before anything looks at it: the tags.name UNIQUE index is a byte comparison, so
    // #Work and #work must collapse to one row. isValidName enforces it structurally too.
    val target = input.trim().lowercase(Locale.ROOT)
    val valid = TagScanner.isValidName(target)
    val unchanged = target == path
    val merging = valid && !unchanged && target in existingPaths

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceElevated,
        titleContentColor = colors.onSurface,
        textContentColor = colors.proseMuted,
        title = { Text(stringResource(R.string.tag_rename_title, path), style = DuckTheme.type.uiTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DuckTheme.spacing.sm)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    isError = input.isNotBlank() && !valid,
                    label = { Text(stringResource(R.string.tag_rename_label)) },
                )
                when {
                    input.isNotBlank() && !valid -> Text(
                        text = stringResource(R.string.tag_rename_invalid),
                        style = DuckTheme.type.uiLabel,
                        // The one new pair this phase adds to ContrastTest's floor table:
                        // error on surfaceElevated at 4.5. error had only ever been floored
                        // against `surface` and `errorSurface` before a dialog existed.
                        color = colors.error,
                    )
                    merging -> Text(
                        text = pluralStringResource(
                            R.plurals.tag_rename_merge,
                            counts[target] ?: 0,
                            target,
                            counts[target] ?: 0,
                        ),
                        style = DuckTheme.type.uiLabel,
                        color = colors.proseMuted,
                    )
                    existingPaths.any { it.startsWith("$path/") } -> Text(
                        text = stringResource(R.string.tag_rename_descendants),
                        style = DuckTheme.type.uiLabel,
                        color = colors.proseMuted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(target) },
                enabled = valid && !unchanged,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = colors.accentText,
                    disabledContentColor = colors.chromeMuted,
                    disabledContainerColor = Color.Transparent,
                ),
            ) {
                Text(stringResource(R.string.tag_rename_confirm))
            }
        },
        dismissButton = { DialogCancel(onDismiss) },
    )
}

/**
 * Delete, naming the two things a user fears: how many notes it touches, and what happens to them.
 *
 * Rename needs no confirmation — it *is* a dialog, so typing a new name is the confirmation. This is
 * a menu item that would otherwise fire on one tap, and it destroys text rather than respelling it.
 * The Snackbar undo is not a substitute: the dialog stops the accident, the undo covers the regret.
 *
 * The confirm button is **always enabled**, so this adds no second distinguishability row.
 */
@Composable
fun DeleteTagDialog(
    path: String,
    noteCount: Int,
    hasChildren: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DuckTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceElevated,
        titleContentColor = colors.onSurface,
        textContentColor = colors.proseMuted,
        title = { Text(stringResource(R.string.tag_delete_title, path), style = DuckTheme.type.uiTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DuckTheme.spacing.xs)) {
                Text(
                    text = pluralStringResource(R.plurals.tag_delete_body, noteCount, noteCount),
                    style = DuckTheme.type.uiBody,
                    color = colors.proseMuted,
                )
                // Said out loud because rename cascades and this does not — a user who has just
                // renamed a parent has every reason to expect delete to behave the same way.
                if (hasChildren) {
                    Text(
                        text = stringResource(R.string.tag_delete_keeps_children),
                        style = DuckTheme.type.uiLabel,
                        color = colors.proseMuted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
            ) {
                Text(stringResource(R.string.tag_delete_confirm))
            }
        },
        dismissButton = { DialogCancel(onDismiss) },
    )
}

/**
 * The icon picker: the eight shipped glyphs plus **Automatic**.
 *
 * Automatic clears the override rather than meaning "no icon" — the row returns to
 * `DuckIcon.forTag`'s keyword resolution, which is total and falls back to the generic glyph.
 *
 * **The set does not grow here.** Phase 5b named the eight and proved them legible at 20dp blind on
 * device; growing toward ~60 is drawable authoring with no logic change, and doing it inside a tag
 * *management* phase is art work smuggled into a data phase. The picker exists so a user can
 * override the resolver, which is what `icon_key` was added to the Phase 2 schema for.
 *
 * **The border carries selection; the fill is a hint at best.** `tagSurface on surfaceElevated` is
 * 1.036–1.157, recorded in `componentFillsOnElevatedContainersAreRecorded` and under the 1.2 the
 * project calls visibly distinct — so the fill is not a second signal, it is a decoration that
 * happens to be there. On device in DuckRed light it is not perceptible at all. The border is what
 * makes the selection perceivable, exactly as on `DuckTagChip`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagIconDialog(
    path: String,
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DuckTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceElevated,
        titleContentColor = colors.onSurface,
        textContentColor = colors.proseMuted,
        title = { Text(stringResource(R.string.tag_icon_title, path), style = DuckTheme.type.uiTitle) },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DuckTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(DuckTheme.spacing.sm),
            ) {
                IconCell(
                    icon = DuckIcon.forTag(path),
                    label = stringResource(R.string.tag_icon_automatic),
                    selected = current == null,
                    onClick = { onPick(null) },
                )
                DuckIcon.entries.forEach { icon ->
                    IconCell(
                        icon = icon,
                        label = icon.name,
                        selected = current.equals(icon.name, ignoreCase = true),
                        onClick = { onPick(icon.name) },
                    )
                }
            }
        },
        confirmButton = { DialogCancel(onDismiss) },
    )
}

@Composable
private fun IconCell(
    icon: DuckIcon,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = DuckTheme.colors

    Box(
        modifier = Modifier
            .size(CELL)
            .border(
                width = 1.dp,
                // Two states, two colours, and a border in BOTH: the fill alone is 1.036-1.157
                // against this container, which is under the project's visibly-distinct rule.
                color = if (selected) colors.accentText else colors.chromeMuted,
                shape = DuckTheme.shapes.small,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        DuckIconImage(
            icon = icon,
            tint = colors.accentText,
            contentDescription = if (selected) {
                stringResource(R.string.tag_icon_selected, label)
            } else {
                label
            },
            modifier = Modifier.size(GLYPH),
        )
    }
}

/** The tag picker behind a note card's "Add tag…". Tags the note already carries are absent. */
@Composable
fun AddTagDialog(
    available: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DuckTheme.colors
    var custom by rememberSaveable { mutableStateOf("") }
    val target = custom.trim().lowercase(Locale.ROOT)
    val valid = TagScanner.isValidName(target)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceElevated,
        titleContentColor = colors.onSurface,
        textContentColor = colors.proseMuted,
        title = { Text(stringResource(R.string.tag_add_title), style = DuckTheme.type.uiTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DuckTheme.spacing.sm)) {
                available.forEach { path ->
                    Text(
                        text = path,
                        style = DuckTheme.type.uiBody,
                        color = colors.onSurface,
                        modifier = Modifier
                            .selectable(selected = false, onClick = { onPick(path) })
                            .padding(vertical = DuckTheme.spacing.xs),
                    )
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    singleLine = true,
                    isError = custom.isNotBlank() && !valid,
                    label = { Text(stringResource(R.string.tag_add_new)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(target) },
                enabled = valid,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = colors.accentText,
                    disabledContentColor = colors.chromeMuted,
                    disabledContainerColor = Color.Transparent,
                ),
            ) {
                Text(stringResource(R.string.tag_add_to_note))
            }
        },
        dismissButton = { DialogCancel(onDismiss) },
    )
}

@Composable
private fun DialogCancel(onDismiss: () -> Unit) {
    TextButton(
        onClick = onDismiss,
        colors = ButtonDefaults.textButtonColors(contentColor = DuckTheme.colors.accentText),
    ) {
        Text(stringResource(R.string.tag_cancel))
    }
}

private val CELL = 48.dp
private val GLYPH = 24.dp

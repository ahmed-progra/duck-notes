package app.duck.notes.presentation.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.model.NoteSort
import app.duck.notes.domain.model.SearchFilters
import app.duck.notes.domain.model.SearchScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteListControls(order: NoteSort, filters: SearchFilters, tagPaths: List<String>,
    onSort: ((NoteSort) -> Unit)?, onFilters: ((SearchFilters) -> Unit)?, sortPending: Boolean) {
    if (onSort == null && onFilters == null) return
    var sorting by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    var pickingTag by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = DuckTheme.spacing.sm)) {
        onSort?.let { change ->
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { sorting = true }, enabled = !sortPending) { Text(stringResource(R.string.note_sort)) }
                DropdownMenu(sorting, onDismissRequest = { sorting = false }) {
                    NoteSort.entries.forEach { value ->
                        val label = when (value) {
                            NoteSort.Modified -> R.string.note_sort_modified
                            NoteSort.Created -> R.string.note_sort_created
                            NoteSort.Title -> R.string.note_sort_title
                        }
                        DropdownMenuItem(text = { Text(stringResource(label), fontWeight = if (value == order) FontWeight.SemiBold else FontWeight.Normal) },
                            modifier = Modifier.semantics { selected = value == order },
                            onClick = { sorting = false; change(value) })
                    }
                }
            }
        }
        onFilters?.let {
            val count = listOf(filters.tagPath != null, filters.pinnedOnly, filters.openTasksOnly, filters.imagesOnly, filters.scope != SearchScope.Both).count { it }
            TextButton(onClick = { filtering = true }) { Text(stringResource(R.string.search_filters, count)) }
        }
    }
    if (filtering && onFilters != null) {
        ModalBottomSheet(onDismissRequest = { filtering = false }, containerColor = DuckTheme.colors.surfaceElevated) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(DuckTheme.spacing.lg)) {
                Text(stringResource(R.string.search_filter_title), style = DuckTheme.type.uiTitle, color = DuckTheme.colors.onSurface)
                TextButton(onClick = { pickingTag = true }) { Text(filters.tagPath?.let { "#$it" } ?: stringResource(R.string.search_any_tag)) }
                FilterCheck(stringResource(R.string.search_pinned), filters.pinnedOnly) { onFilters(filters.copy(pinnedOnly = it)) }
                FilterCheck(stringResource(R.string.search_open_tasks), filters.openTasksOnly) { onFilters(filters.copy(openTasksOnly = it)) }
                FilterCheck(stringResource(R.string.search_images), filters.imagesOnly) { onFilters(filters.copy(imagesOnly = it)) }
                Column(Modifier.selectableGroup()) {
                    SearchScope.entries.forEach { scope ->
                        val label = when (scope) {
                            SearchScope.Both -> R.string.search_scope_both
                            SearchScope.Active -> R.string.search_scope_active
                            SearchScope.Archive -> R.string.search_scope_archive
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(filters.scope == scope, role = Role.RadioButton,
                            onClick = { onFilters(filters.copy(scope = scope)) })) {
                            RadioButton(filters.scope == scope, onClick = null)
                            Text(stringResource(label), modifier = Modifier.padding(vertical = DuckTheme.spacing.md), color = DuckTheme.colors.onSurface)
                        }
                    }
                }
                Row {
                    TextButton(onClick = { onFilters(SearchFilters()) }) { Text(stringResource(R.string.search_reset_filters)) }
                    TextButton(onClick = { filtering = false }) { Text(stringResource(R.string.attachment_close)) }
                }
            }
        }
    }
    if (pickingTag && onFilters != null) {
        var query by remember { mutableStateOf("") }
        val paths = remember(tagPaths) { tagPaths.flatMap { it.split('/').runningReduce { a, b -> "$a/$b" } }.distinct().sorted() }
        AlertDialog(onDismissRequest = { pickingTag = false }, title = { Text(stringResource(R.string.search_choose_tag)) },
            text = {
                Column {
                    OutlinedTextField(query, onValueChange = { query = it }, singleLine = true, label = { Text(stringResource(R.string.search_choose_tag)) })
                    LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
                        item { TextButton(onClick = { onFilters(filters.copy(tagPath = null)); pickingTag = false }) { Text(stringResource(R.string.search_any_tag)) } }
                        items(paths.filter { it.contains(query, ignoreCase = true) }, key = { it }) { path ->
                            TextButton(onClick = { onFilters(filters.copy(tagPath = path)); pickingTag = false }, modifier = Modifier.fillMaxWidth()) { Text("#$path") }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { pickingTag = false }) { Text(stringResource(R.string.attachment_close)) } })
    }
}

@Composable
private fun FilterCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange)) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(vertical = DuckTheme.spacing.md), color = DuckTheme.colors.onSurface)
    }
}

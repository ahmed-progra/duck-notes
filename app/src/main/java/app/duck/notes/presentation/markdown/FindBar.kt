package app.duck.notes.presentation.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.markdown.LiteralFind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class FindResults(val body: String, val query: String, val matchCase: Boolean, val starts: IntArray)

/** Search results are bound to the exact buffer version; stale results cannot replace newer text. */
@Composable
internal fun FindBar(state: TextFieldState, canEdit: () -> Boolean, onMatch: (IntRange?) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var index by remember { mutableStateOf(0) }
    val body = state.text.toString()
    val results by produceState(FindResults("", "", false, intArrayOf()), body, query, matchCase) {
        value = withContext(Dispatchers.Default) { FindResults(body, query, matchCase, LiteralFind.matches(body, query, matchCase)) }
    }
    val ready = results.body == body && results.query == query && results.matchCase == matchCase
    val count = if (ready) results.starts.size else 0
    fun showMatch(position: Int) {
        if (!ready || count == 0) { onMatch(null); return }
        index = ((position % count) + count) % count
        val start = results.starts[index]
        state.edit { selection = TextRange(start, start + query.length) }
        onMatch(start until start + query.length)
    }
    LaunchedEffect(results) { showMatch(0) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().heightIn(max = (maxHeight * 0.5f).coerceAtMost(280.dp))
        .verticalScroll(rememberScrollState()).padding(horizontal = DuckTheme.spacing.md)) {
        OutlinedTextField(query, onValueChange = { query = it }, label = { Text(stringResource(R.string.editor_find)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("findQuery"))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { showMatch(index - 1) }, enabled = count > 0) { Text(stringResource(R.string.editor_find_previous)) }
            Text(stringResource(R.string.editor_find_count, if (count == 0) 0 else index.coerceAtMost(count - 1) + 1, count),
                modifier = Modifier.padding(vertical = 12.dp), color = DuckTheme.colors.onSurface)
            TextButton(onClick = { showMatch(index + 1) }, enabled = count > 0) { Text(stringResource(R.string.editor_find_next)) }
            TextButton(onClick = onClose) { Text(stringResource(R.string.attachment_close)) }
        }
        Row(Modifier.heightIn(min = 48.dp).toggleable(matchCase, role = Role.Checkbox, onValueChange = { matchCase = it })) {
            Checkbox(matchCase, onCheckedChange = null)
            Text(stringResource(R.string.editor_find_case), modifier = Modifier.padding(vertical = 12.dp), color = DuckTheme.colors.onSurface)
        }
        OutlinedTextField(replacement, onValueChange = { replacement = it }, label = { Text(stringResource(R.string.editor_replace)) },
            singleLine = true, enabled = canEdit(), modifier = Modifier.fillMaxWidth().testTag("replaceQuery"))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(enabled = canEdit() && count > 0, onClick = {
                if (canEdit() && ready && state.text.toString() == results.body && count > 0) {
                    val start = results.starts[index.coerceAtMost(count - 1)]
                    state.edit { replace(start, start + query.length, replacement); selection = TextRange(start + replacement.length) }
                    onMatch(null)
                }
            }) { Text(stringResource(R.string.editor_replace_one)) }
            TextButton(enabled = canEdit() && count > 0, onClick = {
                if (canEdit() && ready && state.text.toString() == results.body && count > 0) {
                    state.edit {
                        for (i in results.starts.indices.reversed()) {
                            val start = results.starts[i]
                            replace(start, start + query.length, replacement)
                        }
                        selection = TextRange((results.starts[0] + replacement.length).coerceAtMost(length))
                    }
                    onMatch(null)
                }
            }) { Text(stringResource(R.string.editor_replace_all)) }
        }
    }
    }
}

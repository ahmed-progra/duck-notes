package app.duck.notes.presentation.transfer

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme

/** Always registered, including while app authentication covers the notes. */
@Composable
fun TransferHost(viewModel: TransferViewModel, canAccessNotes: Boolean, canUseTransfers: () -> Boolean, onRetry: () -> Unit, onSubmit: () -> Unit) {
    val request by viewModel.request.collectAsStateWithLifecycle()
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> viewModel.pickerReturned(TransferKind.Import, uri?.toString()) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> viewModel.pickerReturned(TransferKind.Restore, uri?.toString()) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.pickerReturned(TransferKind.Export, result.data?.data?.toString().takeIf { result.resultCode == Activity.RESULT_OK })
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.pickerReturned(TransferKind.Backup, result.data?.data?.toString().takeIf { result.resultCode == Activity.RESULT_OK })
    }
    LaunchedEffect(request, canAccessNotes, lifecycle) {
        val pending = request ?: return@LaunchedEffect
        if (!canAccessNotes || !canUseTransfers() || lifecycle != Lifecycle.State.RESUMED || pending.launched || pending.returned) return@LaunchedEffect
        viewModel.markLaunched(pending.id)
        try {
            when (pending.kind) {
                TransferKind.Import -> open.launch(arrayOf("text/markdown", "text/plain", "application/zip", "application/octet-stream"))
                TransferKind.Restore -> restore.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
                TransferKind.Backup -> backup.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = checkNotNull(pending.document).mime
                    putExtra(Intent.EXTRA_TITLE, pending.document.displayName)
                })
                TransferKind.Export -> create.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = checkNotNull(pending.document).mime
                    putExtra(Intent.EXTRA_TITLE, pending.document.displayName)
                })
                TransferKind.Share -> {
                    val uri = Uri.parse(checkNotNull(pending.uri))
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = checkNotNull(pending.document).mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(pending.document.displayName, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.transfer_share)))
                    viewModel.shareLaunched(pending.id)
                }
            }
        } catch (_: Exception) { viewModel.launchFailed(pending.id) }
    }
    if (!canAccessNotes) return
    val step = state.step
    if (step != null) {
        val message = when (step) {
            TransferStep.Preparing -> R.string.transfer_preparing
            TransferStep.Reading -> R.string.transfer_reading
            TransferStep.Importing -> R.string.transfer_importing
            TransferStep.Writing -> R.string.transfer_writing
            TransferStep.Unlocking -> R.string.backup_unlocking
            TransferStep.Restoring -> R.string.backup_restoring
        }
        AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.transfer_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(DuckTheme.spacing.md)) { CircularProgressIndicator(); Text(stringResource(message)) } },
            confirmButton = { if (step !in setOf(TransferStep.Importing, TransferStep.Restoring)) TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.transfer_cancel)) } })
    } else if (state.prompt != null) {
        BackupPasswordDialog(viewModel, state, onSubmit)
    } else if (state.notice != null || state.cleanupFailed) {
        val message = when (state.notice) {
            TransferNotice.Cancelled -> R.string.transfer_cancelled
            TransferNotice.Written -> R.string.transfer_written
            TransferNotice.Imported -> R.string.transfer_imported
            TransferNotice.PrepareFailed -> R.string.transfer_prepare_failed
            TransferNotice.ImportFailed -> R.string.transfer_import_failed
            TransferNotice.WriteFailed -> R.string.transfer_write_failed
            TransferNotice.Unavailable -> R.string.transfer_unavailable
            TransferNotice.BackupWritten -> R.string.backup_written
            TransferNotice.Restored -> R.string.backup_restored
            TransferNotice.RestoreFailed -> R.string.backup_failed
            null -> R.string.transfer_cleanup_failed
            else -> R.string.transfer_interrupted
        }
        AlertDialog(onDismissRequest = viewModel::dismissNotice, title = { Text(stringResource(R.string.transfer_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(DuckTheme.spacing.md)) {
                Text(when (state.notice) {
                    TransferNotice.Imported -> stringResource(message, state.imported)
                    TransferNotice.Restored -> stringResource(message, state.imported, state.skipped)
                    else -> stringResource(message)
                })
                if (state.notice == TransferNotice.Imported && state.duplicates > 0) Text(stringResource(R.string.transfer_duplicates, state.duplicates))
                if (state.cleanupFailed && state.notice != null) Text(stringResource(R.string.transfer_cleanup_failed))
            } },
            confirmButton = {
                if (state.canRetry) TextButton(onClick = onRetry) { Text(stringResource(R.string.editor_save_retry)) }
                else TextButton(onClick = viewModel::dismissNotice) { Text(stringResource(R.string.attachment_close)) }
            }, dismissButton = { if (state.canRetry) TextButton(onClick = viewModel::dismissNotice) { Text(stringResource(R.string.attachment_close)) } })
    }
}

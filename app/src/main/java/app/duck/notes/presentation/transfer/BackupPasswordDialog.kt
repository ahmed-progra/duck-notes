package app.duck.notes.presentation.transfer

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme

@Composable
internal fun BackupPasswordDialog(viewModel: TransferViewModel, state: TransferUiState, onSubmit: () -> Unit) {
    val creating = state.prompt == BackupPrompt.Create
    AlertDialog(onDismissRequest = viewModel::cancelPassphrase,
        title = { Text(stringResource(if (creating) R.string.backup_create else R.string.backup_restore)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(DuckTheme.spacing.md)) {
            Text(stringResource(if (creating) R.string.backup_password_help else R.string.backup_restore_help))
            PasswordField(viewModel.passphrase, stringResource(R.string.backup_passphrase), "backupPassphrase")
            if (creating) PasswordField(viewModel.confirmation, stringResource(R.string.backup_confirmation), "backupConfirmation")
            state.passwordError?.let { error ->
                val message = when (error) {
                    BackupPasswordError.TooShort -> if (creating) R.string.backup_password_short else R.string.backup_password_empty
                    BackupPasswordError.TooLong -> R.string.backup_password_long
                    BackupPasswordError.Mismatch -> R.string.backup_password_mismatch
                    BackupPasswordError.UnlockFailed -> R.string.backup_unlock_failed
                }
                Text(stringResource(message), color = DuckTheme.colors.error)
            }
        } },
        confirmButton = { TextButton(onClick = onSubmit) { Text(stringResource(if (creating) R.string.backup_submit else R.string.backup_restore_submit)) } },
        dismissButton = { TextButton(onClick = viewModel::cancelPassphrase) { Text(stringResource(R.string.transfer_cancel)) } })
}

@Composable
private fun PasswordField(state: TextFieldState, label: String, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(DuckTheme.spacing.sm)) {
        Text(label, style = DuckTheme.type.uiBody)
        BasicSecureTextField(state = state,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag).semantics { contentDescription = label }
                .border(1.dp, DuckTheme.colors.divider, RoundedCornerShape(8.dp)).padding(DuckTheme.spacing.md),
            inputTransformation = InputTransformation.maxLength(1024),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            textObfuscationMode = TextObfuscationMode.Hidden,
            textStyle = DuckTheme.type.uiBody.copy(color = DuckTheme.colors.onSurface),
            cursorBrush = SolidColor(DuckTheme.colors.accent))
    }
}

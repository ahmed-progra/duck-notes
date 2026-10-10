package app.duck.notes.presentation.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.duck.notes.BuildConfig
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.model.AppSettings
import app.duck.notes.domain.model.ThemePreference

@Composable
fun SettingsScreen(
    settings: AppSettings,
    pendingWrite: Boolean,
    notice: String?,
    onBack: () -> Unit,
    onTheme: (ThemePreference) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DuckTheme.colors
    val spacing = DuckTheme.spacing
    Column(modifier.fillMaxSize().background(colors.surface).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back), tint = colors.onSurface)
            }
            Text(stringResource(R.string.settings_title), style = DuckTheme.type.uiTitle, color = colors.onSurface)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = spacing.lg)) {
            if (notice != null) {
                Text(notice, color = colors.error, style = DuckTheme.type.uiBody,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(vertical = spacing.sm))
                TextButton(onClick = onRetry, enabled = !pendingWrite) { Text(stringResource(R.string.settings_retry)) }
            }
            SettingsHeading(stringResource(R.string.settings_language))
            Text(stringResource(R.string.settings_language_pending), style = DuckTheme.type.uiSecondary, color = colors.proseMuted)
            SettingsHeading(stringResource(R.string.settings_theme))
            Column(Modifier.selectableGroup()) {
                SettingsRadio(stringResource(R.string.settings_system), settings.theme == ThemePreference.System, !pendingWrite) { onTheme(ThemePreference.System) }
                SettingsRadio(stringResource(R.string.settings_light), settings.theme == ThemePreference.Light, !pendingWrite) { onTheme(ThemePreference.Light) }
                SettingsRadio(stringResource(R.string.settings_dark), settings.theme == ThemePreference.Dark, !pendingWrite) { onTheme(ThemePreference.Dark) }
            }
            SettingsHeading(stringResource(R.string.settings_app_lock))
            Text(stringResource(R.string.settings_lock_pending), style = DuckTheme.type.uiSecondary, color = colors.proseMuted)
            SettingsHeading(stringResource(R.string.settings_about))
            Image(painterResource(R.drawable.duck_mascot), contentDescription = null,
                modifier = Modifier.size(64.dp), colorFilter = ColorFilter.tint(colors.chromeMuted))
            Text(stringResource(R.string.app_name), style = DuckTheme.type.uiBody, color = colors.onSurface)
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()),
                style = DuckTheme.type.uiSecondary, color = colors.proseMuted)
            Spacer(Modifier.height(spacing.lg))
        }
    }
}

@Composable
private fun SettingsHeading(label: String) {
    Text(label, style = DuckTheme.type.uiTitle, color = DuckTheme.colors.onSurface,
        modifier = Modifier.padding(top = DuckTheme.spacing.lg, bottom = DuckTheme.spacing.sm))
}

@Composable
private fun SettingsRadio(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = DuckTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = DuckTheme.type.uiBody, color = DuckTheme.colors.onSurface, modifier = Modifier.weight(1f).padding(start = DuckTheme.spacing.sm))
    }
}

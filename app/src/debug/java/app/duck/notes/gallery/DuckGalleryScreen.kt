package app.duck.notes.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.duck.notes.core.theme.DuckColors
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.theme.DuckThemeSpec
import app.duck.notes.presentation.markdown.MarkdownEditorScreen

/**
 * The Phase 5a verification harness. **Debug source set only — never in a release APK.**
 *
 * Two criteria cannot be asserted on the JVM and need something on screen: that a theme switch is
 * instant and free of a stale-colour flash, and that the real editor looks right under a non-default
 * palette. The second is why this hosts [MarkdownEditorScreen] itself rather than a mock — `src/debug`
 * compiles alongside `src/main`, so it can call straight into the real screen, and `MainActivity`
 * needs no debug-only hook to make its theme reachable.
 *
 * The theme state here is a plain `mutableStateOf`. Persisting it is Phase 14's DataStore work; this
 * exists so the switch is exercisable at all before then.
 */
@Composable
fun DuckGalleryScreen() {
    var spec by remember { mutableStateOf(DuckThemeSpec.DuckRed) }
    var dark by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }

    DuckTheme(spec = spec, dark = dark) {
        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(DuckTheme.colors.surface),
            ) {
                Controls(
                    spec = spec,
                    dark = dark,
                    showEditor = showEditor,
                    onSpec = { spec = it },
                    onDark = { dark = it },
                    onShowEditor = { showEditor = it },
                )
                if (showEditor) MarkdownEditorScreen() else TokenSheet()
            }
        }
    }
}

@Composable
private fun Controls(
    spec: DuckThemeSpec,
    dark: Boolean,
    showEditor: Boolean,
    onSpec: (DuckThemeSpec) -> Unit,
    onDark: (Boolean) -> Unit,
    onShowEditor: (Boolean) -> Unit,
) {
    val colors = DuckTheme.colors
    val spacing = DuckTheme.spacing

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceElevated)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            DuckThemeSpec.entries.forEach { entry ->
                Pill(entry.label, selected = entry == spec) { onSpec(entry) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Pill("Light", selected = !dark) { onDark(false) }
            Pill("Dark", selected = dark) { onDark(true) }
            Pill("Tokens", selected = !showEditor) { onShowEditor(false) }
            Pill("Editor", selected = showEditor) { onShowEditor(true) }
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = DuckTheme.colors
    Box(
        Modifier
            .clip(DuckTheme.shapes.small)
            .background(if (selected) colors.accent else colors.tagSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = DuckTheme.spacing.md, vertical = DuckTheme.spacing.sm),
    ) {
        Text(
            text = label,
            style = DuckTheme.type.uiLabel,
            color = if (selected) colors.surface else colors.accentText,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun TokenSheet() {
    val colors = DuckTheme.colors
    val type = DuckTheme.type
    val spacing = DuckTheme.spacing

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        contentPadding = PaddingValues(vertical = spacing.lg),
    ) {
        item { SectionHeader("Colour slots") }
        items(slotsOf(colors)) { (name, value) -> Swatch(name, value) }

        item { SectionHeader("Type roles") }
        item { Text("noteTitle — a note's title", style = type.noteTitle, color = colors.onSurface) }
        item { Text("noteBody — serif reading prose", style = type.noteBody, color = colors.onSurface) }
        item { Text("uiTitle — sidebar section header", style = type.uiTitle, color = colors.onSurface) }
        item { Text("uiBody — sidebar row, card title", style = type.uiBody, color = colors.onSurface) }
        item { Text("uiSecondary — note preview", style = type.uiSecondary, color = colors.proseMuted) }
        item { Text("uiLabel — timestamp, count, chip", style = type.uiLabel, color = colors.chromeMuted) }
        item {
            Text(
                "mono — fun duck() = \"code\"",
                style = type.noteBody.copy(fontFamily = type.mono),
                color = colors.codeOnSurface,
                modifier = Modifier.background(colors.codeSurface).padding(spacing.sm),
            )
        }

        item { SectionHeader("Spacing 4 / 8 / 12 / 16 / 24 / 32") }
        item {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                listOf(spacing.xs, spacing.sm, spacing.md, spacing.lg, spacing.xl, spacing.xxl)
                    .forEach { Box(Modifier.width(it).height(spacing.xxl).background(colors.accent)) }
            }
        }

        item { SectionHeader("Radius 8 / 12 / 16") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                listOf(
                    DuckTheme.shapes.small,
                    DuckTheme.shapes.medium,
                    DuckTheme.shapes.large,
                ).forEach {
                    Box(
                        Modifier
                            .size(spacing.xxl)
                            .clip(it)
                            .background(colors.tagSurface)
                            .border(1.dp, colors.divider, it),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = DuckTheme.type.uiTitle,
        color = DuckTheme.colors.onSurface,
        modifier = Modifier.padding(top = DuckTheme.spacing.lg, bottom = DuckTheme.spacing.xs),
    )
}

@Composable
private fun Swatch(name: String, value: Color) {
    val colors = DuckTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DuckTheme.spacing.md),
    ) {
        Box(
            Modifier
                .size(DuckTheme.spacing.xxl)
                .clip(DuckTheme.shapes.small)
                .background(value)
                .border(1.dp, colors.divider, DuckTheme.shapes.small),
        )
        Text(name, style = DuckTheme.type.uiBody, color = colors.onSurface, modifier = Modifier.width(140.dp))
        Text(hex(value), style = DuckTheme.type.uiLabel.copy(fontFamily = DuckTheme.type.mono), color = colors.chromeMuted)
    }
}

private fun hex(c: Color) =
    "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

private fun slotsOf(c: DuckColors) = listOf(
    "accent" to c.accent,
    "accentPress" to c.accentPress,
    "accentText" to c.accentText,
    "surface" to c.surface,
    "surfaceElevated" to c.surfaceElevated,
    "onSurface" to c.onSurface,
    "proseMuted" to c.proseMuted,
    "chromeMuted" to c.chromeMuted,
    "divider" to c.divider,
    "codeSurface" to c.codeSurface,
    "codeOnSurface" to c.codeOnSurface,
    "tagSurface" to c.tagSurface,
    "highlight" to c.highlight,
    "selection" to c.selection,
    "error" to c.error,
    "errorSurface" to c.errorSurface,
)

package app.duck.notes.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.duck.notes.core.icons.DuckIcon
import app.duck.notes.core.icons.DuckIconImage
import app.duck.notes.core.theme.DuckColors
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.theme.DuckThemeSpec
import app.duck.notes.core.ui.DuckCheckbox
import app.duck.notes.core.ui.DuckDivider
import app.duck.notes.core.ui.DuckEmptyState
import app.duck.notes.core.ui.DuckIconButton
import app.duck.notes.core.ui.DuckTagChip
import app.duck.notes.presentation.markdown.MarkdownEditorScreen
import app.duck.notes.presentation.markdown.SAMPLE_NOTE
import app.duck.notes.presentation.shell.AddTagDialog
import app.duck.notes.presentation.shell.DeleteTagDialog
import app.duck.notes.presentation.shell.DuckShell
import app.duck.notes.presentation.shell.RenameTagDialog
import app.duck.notes.presentation.shell.ShellActions
import app.duck.notes.presentation.shell.TagIconDialog
import app.duck.notes.presentation.shell.flatten
import app.duck.notes.presentation.shell.tagPaths

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
    // rememberSaveable, not remember: verifying the shell means checking each palette at BOTH pane
    // modes, and the only way to change pane mode is to rotate — which recreates the activity. With
    // plain `remember` every rotation snapped back to DuckRed/light/Tokens, so the Expanded half of
    // the sweep could not be reached at all. Persisting across launches is still Phase 14's DataStore
    // work; this only has to survive a configuration change.
    var spec by rememberSaveable { mutableStateOf(DuckThemeSpec.DuckRed) }
    var dark by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(GalleryPage.Tokens) }

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
                    page = page,
                    onSpec = { spec = it },
                    onDark = { dark = it },
                    onPage = { page = it },
                )
                when (page) {
                    GalleryPage.Tokens -> TokenSheet()
                    GalleryPage.Editor -> MarkdownEditorScreen(SAMPLE_NOTE)
                    GalleryPage.Shell -> ShellPage()
                    GalleryPage.Tags -> TagsPage()
                }
            }
        }
    }
}

/** Four pages, one toggle row. A page exists when a criterion needs eyes rather than an assertion. */
private enum class GalleryPage { Tokens, Editor, Shell, Tags }

/**
 * The shell on a fixture rather than on Room.
 *
 * Phase 6's palette work is checked by looking at a *populated* sidebar and note list across all
 * eight themes, and the database on a fresh install is empty — so the page that verifies the colours
 * would render two empty states. The fixture also keeps the harness from depending on Hilt, which
 * the stateless `DuckShell` overload exists for.
 *
 * Interactive, not static: selecting a note and expanding a tag are the two things the accent bar
 * and the chevron are for, and neither can be judged from a screenshot of one state.
 */
@Composable
private fun ShellPage() {
    var state by remember { mutableStateOf(ShellFixture.state) }
    var expanded by remember { mutableStateOf(ShellFixture.initiallyExpanded) }

    DuckShell(
        state = state.copy(tagRows = ShellFixture.tree.flatten(expanded, ShellFixture.counts)),
        actions = ShellActions(
            onFilter = { state = ShellFixture.apply(state, it) },
            onSelectNote = { id -> state = state.copy(selectedNoteId = id, selectedNoteBody = ShellFixture.bodyOf(id)) },
            onClearSelection = { state = state.copy(selectedNoteId = null, selectedNoteBody = "") },
            onToggleTag = { path -> expanded = if (path in expanded) expanded - path else expanded + path },
        ),
    )
}

/**
 * The four tag dialogs, each rendered **open**.
 *
 * Every one of them is modal and reachable only by a long-press or an overflow tap, so the `Shell`
 * page shows none of them at rest — the same defect the fixture's already-selected note exists to
 * avoid, one level deeper. Their surface is `surfaceElevated` over a scrim, which is the pairing
 * `ContrastTest` gained an `error`/`surfaceElevated` row for; a scrim that reads as opaque black in
 * Midnight and as grey in Sepia is a palette question, not an assertion.
 *
 * One at a time, chosen by chip: an `AlertDialog` takes the whole window, so two open at once would
 * stack and only the last would be visible.
 *
 * **Dismiss must work, and the first version of this page is why it is spelled out.** It opened with
 * a dialog up and `onDismiss = {}`, reasoning that a harness which hides the state cannot verify it.
 * But a Compose dialog is its own window with a scrim, so a permanently-open one puts the palette
 * chips *and the page chips* behind it forever — the sweep this page exists for became impossible,
 * and leaving the page did too. Open at rest **and** dismissable: tap out, change palette, tap the
 * chip to reopen.
 *
 * The rename dialog opens on `projects/duck`, the one path whose merge warning has something to say
 * — `existingPaths` contains its siblings, so typing `work` shows the merge copy rather than the
 * plain rename. Delete opens on `projects`, where `own = 1` and `subtree = 5` disagree.
 */
@Composable
private fun TagsPage() {
    var open by remember { mutableStateOf<DialogKind?>(DialogKind.Rename) }
    val spacing = DuckTheme.spacing
    val paths = ShellFixture.tree.tagPaths()

    Column(
        Modifier
            .fillMaxSize()
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            DialogKind.entries.forEach { entry ->
                DuckTagChip(entry.name, selected = entry == open, onClick = { open = entry })
            }
        }
    }

    val dismiss = { open = null }
    when (open) {
        DialogKind.Rename -> RenameTagDialog(
            path = "projects/duck",
            existingPaths = paths,
            counts = ShellFixture.counts.mapValues { it.value.own },
            onConfirm = {},
            onDismiss = dismiss,
        )
        DialogKind.Delete -> DeleteTagDialog(
            path = "projects",
            noteCount = ShellFixture.counts.getValue("projects").own,
            hasChildren = true,
            onConfirm = {},
            onDismiss = dismiss,
        )
        DialogKind.Icon -> TagIconDialog(
            path = "work",
            current = null,
            onPick = {},
            onDismiss = dismiss,
        )
        DialogKind.Add -> AddTagDialog(
            available = paths,
            onPick = {},
            onDismiss = dismiss,
        )
        null -> Unit
    }
}

/** Named `DialogKind`, not `TagDialog`: the latter is a real sealed type in `ShellState.kt`. */
private enum class DialogKind { Rename, Delete, Icon, Add }

@Composable
private fun Controls(
    spec: DuckThemeSpec,
    dark: Boolean,
    page: GalleryPage,
    onSpec: (DuckThemeSpec) -> Unit,
    onDark: (Boolean) -> Unit,
    onPage: (GalleryPage) -> Unit,
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
                DuckTagChip(entry.label, selected = entry == spec, onClick = { onSpec(entry) })
            }
        }
        // Light/dark and the page selector on separate rows: five chips do not fit a portrait phone,
        // and the overflowing one rendered as a single letter per line rather than clipping.
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            DuckTagChip("Light", selected = !dark, onClick = { onDark(false) })
            DuckTagChip("Dark", selected = dark, onClick = { onDark(true) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            GalleryPage.entries.forEach { entry ->
                DuckTagChip(entry.name, selected = entry == page, onClick = { onPage(entry) })
            }
        }
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
        item { Text("uiLabel — timestamp, count, chip", style = type.uiLabel, color = colors.proseMuted) }
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

        item { SectionHeader("Components") }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                DuckTagChip("work/code", selected = false, onClick = {})
                DuckTagChip("todo", selected = true, onClick = {})
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                // Unchecked first, and both boxes adjacent, because that is the pair ce559b4 fixed:
                // disabledUncheckedColor was the same token as uncheckedColor, so a disabled box
                // rendered pixel-identically to a live one. The harness showed only one empty box, so
                // the defect was invisible here and stayed invisible after the fix. ContrastTest
                // asserts the repair at 2.60, but a number is not a look.
                StatePair("unchecked", checked = false)
                StatePair("checked", checked = true)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    var checked by remember { mutableStateOf(true) }
                    DuckCheckbox(checked = checked, onCheckedChange = { checked = it })
                    DuckIconButton(DuckIcon.Idea, "idea", onClick = {})
                    DuckIconButton(DuckIcon.Code, "code", onClick = {}, enabled = false)
                    Text(
                        "tappable · icon enabled, disabled",
                        style = type.uiLabel,
                        color = colors.proseMuted,
                    )
                }
            }
        }
        item { DuckDivider() }

        item { SectionHeader("DuckIcons — all 8, tinted accentText") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                DuckIcon.entries.forEach {
                    DuckIconImage(it, colors.accentText, it.name, Modifier.size(spacing.xl))
                }
            }
        }
        item {
            // Resolution, on screen: leaf-first, walk-up, and the fallback.
            Text(
                "forTag: work/code→${DuckIcon.forTag("work/code")} · " +
                    "work/acme→${DuckIcon.forTag("work/acme")} · " +
                    "to-do→${DuckIcon.forTag("to-do")} · " +
                    "nope→${DuckIcon.forTag("nope")}",
                style = type.uiLabel,
                color = colors.proseMuted,
            )
        }

        item { SectionHeader("Empty state") }
        item { DuckEmptyState("No notes yet", Modifier.height(200.dp)) }

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

/**
 * One checkbox state, enabled and disabled, adjacent.
 *
 * Adjacency is the whole point: a disabled control is checked against the *enabled* one, not against
 * the page, so a single instance can never show whether the states differ. That is the harness gap
 * that let the disabled-unchecked defect ship.
 */
@Composable
private fun StatePair(label: String, checked: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DuckTheme.spacing.sm),
    ) {
        DuckCheckbox(checked = checked, onCheckedChange = {})
        DuckCheckbox(checked = checked, onCheckedChange = {}, enabled = false)
        Text(
            text = "$label · enabled, disabled",
            style = DuckTheme.type.uiLabel,
            color = DuckTheme.colors.proseMuted,
        )
    }
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
        Text(hex(value), style = DuckTheme.type.uiLabel.copy(fontFamily = DuckTheme.type.mono), color = colors.proseMuted)
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

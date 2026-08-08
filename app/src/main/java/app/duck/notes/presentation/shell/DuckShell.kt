package app.duck.notes.presentation.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duck.notes.R
import app.duck.notes.core.icons.DuckIcon
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.ui.DuckEmptyState
import app.duck.notes.core.ui.DuckIconButton
import app.duck.notes.core.ui.DuckTagChip
import app.duck.notes.presentation.markdown.MarkdownEditorScreen
import kotlinx.coroutines.launch

/**
 * The three-pane shell: sidebar, note list, editor.
 *
 * ## Panes come from state, not from routes
 *
 * There is no `NavHost`. A list-detail layout that also has a `NavHost` keeps `selectedNoteId` in
 * two places — route args and the ViewModel — and every Expanded↔Compact resize then has to
 * reconcile a back stack against pane state. Deriving the panes from one piece of state removes the
 * reconciliation rather than implementing it, and back becomes four lines of [BackHandler].
 *
 * Likewise there is no `WindowSizeClass`: [paneModeFor] is a pure function over the width
 * [BoxWithConstraints] already measures, so the breakpoints are a JVM unit test instead of a manual
 * resize on a device. `calculateWindowSizeClass` is experimental, soft-deprecated in favour of the
 * adaptive libraries, needs an `Activity` handle and a dependency, and could not be tested here.
 *
 * Both are deviations from the phase spec, recorded in `DUCK_NOTES_BUILD_PROMPT.md`.
 *
 * ## Two Material alpha composites reach the screen, and both are exempt
 *
 * Phase 5's prerequisite is about **a colour on screen that is in no palette** — `surfaceTint`'s
 * blend and the chip's `0.38`/`0.12` disabled alphas are instances of that, not its definition. No
 * component here gains `enabled`, but two composites arrive anyway. Both were read out of the
 * `material3 1.4.0` bytecode — the version the build resolves, not the stale 1.3.0 AAR also sitting
 * in the Gradle cache — rather than recalled:
 *
 * - **The drawer scrim.** `DrawerDefaults.getScrimColor` reads `ScrimTokens.ContainerColor` and
 *   applies `Color.copy(alpha = 0.32f)`. Our projection pins that token to black, so it is black at
 *   32%. **Exempt: it is occlusion by design.** Its whole function is to make what is behind it not
 *   readable, and 1.4.3 governs text meant to be perceived. What must stay legible is the sheet in
 *   front, a fully opaque `surfaceElevated` fill whose pairs are all already floored.
 * - **The ripple.** `RippleDefaults.RippleAlpha` is `RippleAlpha(0.16f, 0.10f, 0.08f, 0.10f)` —
 *   `material-ripple`'s field order is dragged, focused, hovered, pressed, so pressed is 0.10f —
 *   resolved against `LocalContentColor`, since the default `RippleConfiguration` leaves colour
 *   `Unspecified`. So a pressed sidebar row composites `onSurface` at 10% over `surfaceElevated`.
 *   **Exempt: it is transient feedback, not a state.** It lives between press and release and says
 *   nothing the accent bar and the SemiBold weight do not, both of which survive the finger lifting.
 *
 * Neither can live in `ContrastTest`: that suite compares two opaque colours, which is exactly the
 * property both of these lack. Recorded here and in `ContrastTest.duckPairs`' KDoc, because the
 * prerequisite forbids silence, not alpha.
 */
@Composable
fun DuckShell(modifier: Modifier = Modifier, viewModel: DuckShellViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The flush that makes "no data loss" true for every departure the user can cause from the UI.
    // ON_STOP rather than ON_PAUSE: pause fires for a transparent activity on top, which is not a
    // departure. The coroutine runs in viewModelScope — see DuckShellViewModel.onStop.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }

    DuckShell(
        state = state,
        buffer = viewModel.buffer,
        actions = ShellActions(
            onFilter = viewModel::onFilter,
            onSelectNote = viewModel::onSelectNote,
            onClearSelection = viewModel::onClearSelection,
            onToggleTag = viewModel::onToggleTag,
            onPinTag = viewModel::onPinTag,
            onRenameTag = viewModel::onRenameTag,
            onDeleteTag = viewModel::onDeleteTag,
            onSetTagIcon = viewModel::onSetTagIcon,
            onAddTagToNote = viewModel::onAddTagToNote,
            onAttachImage = viewModel::onAttachImage,
            onDeleteAttachment = viewModel::onDeleteAttachment,
            onUndo = viewModel::onUndo,
            onUndoShown = viewModel::onUndoShown,
            onToggleSection = viewModel::onToggleSection,
            tagsOfNote = viewModel::tagsOfNote,
        ),
        modifier = modifier,
    )
}

/**
 * The stateless half. Ordinary Compose state hoisting, with two real callers: [DuckShell] above and
 * the debug gallery, which drives it from a fixture so the eight palettes can be checked against a
 * populated list rather than whatever happens to be in the database.
 *
 * [buffer] is a parameter rather than a `rememberTextFieldState` inside — that would be the saver
 * Phase 8a just removed, back in `src/main`, and a buffer autosave cannot see.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DuckShell(
    state: ShellUiState,
    buffer: TextFieldState,
    actions: ShellActions,
    modifier: Modifier = Modifier,
) {
    val colors = DuckTheme.colors

    BoxWithConstraints(modifier.fillMaxSize().background(colors.surface)) {
        val mode = paneModeFor(maxWidth)
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val snackbars = remember { SnackbarHostState() }

        // Plain `remember`, not `rememberSaveable`: making a sealed type saveable needs a
        // hand-written Saver, and the cost of not doing it is a rotation dismissing a dialog and
        // losing at most a half-typed tag name. Stated rather than hidden — same class of ceiling
        // as the editor buffer's in Phase 6, and a much cheaper one.
        var dialog by remember { mutableStateOf<TagDialog?>(null) }

        val sidebar: @Composable () -> Unit = {
            DuckSidebar(
                filter = state.filter,
                pinnedTags = state.pinnedTags,
                tagRows = state.tagRows,
                onFilter = {
                    actions.onFilter(it)
                    scope.launch { drawer.close() }
                },
                onToggleTag = actions.onToggleTag,
                onTagMenu = { row, action ->
                    val id = row.tagId ?: return@DuckSidebar
                    when (action) {
                        TagMenuAction.Pin -> actions.onPinTag(id, !row.pinned)
                        TagMenuAction.Rename -> dialog = TagDialog.Rename(row.path)
                        TagMenuAction.Icon -> dialog = TagDialog.Icon(id, row.path, row.iconKey)
                        // ownCount, NOT count. The row shows the subtree total because tapping it
                        // filters the subtree; delete does not cascade, so quoting the same number
                        // here would promise to strip the tag from 12 notes and then strip it from 3.
                        TagMenuAction.Delete ->
                            dialog = TagDialog.Delete(row.path, row.ownCount)
                    }
                },
            )
        }

        val list: @Composable (Modifier) -> Unit = { m ->
            DuckNoteList(
                filter = state.filter,
                notes = state.notes,
                selectedNoteId = state.selectedNoteId,
                onSelectNote = actions.onSelectNote,
                onAddTag = { noteId ->
                    dialog = TagDialog.AddTag(noteId, actions.tagsOfNote(noteId))
                },
                modifier = m,
            )
        }

        /**
         * The editor pane: a chip row for the open note's tags, then the editor.
         *
         * The chip row is where "tappable tag" landed, and it is a deviation from the phase spec's
         * "tag chip inline in editor". Making an inline `#tag` tappable needs a tap offset mapped
         * back through `MarkdownOutputTransformation` to a source `TagSpan` — an inverse map that
         * does not exist, that competes with caret placement and selection, and that Phase 8 has to
         * build anyway for the format toolbar. The inline tag is still painted accent by Phase 4's
         * engine; what moved is only where you tap it.
         *
         * `MarkdownEditorScreen` itself is untouched — Phase 4's prototype stays intact for Phase 8
         * to replace, and this composes above it.
         */
        val editor: @Composable (Modifier) -> Unit = { m ->
            if (state.selectedNoteId == null) {
                DuckEmptyState(stringResource(R.string.shell_no_note_selected), m)
            } else {
                // The system photo picker. Its content:// grant is revocable and process-scoped, which
                // is exactly why AttachImage copies the bytes into filesDir rather than storing the uri
                // — the copy runs in the same session, moments after this callback, while the grant
                // still holds. The mime is read here because the picker's uri is the only place it is
                // authoritative; a null type (rare) falls back to a generic image type.
                val context = LocalContext.current
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia(),
                ) { uri ->
                    if (uri != null) {
                        val mime = context.contentResolver.getType(uri) ?: "image/*"
                        actions.onAttachImage(uri.toString(), mime)
                    }
                }
                Column(m) {
                    if (state.selectedNoteTags.isNotEmpty()) {
                        EditorTagRow(
                            tags = state.selectedNoteTags,
                            onTagClick = { actions.onFilter(ShellFilter.Tag(it)) },
                        )
                    }
                    MarkdownEditorScreen(
                        state = buffer,
                        collapsedSections = state.collapsedSections,
                        onToggleSection = actions.onToggleSection,
                        attachments = state.selectedNoteAttachments,
                        onAddImage = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onDeleteAttachment = { actions.onDeleteAttachment(it.id) },
                    )
                }
            }
        }

        // statusBarsPadding on the pane rows, not on the root: only Compact routes through a
        // `Scaffold` (which supplies insets) and only the drawer sheet gets them from
        // `ModalDrawerSheet`. Expanded and Medium lay panes out directly, so without this the
        // sidebar's first row draws under the clock — and it does so ONLY on wide screens, which is
        // the kind of defect a phone-only check never sees.
        // The VerticalDividers below are NOT decoration — do not remove them to "let the elevation do
        // the work". `surfaceElevated on surface` is 1.06 in SepiaLight, lowest of the eight, and
        // checked on device the panes do not separate as planes there at all: the elevated fill
        // contributes nothing and the rule is what marks the boundary. On the dark palettes
        // (1.13-1.19, MidnightDark best) the planes do separate unaided, which is exactly why a
        // reviewer looking at one dark theme would conclude the lines are redundant.
        //
        // Not the *sole* cue — nav rows, cards and prose differ by content, and that second cue is
        // what keeps `divider` exempt from a 1.4.11 floor at all. So "do not delete" is a Duck design
        // rule, not a WCAG one, and is negotiable with eyes open. The numbers and the full argument
        // live in ContrastTest.dividerAndElevationAreRecorded.
        when (mode) {
            PaneMode.Expanded -> Row(Modifier.fillMaxSize().statusBarsPadding()) {
                androidx.compose.foundation.layout.Box(Modifier.width(SIDEBAR)) { sidebar() }
                VerticalDivider(color = colors.divider)
                list(Modifier.width(NOTE_LIST))
                VerticalDivider(color = colors.divider)
                editor(Modifier.weight(1f))
            }

            // Medium keeps the sidebar in the drawer and shows list + editor together: at 600-839dp
            // three panes leave the editor too narrow to read a serif body in.
            PaneMode.Medium -> DrawerHost(drawer, sidebar) {
                Row(Modifier.fillMaxSize().statusBarsPadding()) {
                    list(Modifier.width(NOTE_LIST))
                    VerticalDivider(color = colors.divider)
                    editor(Modifier.weight(1f))
                }
            }

            PaneMode.Compact -> DrawerHost(drawer, sidebar) {
                // Back walks editor -> list, then falls through to the system, which exits. On the
                // wider modes both panes are already visible, so the handler stays disabled and back
                // exits directly — there is nothing stacked to pop.
                BackHandler(enabled = state.selectedNoteId != null, onBack = actions.onClearSelection)

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        // statusBarsPadding, because `Scaffold`'s topBar slot does NOT apply window
                        // insets to arbitrary content — only its own `TopAppBar` does. Without it
                        // the button draws over the clock under `enableEdgeToEdge`.
                        DuckIconButton(
                            icon = DuckIcon.Tag,
                            contentDescription = stringResource(R.string.shell_open_sidebar),
                            onClick = { scope.launch { drawer.open() } },
                            modifier = Modifier.statusBarsPadding(),
                        )
                    },
                ) { padding ->
                    val motion = DuckTheme.motion
                    val noteOpen = state.selectedNoteId != null
                    // Keyed on "is a note open", NOT on the note id, and the difference is a defect
                    // rather than a preference. AnimatedContent composes outgoing and incoming
                    // together for the length of the transition; keyed on the id, switching notes
                    // would put two editors on screen at once, both bound to the single
                    // DuckShellViewModel.buffer — one TextFieldState driving two BasicTextFields,
                    // contending over focus and the IME. On the Boolean, only list <-> editor
                    // transitions, and exactly one editor exists at any instant.
                    //
                    // It also lands where the frequency rule wants it: moving between two notes is
                    // something you do all day and it stays instant.
                    // Reduced motion does not enter AnimatedContent at all, and that is not a
                    // shortcut — it is the difference between "instant" and "instant and free".
                    // Measured frame by frame: with every duration at zero, AnimatedContent still
                    // holds the outgoing pane for TWO frames while its transition bookkeeping
                    // settles (0ms L1/E0, 16ms L1/E1, 32ms L1/E1, 48ms L0/E1). Invisible, since a
                    // zero-length fade puts the outgoing pane at alpha 0 immediately, but it lays
                    // out and composes both panes — on a 10k-word note that is the editor's whole
                    // layout run alongside the list's, for no reason a user asked for.
                    if (!motion.animates) {
                        if (noteOpen) editor(Modifier.padding(padding)) else list(Modifier.padding(padding))
                        return@Scaffold
                    }
                    AnimatedContent(
                        targetState = noteOpen,
                        transitionSpec = {
                            // Depth, not sideways travel: the editor is *into* the note, and back
                            // returns. Sliding would imply the list and editor are siblings on one
                            // plane, which is the Expanded layout's story, not Compact's.
                            val out = if (targetState) 1.02f else 0.98f
                            (fadeIn(motion.mediumSpec()) + scaleIn(motion.mediumSpec(), initialScale = out))
                                .togetherWith(fadeOut(motion.exitSpec()))
                        },
                        label = "compactPane",
                    ) { noteOpen ->
                        if (noteOpen) editor(Modifier.padding(padding)) else list(Modifier.padding(padding))
                    }
                }
            }
        }

        // Hoisted above the pane branch on purpose: only Compact routes through a `Scaffold`, so a
        // `snackbarHost` slot would give undo to phones and silently withhold it from tablets.
        SnackbarHost(
            hostState = snackbars,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )

        TagDialogHost(
            dialog = dialog,
            state = state,
            actions = actions,
            onDismiss = { dialog = null },
        )

        UndoSnackbar(state.undo, snackbars, actions)
    }
}

/** The open note's tags, above the body. Tapping one filters the list by it. */
@Composable
private fun EditorTagRow(tags: List<String>, onTagClick: (String) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DuckTheme.spacing.lg, vertical = DuckTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(DuckTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(DuckTheme.spacing.xs),
    ) {
        tags.forEach { tag ->
            DuckTagChip(label = tag, selected = false, onClick = { onTagClick(tag) })
        }
    }
}

/** One place that turns a [TagDialog] into a dialog, so the pane branch does not have to. */
@Composable
private fun TagDialogHost(
    dialog: TagDialog?,
    state: ShellUiState,
    actions: ShellActions,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        null -> Unit

        is TagDialog.Rename -> RenameTagDialog(
            path = dialog.path,
            existingPaths = state.allTagPaths,
            counts = state.tagRows.plus(state.pinnedTags).associate { it.path to it.count },
            onConfirm = {
                actions.onRenameTag(dialog.path, it)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        is TagDialog.Delete -> DeleteTagDialog(
            path = dialog.path,
            noteCount = dialog.noteCount,
            hasChildren = state.allTagPaths.any { it.startsWith("${dialog.path}/") },
            onConfirm = {
                actions.onDeleteTag(dialog.path)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        is TagDialog.Icon -> TagIconDialog(
            path = dialog.path,
            current = dialog.current,
            onPick = {
                actions.onSetTagIcon(dialog.tagId, it)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        is TagDialog.AddTag -> AddTagDialog(
            // Tags the note already carries are absent rather than inert: adding one twice writes
            // `#work` into the body a second time while ParseTags dedupes the row, so the database
            // cannot see the duplicate and the user cannot miss it.
            available = state.allTagPaths - dialog.tagsPresent.toSet(),
            onPick = {
                actions.onAddTagToNote(dialog.noteId, it)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

/**
 * Shows the undo prompt once, then tells the ViewModel to drop the snapshot it is holding.
 *
 * The snapshot is N full note bodies in memory, so it is released as soon as the Snackbar goes —
 * whether the user pressed Undo, dismissed it, or let it time out.
 */
@Composable
private fun UndoSnackbar(
    prompt: TagPrompt?,
    host: SnackbarHostState,
    actions: ShellActions,
) {
    if (prompt == null) return
    val message = when (prompt.kind) {
        TagPrompt.Kind.AddFailed -> stringResource(R.string.tag_add_failed, prompt.tagName)
        else -> pluralStringResource(
            if (prompt.kind == TagPrompt.Kind.Renamed) R.plurals.tag_renamed else R.plurals.tag_deleted,
            prompt.noteCount,
            prompt.tagName,
            prompt.noteCount,
        )
    }
    // Null for a failure: an action labelled Undo, over a write that never landed, would undo nothing.
    val undoLabel = stringResource(R.string.tag_undo).takeIf { prompt.undoable }

    LaunchedEffect(prompt) {
        val result = host.showSnackbar(
            message = message,
            actionLabel = undoLabel,
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) actions.onUndo() else actions.onUndoShown()
    }
}

/**
 * `ModalNavigationDrawer` with the sheet container named explicitly.
 *
 * `ModalDrawerSheet` defaults `drawerContainerColor` to `surfaceContainerLow`, which our projection
 * maps to `surface` — so the sidebar would silently render on the page colour rather than the
 * elevated one, and only on phones. The two panes would then disagree about what a sidebar is.
 */
@Composable
private fun DrawerHost(
    drawer: androidx.compose.material3.DrawerState,
    sidebar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = DuckTheme.colors.surfaceElevated,
                drawerContentColor = DuckTheme.colors.onSurface,
            ) {
                sidebar()
            }
        },
        content = content,
    )
}

private val SIDEBAR = 280.dp
private val NOTE_LIST = 320.dp
